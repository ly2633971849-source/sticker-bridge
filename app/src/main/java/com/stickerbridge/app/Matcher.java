package com.stickerbridge.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class Matcher {
    private static final class Template {
        final int width,height;final int[] pixels,full;
        Template(Bitmap b){width=b.getWidth();height=b.getHeight();pixels=new int[width*height];b.getPixels(pixels,0,width,0,0,width,height);full=sample(new Rect(0,0,width,height));}
        int[] sample(Rect r){if(r.width()<4||r.height()<4)return null;int[] p=new int[256];for(int y=0;y<16;y++)for(int x=0;x<16;x++)p[y*16+x]=pixels[(r.top+Math.min(r.height()-1,(y*2+1)*r.height()/32))*width+r.left+Math.min(r.width()-1,(x*2+1)*r.width()/32)];return p;}
    }
    private static final android.util.LruCache<String,Template> templates=new android.util.LruCache<String,Template>(3*1024*1024){@Override protected int sizeOf(String key,Template value){return 256+(value.pixels.length+value.full.length)*4;}};
    private static Template template(String encoded)throws Exception{
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(encoded.getBytes(java.nio.charset.StandardCharsets.US_ASCII));String key=android.util.Base64.encodeToString(digest,android.util.Base64.NO_WRAP);
        Template value=templates.get(key);if(value!=null)return value;Bitmap b=MediaFiles.decodeThumb(encoded);if(b==null)return null;
        try{value=new Template(b);if(value.full==null)return null;templates.put(key,value);return value;}finally{b.recycle();}
    }
    private static JSONObject copy(JSONObject source)throws Exception{JSONObject result=new JSONObject();java.util.Iterator<String> keys=source.keys();while(keys.hasNext()){String key=keys.next();result.put(key,source.get(key));}return result;}
    public static final class Hit { public final JSONObject media; public final double score; public Hit(JSONObject m,double s){media=m;score=s;} }
    public static List<Hit> find(Bitmap screen,int x,int y,Rect bounds,float density,JSONArray items) throws Exception {
        ArrayList<Hit> hits=new ArrayList<>();boolean bounded=bounds!=null&&bounds.width()>20&&bounds.height()>20;int[] boundedPatch=bounded?sample(screen,bounds):null;
        for(int i=0;i<items.length();i++) {
            JSONObject media=items.getJSONObject(i);JSONArray thumbs=media.optJSONArray("thumbs");if(thumbs==null || thumbs.length()==0)continue;
            float aspect=(float)media.optInt("width",1)/Math.max(1,media.optInt("height",1));double best=10;int previewIndex=0;
            ArrayList<Rect> areas=new ArrayList<>();
            if(bounded) {
                float displayedAspect=(float)bounds.width()/bounds.height();
                if(Math.abs(Math.log(displayedAspect/aspect))>0.5)continue;
                areas.add(bounds);
            }
            if(!bounded)for(int dp:new int[]{48,64,80,100,120,144,172,200}) {
                int w=Math.round(dp*density),h=Math.round(w/aspect);
                for(float dx:new float[]{-.12f,0,.12f})for(float dy:new float[]{-.12f,0,.12f}){int cx=x+Math.round(w*dx),cy=y+Math.round(h*dy);areas.add(new Rect(cx-w/2,cy-h/2,cx+w/2,cy+h/2));}
            }
            ArrayList<int[]> patches=new ArrayList<>();if(bounded)patches.add(boundedPatch);else for(Rect area:areas)patches.add(sample(screen,area));
            for(int k=0;k<thumbs.length();k++){Template thumb=template(thumbs.getString(k));if(thumb==null)continue;ArrayList<int[]> variants=new ArrayList<>();variants.add(thumb.full);
                if(bounded){float ratio=(float)bounds.width()/bounds.height();int w=thumb.width,h=thumb.height;if((float)w/h>ratio)w=Math.max(4,Math.round(h*ratio));else h=Math.max(4,Math.round(w/ratio));if(w!=thumb.width||h!=thumb.height){int l=(thumb.width-w)/2,t=(thumb.height-h)/2;variants.add(thumb.sample(new Rect(l,t,l+w,t+h)));}}
                for(int[] pixels:variants)for(int[] patch:patches)if(patch!=null){double score=distance(pixels,patch);if(score<best){best=score;previewIndex=k;}}}
            hits.add(new Hit(copy(media).put("previewIndex",previewIndex),best));
        }
        hits.sort(Comparator.comparingDouble(h->h.score));ArrayList<Hit> unique=new ArrayList<>();for(Hit h:hits){boolean duplicate=false;for(int i=0;i<Math.min(6,unique.size());i++)if(sameAnimation(h.media,unique.get(i).media)){duplicate=true;break;}if(!duplicate)unique.add(h);}return unique;
    }
    private static boolean sameAnimation(JSONObject a,JSONObject b){
        if(a.optInt("width")!=b.optInt("width")||a.optInt("height")!=b.optInt("height")||a.optInt("frames")!=b.optInt("frames")||Math.abs(a.optInt("duration")-b.optInt("duration"))>20)return false;
        JSONArray aa=a.optJSONArray("thumbs"),bb=b.optJSONArray("thumbs");if(aa==null||bb==null||aa.length()<2||aa.length()!=bb.length())return false;
        try{for(int i=0;i<aa.length();i++){Template one=template(aa.getString(i)),two=template(bb.getString(i));if(one==null||two==null)return false;int[] p=one.full,q=two.full;if(distance(p,q)>0.008)return false;for(int ty=0;ty<4;ty++)for(int tx=0;tx<4;tx++){double sum=0;for(int y=ty*4;y<ty*4+4;y++)for(int x=tx*4;x<tx*4+4;x++){int c=p[y*16+x],d=q[y*16+x];sum+=Math.abs(((c>>16)&255)-((d>>16)&255))+Math.abs(((c>>8)&255)-((d>>8)&255))+Math.abs((c&255)-(d&255));}if(sum/(16*3*255)>0.025)return false;}}return true;}catch(Exception ignored){return false;}
    }
    public static int[] sample(Bitmap b,Rect r){
        if(r.left<0 || r.top<0 || r.right>b.getWidth() || r.bottom>b.getHeight() || r.width()<4 || r.height()<4)return null;
        int[] p=new int[256];for(int j=0;j<16;j++)for(int i=0;i<16;i++)p[j*16+i]=b.getPixel(r.left+Math.min(r.width()-1,(i*2+1)*r.width()/32),r.top+Math.min(r.height()-1,(j*2+1)*r.height()/32));return p;
    }
    public static double distance(int[] source,int[] target){
        if(source==null || target==null)return 10;
        int bg=target[0];double error=0,weight=0;
        for(int i=0;i<256;i++){int s=source[i],t=target[i],alpha=s>>>24;int sr=((s>>16)&255)*alpha/255+((bg>>16)&255)*(255-alpha)/255;int sg=((s>>8)&255)*alpha/255+((bg>>8)&255)*(255-alpha)/255;int sb=(s&255)*alpha/255+(bg&255)*(255-alpha)/255;double w=(i%16>1 && i%16<14 && i/16>1 && i/16<14)?1.7:1;error+=w*(Math.abs(sr-((t>>16)&255))+Math.abs(sg-((t>>8)&255))+Math.abs(sb-(t&255)));weight+=w;}
        return error/(weight*3*255);
    }
}
