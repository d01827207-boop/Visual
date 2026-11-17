package com.aero.visual;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Colour-blob detector. No Android dependencies, so it can be checked on a plain JVM. */
public final class ColorDetector {
    private ColorDetector() {}

    public static final class Params {
        public final float hueMin;   // degrees 0..360; hueMin > hueMax means the range wraps (reds)
        public final float hueMax;
        public final float satMin;   // 0..1
        public final float valMin;   // 0..1
        public final int minArea;    // pixels in the analysed frame
        public final int maxBoxes;
        public final int mergeGap;   // boxes closer than this (pixels) are merged into one

        public Params(float hueMin, float hueMax, float satMin, float valMin,
                      int minArea, int maxBoxes, int mergeGap) {
            this.hueMin = hueMin;
            this.hueMax = hueMax;
            this.satMin = satMin;
            this.valMin = valMin;
            this.minArea = minArea;
            this.maxBoxes = maxBoxes;
            this.mergeGap = mergeGap;
        }
    }

    public static final class Box {
        public final int left, top, right, bottom; // right and bottom are exclusive
        public final int area;

        public Box(int left, int top, int right, int bottom, int area) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.area = area;
        }

        public float centerX() { return (left + right) / 2f; }
        public float centerY() { return (top + bottom) / 2f; }
    }

    /** Returns {hue 0..360 (-1 for grey), saturation 0..1, value 0..1}. */
    public static float[] hsv(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int d = max - min;
        float v = max / 255f;
        float s = max == 0 ? 0f : (float) d / max;
        float h;
        if (d == 0) {
            h = -1f;
        } else if (max == r) {
            h = 60f * (((g - b) / (float) d) % 6f);
        } else if (max == g) {
            h = 60f * (((b - r) / (float) d) + 2f);
        } else {
            h = 60f * (((r - g) / (float) d) + 4f);
        }
        if (h < 0f && d != 0) h += 360f;
        return new float[] {h, s, v};
    }

    public static boolean matches(int argb, Params p) {
        float[] c = hsv(argb);
        if (c[0] < 0f || c[1] < p.satMin || c[2] < p.valMin) return false;
        float h = c[0];
        return p.hueMin <= p.hueMax ? (h >= p.hueMin && h <= p.hueMax)
                                    : (h >= p.hueMin || h <= p.hueMax);
    }

    /** Average colour of a (2*radius+1) square around (cx, cy), or null if the window is outside the frame. */
    public static float[] averageHsv(int[] argb, int w, int h, int cx, int cy, int radius) {
        if (argb == null || w <= 0 || h <= 0 || (long) w * h > argb.length) return null;
        int x0 = Math.max(0, cx - radius), x1 = Math.min(w - 1, cx + radius);
        int y0 = Math.max(0, cy - radius), y1 = Math.min(h - 1, cy + radius);
        if (x0 > x1 || y0 > y1) return null;
        long r = 0, g = 0, b = 0;
        int n = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int p = argb[y * w + x];
                r += (p >> 16) & 0xFF;
                g += (p >> 8) & 0xFF;
                b += p & 0xFF;
                n++;
            }
        }
        return hsv(0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n));
    }

    public static boolean insideCircle(float x, float y, float cx, float cy, float radius) {
        float dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy <= radius * radius;
    }

    /** Finds connected blobs of matching pixels and returns their bounding boxes, biggest first. */
    public static List<Box> detect(int[] argb, int w, int h, Params p) {
        List<Box> found = new ArrayList<>();
        if (argb == null || p == null || w <= 0 || h <= 0 || (long) w * h > argb.length) return found;
        final int n = w * h;

        byte[] mask = new byte[n];
        for (int i = 0; i < n; i++) {
            if (matches(argb[i], p)) mask[i] = 1;
        }

        int[] stack = new int[n]; // every pixel is pushed at most once (marked on push)
        for (int start = 0; start < n; start++) {
            if (mask[start] != 1) continue;
            int sp = 0;
            stack[sp++] = start;
            mask[start] = 2;
            int minX = w, minY = h, maxX = -1, maxY = -1, area = 0;
            while (sp > 0) {
                int idx = stack[--sp];
                int x = idx % w, y = idx / w;
                area++;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                if (x > 0 && mask[idx - 1] == 1) { mask[idx - 1] = 2; stack[sp++] = idx - 1; }
                if (x < w - 1 && mask[idx + 1] == 1) { mask[idx + 1] = 2; stack[sp++] = idx + 1; }
                if (y > 0 && mask[idx - w] == 1) { mask[idx - w] = 2; stack[sp++] = idx - w; }
                if (y < h - 1 && mask[idx + w] == 1) { mask[idx + w] = 2; stack[sp++] = idx + w; }
            }
            if (area >= p.minArea) found.add(new Box(minX, minY, maxX + 1, maxY + 1, area));
        }

        sortByAreaDesc(found);
        if (found.size() > 64) found = new ArrayList<>(found.subList(0, 64));

        boolean changed = true;
        while (changed) {
            changed = false;
            outer:
            for (int i = 0; i < found.size(); i++) {
                for (int j = i + 1; j < found.size(); j++) {
                    Box a = found.get(i), b = found.get(j);
                    if (near(a, b, p.mergeGap)) {
                        found.set(i, new Box(Math.min(a.left, b.left), Math.min(a.top, b.top),
                                Math.max(a.right, b.right), Math.max(a.bottom, b.bottom), a.area + b.area));
                        found.remove(j);
                        changed = true;
                        break outer;
                    }
                }
            }
        }

        sortByAreaDesc(found);
        int limit = Math.max(0, p.maxBoxes);
        return found.size() > limit ? new ArrayList<>(found.subList(0, limit)) : found;
    }

    private static boolean near(Box a, Box b, int gap) {
        return a.left - gap < b.right && b.left - gap < a.right
                && a.top - gap < b.bottom && b.top - gap < a.bottom;
    }

    private static void sortByAreaDesc(List<Box> list) {
        Collections.sort(list, new Comparator<Box>() {
            @Override
            public int compare(Box x, Box y) { return Integer.compare(y.area, x.area); }
        });
    }
}
