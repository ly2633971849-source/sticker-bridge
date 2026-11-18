package com.stickerbridge.app;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.HardwareBuffer;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;
import java.util.List;

public final class BridgeAccessibility extends AccessibilityService {
    public static volatile BridgeAccessibility live;
    private WindowManager windows;
    private Picker picker;
    private WindowManager.LayoutParams params;
    private boolean attached;
    private int bubbleX,bubbleY;
    private float density;
    private TextView progress;
    private boolean progressAttached;
    private int statusViewsCreated;
    private int statusWindowErrors;
    private String lastStatusError="";
    private long fallbackJob=-1;
    private final Runnable statusRefresh=this::refreshProgress;
    private final Runnable statusExpiry=()->{long now=android.os.SystemClock.elapsedRealtime();if(!State.busy&&now>=Math.max(State.noticeUntil,State.hintUntil))removeStatus();else refreshProgress();};
    private long lastSelectionId;
    private int rejectedSelections;
    private boolean capturePending;
    private final Runnable visibilityRefresh=this::syncBubbleVisibility;
    private final Runnable lateVisibilityRefresh=this::syncBubbleVisibility;
    private boolean receiverRegistered;
    private final android.content.BroadcastReceiver screenReceiver=new android.content.BroadcastReceiver(){@Override public void onReceive(android.content.Context c,Intent intent){if(Intent.ACTION_CONFIGURATION_CHANGED.equals(intent.getAction())){density=getResources().getDisplayMetrics().density;bubbleX=getResources().getDisplayMetrics().widthPixels-dp(60);rebuildBubble();}else refreshBubble();}};
    private final Runnable healthCheck=new Runnable(){@Override public void run(){refreshBubble();refreshProgress();State.MAIN.postDelayed(this,3000);}};
    public int dp(float n){return Math.round(n*density);}
    public Rect screenBounds(){try{Rect bounds=windows.getMaximumWindowMetrics().getBounds();if(bounds.width()>0&&bounds.height()>0)return new Rect(0,0,bounds.width(),bounds.height());}catch(RuntimeException ignored){}return new Rect(0,0,getResources().getDisplayMetrics().widthPixels,getResources().getDisplayMetrics().heightPixels);}
    @Override protected void onServiceConnected(){super.onServiceConnected();live=this;density=getResources().getDisplayMetrics().density;windows=getSystemService(WindowManager.class);bubbleX=getResources().getDisplayMetrics().widthPixels-dp(60);bubbleY=dp(260);if(!receiverRegistered){android.content.IntentFilter filter=new android.content.IntentFilter();filter.addAction(Intent.ACTION_SCREEN_ON);filter.addAction(Intent.ACTION_USER_PRESENT);filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);registerReceiver(screenReceiver,filter,android.os.Build.VERSION.SDK_INT>=33?android.content.Context.RECEIVER_NOT_EXPORTED:0);receiverRegistered=true;}State.MAIN.removeCallbacks(healthCheck);State.MAIN.postDelayed(healthCheck,3000);refreshBubble();State.changed();}
    @Override public void onAccessibilityEvent(AccessibilityEvent e){
        if(e.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED){syncBubbleVisibility();State.MAIN.removeCallbacks(lateVisibilityRefresh);State.MAIN.postDelayed(lateVisibilityRefresh,900);}
        if(!State.MAIN.hasCallbacks(visibilityRefresh))State.MAIN.postDelayed(visibilityRefresh,300);
    }
    @Override public void onInterrupt(){}
    @Override public void onDestroy(){State.MAIN.removeCallbacks(visibilityRefresh);State.MAIN.removeCallbacks(lateVisibilityRefresh);State.MAIN.removeCallbacks(healthCheck);State.MAIN.removeCallbacks(statusRefresh);State.MAIN.removeCallbacks(statusExpiry);if(receiverRegistered){unregisterReceiver(screenReceiver);receiverRegistered=false;}removeBubble();live=null;State.changed();super.onDestroy();}
    public void refreshBubble(){if(State.prefs(this).getBoolean("enabled",true)){if(attached&&(picker==null||!picker.isAttachedToWindow()))removeBubble();if(!attached)addBubble();syncBubbleVisibility();State.MAIN.removeCallbacks(visibilityRefresh);State.MAIN.postDelayed(visibilityRefresh,300);}else{removeBubble();}}
    public void rebuildBubble(){if(State.busy)return;capturePending=false;removeBubble();bubbleX=getResources().getDisplayMetrics().widthPixels-dp(60);bubbleY=dp(260);refreshBubble();State.changed();}
    private void recoverWindow(){try{if(picker!=null)windows.removeViewImmediate(picker);}catch(Exception ignored){}attached=false;State.MAIN.postDelayed(this::refreshBubble,120);}
    private boolean locked(){android.app.KeyguardManager keyguard=getSystemService(android.app.KeyguardManager.class);return keyguard!=null&&keyguard.isKeyguardLocked();}
    private void syncBubbleVisibility(){
        if(!attached||capturePending)return;
        if(locked()){if(picker.expanded)picker.collapse();picker.setVisibility(View.GONE);return;}
        AccessibilityNodeInfo root=primaryRoot();CharSequence owner=root==null?null:root.getPackageName();
        // Keep the global entry available outside this app without covering its own controls.
        if(owner!=null&&getPackageName().contentEquals(owner)){if(picker.expanded)picker.collapse();picker.setVisibility(View.GONE);return;}
        if(picker.expanded&&(owner==null||!"com.ss.android.ugc.aweme".contentEquals(owner)))picker.collapse();
        picker.setVisibility(View.VISIBLE);
    }
    private void addBubble(){if(attached)return;picker=new Picker();params=new WindowManager.LayoutParams(dp(52),dp(52),WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);params.gravity=Gravity.TOP|Gravity.LEFT;params.x=bubbleX;params.y=bubbleY;params.setTitle("表情直达选取框");try{windows.addView(picker,params);attached=true;}catch(Exception e){State.update(this,"按钮未显示",e.getMessage());}}
    private void removeStatus(){
        if(progress==null){progressAttached=false;return;}
        progress.setVisibility(View.GONE);progress.setText("");
        // Registration precedes View.onAttachedToWindow. Always retain this one view until removal.
        try{windows.removeViewImmediate(progress);progressAttached=false;}
        catch(IllegalArgumentException absent){progressAttached=false;}
        catch(RuntimeException error){statusWindowErrors++;lastStatusError=error.toString();State.MAIN.postDelayed(statusExpiry,100);}
    }
    private void removeBubble(){State.MAIN.removeCallbacks(statusRefresh);State.MAIN.removeCallbacks(statusExpiry);if(picker!=null){picker.sequence++;picker.previewing=false;picker.choosing=false;picker.dragging=false;}if(attached){try{windows.removeView(picker);}catch(Exception ignored){}attached=false;}removeStatus();}
    public org.json.JSONObject feedbackStatus(){org.json.JSONObject result=new org.json.JSONObject();try{result.put("attached",progressAttached).put("viewAttached",progress!=null&&progress.isAttachedToWindow()).put("visible",progressAttached&&progress!=null&&progress.isShown()).put("message",progress==null?"":String.valueOf(progress.getText())).put("hiddenForCapture",capturePending).put("viewInstances",statusViewsCreated).put("expiresInMs",State.busy?-1:Math.max(0,Math.max(State.noticeUntil,State.hintUntil)-android.os.SystemClock.elapsedRealtime())).put("windowErrors",statusWindowErrors).put("lastWindowError",lastStatusError).put("jobId",State.jobId);}catch(Exception ignored){}return result;}
    public void refreshProgress(){
        State.MAIN.removeCallbacks(statusRefresh);if(attached&&picker!=null)picker.invalidate();
        long now=android.os.SystemClock.elapsedRealtime(),until=Math.max(State.noticeUntil,State.hintUntil);
        State.MAIN.removeCallbacks(statusExpiry);if(!State.busy&&until>now)State.MAIN.postDelayed(statusExpiry,until-now);
        if(!attached||locked()||(!State.busy&&now>=until)){removeStatus();return;}
        if(capturePending){if(progressAttached)progress.setVisibility(View.INVISIBLE);return;}
        if(picker!=null&&picker.expanded){if(progressAttached)progress.setVisibility(View.INVISIBLE);State.MAIN.postDelayed(statusRefresh,200);return;}
        try{
            if(progress==null){
                progress=new TextView(this);progress.setTextColor(0xffc3f7d9);progress.setTextSize(13);progress.setMaxLines(4);progress.setEllipsize(android.text.TextUtils.TruncateAt.END);progress.setPadding(dp(16),dp(12),dp(16),dp(12));progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();background.setColor(0xf0182b21);background.setCornerRadius(dp(18));progress.setBackground(background);
                statusViewsCreated++;
            }
            if(!progressAttached){
                WindowManager.LayoutParams layout=new WindowManager.LayoutParams(getResources().getDisplayMetrics().widthPixels-dp(32),WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.TRANSLUCENT);layout.gravity=Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;layout.y=dp(36);layout.setTitle("表情直达保存状态");windows.addView(progress,layout);progressAttached=true;
            }
            String title=(!State.busy&&now<State.hintUntil)?"表情直达":State.stage;
            if(State.busy||(State.savedResult&&now<State.noticeUntil&&now>=State.hintUntil)){if(State.progress>=0)title+="  "+Math.min(100,State.progress)+"%";else{long seconds=(now-State.jobStartedElapsed)/1000;if(seconds>=3)title+=" · 已用 "+seconds+" 秒";}}
            String detail=now<State.hintUntil?State.hint:State.detail;String text=title+"\n"+detail;if(!text.contentEquals(progress.getText()))progress.setText(text);progress.setVisibility(View.VISIBLE);
        }catch(RuntimeException error){statusWindowErrors++;lastStatusError=error.toString();removeStatus();if(fallbackJob!=State.jobId){fallbackJob=State.jobId;android.widget.Toast.makeText(this,State.stage+"："+State.detail,android.widget.Toast.LENGTH_LONG).show();}}
        State.MAIN.postDelayed(statusRefresh,State.busy?250:Math.max(50,Math.min(1000,until-now)));
    }
    public AccessibilityNodeInfo primaryRoot(){List<AccessibilityWindowInfo> list=getWindowsOnAllDisplays().get(0);AccessibilityNodeInfo fallback=null;int layer=-1;if(list!=null){for(AccessibilityWindowInfo w:list)if(w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION){AccessibilityNodeInfo r=w.getRoot();if(r==null)continue;if(w.isActive()||w.isFocused())return r;if(w.getLayer()>layer){fallback=r;layer=w.getLayer();}}}return fallback!=null?fallback:getRootInActiveWindow();}
    AccessibilityNodeInfo sourceRoot(){if(locked())return null;AccessibilityNodeInfo root=primaryRoot();return root!=null&&root.getPackageName()!=null&&root.getPackageName().toString().equals("com.ss.android.ugc.aweme")?root:null;}
    private Rect imageAt(AccessibilityNodeInfo root,int x,int y){if(root==null)return null;Rect b=new Rect();root.getBoundsInScreen(b);if(!b.contains(x,y))return null;Rect best=null;String cls=String.valueOf(root.getClassName()),description=String.valueOf(root.getContentDescription()),id=String.valueOf(root.getViewIdResourceName());boolean mediaContainer=id.equals("com.ss.android.ugc.aweme:id/dx0")||id.equals("com.ss.android.ugc.aweme:id/fs=");if((mediaContainer||cls.contains("Image")||description.contains("表情")||description.contains("图片"))&&b.width()>dp(30)&&b.height()>dp(30)&&b.width()<dp(350)&&b.height()<dp(400))best=new Rect(b);for(int i=0;i<root.getChildCount();i++){Rect child=imageAt(root.getChild(i),x,y);if(child!=null&&(best==null||child.width()*child.height()<best.width()*best.height()))best=child;}return best;}
    public interface ScreenCallback{void ready(Bitmap b);void failed(int code);}
    public void screenshot(ScreenCallback callback){takeScreenshot(0,command->State.MAIN.post(command),new TakeScreenshotCallback(){@Override public void onSuccess(ScreenshotResult result){HardwareBuffer hardware=result.getHardwareBuffer();Bitmap wrapper=null;try{wrapper=Bitmap.wrapHardwareBuffer(hardware,result.getColorSpace());if(wrapper==null)throw new IllegalStateException();Bitmap copy=wrapper.copy(Bitmap.Config.ARGB_8888,false);callback.ready(copy);}catch(Exception e){callback.failed(-1);}finally{if(wrapper!=null)wrapper.recycle();hardware.close();}}@Override public void onFailure(int code){callback.failed(code);}});}
    private void selected(int x,int y,SelectionTargets.Target hit,String scope,String mode,int targetCount,int originX,int originY){
        if(hit==null||sourceRoot()==null){Jobs.toast(this,"请先在抖音里选中图片、动图或小视频");return;}
        if(!State.beginTask(Diagnostics.Operation.PICK)){rejectedSelections++;Jobs.toast(this,"上一张表情仍在处理中");return;}
        Diagnostics.phase(Diagnostics.Phase.CAPTURE);
        final Rect bounds=new Rect(hit.bounds);
        final long selectionId=State.jobId;lastSelectionId=selectionId;
        try{
            org.json.JSONObject report=new org.json.JSONObject().put("selectionId",selectionId).put("time",System.currentTimeMillis()).put("mode",mode).put("x",x).put("y",y).put("bounds",bounds.flattenToString()).put("nodeBounds",hit.nodeBounds.flattenToString()).put("automaticVisualBounds",hit.previewViewport).put("animationExpected",hit.animationExpected).put("overlayOriginX",originX).put("overlayOriginY",originY).put("scope",scope).put("targets",targetCount).put("id",hit.id);
            if(Diagnostics.debug(this))java.nio.file.Files.write(new java.io.File(getFilesDir(),"selection.json").toPath(),report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            State.selection=new org.json.JSONObject(report.toString()).put("pending",true);
        }catch(Exception ignored){}
        capturePending=true;picker.setVisibility(View.INVISIBLE);if(progressAttached)progress.setVisibility(View.INVISIBLE);
        State.MAIN.postDelayed(()->{
            if(sourceRoot()==null){capturePending=false;syncBubbleVisibility();State.finishFailure(this,"选择已取消","画面已经切换，请回到目标表情后重新选择。",Diagnostics.Failure.CANCELLED);return;}
            screenshot(new ScreenCallback(){
                public void ready(Bitmap bitmap){
                    capturePending=false;syncBubbleVisibility();
                    if(!sameTarget(hit,x,y)||live!=BridgeAccessibility.this){cancelCapture(bitmap,selectionId,"图片位置或当前页面已变化，请重新选择。");return;}
                    if(hit.previewViewport){
                        Rect fitted=PreviewGeometry.fit(bitmap,bounds,density,null);
                        if(fitted==null){cancelCapture(bitmap,selectionId,"当前页面未提供清晰图片边界，请回到评论里的图片后重新选择。");return;}
                        bounds.set(fitted);
                        try{org.json.JSONObject report=new org.json.JSONObject(State.selection.toString()).put("bounds",bounds.flattenToString());if(Diagnostics.debug(BridgeAccessibility.this))java.nio.file.Files.write(new java.io.File(getFilesDir(),"selection.json").toPath(),report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));State.selection=report;}catch(Exception ignored){}
                        picker.expand();picker.previewing=true;picker.target=new Rect(bounds);picker.invalidate();
                        State.MAIN.postDelayed(()->{picker.collapse();if(!sameTarget(hit,x,y)||live!=BridgeAccessibility.this){cancelCapture(bitmap,selectionId,"当前图片已经切换，请重新选择。");return;}Jobs.pick(BridgeAccessibility.this,bitmap,x,y,bounds,scope,selectionId,hit.id,hit.description,hit.rank,hit.animationExpected);},180);
                    }else Jobs.pick(BridgeAccessibility.this,bitmap,x,y,bounds,scope,selectionId,hit.id,hit.description,hit.rank,hit.animationExpected);
                }
                public void failed(int code){capturePending=false;syncBubbleVisibility();State.finishFailure(BridgeAccessibility.this,"无法读取选取区域","当前窗口未提供单张画面（"+code+"），请返回目标表情后重试。",Diagnostics.Failure.CAPTURE_UNAVAILABLE);Jobs.toast(BridgeAccessibility.this,State.detail);}
            });
        },160);
    }
    private boolean sameTarget(SelectionTargets.Target previous,int x,int y){AccessibilityNodeInfo root=sourceRoot();Rect display=screenBounds();SelectionTargets.Target current=SelectionTargets.at(SelectionTargets.collect(root,density,display.width(),display.height()),x,y);return current!=null&&current.nodeBounds.equals(previous.nodeBounds)&&current.id.equals(previous.id);}
    private void cancelCapture(Bitmap bitmap,long id,String message){if(!bitmap.isRecycled())bitmap.recycle();State.cancelSelection(id);if(State.busy&&State.jobId==id)State.finishFailure(this,"选择已取消",message,Diagnostics.Failure.CANCELLED);}
    public org.json.JSONObject selectionStatus(){
        org.json.JSONObject j=new org.json.JSONObject();try{AccessibilityNodeInfo root=primaryRoot();j.put("primaryPackage",root==null?"":String.valueOf(root.getPackageName()));j.put("selectionId",lastSelectionId).put("rejectedWhileBusy",rejectedSelections).put("entryAttached",attached).put("entryVisible",attached&&picker!=null&&picker.isShown()).put("keyguardLocked",locked());if(picker!=null){int[] location=new int[2];picker.getLocationOnScreen(location);j.put("entryBounds",new Rect(location[0],location[1],location[0]+picker.getWidth(),location[1]+picker.getHeight()).flattenToString());j.put("expanded",picker.expanded).put("choosing",picker.choosing).put("previewing",picker.previewing).put("targets",picker.targets.size()).put("target",picker.target==null?org.json.JSONObject.NULL:picker.target.flattenToString());}}catch(Exception ignored){}return j;
    }
    private final class Picker extends View {
        private final Paint paint=new Paint(3);
        private float downX,downY,rawX,rawY;
        private boolean dragging,choosing,previewing,expanded,blocked;
        private int originX,originY,bubbleInsets,sequence;
        private Rect target;
        private java.util.List<SelectionTargets.Target> targets=new java.util.ArrayList<>();
        private String scope="all";
        Picker(){super(BridgeAccessibility.this);setContentDescription("表情直达，点一下后选择表情，也可按住拖选");setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
        private void collect(){AccessibilityNodeInfo root=sourceRoot();Rect display=screenBounds();targets=SelectionTargets.collect(root,density,display.width(),display.height());scope=SelectionTargets.isIm(root)?"im":"comments";}
        private void expand(){if(expanded)return;bubbleInsets=params.getFitInsetsTypes();expanded=true;params.width=-1;params.height=-1;params.x=0;params.y=0;params.setFitInsetsTypes(0);try{windows.updateViewLayout(this,params);}catch(RuntimeException error){recoverWindow();}}
        private void collapse(){sequence++;dragging=false;choosing=false;previewing=false;target=null;if(expanded){expanded=false;params.width=dp(52);params.height=dp(52);params.x=bubbleX;params.y=bubbleY;params.setFitInsetsTypes(bubbleInsets);try{windows.updateViewLayout(this,params);}catch(RuntimeException error){recoverWindow();}}invalidate();}
        private SelectionTargets.Target hit(){return SelectionTargets.at(targets,(int)rawX,(int)rawY);}
        private void setTarget(){SelectionTargets.Target h=hit();target=h==null?null:new Rect(h.bounds);invalidate();}
        private void commit(SelectionTargets.Target choice,int x,int y,String mode){
            int count=targets.size(),ox=originX,oy=originY;String selectedScope=scope;
            // Reject a layout change rather than silently selecting a different image.
            collect();SelectionTargets.Target current=SelectionTargets.at(targets,x,y);
            if(choice==null||current==null||!current.bounds.equals(choice.bounds)||!current.id.equals(choice.id)){collapse();Jobs.toast(BridgeAccessibility.this,"图片位置刚发生变化，请重新点选");return;}
            collapse();performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
            selected(x,y,current,selectedScope,mode,count,ox,oy);
        }
        private void choose(){
            if(sourceRoot()==null){Jobs.toast(BridgeAccessibility.this,"请先在抖音里打开想搬的表情");return;}
            collect();if(targets.isEmpty()){Jobs.toast(BridgeAccessibility.this,"没有找到可选图片或视频，请点开目标后再选");return;}
            expand();choosing=true;target=null;invalidate();
            if(targets.size()==1&&targets.get(0).rank<=2){
                SelectionTargets.Target only=targets.get(0);target=new Rect(only.bounds);previewing=true;invalidate();final int version=++sequence;
                State.MAIN.postDelayed(()->{if(attached&&expanded&&previewing&&sequence==version){if(State.busy){collapse();return;}commit(only,only.bounds.centerX(),only.bounds.centerY(),"single_tap");}},550);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            int action=event.getActionMasked();rawX=event.getRawX();rawY=event.getRawY();
            if(action==MotionEvent.ACTION_DOWN){
                blocked=false;
                if(State.busy){rejectedSelections++;blocked=true;collapse();Jobs.toast(BridgeAccessibility.this,"正在处理上一张表情，请稍候");return true;}
                if(sourceRoot()==null){blocked=true;collapse();Jobs.toast(BridgeAccessibility.this,"请先打开抖音评论或聊天，再用「选」选取表情");return true;}
                if(expanded&&event.getY()<dp(120)&&event.getX()>getWidth()-dp(76)){collapse();blocked=true;return true;}
                if(previewing)return true;
                downX=rawX;downY=rawY;dragging=false;collect();setTarget();return true;
            }
            if(blocked)return true;
            if(action==MotionEvent.ACTION_CANCEL){collapse();return true;}
            if(previewing)return true;
            if(action==MotionEvent.ACTION_MOVE){
                if(!choosing&&!dragging&&Math.hypot(rawX-downX,rawY-downY)>dp(10)){dragging=true;expand();}
                if(dragging||choosing)setTarget();return true;
            }
            if(action==MotionEvent.ACTION_UP){
                if(dragging||choosing){SelectionTargets.Target h=hit();String mode=dragging?"drag":"tap_target";if(h!=null)commit(h,(int)rawX,(int)rawY,mode);else if(dragging){collapse();Jobs.toast(BridgeAccessibility.this,"还没选中图片，可点「选」后直接点图片");}else{target=null;invalidate();}return true;}
                choose();return true;
            }
            return true;
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);paint.setTypeface(android.graphics.Typeface.DEFAULT);
            if(expanded){
                int[] origin=new int[2];getLocationOnScreen(origin);originX=origin[0];originY=origin[1];
                paint.setStyle(Paint.Style.FILL);paint.setColor(0xf0182b21);canvas.drawRoundRect(new RectF(dp(16),dp(64),getWidth()-dp(16),dp(116)),dp(18),dp(18),paint);
                paint.setColor(0xffc3f7d9);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(dp(13));
                String hint=previewing?"已贴合，正在保存到相册":dragging?(target==null?"移到表情内，边框会自动贴合":"已贴合，松手保存到相册"):"点一下表情，自动贴合并保存";
                canvas.drawText(hint,getWidth()/2f-dp(18),dp(97),paint);paint.setTextSize(dp(21));canvas.drawText("×",getWidth()-dp(44),dp(98),paint);
                canvas.save();canvas.translate(-originX,-originY);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.5f));
                if(choosing&&!previewing){paint.setColor(0x998cf0b8);for(SelectionTargets.Target t:targets)canvas.drawRoundRect(new RectF(t.bounds),dp(4),dp(4),paint);}
                if(target!=null){paint.setColor(0x2269e5a1);paint.setStyle(Paint.Style.FILL);canvas.drawRoundRect(new RectF(target),dp(4),dp(4),paint);paint.setColor(0xff8cf0b8);paint.setStyle(Paint.Style.STROKE);canvas.drawRoundRect(new RectF(target),dp(4),dp(4),paint);}
                if(dragging)canvas.drawCircle(rawX,rawY,dp(5),paint);canvas.restore();paint.setStyle(Paint.Style.FILL);return;
            }
            paint.setColor(State.busy?0xfff4cc76:0xff8cf0b8);canvas.drawCircle(getWidth()/2f,getHeight()/2f,dp(23),paint);paint.setColor(0xff163525);paint.setTextSize(dp(21));paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);paint.setTextAlign(Paint.Align.CENTER);canvas.drawText(State.busy?"…":"选",getWidth()/2f,getHeight()/2f+dp(7),paint);
        }
    }
}
