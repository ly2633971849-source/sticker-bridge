package com.stickerbridge.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;
import java.io.File;
import java.util.List;

/** Bounded native frame batches. Returned frames are consumed immediately instead of accumulating. */
public final class VideoFrames implements AutoCloseable {
    private final MediaMetadataRetriever reader=new MediaMetadataRetriever();
    private List<Bitmap> batch;
    private int batchStart,batchSize,next,decodedUntil,rotation;
    private boolean indexed=true,preferScaled;
    private final RectF crop;
    private final int displayWidth,displayHeight;
    private final Paint cropPaint=new Paint(Paint.FILTER_BITMAP_FLAG);
    public final int sourceFrames,duration,width,height,count;
    public long decodeMs;
    public VideoFrames(File source,int maximum,int maximumFrames)throws Exception{this(source,maximum,maximumFrames,true);}
    public VideoFrames(File source,int maximum,int maximumFrames,boolean limitFrameRate)throws Exception{
        this(source,maximum,maximumFrames,limitFrameRate,null);
    }
    public VideoFrames(File source,int maximum,int maximumFrames,RectF crop)throws Exception{
        this(source,maximum,maximumFrames,true,crop);
    }
    private VideoFrames(File source,int maximum,int maximumFrames,boolean limitFrameRate,RectF crop)throws Exception{
        try{
            MediaFiles.videoSource(reader,source);int sourceWidth=MediaFiles.integer(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),0),sourceHeight=MediaFiles.integer(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),0);
            duration=MediaFiles.integer(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),0);sourceFrames=MediaFiles.integer(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT),0);
            if(sourceWidth<=0||sourceHeight<=0||sourceWidth>4096||sourceHeight>4096)throw new IllegalArgumentException("视频尺寸超过 4096 像素，暂时无法转换");
            if(duration<=0||duration>60000)throw new IllegalArgumentException("当前支持 60 秒以内的动态表情，这张为 "+Math.round(duration/1000f)+" 秒，未保存");
            int available=sourceFrames>0?sourceFrames:Math.max(2,Math.round(duration/100f));
            preferScaled=(long)sourceWidth*sourceHeight>2048L*2048;indexed=sourceFrames>0&&!preferScaled;
            int wanted=Math.min(maximumFrames,available);count=limitFrameRate?Math.min(wanted,Math.max(2,Math.round(duration/50f))):wanted;
            rotation=MediaFiles.integer(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),0);if(rotation==90||rotation==270){int swap=sourceWidth;sourceWidth=sourceHeight;sourceHeight=swap;}
            displayWidth=sourceWidth;displayHeight=sourceHeight;this.crop=crop==null?null:new RectF(crop);
            Rect selection=this.crop==null?null:pixelCrop(this.crop,displayWidth,displayHeight);
            int selectedWidth=selection==null?displayWidth:selection.width(),selectedHeight=selection==null?displayHeight:selection.height();
            float scale=Math.min(1f,(float)maximum/Math.max(selectedWidth,selectedHeight));width=Math.max(1,Math.round(selectedWidth*scale));height=Math.max(1,Math.round(selectedHeight*scale));
            long budget=Math.min(24L*1024*1024,Runtime.getRuntime().maxMemory()/6);batchSize=Math.max(1,Math.min(Math.max(1,sourceFrames),(int)(budget/Math.max(1L,(long)sourceWidth*sourceHeight*4))));
        }catch(Exception error){reader.close();throw error;}
    }
    private static Rect pixelCrop(RectF area,int frameWidth,int frameHeight){
        if(!(area.left>=0&&area.top>=0&&area.right<=1&&area.bottom<=1&&area.left<area.right&&area.top<area.bottom))
            throw new IllegalArgumentException("视频裁剪范围无效，请重新框选");
        Rect bounds=new Rect(Math.round(area.left*frameWidth),Math.round(area.top*frameHeight),
                Math.round(area.right*frameWidth),Math.round(area.bottom*frameHeight));
        if(bounds.width()<2||bounds.height()<2)throw new IllegalArgumentException("视频选区太小，请扩大选区");
        return bounds;
    }
    static Bitmap orientCropFrame(Bitmap frame,int rotation,int orientedWidth,int orientedHeight){
        if((rotation!=90&&rotation!=270)||orientedWidth==orientedHeight)return frame;
        double frameAspect=(double)frame.getWidth()/frame.getHeight();
        double expectedAspect=(double)orientedWidth/orientedHeight;
        double expectedError=Math.abs(Math.log(frameAspect/expectedAspect));
        double rawError=Math.abs(Math.log(frameAspect*expectedAspect));
        if(expectedError<=.08||rawError>=expectedError||rawError>.08)return frame;
        Matrix transform=new Matrix();transform.postRotate(rotation);
        return Bitmap.createBitmap(frame,0,0,frame.getWidth(),frame.getHeight(),transform,false);
    }
    private Bitmap cropFrame(Bitmap frame){
        Bitmap displayed=orientCropFrame(frame,rotation,displayWidth,displayHeight);
        Bitmap result=null;
        try{
            Rect bounds=pixelCrop(crop,displayed.getWidth(),displayed.getHeight());
            result=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            new Canvas(result).drawBitmap(displayed,bounds,new Rect(0,0,width,height),cropPaint);
            return result;
        }catch(RuntimeException|Error error){
            if(result!=null)result.recycle();
            throw error;
        }finally{
            if(displayed!=frame)displayed.recycle();
        }
    }
    private void releaseBatch(){if(batch!=null){for(Bitmap image:batch)if(image!=null&&!image.isRecycled())image.recycle();batch=null;}}
    public Bitmap next()throws Exception{
        if(next>=count)return null;long started=SystemClock.elapsedRealtime();Bitmap frame=null;boolean ownedByBatch=false;
        try{
            int index=sourceFrames>0?Math.min(sourceFrames-1,Math.round((float)next*sourceFrames/count)):0;
            if(indexed){
                while(indexed&&(batch==null||index>=batchStart+batch.size())){
                    releaseBatch();batchStart=decodedUntil;
                    try{MediaMetadataRetriever.BitmapParams params=new MediaMetadataRetriever.BitmapParams();params.setPreferredConfig(Bitmap.Config.ARGB_8888);batch=new java.util.ArrayList<>(reader.getFramesAtIndex(batchStart,Math.min(batchSize,sourceFrames-batchStart),params));if(batch.isEmpty())indexed=false;else decodedUntil=batchStart+batch.size();}
                    catch(Exception unavailable){indexed=false;}
                }
                if(indexed&&batch!=null&&index>=batchStart&&index-batchStart<batch.size()){frame=batch.get(index-batchStart);ownedByBatch=true;}
            }
            if(frame==null){
                if(sourceFrames>0&&!preferScaled)try{frame=reader.getFrameAtIndex(index);}catch(Exception ignored){}
                if(frame==null){
                    long time=(long)next*duration*1000/count;
                    frame=crop==null?reader.getScaledFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST,width,height)
                            :reader.getFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST);
                }
            }
            if(frame==null)throw new IllegalArgumentException("原动画第 "+(next+1)+" 帧没有加载完整，未保存");
            if(crop!=null){
                try{Bitmap output=cropFrame(frame);next++;return output;}
                finally{if(!ownedByBatch)frame.recycle();}
            }
            Bitmap display=frame;
            if((rotation==90||rotation==270)&&Math.abs(Math.log(((float)frame.getWidth()/frame.getHeight())/((float)width/height)))>.25){android.graphics.Matrix matrix=new android.graphics.Matrix();matrix.postRotate(rotation);display=Bitmap.createBitmap(frame,0,0,frame.getWidth(),frame.getHeight(),matrix,false);}
            Bitmap small;try{small=Bitmap.createScaledBitmap(display,width,height,true);if(small==display)small=display.copy(Bitmap.Config.ARGB_8888,false);}finally{if(display!=frame)display.recycle();}
            if(!ownedByBatch)frame.recycle();next++;return small;
        }catch(Exception error){if(!ownedByBatch&&frame!=null&&!frame.isRecycled())frame.recycle();throw error;}finally{decodeMs+=SystemClock.elapsedRealtime()-started;}
    }
    @Override public void close()throws Exception{releaseBatch();reader.close();}
}
