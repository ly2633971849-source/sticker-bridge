package com.stickerbridge.app;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/** GIF89a writer with a local 256-color palette and standard LZW. No platform dependencies. */
public final class GifWriter implements AutoCloseable {
    private final OutputStream out;
    private final int width, height;
    private final int[] histogram = new int[32768], lookup = new int[32768];
    private final int[] colors = new int[32768], sorted = new int[32768], buckets = new int[32];
    private final int[] palette = new int[256], dictionaryKeys = new int[8192];
    private final short[] dictionaryCodes = new short[8192];
    private final byte[] indices, block = new byte[255];
    private final Box[] boxes = new Box[255], boxPool = new Box[509];
    private int bitBuffer, bitCount, blockLength;
    private boolean closed;

    private static final class Box {
        int start, end, axis;
        long weight, priority;
    }

    public GifWriter(OutputStream output, int width, int height) throws IOException {
        if (width < 1 || height < 1 || width > 65535 || height > 65535
                || (long) width * height > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid GIF size");
        this.out = output instanceof java.io.BufferedOutputStream
                ? output : new java.io.BufferedOutputStream(output, 65536);
        this.width = width;
        this.height = height;
        indices = new byte[width * height];
        for (int i = 0; i < boxPool.length; i++) boxPool[i] = new Box();
        ascii("GIF89a");
        shortLE(width); shortLE(height); out.write(0x70); out.write(0); out.write(0);
        out.write(new byte[]{0x21, (byte) 0xff, 11});
        ascii("NETSCAPE2.0");
        out.write(new byte[]{3, 1, 0, 0, 0});
    }

    private void ascii(String value) throws IOException {
        out.write(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private void shortLE(int value) throws IOException {
        out.write(value & 255); out.write((value >> 8) & 255);
    }

    public void frame(int[] argb, int delayMs) throws IOException {
        if (closed || argb.length != indices.length) throw new IllegalArgumentException("Invalid GIF frame");
        Arrays.fill(histogram, 0);
        boolean transparent = false;
        for (int color : argb) {
            if ((color >>> 24) < 128) { transparent = true; continue; }
            histogram[((color >> 19) & 31) << 10 | ((color >> 11) & 31) << 5 | ((color >> 3) & 31)]++;
        }
        buildPalette();
        Arrays.fill(lookup, -1);
        for (int p = 0; p < argb.length; p++) {
            int color = argb[p];
            if ((color >>> 24) < 128) { indices[p] = 0; continue; }
            int key = ((color >> 19) & 31) << 10 | ((color >> 11) & 31) << 5 | ((color >> 3) & 31);
            int best = lookup[key];
            if (best < 0) {
                int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
                int score = Integer.MAX_VALUE;
                best = 1;
                for (int i = 1; i < 256; i++) {
                    int q = palette[i], dr = r - ((q >> 16) & 255), dg = g - ((q >> 8) & 255), db = b - (q & 255);
                    int distance = dr * dr + dg * dg + db * db;
                    if (distance < score) { score = distance; best = i; }
                }
                lookup[key] = best;
            }
            indices[p] = (byte) best;
        }
        out.write(0x21); out.write(0xf9); out.write(4); out.write(transparent ? 9 : 8);
        shortLE(Math.max(2, Math.round(delayMs / 10f))); out.write(0); out.write(0);
        out.write(0x2c); shortLE(0); shortLE(0); shortLE(width); shortLE(height); out.write(0x87);
        for (int color : palette) {
            out.write((color >> 16) & 255); out.write((color >> 8) & 255); out.write(color & 255);
        }
        out.write(8);
        lzw();
        out.write(0);
    }

    private void measure(Box box, int start, int end) {
        box.start = start; box.end = end;
        int r0 = 31, g0 = 31, b0 = 31, r1 = 0, g1 = 0, b1 = 0;
        long weight = 0;
        for (int i = start; i < end; i++) {
            int color = colors[i], r = (color >> 10) & 31, g = (color >> 5) & 31, b = color & 31;
            r0 = Math.min(r0, r); r1 = Math.max(r1, r);
            g0 = Math.min(g0, g); g1 = Math.max(g1, g);
            b0 = Math.min(b0, b); b1 = Math.max(b1, b);
            weight += histogram[color];
        }
        int range = r1 - r0;
        box.axis = 0;
        if (g1 - g0 > range) { range = g1 - g0; box.axis = 1; }
        if (b1 - b0 > range) { range = b1 - b0; box.axis = 2; }
        box.weight = weight;
        box.priority = weight * range;
    }

    private void buildPalette() {
        int colorCount = 0;
        for (int i = 0; i < histogram.length; i++) if (histogram[i] > 0) colors[colorCount++] = i;
        int count = 1, pool = 1;
        boxes[0] = boxPool[0];
        measure(boxes[0], 0, colorCount);
        while (count < 255) {
            int chosen = -1;
            long priority = -1;
            for (int i = 0; i < count; i++) {
                Box box = boxes[i];
                if (box.end - box.start > 1 && box.priority > priority) {
                    priority = box.priority; chosen = i;
                }
            }
            if (chosen < 0) break;
            Box box = boxes[chosen];
            int shift = (2 - box.axis) * 5;
            Arrays.fill(buckets, 0);
            for (int i = box.start; i < box.end; i++) buckets[(colors[i] >> shift) & 31]++;
            int offset = box.start;
            for (int i = 0; i < 32; i++) {
                int length = buckets[i]; buckets[i] = offset; offset += length;
            }
            // Stable counting sort preserves the old stable axis-sort and every palette tie.
            for (int i = box.start; i < box.end; i++) {
                int color = colors[i]; sorted[buckets[(color >> shift) & 31]++] = color;
            }
            System.arraycopy(sorted, box.start, colors, box.start, box.end - box.start);
            long sum = 0;
            int split = box.end - 1;
            for (int i = box.start; i < box.end - 1; i++) {
                sum += histogram[colors[i]];
                if (sum * 2 >= box.weight) { split = i + 1; break; }
            }
            Box left = boxPool[pool++], right = boxPool[pool++];
            measure(left, box.start, split);
            measure(right, split, box.end);
            System.arraycopy(boxes, chosen + 1, boxes, chosen, count - chosen - 1);
            boxes[count - 1] = left;
            boxes[count++] = right;
        }
        Arrays.fill(palette, 0);
        int at = 1;
        for (int i = 0; i < count; i++) {
            Box box = boxes[i];
            long r = 0, g = 0, b = 0;
            for (int p = box.start; p < box.end; p++) {
                int color = colors[p];
                long weight = histogram[color];
                r += (((color >> 10) & 31) * 255 / 31) * weight;
                g += (((color >> 5) & 31) * 255 / 31) * weight;
                b += ((color & 31) * 255 / 31) * weight;
            }
            if (box.weight > 0)
                palette[at++] = (int) (r / box.weight) << 16 | (int) (g / box.weight) << 8 | (int) (b / box.weight);
        }
        for (int i = at; i < 256; i++) palette[i] = palette[Math.max(1, at - 1)];
    }

    private void lzw() throws IOException {
        Arrays.fill(dictionaryKeys, -1);
        bitBuffer = bitCount = blockLength = 0;
        int next = 258, size = 9;
        code(256, size);
        int prefix = indices[0] & 255;
        for (int i = 1; i < indices.length; i++) {
            int suffix = indices[i] & 255, key = (prefix << 8) | suffix;
            int slot = (key * 0x9e3779b9) >>> 19;
            while (dictionaryKeys[slot] != -1 && dictionaryKeys[slot] != key) slot = (slot + 1) & 8191;
            if (dictionaryKeys[slot] == key) { prefix = dictionaryCodes[slot]; continue; }
            code(prefix, size);
            if (next < 4096) {
                dictionaryKeys[slot] = key;
                dictionaryCodes[slot] = (short) next++;
                if (next > (1 << size) && size < 12) size++;
            } else {
                code(256, size);
                Arrays.fill(dictionaryKeys, -1);
                next = 258; size = 9;
            }
            prefix = suffix;
        }
        code(prefix, size);
        if (next == (1 << size) && size < 12) size++;
        code(257, size);
        if (bitCount > 0) packedByte(bitBuffer & 255);
        if (blockLength > 0) flushBlock();
    }

    private void code(int value, int width) throws IOException {
        bitBuffer |= value << bitCount;
        bitCount += width;
        while (bitCount >= 8) {
            packedByte(bitBuffer & 255);
            bitBuffer >>>= 8;
            bitCount -= 8;
        }
    }

    private void packedByte(int value) throws IOException {
        block[blockLength++] = (byte) value;
        if (blockLength == 255) flushBlock();
    }

    private void flushBlock() throws IOException {
        out.write(blockLength);
        out.write(block, 0, blockLength);
        blockLength = 0;
    }

    @Override public void close() throws IOException {
        if (!closed) { out.write(0x3b); out.flush(); closed = true; }
    }
}
