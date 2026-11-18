package com.stickerbridge.app;

import android.graphics.Bitmap;
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
    public final int sourceFrames,duration,width,height,count;
    public long decodeMs;
    public VideoFrames(File source,int maximum,int maximumFrames)throws Exception{this(source,maximum,maximumFrames,true);}
    public VideoFrames(File source,int maximum,int maximumFrames,boolean limitFrameRate)throws Exception{
        try{
            MediaFiles.videoSource(reader,source);int sourceWidth=MediaFiles.integer(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),0),sourceHeight=MediaFiles.integer(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),0);
            duration=MediaFiles.integer(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),0);sourceFrames=MediaFiles.integer(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT),0);
            if(sourceWidth<=0||sourceHeight<=0||sourceWidth>4096||sourceHeight>4096)throw new IllegalArgumentException("视频尺寸超过 4096 像素，暂时无法转换");
            if(duration<=0||duration>60000)throw new IllegalArgumentException("当前支持 60 秒以内的动态表情，这张为 "+Math.round(duration/1000f)+" 秒，未保存");
            int available=sourceFrames>0?sourceFrames:Math.max(2,Math.round(duration/100f));
            preferScaled=(long)sourceWidth*sourceHeight>2048L*2048;indexed=sourceFrames>0&&!preferScaled;
            int wanted=Math.min(maximumFrames,available);count=limitFrameRate?Math.min(wanted,Math.max(2,Math.round(duration/50f))):wanted;
            rotation=MediaFiles.integer(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),0);if(rotation==90||rotation==270){int swap=sourceWidth;sourceWidth=sourceHeight;sourceHeight=swap;}
            float scale=Math.min(1f,(float)maximum/Math.max(sourceWidth,sourceHeight));width=Math.max(1,Math.round(sourceWidth*scale));height=Math.max(1,Math.round(sourceHeight*scale));
            long budget=Math.min(24L*1024*1024,Runtime.getRuntime().maxMemory()/6);batchSize=Math.max(1,Math.min(Math.max(1,sourceFrames),(int)(budget/Math.max(1L,(long)sourceWidth*sourceHeight*4))));
        }catch(Exception error){reader.close();throw error;}
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
                if(frame==null)frame=reader.getScaledFrameAtTime((long)next*duration*1000/count,MediaMetadataRetriever.OPTION_CLOSEST,width,height);
            }
            if(frame==null)throw new IllegalArgumentException("原动画第 "+(next+1)+" 帧没有加载完整，未保存");
            Bitmap display=frame;
            if((rotation==90||rotation==270)&&Math.abs(Math.log(((float)frame.getWidth()/frame.getHeight())/((float)width/height)))>.25){android.graphics.Matrix matrix=new android.graphics.Matrix();matrix.postRotate(rotation);display=Bitmap.createBitmap(frame,0,0,frame.getWidth(),frame.getHeight(),matrix,false);}
            Bitmap small;try{small=Bitmap.createScaledBitmap(display,width,height,true);if(small==display)small=display.copy(Bitmap.Config.ARGB_8888,false);}finally{if(display!=frame)display.recycle();}
            if(!ownedByBatch)frame.recycle();next++;return small;
        }catch(Exception error){if(!ownedByBatch&&frame!=null&&!frame.isRecycled())frame.recycle();throw error;}finally{decodeMs+=SystemClock.elapsedRealtime()-started;}
    }
    @Override public void close()throws Exception{releaseBatch();reader.close();}
}
