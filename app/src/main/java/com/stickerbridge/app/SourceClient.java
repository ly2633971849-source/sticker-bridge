package com.stickerbridge.app;

import android.content.Context;
import android.os.SystemClock;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Indexes originals for background resolution and optional manual file selection. */
public final class SourceClient {
    public static final int PORT=47381;
    private static final String INDEX_DIRECTORY="media_index_v4";
    private final String token;
    public SourceClient(Context c){token=State.token(c);}
    private static int timeout(long deadline,int maximum)throws SocketTimeoutException{
        long left=deadline-SystemClock.elapsedRealtime();if(left<=0)throw new SocketTimeoutException("等待原素材超时，请稍后重试");return (int)Math.min(left,maximum);
    }
    private Socket open(long deadline)throws Exception{
        Socket socket=new Socket();
        try{socket.connect(new InetSocketAddress("127.0.0.1",PORT),timeout(deadline,1800));socket.setSoTimeout(timeout(deadline,15000));return socket;}
        catch(Exception error){try{socket.close();}catch(Exception ignored){}throw error;}
    }
    public JSONObject call(String op)throws Exception{return call(new JSONObject().put("op",op));}
    public boolean supportsOriginals(){
        try{return call(new JSONObject().put("op","ping"),SystemClock.elapsedRealtime()+600).optBoolean("readOnly");}
        catch(Exception ignored){return false;}
    }
    public JSONObject call(JSONObject request)throws Exception{return call(request,Long.MAX_VALUE);}
    private JSONObject call(JSONObject request,long deadline)throws Exception{
        request.put("token",token);
        try(Socket s=open(deadline)){
            DataOutputStream out=new DataOutputStream(s.getOutputStream());byte[] body=request.toString().getBytes(StandardCharsets.UTF_8);out.writeInt(body.length);out.write(body);out.flush();
            DataInputStream in=new DataInputStream(s.getInputStream());int size=in.readInt();if(size<0||size>12000000)throw new java.io.IOException("素材列表过大");
            byte[] data=new byte[size];in.readFully(data);JSONObject response=new JSONObject(new String(data,StandardCharsets.UTF_8));
            if(!response.optBoolean("ok"))throw new java.io.IOException(response.optString("error","原素材暂不可读"));return response;
        }
    }
    public static final class Gallery {public final JSONArray items;public final boolean more;Gallery(JSONArray items,boolean more){this.items=items;this.more=more;}}
    static String hex(byte[] data){
        String digits="0123456789abcdef";char[] encoded=new char[data.length*2];
        for(int index=0;index<data.length;index++){int value=data[index]&255;encoded[index*2]=digits.charAt(value>>>4);encoded[index*2+1]=digits.charAt(value&15);}
        return new String(encoded);
    }
    private static String hash(byte[] data)throws Exception{return hex(java.security.MessageDigest.getInstance("SHA-256").digest(data));}
    private static String identity(JSONObject media)throws Exception{return media.getString("path")+":"+media.optLong("modified")+":"+media.optLong("size");}
    private File metadataFile(Context c,JSONObject media)throws Exception{
        File dir=new File(c.getCacheDir(),INDEX_DIRECTORY);return metadataFile(dir,identity(media));
    }
    private File metadataFile(File dir,String key)throws Exception{if(!dir.isDirectory())dir.mkdirs();return new File(dir,hash(key.getBytes(StandardCharsets.UTF_8))+".json");}
    public JSONArray known(Context c,String scope,float aspect)throws Exception{
        File dir=new File(c.getCacheDir(),INDEX_DIRECTORY);File[] files=dir.listFiles((d,name)->name.endsWith(".json"));JSONArray result=new JSONArray();if(files==null)return result;
        java.util.HashMap<File,Long> times=new java.util.HashMap<>();for(File file:files)times.put(file,file.lastModified());java.util.Arrays.sort(files,(a,b)->Long.compare(times.get(b),times.get(a)));
        java.util.LinkedHashMap<String,JSONObject> paths=new java.util.LinkedHashMap<>();String last=State.last.optString("source");long retainedBytes=0;
        long readBytes=0,started=android.os.SystemClock.elapsedRealtime();
        for(int i=0;i<Math.min(128,files.length)&&readBytes<12L*1024*1024&&android.os.SystemClock.elapsedRealtime()-started<300;i++)try{
            if(files[i].length()>2000000)continue;JSONObject media=new JSONObject(new String(java.nio.file.Files.readAllBytes(files[i].toPath()),StandardCharsets.UTF_8));String path=media.optString("path");
            readBytes+=files[i].length();
            if(!media.has("thumbs")||media.optString("sha256").isEmpty())continue;
            if(!path.contains("/Android/data/com.ss.android.ugc.aweme/cache/")||(scope.equals("im")&&!path.contains("/im_fresco_cache/")))continue;
            if(scope.equals("comments")&&path.contains("/im_fresco_cache/"))continue;
            if(aspect>0&&Math.abs(Math.log(((float)media.optInt("width",1)/Math.max(1,media.optInt("height",1)))/aspect))>.5)continue;
            media.put("knownSource",true).put("recentlyUsed",path.equals(last)||media.optLong("usedAt")>System.currentTimeMillis()-3600000);
            media.put("_indexBytes",files[i].length());JSONObject previous=paths.get(path);if(previous==null){if(paths.size()>=128||retainedBytes+files[i].length()>8L*1024*1024)continue;retainedBytes+=files[i].length();paths.put(path,media);}else if(previous.optJSONArray("thumbs").length()<media.getJSONArray("thumbs").length()){long updated=retainedBytes-previous.optLong("_indexBytes")+files[i].length();if(updated<=8L*1024*1024){retainedBytes=updated;paths.put(path,media);}}
        }catch(Exception ignored){}
        java.util.HashSet<String> hashes=new java.util.HashSet<>();for(JSONObject media:paths.values())if(hashes.add(media.optString("sha256"))){media.remove("_indexBytes");result.put(media);}return result;
    }
    public static JSONArray merge(JSONArray known,JSONArray loaded)throws Exception{
        java.util.LinkedHashMap<String,JSONObject> items=new java.util.LinkedHashMap<>();
        for(JSONArray group:new JSONArray[]{known,loaded})for(int i=0;i<group.length();i++){JSONObject media=group.getJSONObject(i),previous=items.get(media.getString("path"));if(previous==null||!previous.optString("sha256").equals(media.optString("sha256"))||previous.optJSONArray("thumbs").length()<media.optJSONArray("thumbs").length())items.put(media.getString("path"),media);}
        JSONArray result=new JSONArray();for(JSONObject media:items.values())result.put(media);return result;
    }
    public void remember(Context c,JSONObject media){try{JSONObject item=new JSONObject(media.toString()).put("usedAt",System.currentTimeMillis());java.nio.file.Files.write(metadataFile(c,item).toPath(),item.toString().getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}}
    public static boolean needsRefine(JSONObject media){return media.optBoolean("animated")&&media.optInt("fingerprintVersion")<2;}
    public void forget(Context c,String path){File[] files=new File(c.getCacheDir(),INDEX_DIRECTORY).listFiles((dir,name)->name.endsWith(".json"));if(files==null)return;for(File file:files)try{if(file.length()<2000000&&new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)).optString("path").equals(path))file.delete();}catch(Exception ignored){}}
    public interface IndexObserver { boolean sufficient(JSONArray items)throws Exception; }
    public JSONArray sourceIndex(Context c,int limit,String scope,float aspect,long deadline,int from,int to,boolean animatedOnly,Set<String> scanned)throws Exception{
        return sourceIndex(c,limit,scope,aspect,deadline,from,to,animatedOnly,scanned,null);
    }
    public JSONArray sourceIndex(Context c,int limit,String scope,float aspect,long deadline,int from,int to,boolean animatedOnly,Set<String> scanned,IndexObserver observer)throws Exception{
        JSONArray raw,result=new JSONArray();Set<String> seen=new HashSet<>();
        try{raw=call(new JSONObject().put("op","index").put("limit",limit).put("scope",scope).put("animatedOnly",animatedOnly),deadline).getJSONArray("items");}
        catch(SocketTimeoutException error){if(SystemClock.elapsedRealtime()>=deadline)return result;throw error;}
        File dir=new File(c.getCacheDir(),INDEX_DIRECTORY);
        for(int i=0;i<raw.length()&&SystemClock.elapsedRealtime()<deadline;i++){
            if(i%12==0)State.phaseProgress(from,to,i,raw.length());
            JSONObject entry=raw.getJSONObject(i);String key=identity(entry);if(scanned.contains(key))continue;
            File source=null,metadata=metadataFile(dir,key);
            try{
                JSONObject media=null;
                if(metadata.isFile()&&metadata.length()<2000000)try{media=new JSONObject(new String(java.nio.file.Files.readAllBytes(metadata.toPath()),StandardCharsets.UTF_8));}catch(Exception ignored){}
                if(media!=null&&(media.optBoolean("unreadable")||!media.optString("containerKind",media.optString("kind")).equals(entry.optString("kind"))))media=null;
                boolean write=media==null;
                if(media==null){source=fetch(c,entry.getString("path"),deadline);media=MediaFiles.describe(source,true);media.put("path",entry.getString("path")).put("modified",entry.optLong("modified")).put("sha256",Jobs.sha256(source));}
                media.put("size",entry.optLong("size")).put("containerKind",entry.optString("kind"));
                if(write)java.nio.file.Files.write(metadata.toPath(),media.toString().getBytes(StandardCharsets.UTF_8));
                if(animatedOnly&&!media.optBoolean("animated"))continue;
                if(aspect>0&&Math.abs(Math.log(((float)media.optInt("width",1)/Math.max(1,media.optInt("height",1)))/aspect))>.5){scanned.add(key);continue;}
                if(!media.has("thumbs")){if(source==null)source=fetch(c,entry.getString("path"),deadline);JSONObject pictured=MediaFiles.describe(source,true);media.put("thumbs",pictured.getJSONArray("thumbs"));if(pictured.optBoolean("animated"))media.put("animated",true);java.nio.file.Files.write(metadata.toPath(),media.toString().getBytes(StandardCharsets.UTF_8));}
                scanned.add(key);
                if(seen.add(media.optString("sha256",entry.getString("path")))){result.put(media);if(observer!=null&&result.length()%8==0&&observer.sufficient(result))break;}
            }catch(Exception ignored){}finally{if(source!=null)source.delete();}
        }return result;
    }
    public JSONObject refine(Context c,JSONObject media,long deadline)throws Exception{
        JSONObject result=new JSONObject(media.toString());if(!needsRefine(result))return result;
        File source=fetch(c,result.getString("path"),deadline);try{if(!Jobs.sha256(source).equals(result.optString("sha256")))throw new java.io.IOException("原文件已更新，请重新选择");JSONArray thumbs=MediaFiles.denseThumbnails(source,result);result.put("thumbs",thumbs).put("dense",true).put("fingerprintVersion",2).put("phaseCoverage",thumbs.length());java.nio.file.Files.write(metadataFile(c,result).toPath(),result.toString().getBytes(StandardCharsets.UTF_8));return result;}finally{source.delete();}
    }
    public Gallery recent(Context c,int limit)throws Exception{
        JSONObject index=call(new JSONObject().put("op","index").put("limit",limit));
        JSONObject animatedIndex=call(new JSONObject().put("op","index").put("limit",limit).put("animatedOnly",true));
        JSONArray raw=new JSONArray(),items=new JSONArray();java.util.HashSet<String> paths=new java.util.HashSet<>(),seen=new java.util.HashSet<>();
        for(JSONArray group:new JSONArray[]{animatedIndex.getJSONArray("items"),index.getJSONArray("items")})for(int i=0;i<group.length();i++){JSONObject entry=group.getJSONObject(i);if(paths.add(entry.getString("path")))raw.put(entry);}
        File cache=new File(c.getCacheDir(),INDEX_DIRECTORY);cache.mkdirs();
        for(int i=0;i<raw.length();i++){
            if(i%6==0)State.phaseProgress(0,90,i,raw.length());
            JSONObject entry=raw.getJSONObject(i);String path=entry.getString("path"),key=path+":"+entry.optLong("modified")+":"+entry.optLong("size");
            File metadata=new File(cache,hash(key.getBytes(StandardCharsets.UTF_8))+".json"),source=null;
            try{
                JSONObject media=null;
                if(metadata.isFile()&&metadata.length()<2000000)try{media=new JSONObject(new String(java.nio.file.Files.readAllBytes(metadata.toPath()),StandardCharsets.UTF_8));}catch(Exception ignored){}
                if(media!=null&&(media.optBoolean("unreadable")||!media.optString("containerKind",media.optString("kind")).equals(entry.optString("kind"))))media=null;
                if(media==null||!media.has("thumbs")){
                    source=fetch(c,path);media=MediaFiles.describe(source,true);media.put("path",path).put("modified",entry.optLong("modified")).put("sha256",Jobs.sha256(source));
                    java.nio.file.Files.write(metadata.toPath(),media.toString().getBytes(StandardCharsets.UTF_8));
                }
                if(media.optInt("width")<64||media.optInt("height")<64)continue;
                String identity=media.optString("sha256",key);if(!seen.add(identity))continue;
                // Only one poster is needed for the gallery; discard old multi-phase data.
                JSONArray posters=media.getJSONArray("thumbs");if(posters.length()==0)continue;
                media.put("thumbs",new JSONArray().put(posters.getString(0)));media.remove("dense");media.remove("previewIndex");items.put(media);
            }catch(Exception ignored){}finally{if(source!=null)source.delete();}
        }
        java.util.ArrayList<JSONObject> ordered=new java.util.ArrayList<>();for(int i=0;i<items.length();i++)ordered.add(items.getJSONObject(i));
        ordered.sort((a,b)->a.optBoolean("animated")!=b.optBoolean("animated")?(a.optBoolean("animated")?-1:1):Long.compare(b.optLong("modified"),a.optLong("modified")));
        JSONArray result=new JSONArray();for(JSONObject media:ordered)result.put(media);
        return new Gallery(result,index.optBoolean("more")||animatedIndex.optBoolean("more"));
    }
    public File fetch(Context c,String path)throws Exception{return fetch(c,path,Long.MAX_VALUE);}
    private File fetch(Context c,String path,long deadline)throws Exception{
        JSONObject request=new JSONObject().put("op","read").put("path",path).put("token",token);
        try(Socket s=open(deadline)){
            DataOutputStream out=new DataOutputStream(s.getOutputStream());byte[] body=request.toString().getBytes(StandardCharsets.UTF_8);out.writeInt(body.length);out.write(body);out.flush();
            DataInputStream in=new DataInputStream(s.getInputStream());int headSize=in.readInt();if(headSize<1||headSize>65536)throw new java.io.IOException("原文件响应无效");
            byte[] head=new byte[headSize];in.readFully(head);JSONObject response=new JSONObject(new String(head,StandardCharsets.UTF_8));if(!response.optBoolean("ok"))throw new java.io.IOException(response.optString("error"));
            long size=response.getLong("size");if(size<=0||size>32L*1024*1024)throw new java.io.IOException("原文件超过 32 MB 上限");
            File source=File.createTempFile("original_",".bin",c.getCacheDir());
            try(FileOutputStream file=new FileOutputStream(source)){
                byte[] block=new byte[65536];long left=size;while(left>0){if(deadline!=Long.MAX_VALUE)s.setSoTimeout(timeout(deadline,15000));int n=in.read(block,0,(int)Math.min(left,block.length));if(n<0)throw new java.io.EOFException();file.write(block,0,n);left-=n;}
            }catch(Exception e){source.delete();throw e;}return source;
        }
    }
}
