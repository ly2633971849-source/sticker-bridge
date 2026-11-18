package com.stickerbridge.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Reads GIF structure, rather than guessing the frame count from its extension. */
public final class GifInfo {
    public int width,height,frames,duration;
    private static int read(InputStream in)throws IOException{int n=in.read();if(n<0)throw new java.io.EOFException();return n;}
    private static int shortLE(InputStream in)throws IOException{return read(in)|(read(in)<<8);}
    private static void skip(InputStream in,long n)throws IOException{while(n>0){long k=in.skip(n);if(k==0){read(in);k=1;}n-=k;}}
    private static void blocks(InputStream in)throws IOException{int n;while((n=read(in))!=0)skip(in,n);}
    public static GifInfo read(File file)throws IOException{
        GifInfo result=new GifInfo();try(InputStream in=new FileInputStream(file)){byte[] header=new byte[6];if(in.read(header)!=6||header[0]!='G'||header[1]!='I'||header[2]!='F')throw new IOException("Not a GIF");result.width=shortLE(in);result.height=shortLE(in);int packed=read(in);skip(in,2);if((packed&128)!=0)skip(in,3*(1<<((packed&7)+1)));int delay=0;
            for(int limit=0;limit<10000;limit++){int type=read(in);if(type==0x3b)break;if(type==0x21){int label=read(in);if(label==0xf9){int size=read(in);if(size!=4)throw new IOException("Bad GIF control block");read(in);delay=shortLE(in)*10;skip(in,2);}else blocks(in);}else if(type==0x2c){skip(in,8);int flags=read(in);if((flags&128)!=0)skip(in,3*(1<<((flags&7)+1)));read(in);blocks(in);result.frames++;result.duration+=delay;delay=0;}else throw new IOException("Bad GIF record");}
        }return result;
    }
}
