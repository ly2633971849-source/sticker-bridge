package com.stickerbridge.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MediaFiles {
    public static String kind(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[64]; int n = in.read(b);
            if (n < 12) return "unknown";
            if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "gif";
            if ((b[0]&255) == 137 && b[1] == 'P' && b[2] == 'N') return "png";
            if ((b[0]&255) == 255 && (b[1]&255) == 216) {
                String xmp=jpegXmp(f);if(java.util.regex.Pattern.compile("(?:[A-Za-z0-9_]+:)?(?:MicroVideo|MotionPhoto)\\s*=\\s*[\"']1[\"']").matcher(xmp).find())return "live_photo";
                return "jpeg";
            }
            if (b[0] == 'R' && b[1] == 'I' && b[8] == 'W' && b[9] == 'E') return "webp";
            if ((b[0]&255)==0x1a&&(b[1]&255)==0x45&&(b[2]&255)==0xdf&&(b[3]&255)==0xa3) return "video";
            if (b[0]=='B'&&b[1]=='M') return "bmp";
            if (b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
                IsoMediaBrands.Kind brands=IsoMediaBrands.inspect(f);
                if(brands==IsoMediaBrands.Kind.STILL)return "heif";
                return brands==IsoMediaBrands.Kind.UNRECOGNIZED?"unknown":"video";
            }
        } catch (Exception ignored) { }
        return "unknown";
    }
    private static String jpegXmp(File file){
        // Skip JPEG segments instead of rereading 256 KB of every cached picture.
        try(java.io.RandomAccessFile in=new java.io.RandomAccessFile(file,"r")){
            if(in.readUnsignedShort()!=0xffd8)return "";
            while(in.getFilePointer()<Math.min(file.length(),262144)){
                int prefix=in.readUnsignedByte();if(prefix!=0xff)break;int marker;do{marker=in.readUnsignedByte();}while(marker==0xff);
                if(marker==0xda||marker==0xd9)break;if(marker==1||(marker>=0xd0&&marker<=0xd7))continue;
                int length=in.readUnsignedShort()-2;if(length<0||in.getFilePointer()+length>file.length())break;
                if(marker==0xe1){byte[] bytes=new byte[length];in.readFully(bytes);String text=new String(bytes,java.nio.charset.StandardCharsets.ISO_8859_1);if(text.startsWith("http://ns.adobe.com/xap/1.0/"))return text;}
                else in.seek(in.getFilePointer()+length);
            }
        }catch(Exception ignored){}return "";
    }
    public static long motionOffset(File file){
        try{
            String xmp=jpegXmp(file);java.util.regex.Matcher offset=java.util.regex.Pattern.compile("(?:[A-Za-z0-9_]+:)?MicroVideoOffset\\s*=\\s*[\"']([0-9]+)[\"']").matcher(xmp);
            if(!offset.find())return -1;long length=Long.parseLong(offset.group(1)),start=file.length()-length;if(length<24||start<4)return -1;
            try(java.io.RandomAccessFile in=new java.io.RandomAccessFile(file,"r")){in.seek(start);long size=in.readInt()&0xffffffffL;int tag=in.readInt();if(size<16||size>length||tag!=0x66747970)return -1;}
            return start;
        }catch(Exception ignored){return -1;}
    }
    public static void videoSource(MediaMetadataRetriever reader,File file)throws Exception{
        if(kind(file).equals("live_photo")){
            long start=motionOffset(file);if(start<0)throw new IllegalArgumentException("实况片段未加载完整，请先点开原图后重试");
            try(FileInputStream in=new FileInputStream(file)){reader.setDataSource(in.getFD(),start,file.length()-start);}
        }else reader.setDataSource(file.getAbsolutePath());
    }
    public static boolean imageSequence(File file){
        return IsoMediaBrands.inspect(file)==IsoMediaBrands.Kind.SEQUENCE;
    }
    public static boolean animationContainer(File f,String kind){
        try{
            if(kind.equals("video")||kind.equals("live_photo")&&motionOffset(f)>=0)return true;
            if(kind.equals("gif"))return GifInfo.read(f).frames>1;
            if(kind.equals("webp"))try(FileInputStream in=new FileInputStream(f)){byte[] header=new byte[21];int n=in.read(header);return n==21&&header[12]=='V'&&header[13]=='P'&&header[14]=='8'&&header[15]=='X'&&(header[20]&2)!=0;}
        }catch(Exception ignored){}return false;
    }
    public static int integer(String s, int fallback) { try { return Integer.parseInt(s); } catch (Exception e) { return fallback; } }
    public static Bitmap still(File f, int max) throws Exception {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(f), (decoder, info, source) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            int w = info.getSize().getWidth(), h = info.getSize().getHeight(); float scale = Math.min(1f, (float)max / Math.max(w,h));
            decoder.setTargetSize(Math.max(1, Math.round(w*scale)), Math.max(1, Math.round(h*scale)));
        });
    }
    public static JSONObject describe(File f, boolean thumbnails) throws Exception {
        String kind = kind(f);boolean motionMissing=kind.equals("live_photo")&&motionOffset(f)<0;if(motionMissing)kind="jpeg";if (kind.equals("unknown")) throw new IllegalArgumentException("未识别这个文件，请提供图片、GIF、WebP 或 MP4/WebM 视频原文件");
        JSONObject m = new JSONObject(); m.put("path", f.getCanonicalPath()); m.put("size", f.length()); m.put("modified", f.lastModified()); m.put("kind", kind);
        if(motionMissing)m.put("motionUnavailable",true);
        JSONArray thumbs = new JSONArray(); int w, h, frames = 1, duration = 0; boolean animated = false;
        if (kind.equals("video")||kind.equals("live_photo")) {
            try (MediaMetadataRetriever r = new MediaMetadataRetriever()) {
                videoSource(r,f);
                w = integer(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 0); h = integer(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 0);
                frames = integer(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT), 0); duration = integer(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 0); animated = frames > 1 || (frames<1&&duration>0);
                int rotation=integer(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),0);if(rotation==90||rotation==270){int swap=w;w=h;h=swap;}
                boolean sequence=kind.equals("video")&&imageSequence(f);
                m.put("rotation",rotation).put("imageSequence",sequence);
                if(w<=0||h<=0)throw new IllegalArgumentException("未读取到视频画面，请让原文件加载完整后重试");
                if (w > 4096 || h > 4096) throw new IllegalArgumentException("视频尺寸超过 4096 像素，暂时无法转换");
                if(duration>60000)throw new IllegalArgumentException("当前支持 60 秒内的动态表情，这张为 "+Math.round(duration/1000f)+" 秒");
                if (thumbnails) {
                    int[] indices = new int[]{0};
                    for (int index : indices) {
                        Bitmap frame=null;
                        if(sequence&&frames>0)try{frame=r.getFrameAtIndex(index);}catch(RuntimeException unavailable){}
                        if(frame==null)frame=r.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST,96,96);
                        if (frame != null) { thumbs.put(thumb(frame)); frame.recycle(); }
                    }
                }
            }
        } else if(kind.equals("webp")&&animationContainer(f,kind)){
            WebpAnimation animation=WebpAnimation.read(f);w=animation.width;h=animation.height;frames=animation.count();duration=animation.duration;animated=frames>1;
            if(thumbnails)try(WebpAnimation.Decoded decoded=animation.decode(96,1)){for(Bitmap frame:decoded.images)thumbs.put(thumb(frame));}
        } else {
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(f.getAbsolutePath(),bounds);w=bounds.outWidth;h=bounds.outHeight;
            if(w>0&&h>0&&(kind.equals("jpeg")||kind.equals("heif")))try{int orientation=new android.media.ExifInterface(f.getAbsolutePath()).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1);if(orientation>=5&&orientation<=8){int swap=w;w=h;h=swap;}}catch(Exception ignored){}
            if(w<=0||h<=0){final int[] size=new int[2];Bitmap tiny=ImageDecoder.decodeBitmap(ImageDecoder.createSource(f),(decoder,info,source)->{size[0]=info.getSize().getWidth();size[1]=info.getSize().getHeight();decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);decoder.setTargetSize(1,1);});tiny.recycle();w=size[0];h=size[1];}
            if(kind.equals("gif")){GifInfo info=GifInfo.read(f);frames=info.frames;duration=info.duration;animated=frames>1;}
            if (w <= 0 || h <= 0 || w > 16384 || h > 16384 || (long)w*h>80000000) throw new IllegalArgumentException("原图片尺寸过大，请选择 8000 万像素以内的图片");
            if (thumbnails) {
                if(kind.equals("gif")&&animated){thumbs=gifThumbnails(f,1);}
                else{Bitmap frame=still(f,96);try{thumbs.put(thumb(frame));}finally{frame.recycle();}}
            }
        }
        m.put("width", w); m.put("height", h); m.put("frames", frames); m.put("duration", duration); m.put("animated", animated); if (thumbnails) m.put("thumbs", thumbs); return m;
    }
    public static String thumb(Bitmap bitmap) throws Exception {
        float scale = Math.min(1f, 96f / Math.max(bitmap.getWidth(),bitmap.getHeight())); Bitmap small = Bitmap.createScaledBitmap(bitmap, Math.max(1,Math.round(bitmap.getWidth()*scale)), Math.max(1,Math.round(bitmap.getHeight()*scale)), true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.PNG, 100, bytes); String encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP); if (small != bitmap) small.recycle(); return encoded;
    }
    public static Bitmap decodeThumb(String s) { byte[] data = Base64.decode(s, Base64.NO_WRAP); return BitmapFactory.decodeByteArray(data,0,data.length); }
    private static JSONArray gifThumbnails(File f,int count)throws Exception{
        android.graphics.Movie movie=android.graphics.Movie.decodeFile(f.getAbsolutePath());if(movie==null)throw new IllegalArgumentException("GIF 无法解码");JSONArray result=new JSONArray();float scale=Math.min(1f,96f/Math.max(movie.width(),movie.height()));
        for(int i=0;i<count;i++){Bitmap b=Bitmap.createBitmap(Math.max(1,Math.round(movie.width()*scale)),Math.max(1,Math.round(movie.height()*scale)),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(b);canvas.scale(scale,scale);movie.setTime(i*Math.max(1,movie.duration())/count);movie.draw(canvas,0,0);result.put(thumb(b));b.recycle();}return result;
    }
    public static JSONArray denseThumbnails(File f,JSONObject meta)throws Exception{
        if(meta.optString("kind").equals("gif"))return gifThumbnails(f,Math.min(160,Math.max(1,meta.optInt("frames"))));
        JSONArray result=new JSONArray();
        if(meta.optString("kind").equals("webp")){try(WebpAnimation.Decoded decoded=WebpAnimation.read(f).decode(64,Math.min(160,Math.max(1,meta.optInt("frames"))))){for(Bitmap b:decoded.images)result.put(thumb(b));}return result;}
        if(!meta.optString("kind").equals("video")&&!meta.optString("kind").equals("live_photo"))return meta.getJSONArray("thumbs");
        int count=meta.optInt("frames");if(count<2&&meta.optInt("duration")>0)count=Math.max(2,Math.min(160,meta.optInt("duration")/100));if(count<2)return meta.getJSONArray("thumbs");
        try(VideoFrames frames=new VideoFrames(f,64,Math.min(160,count),false)){Bitmap image;while((image=frames.next())!=null){try{result.put(thumb(image));}finally{image.recycle();}}}
        return result.length()>0?result:meta.getJSONArray("thumbs");
    }
    public static List<Bitmap> videoFrames(File f,int[] timing)throws Exception{
        ArrayList<Bitmap> result=new ArrayList<>();
        try(VideoFrames frames=new VideoFrames(f,320,90)){timing[0]=Math.max(20,Math.round((float)frames.duration/frames.count));timing[1]=frames.sourceFrames;Bitmap image;while((image=frames.next())!=null)result.add(image);}
        catch(Exception error){for(Bitmap image:result)image.recycle();throw error;}
        if(result.isEmpty())throw new IllegalArgumentException("未取得原动画帧");return result;
    }
}
