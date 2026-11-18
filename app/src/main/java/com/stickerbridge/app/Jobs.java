package com.stickerbridge.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;
import org.json.JSONArray;

/** Select on screen, resolve the original in the background, and stop after saving to the album. */
public final class Jobs {
    private static final long MAX_BYTES=32L*1024*1024;
    private static final long LOOKUP_BUDGET_MS=12000;
    public static void toast(Context c,String message){State.showHint(message);if(BridgeAccessibility.live==null||!State.prefs(c).getBoolean("enabled",true))State.MAIN.post(()->Toast.makeText(c,message,Toast.LENGTH_LONG).show());}
    private static boolean begin(Context c,Diagnostics.Operation operation){if(State.beginTask(operation))return true;toast(c,"正在处理，请等这一张保存完成");return false;}
    public static void pick(Context context,Bitmap screen,int x,int y,Rect bounds,String scope,long selectionId,String targetId,String targetDescription,int targetRank,boolean animationExpected){
        Context c=context.getApplicationContext();Diagnostics.phase(Diagnostics.Phase.SOURCE_LOOKUP);State.lookingForOriginal=true;State.update(c,"正在查找原件","找到可用原件后才开始转换。");State.advanceProgress(5);
        State.IO.execute(()->{File source=null;try{
            long lookupStarted=android.os.SystemClock.elapsedRealtime();
            if(targetRank<0||targetRank>=99)throw new IllegalArgumentException("当前选区不是图片或视频，请重新选择目标");
            Rect crop=new Rect(bounds);if(!crop.intersect(0,0,screen.getWidth(),screen.getHeight())||crop.isEmpty())throw new IllegalArgumentException("选框已离开图片，请重新选择");
            Rect badge=!animationExpected&&!scope.equals("im")&&targetRank>=2?MotionBadge.find(screen,crop,c.getResources().getDisplayMetrics().density):null;
            final boolean motionRequired=animationExpected||badge!=null;
            if(motionRequired){JSONObject current=new JSONObject(State.selection.toString()).put("animationExpected",true).put("motionEvidence",badge==null?"accessibility_label":"visual_live_badge");if(badge!=null)current.put("motionBadgeBounds",badge.flattenToString());State.selection=current;if(Diagnostics.debug(c))Files.write(new File(c.getFilesDir(),"selection.json").toPath(),current.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            SourceClient client=State.prefs(c).getBoolean("force_single_frame",false)?null:new SourceClient(c);
            if(client==null||!client.supportsOriginals()){
                if(motionRequired)throw new JobFailure(Diagnostics.Failure.MOTION_UNAVAILABLE,"动画原件暂不可读。请从抖音分享或保存原文件后，再导入表情直达。");
                convertSelectionFrame(c,screen,crop,selectionId,targetRank);return;
            }
            if(Diagnostics.debug(c)){
                Bitmap preview=Bitmap.createBitmap(screen,crop.left,crop.top,crop.width(),crop.height());
                try(FileOutputStream out=new FileOutputStream(new File(c.getFilesDir(),"selection-preview.png"))){preview.compress(Bitmap.CompressFormat.PNG,100,out);}
                finally{if(preview!=screen)preview.recycle();}
            }
            long deadline=android.os.SystemClock.elapsedRealtime()+LOOKUP_BUDGET_MS;float aspect=(float)crop.width()/crop.height(),density=c.getResources().getDisplayMetrics().density;
            String sourceScope=scope.equals("im")?"im":"comments";JSONArray known=client.known(c,sourceScope,aspect);
            if(motionRequired){JSONArray animated=new JSONArray();for(int i=0;i<known.length();i++)if(known.getJSONObject(i).optBoolean("animated"))animated.put(known.getJSONObject(i));known=animated;}
            List<Matcher.Hit> hits=refine(c,client,screen,x,y,crop,scope,Matcher.find(screen,x,y,crop,c.getResources().getDisplayMetrics().density,known),deadline);
            boolean cached=confident(hits,.105,.02),fast=cached;
            int animationPool=0;
            if(!cached){
                JSONArray items=known,loaded;Set<String> scanned=new HashSet<>();
                // A visible IM sticker can be older than hundreds of recently cached avatars.
                // Search the bounded animation pool before unrelated still files; static stickers retain fallback.
                if(scope.equals("im")||motionRequired){
                    final JSONArray seed=known;
                    Set<String> earlyRefinements=new HashSet<>();
                    long earlyDeadline=Math.min(deadline,android.os.SystemClock.elapsedRealtime()+5000);
                    loaded=client.sourceIndex(c,96,sourceScope,aspect,deadline,5,16,true,scanned,partial->matchAnimationBatch(c,client,screen,x,y,crop,density,seed,partial,earlyRefinements,earlyDeadline));animationPool=loaded.length();items=SourceClient.merge(items,loaded);
                    hits=refine(c,client,screen,x,y,crop,scope,Matcher.find(screen,x,y,crop,density,items),deadline);fast=confident(hits,.075,.025);
                }
                if(!fast&&android.os.SystemClock.elapsedRealtime()<deadline){loaded=client.sourceIndex(c,16,sourceScope,aspect,deadline,5,16,motionRequired,scanned);items=SourceClient.merge(items,loaded);hits=refine(c,client,screen,x,y,crop,scope,Matcher.find(screen,x,y,crop,density,items),deadline);fast=confident(hits,.045,.03);}
                if(!fast&&android.os.SystemClock.elapsedRealtime()<deadline){loaded=client.sourceIndex(c,64,sourceScope,aspect,deadline,12,20,motionRequired,scanned);items=SourceClient.merge(items,loaded);hits=refine(c,client,screen,x,y,crop,scope,Matcher.find(screen,x,y,crop,density,items),deadline);fast=confident(hits,.075,.025);}
                if(!fast&&android.os.SystemClock.elapsedRealtime()<deadline){loaded=client.sourceIndex(c,2400,scope.equals("im")?"all":"comments",aspect,deadline,20,32,motionRequired,scanned);items=SourceClient.merge(items,loaded);hits=refine(c,client,screen,x,y,crop,scope,Matcher.find(screen,x,y,crop,density,items),deadline);}
            }
            if(Diagnostics.debug(c)){
                JSONObject report=new JSONObject().put("selectionId",selectionId).put("selectedBounds",crop.flattenToString()).put("scope",scope).put("fastPath",fast).put("knownSourcePool",known.length()).put("knownSourceResolved",cached).put("animationPool",animationPool).put("lookupBudgetMs",LOOKUP_BUDGET_MS).put("lookupElapsedMs",android.os.SystemClock.elapsedRealtime()-lookupStarted);JSONArray scores=new JSONArray();for(int i=0;i<Math.min(3,hits.size());i++){JSONObject m=new JSONObject(hits.get(i).media.toString());m.remove("thumbs");m.put("sourceScore",hits.get(i).score);scores.put(m);}report.put("sources",scores);
                Files.write(new File(c.getFilesDir(),"source-resolution.json").toPath(),report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if(!confident(hits,.105,.02)){
                if(android.os.SystemClock.elapsedRealtime()>=deadline)throw new JobFailure(Diagnostics.Failure.LOOKUP_TIMEOUT,
                        "查找原件超时，未保存。若抖音能保存或分享完整文件，可导入表情直达。");
                throw new JobFailure(motionRequired?Diagnostics.Failure.MOTION_UNAVAILABLE:Diagnostics.Failure.NO_ORIGINAL,
                        motionRequired?"没有找到这张动图的完整原件，未保存。若抖音能保存或分享文件，可导入表情直达。"
                                :"没有找到与选区匹配的原件，未保存。若抖音能保存或分享文件，可导入表情直达。");
            }
            State.lookingForOriginal=false;
            State.advanceProgress(32);
            Matcher.Hit selected=hits.get(0);JSONObject selection=new JSONObject().put("selectionId",selectionId).put("selectedBounds",crop.flattenToString()).put("sourceScore",selected.score).put("lookupMs",android.os.SystemClock.elapsedRealtime()-lookupStarted).put("targetRank",targetRank).put("animationExpected",motionRequired);
            Diagnostics.phase(Diagnostics.Phase.INPUT_READ);if(!screen.isRecycled())screen.recycle();long readStarted=android.os.SystemClock.elapsedRealtime();source=client.fetch(c,selected.media.getString("path"));String sourceHash=sha256(source);selection.put("sourceReadMs",android.os.SystemClock.elapsedRealtime()-readStarted);
            if(!selected.media.optString("sha256").isEmpty()&&!sourceHash.equals(selected.media.optString("sha256"))){client.forget(c,selected.media.getString("path"));throw new IllegalArgumentException("原文件已更新，这次未保存，请重新选择");}
            convert(c,source,selected.media.getString("path"),"screen_selection",selectionId,selection,selected.media,sourceHash);client.remember(c,selected.media);
        }catch(OutOfMemoryError e){State.cancelSelection(selectionId);lowMemory(c);}catch(Exception e){State.cancelSelection(selectionId);fail(c,e);}finally{if(!screen.isRecycled())screen.recycle();if(source!=null)source.delete();}});
    }
    private static boolean confident(List<Matcher.Hit> hits,double threshold,double gap){return !hits.isEmpty()&&hits.get(0).score<threshold&&(hits.size()==1||hits.get(1).score-hits.get(0).score>gap);}
    private static boolean matchAnimationBatch(Context c,SourceClient client,Bitmap screen,int x,int y,Rect bounds,float density,JSONArray known,JSONArray batch,Set<String> attempted,long deadline)throws Exception{
        List<Matcher.Hit> hits=Matcher.find(screen,x,y,bounds,density,SourceClient.merge(known,batch));
        if(confident(hits,.045,.03))return true;
        if(batch.length()<16||attempted.size()>=2||android.os.SystemClock.elapsedRealtime()>=deadline||!confident(hits,.15,.025))return false;
        JSONObject best=hits.get(0).media;String path=best.getString("path");
        if(!SourceClient.needsRefine(best)||attempted.contains(path))return false;
        for(int index=0;index<batch.length();index++)if(batch.getJSONObject(index).getString("path").equals(path)){
            attempted.add(path);batch.put(index,client.refine(c,best,deadline));
            return confident(Matcher.find(screen,x,y,bounds,density,SourceClient.merge(known,batch)),.035,.03);
        }
        return false;
    }
    private static List<Matcher.Hit> refine(Context c,SourceClient client,Bitmap screen,int x,int y,Rect bounds,String scope,List<Matcher.Hit> hits,long deadline)throws Exception{
        if(hits.isEmpty()||hits.get(0).score<.035||confident(hits,.045,.03))return hits;JSONArray items=new JSONArray();boolean changed=false;java.util.LinkedHashSet<Integer> selected=new java.util.LinkedHashSet<>();
        // A very different first frame must not exclude a newly loaded animation from phase coverage.
        java.util.ArrayList<Integer> recent=new java.util.ArrayList<>();
        for(int i=0;i<hits.size();i++){JSONObject media=hits.get(i).media;boolean im=media.optString("path").contains("/im_fresco_cache/");if(SourceClient.needsRefine(media)&&im==scope.equals("im"))recent.add(i);}
        recent.sort((a,b)->Long.compare(hits.get(b).media.optLong("modified"),hits.get(a).media.optLong("modified")));
        // Refine the closest candidates first, including older originals already on screen.
        for(int i=0;i<Math.min(12,hits.size())&&selected.size()<4;i++)if(SourceClient.needsRefine(hits.get(i).media)&&hits.get(i).score<.42)selected.add(i);
        int recentCount=0;for(int i:recent){if(selected.size()>=6||recentCount>=2)break;if(selected.add(i))recentCount++;}
        int remembered=0;for(int i=0;i<hits.size()&&selected.size()<8&&remembered<2;i++){JSONObject media=hits.get(i).media;if(media.optBoolean("recentlyUsed")&&SourceClient.needsRefine(media)&&selected.add(i))remembered++;}
        for(int i=0;i<Math.min(12,hits.size())&&selected.size()<8;i++){JSONObject media=hits.get(i).media;if(SourceClient.needsRefine(media)&&hits.get(i).score<.42)selected.add(i);}
        for(Matcher.Hit hit:hits)items.put(hit.media);
        for(int index:selected){if(android.os.SystemClock.elapsedRealtime()>=deadline)break;try{items.put(index,client.refine(c,hits.get(index).media,deadline));changed=true;List<Matcher.Hit> checked=Matcher.find(screen,x,y,bounds,c.getResources().getDisplayMetrics().density,items);if(confident(checked,.105,.02))return checked;}catch(Exception ignored){}}
        return changed?Matcher.find(screen,x,y,bounds,c.getResources().getDisplayMetrics().density,items):hits;
    }
    private static void convertSelectionFrame(Context c,Bitmap screen,Rect crop,long id,int targetRank)throws Exception{
        State.lookingForOriginal=false;
        Diagnostics.phase(Diagnostics.Phase.CONVERT);
        State.update(c,"保存静态截图","正在将选框中的单帧画面保存为 PNG。");
        File source=File.createTempFile("selected_frame_",".png",c.getCacheDir());
        Bitmap frame=screen;
        try{
            frame=Bitmap.createBitmap(screen,crop.left,crop.top,crop.width(),crop.height());
            float scale=Math.min(1f,480f/Math.max(frame.getWidth(),frame.getHeight()));
            if(scale<1f){Bitmap scaled=Bitmap.createScaledBitmap(frame,Math.max(1,Math.round(frame.getWidth()*scale)),Math.max(1,Math.round(frame.getHeight()*scale)),true);if(frame!=screen)frame.recycle();frame=scaled;}
            try(FileOutputStream out=new FileOutputStream(source)){if(!frame.compress(Bitmap.CompressFormat.PNG,100,out))throw new java.io.IOException("截图生成失败，请重新选择");}
            JSONObject selection=new JSONObject().put("selectionId",id).put("selectedBounds",crop.flattenToString()).put("targetRank",targetRank).put("animationExpected",false).put("screenFrame",true);
            convert(c,source,"选区截图","selection_frame",id,selection);
        }finally{if(frame!=screen)frame.recycle();source.delete();}
    }
    public static boolean openLibrary(Context context,boolean more){
        Context c=context.getApplicationContext();if(!begin(c,Diagnostics.Operation.LIBRARY))return false;
        Diagnostics.phase(Diagnostics.Phase.SOURCE_LOOKUP);
        final int limit=more?Math.min(180,State.galleryLimit+36):36;
        State.update(c,"读取最近素材","正在载入抖音已加载的原图片和动图…");
        State.IO.execute(()->{try{
            SourceClient.Gallery result=new SourceClient(c).recent(c,limit);
            State.gallery=result.items;State.galleryMore=result.more&&limit<180;State.galleryLimit=limit;
            State.finish(c,"选择一张，直接保存",result.items.length()==0?"暂未读到素材。先在抖音点开表情，让原图加载完成，再回来刷新。":"已载入 "+result.items.length()+" 张原素材，点哪张就保存哪张。");
        }catch(Exception e){fail(c,e);}});return true;
    }
    public static boolean convertPath(Context context,String path){
        Context c=context.getApplicationContext();if(!begin(c,Diagnostics.Operation.LIBRARY))return false;final long id=State.jobId;
        Diagnostics.phase(Diagnostics.Phase.INPUT_READ);
        State.update(c,"读取原文件","正在读取你点选的这一张…");State.advanceProgress(5);
        State.IO.execute(()->{File source=null;try{source=new SourceClient(c).fetch(c,path);convert(c,source,path,"douyin_recent",id);}catch(OutOfMemoryError e){lowMemory(c);}catch(Exception e){fail(c,e);}finally{if(source!=null)source.delete();}});return true;
    }
    public static boolean convertShared(Context context,Uri uri){
        return convertShared(context,uri,null);
    }
    public static boolean convertCroppedVideo(Context context,Uri uri,RectF crop){
        if(crop==null)throw new IllegalArgumentException("请先圈出要保留的画面");
        return convertShared(context,uri,new RectF(crop));
    }
    private static boolean convertShared(Context context,Uri uri,RectF crop){
        Context c=context.getApplicationContext();if(!begin(c,Diagnostics.Operation.IMPORT))return false;final long id=State.jobId;
        Diagnostics.phase(Diagnostics.Phase.INPUT_READ);
        State.update(c,"读取原文件","正在接收图片或动图…");State.advanceProgress(5);
        State.IO.execute(()->{
            File source=new File(c.getCacheDir(),"received_"+id+".bin");
            try{
                if(uri==null||!"content".equals(uri.getScheme()))throw new IllegalArgumentException("请通过系统选图，或分享原图片文件到表情直达");
                String name="分享的原文件";
                try(Cursor cursor=c.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())name=cursor.getString(0);}catch(Exception ignored){}
                try(java.io.InputStream in=c.getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(source)){
                    if(in==null)throw new java.io.IOException("无法打开原文件，请重新选择");
                    byte[] block=new byte[32768];long size=0;int n;
                    while((n=in.read(block))!=-1){size+=n;if(size>MAX_BYTES)throw new IllegalArgumentException("原文件超过 32 MB，请选择较小的图片或短视频");out.write(block,0,n);}
                    if(size==0)throw new IllegalArgumentException("收到的是空文件，请重新选择原图片");
                }
                convert(c,source,name==null?"原图片":name,crop==null?"shared_file":"cropped_video",id,null,null,null,crop);
            }catch(OutOfMemoryError e){lowMemory(c);}catch(Exception e){fail(c,e);}finally{source.delete();}
        });return true;
    }
    static String sha256(File file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(FileInputStream in=new FileInputStream(file)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
        return SourceClient.hex(digest.digest());
    }
    private static void convert(Context c,File source,String original,String input,long id)throws Exception{
        convert(c,source,original,input,id,null);
    }
    private static void convert(Context c,File source,String original,String input,long id,JSONObject selection)throws Exception{
        convert(c,source,original,input,id,selection,null,null);
    }
    private static void convert(Context c,File source,String original,String input,long id,JSONObject selection,JSONObject verifiedMeta,String verifiedHash)throws Exception{
        convert(c,source,original,input,id,selection,verifiedMeta,verifiedHash,null);
    }
    private static void convert(Context c,File source,String original,String input,long id,JSONObject selection,JSONObject verifiedMeta,String verifiedHash,RectF crop)throws Exception{
        Diagnostics.phase(Diagnostics.Phase.MEDIA_INSPECT);
        long conversionStarted=android.os.SystemClock.elapsedRealtime(),decodeMs=0,encodeMs=0;String sourceHash=verifiedHash==null?sha256(source):verifiedHash;JSONObject cached=crop==null?ConversionCache.get(c,sourceHash):null;
        JSONObject meta=verifiedMeta;
        // A previous PNG is not proof that the input was static: re-read its container metadata.
        if(meta==null&&cached!=null&&cached.optInt("frames")>1)meta=new JSONObject().put("kind",cached.getString("sourceKind")).put("frames",cached.optInt("sourceFrames")).put("duration",cached.optInt("sourceDuration")).put("width",cached.optInt("sourceWidth")).put("height",cached.optInt("sourceHeight")).put("animated",true);
        if(meta==null)meta=MediaFiles.describe(source,false);String kind=meta.getString("kind");
        if(crop!=null&&(!kind.equals("video")||!meta.optBoolean("animated")))throw new IllegalArgumentException("请选一段有多个画面的视频");
        if(meta.optBoolean("motionUnavailable"))throw new JobFailure(Diagnostics.Failure.MOTION_UNAVAILABLE,"这份实况文件缺少动画片段，未保存为静态图片。请提供完整原文件。");
        if((kind.equals("video")||kind.equals("live_photo"))&&!meta.optBoolean("animated"))throw new IllegalArgumentException("这个视频的动画信息尚未读完整，未保存为静态图片。请重新导入完整原文件。");
        if(cached!=null&&cached.optInt("frames")<2&&meta.optBoolean("animated"))cached=null;
        // Check before consulting/publishing the old static conversion of this same cover.
        if(selection!=null&&selection.optBoolean("animationExpected")&&(!meta.optBoolean("animated")||(cached!=null&&cached.optInt("frames")<2)))throw new IllegalArgumentException("这张动态图只有静态封面，动画原件暂不可读，未保存。请导入完整视频或动态图原文件。");
        File dir=new File(c.getFilesDir(),"exports");if(!dir.isDirectory()&&!dir.mkdirs())throw new java.io.IOException("无法创建导出目录");
        File output=cached==null?new File(dir,"sticker_"+id+(meta.optBoolean("animated")?".gif":".png")):new File(cached.getString("output"));
        boolean saved=false;
        try{
            Diagnostics.phase(Diagnostics.Phase.CONVERT);
            State.update(c,"生成表情",cached!=null?"正在快速准备表情…":meta.optBoolean("animated")?"正在保留动画，生成 GIF…":"正在生成清晰的 PNG 图片…");State.advanceProgress(35);
            if(cached!=null){}
            else if(input.equals("selection_frame")||kind.equals("gif")&&meta.optBoolean("animated")){long started=android.os.SystemClock.elapsedRealtime();Files.copy(source.toPath(),output.toPath());encodeMs=android.os.SystemClock.elapsedRealtime()-started;}
            else if((kind.equals("video")||kind.equals("live_photo"))&&meta.optBoolean("animated")){
                int maximumFrames=meta.optInt("duration")>15000?240:90;
                try(VideoFrames frames=new VideoFrames(source,320,maximumFrames,crop);FileOutputStream bytes=new FileOutputStream(output);GifWriter gif=new GifWriter(bytes,frames.width,frames.height)){
                    int written=0;int[] pixels=new int[frames.width*frames.height];
                    int[] first=crop==null?null:new int[pixels.length];boolean changed=false;
                    for(int i=0;i<frames.count;i++){
                        Bitmap frame=frames.next();if(frame==null)throw new IllegalArgumentException("原动画帧尚未读完，未保存");
                        try{long started=android.os.SystemClock.elapsedRealtime();frame.getPixels(pixels,0,frame.getWidth(),0,0,frame.getWidth(),frame.getHeight());if(first!=null){if(i==0)System.arraycopy(pixels,0,first,0,pixels.length);else if(!changed)changed=!java.util.Arrays.equals(first,pixels);}int end=Math.round((i+1)*(float)frames.duration/frames.count/10)*10;gif.frame(pixels,Math.max(20,end-written));written=end;encodeMs+=android.os.SystemClock.elapsedRealtime()-started;}
                        finally{frame.recycle();}
                        State.phaseProgress(35,90,i+1,frames.count);
                    }if(first!=null&&!changed)throw new IllegalArgumentException("所选区域没有活动画面，请重新圈选");decodeMs=frames.decodeMs;
                }
            }else if(kind.equals("webp")&&meta.optBoolean("animated")){
                WebpAnimation animation=WebpAnimation.read(source);int maximumFrames=animation.duration>15000?240:90;float scale=Math.min(1f,320f/Math.max(animation.width,animation.height));
                long started=android.os.SystemClock.elapsedRealtime();final long[] encoding={0};int width=Math.max(1,Math.round(animation.width*scale)),height=Math.max(1,Math.round(animation.height*scale));
                try(FileOutputStream bytes=new FileOutputStream(output);GifWriter gif=new GifWriter(bytes,width,height)){
                    final int[] elapsed={0},written={0},pixels=new int[width*height];
                    animation.stream(320,maximumFrames,(frame,delay,index,count)->{long before=android.os.SystemClock.elapsedRealtime();elapsed[0]+=delay;int end=Math.round(elapsed[0]/10f)*10;frame.getPixels(pixels,0,frame.getWidth(),0,0,frame.getWidth(),frame.getHeight());gif.frame(pixels,Math.max(20,end-written[0]));written[0]=end;encoding[0]+=android.os.SystemClock.elapsedRealtime()-before;State.phaseProgress(35,90,index+1,count);});
                }
                encodeMs=encoding[0];decodeMs=Math.max(0,android.os.SystemClock.elapsedRealtime()-started-encodeMs);
            }else if(meta.optBoolean("animated")){throw new IllegalArgumentException("该动画格式暂不支持，请提供 GIF、动态 WebP 或短视频原文件");}
            else{long started=android.os.SystemClock.elapsedRealtime();Bitmap image;if(kind.equals("video")||kind.equals("live_photo")){try(VideoFrames frame=new VideoFrames(source,480,1)){image=frame.next();}}else image=MediaFiles.still(source,480);decodeMs=android.os.SystemClock.elapsedRealtime()-started;started=android.os.SystemClock.elapsedRealtime();try(FileOutputStream out=new FileOutputStream(output)){if(!image.compress(Bitmap.CompressFormat.PNG,100,out))throw new java.io.IOException("图片生成失败");}finally{image.recycle();}encodeMs=android.os.SystemClock.elapsedRealtime()-started;}
            State.advanceProgress(90);
            JSONObject result=(selection==null?new JSONObject():new JSONObject(selection.toString())).put("jobId",id).put("inputMethod",input).put("source",original).put("sourceSha256",sourceHash).put("sourceKind",kind).put("sourceFrames",meta.optInt("frames",1)).put("sourceDuration",meta.optInt("duration")).put("sourceWidth",meta.optInt("width")).put("sourceHeight",meta.optInt("height")).put("output",output.getAbsolutePath()).put("bytes",output.length()).put("outputSha256",cached==null?sha256(output):cached.getString("outputSha256")).put("decodeMs",decodeMs).put("encodeMs",encodeMs).put("conversionMs",android.os.SystemClock.elapsedRealtime()-conversionStarted).put("conversionRevision",ConversionCache.REVISION).put("reusedConversion",cached!=null);
            if(cached!=null){for(String key:new String[]{"width","height","frames","duration","mimeType"})result.put(key,cached.get(key));}
            else if(output.getName().endsWith(".gif")){GifInfo info=GifInfo.read(output);result.put("width",info.width).put("height",info.height).put("frames",info.frames).put("duration",info.duration).put("mimeType","image/gif");}
            else{Bitmap image=BitmapFactory.decodeFile(output.getAbsolutePath());if(image==null)throw new java.io.IOException("输出图片校验失败");result.put("width",image.getWidth()).put("height",image.getHeight()).put("frames",1).put("duration",0).put("mimeType","image/png");image.recycle();}
            Diagnostics.phase(Diagnostics.Phase.ALBUM_WRITE);State.advanceProgress(92);State.update(c,"保存到相册","正在写入最近照片…");
            long albumStarted=android.os.SystemClock.elapsedRealtime();Uri uri=save(c,output,result.getString("mimeType"),id);saved=true;
            result.put("mediaUri",uri.toString()).put("album","Pictures/StickerBridge").put("savedAt",System.currentTimeMillis()).put("savedToAlbum",true);
            result.put("albumWriteMs",android.os.SystemClock.elapsedRealtime()-albumStarted).put("totalMs",android.os.SystemClock.elapsedRealtime()-State.jobStartedElapsed);if(crop==null)ConversionCache.remember(c,result);
            State.last=result;if(selection!=null)State.selection=new JSONObject(result.toString()).put("pending",false);State.prefs(c).edit().putString("albumLast",result.toString()).commit();
            State.finishSaved(c,input.equals("selection_frame")?"静态截图已保存，在 QQ 收藏里从最近照片添加。":result.optInt("frames")>1?"动态 GIF 已保存，在 QQ 收藏里从最近照片添加。":"静态 PNG 已保存，在 QQ 收藏里从最近照片添加。");
        }finally{if(crop!=null||!saved&&cached==null)output.delete();}
    }
    private static Uri save(Context c,File file,String mime,long id)throws Exception{
        ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,"sticker_"+id+(mime.equals("image/gif")?".gif":".png"));values.put(MediaStore.Images.Media.MIME_TYPE,mime);values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/StickerBridge");values.put(MediaStore.Images.Media.IS_PENDING,1);
        Uri uri=c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new java.io.IOException("相册保存失败，请检查可用存储空间");
        try{
            try(java.io.OutputStream out=c.getContentResolver().openOutputStream(uri);FileInputStream in=new FileInputStream(file)){if(out==null)throw new java.io.IOException("无法写入相册");byte[] b=new byte[32768];int n;long written=0;while((n=in.read(b))!=-1){out.write(b,0,n);written+=n;State.phaseProgress(92,98,written,file.length());}}
            ContentValues done=new ContentValues();done.put(MediaStore.Images.Media.IS_PENDING,0);if(c.getContentResolver().update(uri,done,null,null)!=1)throw new java.io.IOException("相册文件未能完成保存");State.advanceProgress(99);return uri;
        }catch(Exception e){c.getContentResolver().delete(uri,null,null);throw e;}
    }
    private static final class JobFailure extends IllegalArgumentException {
        final Diagnostics.Failure code;
        JobFailure(Diagnostics.Failure code,String message){super(message);this.code=code;}
    }
    private static void lowMemory(Context c){
        State.finishFailure(c,"这次还未保存","当前手机可用内存不足，已停止处理。请关闭其它应用后重试。",Diagnostics.Failure.MEMORY);
    }
    private static void fail(Context c,Exception error){
        Diagnostics.Failure code=error instanceof JobFailure?((JobFailure)error).code:
                error instanceof java.net.ConnectException||error instanceof java.net.SocketTimeoutException?Diagnostics.Failure.CONNECTION:
                error instanceof SecurityException?Diagnostics.Failure.NO_PERMISSION:
                error instanceof IllegalArgumentException?Diagnostics.Failure.INVALID_INPUT:
                error instanceof java.io.IOException?Diagnostics.Failure.IO:Diagnostics.Failure.UNEXPECTED;
        String message=error.getMessage();
        if(error instanceof java.net.ConnectException||error instanceof java.net.SocketTimeoutException)message="抖音素材服务未连接。当前仍可选择本地文件，或分享原图片到本应用。";
        if(error instanceof SecurityException)message="没有取得这个原文件的读取权限，请重新从系统选图或分享入口选择。";
        State.finishFailure(c,"这次还未保存",message==null?"原文件读取失败，请重新选择":message,code);
    }
}
