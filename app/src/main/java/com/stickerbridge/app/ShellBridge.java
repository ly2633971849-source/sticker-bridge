package com.stickerbridge.app;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** A phone-local, read-only media bridge. It has no command execution or write API. */
public final class ShellBridge {
    private static final String BASE = "/sdcard/Android/data/com.ss.android.ugc.aweme/cache/";
    private static final String[] ROOTS = {BASE+"picture/im_fresco_cache",BASE+"picture/fresco_cache",BASE+"sharetmp",BASE+"share/tmp",BASE+"video/cache",BASE+"tmpimages",BASE+"hashedimages"};
    private static final String[] CANONICAL_ROOTS=new String[ROOTS.length];
    private static final long MAX_BYTES = 32L*1024*1024;
    private static String token;
    private static volatile boolean running = true;
    private static ServerSocket server;
    private static final java.util.LinkedHashMap<String,JSONObject> cached = new java.util.LinkedHashMap<String,JSONObject>(128,.75f,true){@Override protected boolean removeEldestEntry(java.util.Map.Entry<String,JSONObject> entry){return size()>4096;}};
    private static final ExecutorService workers = Executors.newFixedThreadPool(2);
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].equals("/data/local/tmp/stickerbridge.key")) throw new IllegalArgumentException("Expected setup key file");
        for(int i=0;i<ROOTS.length;i++)CANONICAL_ROOTS[i]=new File(ROOTS[i]).getCanonicalPath()+File.separator;
        File key = new File(args[0]); token = new String(Files.readAllBytes(key.toPath()),StandardCharsets.UTF_8).trim(); if (!token.matches("[a-f0-9]{48}")) throw new IllegalArgumentException("Invalid setup key"); key.delete();
        server = new ServerSocket(SourceClient.PORT,8,InetAddress.getByName("127.0.0.1")); System.out.println("stickerbridge ready; read-only; loopback only");
        while(running) { try { Socket socket=server.accept(); workers.submit(() -> handle(socket)); } catch(Exception e) { if(running) throw e; } }
        workers.shutdownNow();
    }
    private static void send(DataOutputStream out, JSONObject j) throws Exception { byte[] b=j.toString().getBytes(StandardCharsets.UTF_8); out.writeInt(b.length); out.write(b); out.flush(); }
    private static File allowed(String path) throws Exception {
        File f=new File(path).getCanonicalFile(); boolean accepted=false;
        for(String root:CANONICAL_ROOTS) if(root!=null&&f.getPath().startsWith(root)) accepted=true;
        if(!accepted || !f.isFile() || f.length()<=0 || f.length()>MAX_BYTES) throw new SecurityException("Only supported media in the configured Douyin caches may be read");
        return f;
    }
    private static void walk(File dir,ArrayList<File> files,int depth) {
        if(depth>6 || files.size()>6500) return; File[] items=dir.listFiles(); if(items==null) return;
        for(File f:items) { if(f.isDirectory()) walk(f,files,depth+1); else if(f.length()>100 && f.length()<=MAX_BYTES) files.add(f); }
    }
    private static JSONObject index(JSONObject request) throws Exception {
        ArrayList<File> files=new ArrayList<>();String scope=request.optString("scope");if(scope.equals("im"))walk(new File(ROOTS[0]),files,0);else if(scope.equals("comments")){for(int i=1;i<ROOTS.length;i++)walk(new File(ROOTS[i]),files,0);}else for(String root:ROOTS) walk(new File(root),files,0);HashMap<File,Long> times=new HashMap<>();for(File file:files)times.put(file,file.lastModified());files.sort((a,b)->{int order=Long.compare(times.get(b),times.get(a));return order!=0?order:a.getPath().compareTo(b.getPath());});
        int limit=Math.min(2400,Math.max(1,request.optInt("limit",80))), offset=Math.max(0,request.optInt("offset",0)); boolean animatedOnly=request.optBoolean("animatedOnly",false);
        JSONArray entries=new JSONArray(); int seen=0; boolean more=false; int inspected=0;
        synchronized(cached) {
            for(File raw:files) {
                if(inspected++>3000) break;
                try {
                    String key=raw.getPath()+":"+times.get(raw)+":"+raw.length(); JSONObject entry=cached.get(key);
                    if(entry==null) { entry=rawInfo(allowed(raw.getPath())); cached.put(key,entry); }
                    if(animatedOnly){if(!entry.has("animatedContainer"))entry.put("animatedContainer",MediaFiles.animationContainer(allowed(raw.getPath()),entry.getString("kind")));if(!entry.optBoolean("animatedContainer"))continue;}
                    if(seen++<offset) continue; if(entries.length()>=limit) { more=true; break; } entries.put(entry);
                } catch(Throwable ignored) { }
            }
        }
        return new JSONObject().put("ok",true).put("items",entries).put("more",more).put("scannedFiles",files.size());
    }
    private static void handle(Socket socket) {
        try(Socket s=socket) {
            s.setSoTimeout(8000); DataInputStream in=new DataInputStream(s.getInputStream()); DataOutputStream out=new DataOutputStream(s.getOutputStream());
            int length=in.readInt(); if(length<1 || length>16384) return; byte[] bytes=new byte[length]; in.readFully(bytes); JSONObject request=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            if(!java.security.MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII),request.optString("token").getBytes(StandardCharsets.US_ASCII))) { send(out,new JSONObject().put("ok",false).put("error","pairing required")); return; }
            String op=request.optString("op");
            try {
                if(op.equals("ping")) send(out,new JSONObject().put("ok",true).put("uid",android.os.Process.myUid()).put("readOnly",true).put("version",1));
                else if(op.equals("index")) send(out,index(request));
                else if(op.equals("inspect")) { File f=allowed(request.getString("path")); send(out,new JSONObject().put("ok",true).put("media",rawInfo(f))); }
                else if(op.equals("read")) { File f=allowed(request.getString("path")); if(MediaFiles.kind(f).equals("unknown"))throw new SecurityException("Unsupported media container");send(out,new JSONObject().put("ok",true).put("size",f.length())); try(FileInputStream file=new FileInputStream(f)) { byte[] buffer=new byte[65536]; int n; while((n=file.read(buffer))!=-1) out.write(buffer,0,n); out.flush(); } }
                else if(op.equals("stop")) { send(out,new JSONObject().put("ok",true)); running=false; server.close(); }
                else send(out,new JSONObject().put("ok",false).put("error","unsupported operation"));
            } catch(Throwable e) { send(out,new JSONObject().put("ok",false).put("error",e.getClass().getSimpleName()+": "+e.getMessage())); }
        } catch(Throwable e) { System.err.println("request ended: "+e.getClass().getSimpleName()); }
    }
    private static JSONObject rawInfo(File f)throws Exception{String kind=MediaFiles.kind(f);if(kind.equals("unknown"))throw new IllegalArgumentException("Unsupported media container");return new JSONObject().put("path",f.getCanonicalPath()).put("size",f.length()).put("modified",f.lastModified()).put("kind",kind);}
}
