package com.forddrive.launcher;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;
import android.os.Bundle;
import android.os.Build;
import android.content.Intent;
import android.content.Context;
import android.provider.Settings;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.content.res.AssetFileDescriptor;
import android.os.Handler;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.view.WindowManager;
import android.content.pm.ActivityInfo;
import org.json.JSONObject;

public class MainActivity extends Activity {
  private WebView web;
  private ValueCallback<Uri[]> fileCallback;
  private static final int PICK_IMAGE = 9001;
  private static final int GPS_PERMISSION_REQUEST = 9102;
  private LocationManager locationManager;
  private LocationListener gpsListener;
  private boolean gpsRequested = false;
  private MediaPlayer welcomePlayer;
  @Override public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    web=new WebView(this);
    web.setBackgroundColor(0xff06111e);
    web.getSettings().setJavaScriptEnabled(true);
    web.getSettings().setDomStorageEnabled(true);
    web.getSettings().setAllowFileAccess(true);
    web.setWebViewClient(new WebViewClient());
    web.setWebChromeClient(new WebChromeClient(){
      @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
        if(fileCallback!=null)fileCallback.onReceiveValue(null);
        fileCallback=callback;
        try{Intent i=new Intent(Intent.ACTION_GET_CONTENT);i.addCategory(Intent.CATEGORY_OPENABLE);String[] types=params.getAcceptTypes();String mime=(types!=null&&types.length>0&&types[0]!=null&&!types[0].isEmpty())?types[0]:"*/*";i.setType(mime);startActivityForResult(Intent.createChooser(i,"اختر ملفًا"),PICK_IMAGE);return true;}
        catch(Exception e){fileCallback=null;return false;}
      }
    });
    web.addJavascriptInterface(new Bridge(),"Android");
    setContentView(web);web.loadUrl("file:///android_asset/index.html");
    if(getPreferences(MODE_PRIVATE).getBoolean("welcome_sound",true))new Handler(Looper.getMainLooper()).postDelayed(()->playWelcome(),380);
  }
  @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
    super.onActivityResult(requestCode,resultCode,data);
    if(requestCode==PICK_IMAGE && fileCallback!=null){
      Uri[] arr=resultCode==RESULT_OK&&data!=null&&data.getData()!=null?new Uri[]{data.getData()}:null;
      fileCallback.onReceiveValue(arr);fileCallback=null;
    }
  }
  private void playWelcome(){
    try{
      if(welcomePlayer!=null){welcomePlayer.release();welcomePlayer=null;}
      AssetFileDescriptor afd=getAssets().openFd("welcome.wav");
      welcomePlayer=new MediaPlayer();welcomePlayer.setDataSource(afd.getFileDescriptor(),afd.getStartOffset(),afd.getLength());afd.close();
      welcomePlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);welcomePlayer.setVolume(.56f,.56f);
      welcomePlayer.setOnCompletionListener(mp->{mp.release();if(welcomePlayer==mp)welcomePlayer=null;});
      welcomePlayer.prepare();welcomePlayer.start();
    }catch(Exception e){android.util.Log.w("FordDrive","Could not play startup sound",e);}
  }
  public final class Bridge{
    @JavascriptInterface public boolean getWelcomeSound(){return getPreferences(MODE_PRIVATE).getBoolean("welcome_sound",true);}
    @JavascriptInterface public void setWelcomeSound(boolean enabled){getPreferences(MODE_PRIVATE).edit().putBoolean("welcome_sound",enabled).apply();if(!enabled)runOnUiThread(()->{if(welcomePlayer!=null){welcomePlayer.stop();welcomePlayer.release();welcomePlayer=null;}});}
    @JavascriptInterface public void playWelcomeSound(){runOnUiThread(()->playWelcome());}

    @JavascriptInterface public void startSpeed(){runOnUiThread(()->beginGps());}
    @JavascriptInterface public void stopSpeed(){runOnUiThread(()->endGps());}
    @JavascriptInterface public void open(String action){runOnUiThread(()->launch(action));}
    @JavascriptInterface public String getNetworkInfo(){return networkInfo();}
    @JavascriptInterface public void setVolume(int p){runOnUiThread(()->{AudioManager am=(AudioManager)getSystemService(Context.AUDIO_SERVICE);if(am!=null){int max=am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);am.setStreamVolume(AudioManager.STREAM_MUSIC,Math.round(Math.max(0,Math.min(100,p))*max/100f),0);}});}
    @JavascriptInterface public void setBrightness(int p){runOnUiThread(()->{WindowManager.LayoutParams lp=getWindow().getAttributes();lp.screenBrightness=Math.max(.1f,Math.min(1,p/100f));getWindow().setAttributes(lp);});}
  }
  private void sendGps(double speed, double accuracy, long age, String error){
    try{
      JSONObject data=new JSONObject();data.put("speed",speed);data.put("accuracy",accuracy);data.put("age",age);
      if(error!=null)data.put("error",error);
      if(web!=null)web.evaluateJavascript("window.fordGpsUpdate("+JSONObject.quote(data.toString())+")",null);
    }catch(Exception ignored){}
  }
  private void beginGps(){
    gpsRequested=true;
    if(android.os.Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
      requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},GPS_PERMISSION_REQUEST);return;
    }
    endGpsListenerOnly();
    try{
      locationManager=(LocationManager)getSystemService(Context.LOCATION_SERVICE);
      if(locationManager==null || !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)){
        sendGps(-1,999,0,"فعّل خدمات الموقع GPS من إعدادات الجهاز");return;
      }
      gpsListener=new LocationListener(){
        @Override public void onLocationChanged(Location location){
          long age=Math.max(0,(android.os.SystemClock.elapsedRealtimeNanos()-location.getElapsedRealtimeNanos())/1000000L);
          if(age>10000){sendGps(-1,999,age,"إشارة الموقع قديمة");return;}
          double speed=location.hasSpeed()? Math.max(0,location.getSpeed()*3.6) : -1;
          sendGps(speed,location.hasAccuracy()?location.getAccuracy():999,age,null);
        }
        @Override public void onProviderDisabled(String provider){sendGps(-1,999,0,"تم إيقاف GPS");}
        @Override public void onProviderEnabled(String provider){}
        @Override public void onStatusChanged(String provider,int status,Bundle extras){}
      };
      locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0f,gpsListener,Looper.getMainLooper());
    }catch(SecurityException e){sendGps(-1,999,0,"امنح التطبيق إذن الموقع الدقيق");}
     catch(Exception e){sendGps(-1,999,0,"تعذر تشغيل مستقبل GPS");}
  }
  private void endGpsListenerOnly(){
    if(locationManager!=null&&gpsListener!=null){try{locationManager.removeUpdates(gpsListener);}catch(Exception ignored){}}
    gpsListener=null;
  }
  private void endGps(){gpsRequested=false;endGpsListenerOnly();}
  @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
    super.onRequestPermissionsResult(requestCode,permissions,results);
    if(requestCode==GPS_PERMISSION_REQUEST){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED&&gpsRequested)beginGps();else sendGps(-1,999,0,"تم رفض إذن الموقع");}
  }
  @Override protected void onPause(){super.onPause();endGpsListenerOnly();}
  @Override protected void onResume(){super.onResume();if(gpsRequested&&locationManager!=null)beginGps();}
  @Override protected void onDestroy(){endGps();if(welcomePlayer!=null){welcomePlayer.release();welcomePlayer=null;}if(web!=null)web.destroy();super.onDestroy();}
  private String networkInfo(){
    boolean connected=false;String type="غير متصل";
    try{
      ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
      if(cm!=null){
        if(Build.VERSION.SDK_INT>=23){
          Network n=cm.getActiveNetwork(); NetworkCapabilities caps=cm.getNetworkCapabilities(n);
          if(caps!=null){connected=caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);if(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))type="Wi-Fi";else if(caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))type="بيانات الجوال";else if(caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))type="Ethernet";else type="شبكة متاحة";}
        }else{NetworkInfo ni=cm.getActiveNetworkInfo();if(ni!=null){connected=ni.isConnected();type=ni.getTypeName();}}
      }
    }catch(Exception ignored){}
    JSONObject j=new JSONObject();try{j.put("connected",connected);j.put("type",type);}catch(Exception ignored){}return j.toString();
  }
  private void launch(String action){
    Intent i;switch(action){
      case "maps":i=getPackageManager().getLaunchIntentForPackage("com.google.android.apps.maps");if(i==null)i=new Intent(Intent.ACTION_VIEW,Uri.parse("geo:0,0?q="));break;
      case "music":i=getPackageManager().getLaunchIntentForPackage("com.google.android.apps.youtube.music");if(i==null)i=new Intent("android.intent.action.MUSIC_PLAYER");break;
      case "phone":i=new Intent(Intent.ACTION_DIAL,Uri.parse("tel:"));break;
      case "wifi":i=new Intent(Settings.ACTION_WIFI_SETTINGS);break;
      case "wireless":i=new Intent(Settings.ACTION_WIRELESS_SETTINGS);break;
      case "hotspot":i=new Intent("android.settings.TETHER_SETTINGS");break;
      case "bluetooth":i=new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);break;
      case "sound":i=new Intent(Settings.ACTION_SOUND_SETTINGS);break;
      case "display":i=new Intent(Settings.ACTION_DISPLAY_SETTINGS);break;
      case "datetime":i=new Intent(Settings.ACTION_DATE_SETTINGS);break;
      case "storage":i=new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS);break;
      case "apps":i=new Intent(Settings.ACTION_APPLICATION_SETTINGS);break;
      case "launcher":i=Build.VERSION.SDK_INT>=21?new Intent(Settings.ACTION_HOME_SETTINGS):new Intent(Settings.ACTION_SETTINGS);break;
      case "androidsettings":i=new Intent(Settings.ACTION_SETTINGS);break;
      default:return;
    }
    try{startActivity(i);}catch(Exception ignored){try{startActivity(new Intent(Settings.ACTION_SETTINGS));}catch(Exception ignoredToo){}}
  }
  @Override public void onBackPressed(){if(web!=null)web.evaluateJavascript("window.fordBack&&window.fordBack()",null);else super.onBackPressed();}
}
