package com.stickerbridge.app;

import android.graphics.Bitmap;
import android.graphics.Rect;
import java.util.Arrays;

/** Conservative pixel bounds for a known image-preview viewport, in screenshot coordinates. */
public final class PreviewGeometry {
    private PreviewGeometry() {}

    private static final class Candidate {
        final Rect bounds;
        Candidate(Rect bounds) { this.bounds = bounds; }
        long area() { return (long) bounds.width() * bounds.height(); }
    }

    /**
     * Finds one dominant, filled image band against a consistent surrounding background.
     * The bitmap and rectangles are borrowed and never changed. Unreadable/ambiguous
     * screenshots return null. Scratch storage is one pixel row plus O(width) counters.
     */
    public static Rect fit(Bitmap screen, Rect viewport, float density, Rect ignore) {
        if (screen == null || screen.isRecycled() || viewport == null
                || density <= 0 || Float.isNaN(density) || Float.isInfinite(density)) return null;
        try {
            Rect view = new Rect(viewport);
            if (!view.intersect(0, 0, screen.getWidth(), screen.getHeight())) return null;
            int minimum = Math.max(4, Math.round(64 * density));
            if (view.width() < minimum || view.height() < minimum) return null;
            int gap = Math.max(1, Math.round(8 * density));
            Rect mask = null;
            if (ignore != null && !ignore.isEmpty()) {
                int pad = Math.max(1, Math.round(2 * density));
                mask = new Rect((int) Math.max(view.left, (long) ignore.left - pad),
                        (int) Math.max(view.top, (long) ignore.top - pad),
                        (int) Math.min(view.right, (long) ignore.right + pad),
                        (int) Math.min(view.bottom, (long) ignore.bottom + pad));
                if (mask.isEmpty()) mask = null;
            }
            int[] row = new int[view.width()];
            int background = background(screen, view, density, mask, row);
            if (background < 0) return null;
            int[] columnInk = new int[view.width()], columnSeen = new int[view.width()];
            Candidate best = null, second = null;
            int step = Math.max(1, view.width() / 512);
            int start = -1, last = -1, strongRows = 0;
            for (int y = view.top; y <= view.bottom; y++) {
                boolean strong = false;
                if (y < view.bottom) {
                    screen.getPixels(row, 0, row.length, view.left, y, row.length, 1);
                    int ink = 0, seen = 0;
                    for (int x = 0; x < row.length; x += step) {
                        if (masked(mask, view.left + x, y)) continue;
                        seen++;
                        if (foreground(row[x], background)) ink++;
                    }
                    int threshold = Math.max(3, Math.min((int) Math.ceil(seen * .12),
                            (int) Math.ceil((double) minimum / step * .4)));
                    strong = seen > 0 && ink >= threshold;
                }
                if (strong) {
                    if (start < 0) { start = y; strongRows = 0; }
                    last = y;
                    strongRows++;
                } else if (start >= 0 && (y == view.bottom || y - last > gap)) {
                    int height = last + 1 - start;
                    if (height >= minimum && strongRows >= height * .80) {
                        Candidate candidate = columns(screen, view, mask, row, columnInk, columnSeen,
                                background, start, last + 1, minimum, gap, density);
                        if (candidate != null) {
                            if (best == null || candidate.bounds.height() > best.bounds.height()
                                    || (candidate.bounds.height() == best.bounds.height() && candidate.area() > best.area())) {
                                second = best; best = candidate;
                            } else if (second == null || candidate.bounds.height() > second.bounds.height()) {
                                second = candidate;
                            }
                        }
                    }
                    start = -1;
                }
            }
            if (best == null) return null;
            if (second != null && second.bounds.height() >= best.bounds.height() * .8
                    && second.area() >= best.area() * .7) return null;
            return new Rect(best.bounds);
        } catch (RuntimeException unreadable) {
            // Hardware-backed, recycled during capture, or otherwise unreadable pixels.
            return null;
        }
    }

    private static boolean masked(Rect mask, int x, int y) {
        return mask != null && mask.contains(x, y);
    }

    private static boolean foreground(int color, int background) {
        if ((color >>> 24) < 128) return false;
        return Math.abs(((color >> 16) & 255) - ((background >> 16) & 255)) > 20
                || Math.abs(((color >> 8) & 255) - ((background >> 8) & 255)) > 20
                || Math.abs((color & 255) - (background & 255)) > 20;
    }

    private static int background(Bitmap screen, Rect view, float density, Rect mask, int[] row) {
        int edge = Math.max(2, Math.min(Math.min(view.width(), view.height()) / 8, Math.round(6 * density)));
        int[] counts = new int[4096], sums = new int[4096 * 3];
        int total = 0;
        for (int side = 0; side < 2; side++) {
            int first = side == 0 ? view.top : view.bottom - edge;
            for (int y = first; y < first + edge; y++) {
                screen.getPixels(row, 0, row.length, view.left, y, row.length, 1);
                for (int corner = 0; corner < 2; corner++) {
                    int from = corner == 0 ? 0 : row.length - edge;
                    for (int x = from; x < from + edge; x++) {
                        if (masked(mask, view.left + x, y) || (row[x] >>> 24) < 128) continue;
                        int color = row[x], r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
                        int key = (r >> 4) << 8 | (g >> 4) << 4 | (b >> 4);
                        counts[key]++;
                        sums[key * 3] += r; sums[key * 3 + 1] += g; sums[key * 3 + 2] += b;
                        total++;
                    }
                }
            }
        }
        int chosen = 0;
        for (int i = 1; i < counts.length; i++) if (counts[i] > counts[chosen]) chosen = i;
        if (total < 16 || counts[chosen] < total * .65) return -1;
        int n = counts[chosen];
        return (sums[chosen * 3] / n) << 16 | (sums[chosen * 3 + 1] / n) << 8 | sums[chosen * 3 + 2] / n;
    }

    private static Candidate columns(Bitmap screen, Rect view, Rect mask, int[] row, int[] ink, int[] seen,
                                     int background, int top, int bottom, int minimum, int gap, float density) {
        Arrays.fill(ink, 0); Arrays.fill(seen, 0);
        int step = Math.max(1, (bottom - top) / 512);
        for (int y = top; y < bottom; y += step) {
            screen.getPixels(row, 0, row.length, view.left, y, row.length, 1);
            for (int x = 0; x < row.length; x++) {
                if (masked(mask, view.left + x, y)) continue;
                seen[x]++;
                if (foreground(row[x], background)) ink[x]++;
            }
        }
        int start = -1, last = -1, strongColumns = 0;
        int bestStart = -1, bestEnd = -1, bestWidth = 0, secondWidth = 0;
        for (int x = 0; x <= row.length; x++) {
            boolean strong = x < row.length && seen[x] > 0 && ink[x] >= Math.max(2, Math.ceil(seen[x] * .18));
            if (strong) {
                if (start < 0) { start = x; strongColumns = 0; }
                last = x; strongColumns++;
            } else if (start >= 0 && (x == row.length || x - last > gap)) {
                int width = last + 1 - start;
                if (width >= minimum && strongColumns >= width * .80) {
                    if (width > bestWidth) {
                        secondWidth = bestWidth; bestWidth = width;
                        bestStart = start; bestEnd = last + 1;
                    } else secondWidth = Math.max(secondWidth, width);
                }
                start = -1;
            }
        }
        if (bestStart < 0 || (secondWidth >= minimum && secondWidth >= bestWidth * .6)) return null;
        long visible = 0, filled = 0;
        for (int x = bestStart; x < bestEnd; x++) { visible += seen[x]; filled += ink[x]; }
        if (visible == 0 || filled < visible * .5) return null;
        int left = view.left + bestStart, right = view.left + bestEnd;
        int edge = Math.max(1, Math.round(density));
        if (left - view.left <= edge) left = view.left;
        if (view.right - right <= edge) right = view.right;
        // Require observable background on both sides of at least one axis.
        boolean verticalBorder = top - view.top >= gap && view.bottom - bottom >= gap;
        boolean horizontalBorder = left - view.left >= gap && view.right - right >= gap;
        if (!verticalBorder && !horizontalBorder) return null;
        return new Candidate(new Rect(left, top, right, bottom));
    }
}
