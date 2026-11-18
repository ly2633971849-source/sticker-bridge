package com.stickerbridge.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public final class State {
    public static final Handler MAIN=new Handler(Looper.getMainLooper());
    public static final ExecutorService IO=Executors.newSingleThreadExecutor();
    public static volatile String stage="准备好保存到相册",detail="选择原图片或动图，直接生成 QQ 可用的文件。";
    public static volatile boolean busy;
    public static volatile int progress=-1;
    public static volatile boolean savedResult;
    public static volatile boolean lookingForOriginal;
    private static JSONArray progressHistory=new JSONArray();
    public static volatile long jobId;
    public static volatile long jobStartedElapsed,noticeUntil,hintUntil;
    public static volatile String hint="";
    public static volatile JSONObject last=new JSONObject();
    public static volatile JSONObject selection=new JSONObject();
    public static volatile JSONArray gallery=new JSONArray();
    public static volatile boolean galleryMore;
    public static volatile int galleryLimit=36;
    public static volatile Runnable listener;
    private static boolean restored;
    public static SharedPreferences prefs(Context c){return c.getSharedPreferences("bridge",Context.MODE_PRIVATE);}
    public static synchronized void restore(Context c){
        if(restored)return;restored=true;
        try{last=new JSONObject(prefs(c).getString("albumLast","{}"));}catch(Exception ignored){}
        if(last.length()>0){stage="已保存到相册";detail="在 QQ 收藏中，从最近照片添加。";progress=100;savedResult=true;}
    }
    private static void recordProgress(){try{if(progressHistory.length()<128)progressHistory.put(new JSONObject().put("progress",progress).put("stage",stage).put("elapsedMs",Math.max(0,android.os.SystemClock.elapsedRealtime()-jobStartedElapsed)));}catch(Exception ignored){}}
    public static synchronized JSONArray progressTrace(){try{return new JSONArray(progressHistory.toString());}catch(Exception ignored){return new JSONArray();}}
    public static synchronized boolean beginTask(Diagnostics.Operation operation){if(busy)return false;Diagnostics.begin(operation);busy=true;progress=0;savedResult=false;lookingForOriginal=false;progressHistory=new JSONArray();jobId=Math.max(System.currentTimeMillis(),jobId+1);jobStartedElapsed=android.os.SystemClock.elapsedRealtime();noticeUntil=hintUntil=0;hint="";stage="准备读取表情";detail="正在取得选中的原表情，请稍候。";recordProgress();changed();return true;}
    /** One job owns one monotonic workflow counter; only finishSaved may publish 100. */
    public static synchronized void advanceProgress(int value){if(!busy)return;int next=Math.max(progress,Math.max(0,Math.min(99,value)));if(next==progress)return;progress=next;recordProgress();changed();}
    public static void phaseProgress(int from,int to,long completed,long total){advanceProgress(from+(int)((to-from)*Math.min(Math.max(0,completed),Math.max(1,total))/Math.max(1,total)));}
    public static synchronized void showHint(String text){hint=text;hintUntil=android.os.SystemClock.elapsedRealtime()+2500;changed();}
    public static synchronized void cancelSelection(long id){if(selection.optLong("selectionId")!=id)return;try{selection=new JSONObject(selection.toString()).put("pending",false).put("savedToAlbum",false);}catch(Exception ignored){}}
    public static synchronized String token(Context c){
        String token=prefs(c).getString("pairing","");if(!token.isEmpty())return token;
        byte[] bytes=new byte[24];new SecureRandom().nextBytes(bytes);StringBuilder s=new StringBuilder();
        for(byte b:bytes)s.append(String.format("%02x",b&255));token=s.toString();prefs(c).edit().putString("pairing",token).commit();return token;
    }
    public static void changed(){MAIN.post(()->{if(listener!=null)listener.run();if(BridgeAccessibility.live!=null)BridgeAccessibility.live.refreshProgress();});}
    public static void update(Context c,String next,String message){
        stage=next;detail=message;
        if(Diagnostics.debug(c))try{JSONObject e=new JSONObject().put("time",System.currentTimeMillis()).put("jobId",jobId).put("stage",next).put("detail",message).put("progress",progress);
            File log=new File(c.getFilesDir(),"events.jsonl");
            try(FileOutputStream f=new FileOutputStream(log,log.length()<1024*1024)){f.write((e.toString()+"\n").getBytes(StandardCharsets.UTF_8));}
        }catch(Exception ignored){}
        changed();
    }
    public static synchronized void finish(Context c,String next,String message){Diagnostics.end(Diagnostics.Result.COMPLETED,Diagnostics.Failure.NONE);finishTask(c,next,message,false);}
    public static synchronized void finishFailure(Context c,String next,String message,Diagnostics.Failure failure){Diagnostics.end(Diagnostics.Result.FAILED,failure);finishTask(c,next,message,false);}
    public static synchronized void finishSaved(Context c,String message){Diagnostics.end(Diagnostics.Result.SAVED,Diagnostics.Failure.NONE);finishTask(c,"已保存到相册",message,true);}
    private static void finishTask(Context c,String next,String message,boolean saved){savedResult=saved;lookingForOriginal=false;stage=next;if(saved){progress=100;recordProgress();}busy=false;noticeUntil=android.os.SystemClock.elapsedRealtime()+3000;hintUntil=0;hint="";if(selection.optBoolean("pending")&&selection.optLong("selectionId")==jobId)cancelSelection(jobId);update(c,next,message);}
    public static synchronized JSONObject status(Context c){
        restore(c);JSONObject s=new JSONObject();
        try{s.put("mode","selection_to_album").put("manualMatching",false).put("backgroundSourceIdentification",true).put("automaticQq",false).put("accessibility",BridgeAccessibility.live!=null).put("enabled",prefs(c).getBoolean("enabled",true)).put("stage",stage).put("detail",detail).put("busy",busy).put("progress",progress).put("jobId",jobId).put("galleryCount",gallery.length()).put("galleryMore",galleryMore).put("last",last).put("selection",selection);if(BridgeAccessibility.live!=null)s.put("picker",BridgeAccessibility.live.selectionStatus());}catch(Exception ignored){}
        try{s.put("stickerOnly",false).put("commentImages",true).put("shortVideos",true).put("pickerServiceEnabled",PickerSettings.enabled(c)).put("pickerRecoveryMessage",PickerSettings.message(c)).put("elapsedMs",busy?Math.max(0,android.os.SystemClock.elapsedRealtime()-jobStartedElapsed):0).put("savedResult",savedResult).put("progressTrace",progressTrace());if(BridgeAccessibility.live!=null)s.put("feedback",BridgeAccessibility.live.feedbackStatus());}catch(Exception ignored){}
        return s;
    }
}
