import com.stickerbridge.app.GifWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Random;

public final class GifWriterProbe {
    public static void main(String[] args) throws Exception {
        File root=new File(args[0]);root.mkdirs();
        for(int mode=0;mode<7;mode++){
            int w=mode==0?1:mode==1?37:mode==2?290:mode==4?17:mode==5?256:mode==6?83:320,h=mode==0?1:mode==1?23:mode==2?300:mode==4?11:mode==5?128:mode==6?79:240;
            try(FileOutputStream bytes=new FileOutputStream(new File(root,"case"+mode+".gif"));GifWriter gif=new GifWriter(bytes,w,h)){
                Random random=new Random(42);
                for(int frame=0;frame<3;frame++){int[] p=new int[w*h];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int color=mode==3?random.nextInt(0x1000000):((x*255/Math.max(1,w-1))<<16)|((y*255/Math.max(1,h-1))<<8)|(frame*90);if(mode==2&&(x<70||x>220||y<50||y>230))color=0xffffff;p[y*w+x]=mode==1&&x<4?0:0xff000000|color;
                    if(mode==4)p[y*w+x]=0;
                    if(mode==5){int key=y*w+x;p[y*w+x]=0xff000000|(((key>>10)&31)*8+frame)<<16|(((key>>5)&31)*8+frame)<<8|(key&31)*8+frame;}
                    if(mode==6)p[y*w+x]=frame==1?0:frame==2?0xffa08060:((x&1)==0?0x7f000000:0x80000000)|color;
                }gif.frame(p,120+frame*50);}
            }
        }
    }
}
