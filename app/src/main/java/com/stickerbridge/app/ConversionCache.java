package com.stickerbridge.app;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import org.json.JSONObject;

/** Reuse only a validated conversion of the exact same original; public album copies remain independent. */
final class ConversionCache {
    static final int REVISION=3;
    private static File index(Context c){File dir=new File(c.getFilesDir(),"conversion_index_v3");dir.mkdirs();return dir;}
    static JSONObject get(Context c,String hash){
        if(!hash.matches("[a-f0-9]{64}"))return null;
        try{
            File entry=new File(index(c),hash+".json");if(!entry.isFile()||entry.length()>65536)return null;
            JSONObject result=new JSONObject(new String(Files.readAllBytes(entry.toPath()),StandardCharsets.UTF_8));
            File output=new File(result.getString("output")).getCanonicalFile(),exports=new File(c.getFilesDir(),"exports").getCanonicalFile();
            if(result.optInt("conversionRevision")!=REVISION||!hash.equals(result.optString("sourceSha256"))||!output.getPath().startsWith(exports.getPath()+File.separator)||!output.isFile()||output.length()!=result.optLong("bytes")||!Jobs.sha256(output).equals(result.optString("outputSha256")))return null;
            return result;
        }catch(Exception ignored){return null;}
    }
    static void remember(Context c,JSONObject result){
        try{
            String hash=result.getString("sourceSha256");if(!hash.matches("[a-f0-9]{64}"))return;
            File dir=index(c),target=new File(dir,hash+".json"),temporary=new File(dir,hash+".tmp");
            Files.write(temporary.toPath(),result.toString().getBytes(StandardCharsets.UTF_8));Files.move(temporary.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            File[] entries=dir.listFiles((d,name)->name.endsWith(".json"));if(entries==null)return;
            Arrays.sort(entries,(a,b)->Long.compare(b.lastModified(),a.lastModified()));long retained=0;int count=0;HashSet<String> keep=new HashSet<>();
            keep.add(new File(result.getString("output")).getCanonicalPath());
            for(File entry:entries)try{
                JSONObject item=new JSONObject(new String(Files.readAllBytes(entry.toPath()),StandardCharsets.UTF_8));File output=new File(item.getString("output")).getCanonicalFile();
                boolean current=entry.equals(target);if(current||(count<8&&retained+output.length()<=64L*1024*1024)){count++;retained+=output.length();keep.add(output.getPath());}else entry.delete();
            }catch(Exception ignored){entry.delete();}
            File exports=new File(c.getFilesDir(),"exports").getCanonicalFile();File[] files=exports.listFiles((d,name)->name.startsWith("sticker_")&&(name.endsWith(".png")||name.endsWith(".gif")));
            if(files!=null)for(File file:files)if(!keep.contains(file.getCanonicalPath()))file.delete();
        }catch(Exception ignored){}
    }
}
