package com.stickerbridge.app;

import android.graphics.Bitmap;
import android.graphics.Rect;

/** Recognizes the small concentric, dotted live-photo badge; never reconstructs animation. */
public final class MotionBadge {
    private static final float[][] COS=new float[73][],SIN=new float[73][];
    static{for(int count:new int[]{8,24,32,48,72}){COS[count]=new float[count];SIN[count]=new float[count];for(int i=0;i<count;i++){double a=i*2*Math.PI/count;COS[count][i]=(float)Math.cos(a);SIN[count][i]=(float)Math.sin(a);}}}
    public static Rect find(Bitmap screen,Rect target,float density){
        if(screen==null||screen.isRecycled()||density<=0||target==null)return null;
        Rect image=new Rect(target);if(!image.intersect(0,0,screen.getWidth(),screen.getHeight()))return null;
        int size=Math.min(Math.min(image.width(),image.height()),Math.round(32*density));
        if(size<24)return null;
        int[] pixels=new int[size*size];screen.getPixels(pixels,0,size,image.left,image.top,size,size);
        // The badge occupies a small corner overlay, not an arbitrary circular subject in the photo.
        int step=Math.max(1,Math.round(density*.5f));double best=.0;Rect found=null;
        int first=Math.round(8*density),last=Math.min(size-1,Math.round(18*density));
        for(float radius=4*density;radius<=6.5f*density;radius+=Math.max(.5f,density*.25f)){
            for(int cy=first;cy<=last;cy+=step)for(int cx=first;cx<=last;cx+=step){
                if(cx-radius<0||cy-radius<0||cx+radius>=size-1||cy+radius>=size-1)continue;
                double inner=ring(pixels,size,cx,cy,radius*.30f,24),middle=ring(pixels,size,cx,cy,radius*.66f,48);
                if(inner<.80||middle<.80)continue;
                double hole=ring(pixels,size,cx,cy,radius*.06f,8),gap=ring(pixels,size,cx,cy,radius*.46f,32),outside=ring(pixels,size,cx,cy,radius*.82f,48);
                if(hole>.25||gap>.25||outside>.30)continue;
                // Subpixel outer dots blend with the dark photo at low density; the two solid rings stay strict.
                double dots=ring(pixels,size,cx,cy,radius,72,128,72);
                if(dots<.32||dots>.82)continue;
                double score=inner+middle-hole-gap-outside;
                if(score>best){best=score;int r=Math.round(radius)+1;found=new Rect(image.left+cx-r,image.top+cy-r,image.left+cx+r+1,image.top+cy+r+1);}
            }
        }return found;
    }
    private static double ring(int[] pixels,int size,int x,int y,float radius,int count){
        return ring(pixels,size,x,y,radius,count,190,48);
    }
    private static double ring(int[] pixels,int size,int x,int y,float radius,int count,int brightness,int chroma){
        int white=0;
        for(int i=0;i<count;i++){
            int px=Math.round(x+radius*COS[count][i]),py=Math.round(y+radius*SIN[count][i]);
            int color=pixels[py*size+px],r=(color>>>16)&255,g=(color>>>8)&255,b=color&255;
            int low=Math.min(r,Math.min(g,b)),high=Math.max(r,Math.max(g,b));if(low>=brightness&&high-low<chroma)white++;
        }return (double)white/count;
    }
}
