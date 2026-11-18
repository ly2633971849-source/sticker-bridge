package com.stickerbridge.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;

/** Decode ANMF patches with Android WebP, preserving offsets, blending and disposal. */
public final class WebpAnimation {
    public int width, height, duration, background;
    private byte[] data, profile;
    private final ArrayList<Frame> frames = new ArrayList<>();
    private static final byte[] PATCH_HEADER = {
            'R','I','F','F',0,0,0,0,'W','E','B','P','V','P','8','X',10,0,0,0,
            0x10,0,0,0,0,0,0,0,0,0
    };
    private static final class Frame { int x, y, w, h, delay, flags, start, size; }

    public static final class Decoded implements AutoCloseable {
        public final ArrayList<Bitmap> images = new ArrayList<>();
        public final ArrayList<Integer> delays = new ArrayList<>();
        @Override public void close() { for (Bitmap image : images) if (!image.isRecycled()) image.recycle(); }
    }

    public int count() { return frames.size(); }
    private static int u24(byte[] b, int p) {
        return (b[p] & 255) | ((b[p + 1] & 255) << 8) | ((b[p + 2] & 255) << 16);
    }
    private static long u32(byte[] b, int p) { return ((long) u24(b, p)) | ((long) (b[p + 3] & 255) << 24); }
    private static String tag(byte[] b, int p) {
        return new String(b, p, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }

    public static WebpAnimation read(File file) throws Exception {
        long length = Files.size(file.toPath());
        if (length > 12 * 1024 * 1024) throw new IllegalArgumentException("动态WebP最大12MB");
        if (length < 12) throw new IllegalArgumentException("无效 WebP");
        WebpAnimation animation = new WebpAnimation();
        animation.data = Files.readAllBytes(file.toPath());
        byte[] b = animation.data;
        if (b.length > 12 * 1024 * 1024) throw new IllegalArgumentException("动态WebP最大12MB");
        if (b.length < 12
                || !tag(b, 0).equals("RIFF") || !tag(b, 8).equals("WEBP"))
            throw new IllegalArgumentException("无效 WebP");
        long end = u32(b, 4) + 8;
        if (end > b.length || end < 12) throw new IllegalArgumentException("WebP 不完整");
        for (int p = 12; p + 8 <= end;) {
            long size = u32(b, p + 4);
            int start = p + 8;
            if (size > end - start) throw new IllegalArgumentException("WebP 数据块越界");
            int n = (int) size;
            String type = tag(b, p);
            if (type.equals("VP8X") && n >= 10) {
                animation.width = u24(b, start + 4) + 1;
                animation.height = u24(b, start + 7) + 1;
            } else if (type.equals("ANIM") && n >= 6) {
                animation.background = (int) u32(b, start);
            } else if (type.equals("ICCP")) {
                animation.profile = Arrays.copyOfRange(b, p, start + n + (n & 1));
            } else if (type.equals("ANMF")) {
                if (n < 16 || animation.frames.size() >= 1800)
                    throw new IllegalArgumentException("WebP 帧数或帧头无效");
                Frame frame = new Frame();
                frame.x = u24(b, start) * 2; frame.y = u24(b, start + 3) * 2;
                frame.w = u24(b, start + 6) + 1; frame.h = u24(b, start + 9) + 1;
                int rawDelay = u24(b, start + 12);
                frame.delay = rawDelay <= 10 ? 100 : Math.max(20, rawDelay);
                frame.flags = b[start + 15] & 3;
                frame.start = start + 16; frame.size = n - 16;
                animation.duration += frame.delay;
                animation.frames.add(frame);
            }
            long next = (long) start + n + (n & 1);
            if (next > end) throw new IllegalArgumentException("WebP 填充无效");
            p = (int) next;
        }
        if (!animation.frames.isEmpty()) {
            if (animation.width < 1 || animation.height < 1 || animation.width > 1280 || animation.height > 1280)
                throw new IllegalArgumentException("WebP 原图尺寸超过 1280 像素，暂时无法转换");
            if (animation.duration > 60000)
                throw new IllegalArgumentException("当前支持 60 秒内的动态表情，这张为 " + Math.round(animation.duration / 1000f) + " 秒");
            for (Frame frame : animation.frames)
                if (frame.x + frame.w > animation.width || frame.y + frame.h > animation.height)
                    throw new IllegalArgumentException("WebP 帧区域越界");
        }
        return animation;
    }

    private static void little(byte[] output, int at, int value, int bytes) {
        for (int i = 0; i < bytes; i++) output[at + i] = (byte) (value >>> (8 * i));
    }

    private int patch(Frame frame, byte[] output) {
        int offset = PATCH_HEADER.length + (profile == null ? 0 : profile.length);
        int length = offset + frame.size;
        little(output, 4, length - 8, 4);
        little(output, 24, frame.w - 1, 3);
        little(output, 27, frame.h - 1, 3);
        System.arraycopy(data, frame.start, output, offset, frame.size);
        return length;
    }

    /** The image is borrowed until this synchronous callback returns; copy it to retain it. */
    public interface FrameConsumer {
        void frame(Bitmap image, int delay, int index, int count) throws Exception;
    }

    public Decoded decode(int maximum, int maximumFrames) throws Exception {
        Decoded result = new Decoded();
        try {
            stream(maximum, maximumFrames, (image, delay, index, count) -> {
                result.images.add(image.copy(Bitmap.Config.ARGB_8888, false));
                result.delays.add(delay);
            });
            return result;
        } catch (Exception error) {
            result.close();
            throw error;
        }
    }

    public void stream(int maximum, int maximumFrames, FrameConsumer consumer) throws Exception {
        if (frames.isEmpty()) throw new IllegalArgumentException("不是 WebP 动画");
        if (maximum < 1 || maximumFrames < 1) throw new IllegalArgumentException("WebP 输出尺寸或帧数无效");
        int count = Math.min(maximumFrames, frames.size());
        int[] wanted = new int[count], delays = new int[count];
        if (count == frames.size()) {
            for (int i = 0; i < count; i++) { wanted[i] = i; delays[i] = frames.get(i).delay; }
        } else {
            int source = 0, time = 0;
            for (int i = 0; i < count; i++) {
                int at = i * duration / count;
                while (source < frames.size() - 1 && time + frames.get(source).delay <= at)
                    time += frames.get(source++).delay;
                wanted[i] = source;
                delays[i] = Math.round((i + 1) * (float) duration / count) - Math.round(i * (float) duration / count);
            }
        }
        // Only a complete SRC replacement can discard the previous compositing history.
        int[] restart = new int[frames.size()];
        int replacement = -1, maximumPatch = 0;
        for (int i = 0; i < frames.size(); i++) {
            Frame frame = frames.get(i);
            if (frame.x == 0 && frame.y == 0 && frame.w == width && frame.h == height && (frame.flags & 2) != 0)
                replacement = i;
            restart[i] = replacement;
            maximumPatch = Math.max(maximumPatch, frame.size);
        }
        byte[] patchBytes = Arrays.copyOf(PATCH_HEADER,
                PATCH_HEADER.length + (profile == null ? 0 : profile.length) + maximumPatch);
        if (profile != null) {
            patchBytes[20] |= 0x20;
            System.arraycopy(profile, 0, patchBytes, PATCH_HEADER.length, profile.length);
        }
        float scale = Math.min(1f, (float) maximum / Math.max(width, height));
        int ow = Math.max(1, Math.round(width * scale)), oh = Math.max(1, Math.round(height * scale));
        Bitmap canvasBitmap = null, outputBitmap = null, decodedPatch = null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inMutable = true;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        boolean reuseAllowed = true;
        try {
            canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(canvasBitmap);
            canvas.drawColor(background, PorterDuff.Mode.SRC);
            Canvas outputCanvas = null;
            if (ow != width || oh != height) {
                outputBitmap = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
                outputCanvas = new Canvas(outputBitmap);
            }
            Paint sourcePaint = new Paint();
            sourcePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
            Paint backgroundPaint = new Paint();
            backgroundPaint.setColor(background);
            backgroundPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
            Paint scalePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
            scalePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
            Rect patchRect = new Rect(), outputRect = new Rect(0, 0, ow, oh);
            int next = 0;
            for (int i = 0; i < frames.size() && next < count; i++) {
                int start = restart[wanted[next]];
                if (start > i) i = start;
                Frame frame = frames.get(i);
                boolean selected = wanted[next] == i;
                // An unobserved, disposed patch leaves only its background rectangle behind.
                if (selected || (frame.flags & 1) == 0) {
                    int length = patch(frame, patchBytes);
                    boolean reusable = reuseAllowed && decodedPatch != null && decodedPatch.isMutable()
                            && decodedPatch.getConfig() == Bitmap.Config.ARGB_8888
                            && decodedPatch.getAllocationByteCount() >= (long) frame.w * frame.h * 4;
                    if (!reusable && decodedPatch != null) {
                        decodedPatch.recycle(); decodedPatch = null;
                    }
                    options.inBitmap = reusable ? decodedPatch : null;
                    Bitmap bitmap;
                    try {
                        bitmap = BitmapFactory.decodeByteArray(patchBytes, 0, length, options);
                    } catch (IllegalArgumentException rejected) {
                        if (options.inBitmap == null) throw rejected;
                        // Some vendor decoders reject reuse: use the ordinary path for this stream.
                        reuseAllowed = false;
                        options.inBitmap = null;
                        decodedPatch.recycle(); decodedPatch = null;
                        bitmap = BitmapFactory.decodeByteArray(patchBytes, 0, length, options);
                    }
                    if (bitmap == null) throw new IllegalArgumentException("WebP 动画帧无法解码");
                    if (decodedPatch != null && decodedPatch != bitmap) decodedPatch.recycle();
                    decodedPatch = bitmap;
                    patchRect.set(frame.x, frame.y, frame.x + frame.w, frame.y + frame.h);
                    canvas.drawBitmap(decodedPatch, null, patchRect, (frame.flags & 2) != 0 ? sourcePaint : null);
                }
                while (next < count && wanted[next] == i) {
                    Bitmap image = canvasBitmap;
                    if (outputCanvas != null) {
                        outputCanvas.drawBitmap(canvasBitmap, null, outputRect, scalePaint);
                        image = outputBitmap;
                    }
                    consumer.frame(image, delays[next], next, count);
                    next++;
                }
                if ((frame.flags & 1) != 0)
                    canvas.drawRect(frame.x, frame.y, frame.x + frame.w, frame.y + frame.h, backgroundPaint);
            }
            if (next != count) throw new IllegalArgumentException("WebP 动画帧不完整");
        } finally {
            options.inBitmap = null;
            if (decodedPatch != null) decodedPatch.recycle();
            if (outputBitmap != null) outputBitmap.recycle();
            if (canvasBitmap != null) canvasBitmap.recycle();
        }
    }
}
