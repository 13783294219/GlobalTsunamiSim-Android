package com.globaltsunamisim.android;

import android.app.Activity;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native Android host for the numerical tsunami solver and the map/result UI. */
public class MainActivity extends Activity {
    private WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile Thread simulationThread;
    private volatile TsunamiModel activeModel;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF071525);
        getWindow().setNavigationBarColor(0xFF071525);
        webView = new WebView(this);
        webView.setBackgroundColor(0xFF071525);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setSupportZoom(false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new AndroidBridge(), "Android");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override public void onBackPressed() {
        stopRequested.set(true);
        if (activeModel != null) activeModel.requestStop();
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    private void callJs(final String function, final JSONObject argument) {
        final String expression = "window." + function + "(" + (argument == null ? "{}" : argument.toString()) + ");";
        mainHandler.post(() -> {
            if (webView != null) webView.evaluateJavascript(expression, null);
        });
    }

    private JSONObject errorObject(String message) {
        JSONObject obj = new JSONObject();
        try { obj.put("message", message == null ? "未知错误" : message); }
        catch (JSONException ignored) { }
        return obj;
    }

    private static String regionTitle(String id) {
        switch (id) {
            case "nwpac": return "西北太平洋";
            case "china": return "中国沿岸视图";
            case "japan": return "日本周边视图";
            case "mariana": return "马里亚纳周边视图";
            default: return "全球视图";
        }
    }

    private static int previewWidth(String id) { return "global".equals(id) ? 240 : ("nwpac".equals(id) ? 240 : 180); }
    private static int previewHeight(String id) {
        if ("global".equals(id)) return 96;
        if ("nwpac".equals(id)) return 180;
        if ("china".equals(id)) return 194;
        if ("japan".equals(id)) return 168;
        return 154;
    }

    private JSONObject gridInfo(String region) throws Exception {
        String id = validRegion(region);
        boolean regional = !"global".equals(id);
        GridData grid = BathymetryGridLoader.load(this, regional);
        GridData.Bounds bounds = GridData.viewBounds(id);
        int w = previewWidth(id), h = previewHeight(id);
        float[] depthMap = grid.preview(bounds,w,h,true,null);
        JSONObject obj = new JSONObject();
        obj.put("region",id);
        obj.put("regionTitle",regionTitle(id));
        obj.put("width",w); obj.put("height",h);
        obj.put("west",bounds.west); obj.put("east",bounds.east);
        obj.put("south",bounds.south); obj.put("north",bounds.north);
        obj.put("resolution",grid.resolutionLabel);
        obj.put("mapB64",Base64.encodeToString(GridData.floatsToLittleEndian(depthMap),Base64.NO_WRAP));
        obj.put("solverGridWidth",grid.nx); obj.put("solverGridHeight",grid.ny);
        return obj;
    }

    private static String validRegion(String id) {
        if ("nwpac".equals(id)||"china".equals(id)||"japan".equals(id)||"mariana".equals(id)||"global".equals(id)) return id;
        return "nwpac";
    }

    private final class AndroidBridge {
        @JavascriptInterface public String getGridInfo(String region) {
            try { return gridInfo(region).toString(); }
            catch (Exception e) { return errorObject("读取海底地形失败："+e.getMessage()).toString(); }
        }

        @JavascriptInterface public void startSimulation(String rawConfig) {
            final JSONObject config;
            try { config = new JSONObject(rawConfig); }
            catch (Exception e) { callJs("onSolverError",errorObject("模拟参数格式错误")); return; }
            stopRequested.set(true);
            TsunamiModel old=activeModel;
            if(old!=null) old.requestStop();
            final String region=validRegion(config.optString("region","nwpac"));
            final double magnitude=config.optDouble("magnitude",8.8);
            final double depthKm=config.optDouble("depthKm",25.0);
            final double lat=config.optDouble("lat",38.3);
            final double lon=config.optDouble("lon",142.4);
            final double strike=config.optDouble("strike",200.0);
            final double dip=config.optDouble("dip",15.0);
            final double hours=config.optDouble("hours",6.0);
            stopRequested.set(false);
            Thread t = new Thread(() -> runSimulation(region,magnitude,depthKm,lon,lat,strike,dip,hours),"TsunamiSWE");
            simulationThread=t;
            t.start();
        }

        @JavascriptInterface public void stopSimulation() {
            stopRequested.set(true);
            TsunamiModel m=activeModel;
            if(m!=null) m.requestStop();
        }

        @JavascriptInterface public void fetchRecentEarthquakes() {
            Thread t=new Thread(() -> {
                HttpURLConnection connection=null;
                try {
                    long start=System.currentTimeMillis()-30L*24L*60L*60L*1000L;
                    SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd",Locale.US);
                    fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
                    String from=URLEncoder.encode(fmt.format(new Date(start)),"UTF-8");
                    URL url=new URL("https://earthquake.usgs.gov/fdsnws/event/1/query?format=geojson&starttime="+from+"&minmagnitude=6&orderby=time&limit=20");
                    connection=(HttpURLConnection)url.openConnection();
                    connection.setConnectTimeout(10000); connection.setReadTimeout(12000);
                    connection.setRequestProperty("Accept","application/geo+json");
                    int status=connection.getResponseCode();
                    if(status<200||status>=300) throw new Exception("USGS 服务返回 HTTP "+status);
                    JSONObject root=new JSONObject(readText(connection.getInputStream()));
                    JSONArray features=root.optJSONArray("features");
                    JSONArray events=new JSONArray();
                    if(features!=null) for(int i=0;i<features.length();i++) {
                        JSONObject feature=features.optJSONObject(i); if(feature==null) continue;
                        JSONObject properties=feature.optJSONObject("properties");
                        JSONObject geometry=feature.optJSONObject("geometry");
                        JSONArray coords=geometry==null?null:geometry.optJSONArray("coordinates");
                        if(properties==null||coords==null||coords.length()<3) continue;
                        JSONObject e=new JSONObject();
                        e.put("id",feature.optString("id","event-"+i));
                        e.put("place",properties.optString("place","未知位置"));
                        e.put("magnitude",properties.optDouble("mag",6.0));
                        e.put("timeMs",properties.optLong("time",0));
                        e.put("lon",coords.optDouble(0,0)); e.put("lat",coords.optDouble(1,0));
                        e.put("depthKm",coords.optDouble(2,10));
                        events.put(e);
                    }
                    JSONObject out=new JSONObject(); out.put("events",events); out.put("source","USGS FDSN earthquake catalog");
                    callJs("onEventsLoaded",out);
                } catch(Exception e) { callJs("onEventsError",errorObject("无法读取 USGS 地震目录："+e.getMessage())); }
                finally { if(connection!=null) connection.disconnect(); }
            },"USGS-Earthquake-Catalog");
            t.start();
        }

        @JavascriptInterface public void savePng(String dataUrl) {
            Thread t=new Thread(() -> {
                try {
                    int comma=dataUrl.indexOf(','); if(comma<0) throw new Exception("图片数据无效");
                    byte[] data=Base64.decode(dataUrl.substring(comma+1),Base64.DEFAULT);
                    String name="GlobalTsunamiSim_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".png";
                    if(Build.VERSION.SDK_INT>=29) {
                        ContentValues values=new ContentValues();
                        values.put(MediaStore.Images.Media.DISPLAY_NAME,name);
                        values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
                        values.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/GlobalTsunamiSim");
                        Uri uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
                        if(uri==null) throw new Exception("无法创建图片文件");
                        try(OutputStream out=getContentResolver().openOutputStream(uri)) { if(out==null) throw new Exception("无法写入图片"); out.write(data); }
                    } else {
                        File dir=new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),"GlobalTsunamiSim");
                        if(!dir.exists()&&!dir.mkdirs()) throw new Exception("无法创建图片目录");
                        try(FileOutputStream out=new FileOutputStream(new File(dir,name))) { out.write(data); }
                    }
                    JSONObject result=new JSONObject(); result.put("name",name); callJs("onPngSaved",result);
                } catch(Exception e) { callJs("onPngSaveError",errorObject("保存 PNG 失败："+e.getMessage())); }
            },"Save-Tsunami-Image");
            t.start();
        }
    }

    private void runSimulation(String focusRegion,double magnitude,double depthKm,double lon,double lat,double strike,double dip,double hours) {
        try {
            if(hours<0.25 || hours>24.0) throw new IllegalArgumentException("模拟时长须为 0.25–24 小时");
            boolean regionalModel=!"global".equals(focusRegion) && lon>=100.0 && lon<=180.0 && lat>=0.0 && lat<=60.0;
            GridData grid=BathymetryGridLoader.load(this,regionalModel);
            TsunamiModel.Params p=new TsunamiModel.Params(magnitude,depthKm,lon,lat,strike,dip,hours);
            TsunamiModel model=new TsunamiModel(grid,p);
            activeModel=model;
            GridData.Bounds view=GridData.viewBounds(focusRegion);
            int outW=previewWidth(focusRegion), outH=previewHeight(focusRegion);
            double totalSeconds=hours*3600.0;
            long frameSteps=Math.max(1L,Math.round(300.0/model.dt)); // 5 min of simulated time per frame
            JSONObject first=progressObject(model,grid,view,focusRegion,outW,outH,totalSeconds,regionalModel,0);
            callJs("onSolverStarted",first);
            long stepsInFrame=0;
            while(model.timeSeconds < totalSeconds && !stopRequested.get() && !model.isStopped()) {
                model.step(); stepsInFrame++;
                if(stepsInFrame>=frameSteps || model.timeSeconds>=totalSeconds) {
                    model.trackCoast(); stepsInFrame=0;
                    double progress=Math.min(1.0,model.timeSeconds/totalSeconds);
                    callJs("onSolverProgress",progressObject(model,grid,view,focusRegion,outW,outH,totalSeconds,regionalModel,progress));
                }
            }
            JSONObject done=progressObject(model,grid,view,focusRegion,outW,outH,totalSeconds,regionalModel,Math.min(1.0,model.timeSeconds/totalSeconds));
            done.put("cancelled",stopRequested.get()||model.isStopped());
            done.put("finished",!stopRequested.get()&&!model.isStopped());
            callJs("onSolverFinished",done);
        } catch(Exception e) {
            callJs("onSolverError",errorObject(e.getMessage()==null?e.toString():e.getMessage()));
        } finally {
            activeModel=null;
            simulationThread=null;
        }
    }

    private JSONObject progressObject(TsunamiModel model,GridData grid,GridData.Bounds view,String region,
                                      int outW,int outH,double totalSeconds,boolean regionalModel,double progress) throws Exception {
        JSONObject obj=new JSONObject();
        obj.put("region",region); obj.put("viewTitle",regionTitle(region));
        obj.put("simSeconds",model.timeSeconds); obj.put("totalSeconds",totalSeconds);
        obj.put("progress",progress); obj.put("dt",model.dt); obj.put("steps",model.steps);
        obj.put("maxAbsEta",model.maxAbsEta());
        obj.put("gridResolution",regionalModel?"0.25°（约 28 km 纬向网格）":"1° 全球粗网格");
        obj.put("sourceNote",model.sourceNote);
        float[] etaMap=grid.preview(view,outW,outH,false,model.eta);
        obj.put("etaB64",Base64.encodeToString(GridData.floatsToLittleEndian(etaMap),Base64.NO_WRAP));
        JSONArray alerts=new JSONArray();
        for(TsunamiModel.Alert a:model.getAlerts(region)) {
            JSONObject x=new JSONObject(); x.put("name",a.name); x.put("heightMeters",a.heightMeters);
            x.put("level",a.level); x.put("color",a.color);
            if(Double.isNaN(a.arrivalSeconds)) x.put("arrivalSeconds",JSONObject.NULL); else x.put("arrivalSeconds",a.arrivalSeconds);
            if(Double.isNaN(a.peakSeconds)) x.put("peakSeconds",JSONObject.NULL); else x.put("peakSeconds",a.peakSeconds);
            alerts.put(x);
        }
        obj.put("alerts",alerts);
        return obj;
    }

    private static String readText(InputStream stream) throws Exception {
        try(InputStream in=stream; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[8192]; int n; while((n=in.read(b))!=-1) out.write(b,0,n);
            return out.toString("UTF-8");
        }
    }
}
