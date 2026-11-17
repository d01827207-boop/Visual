package com.aero.visual;

/**
 * Pure geometry for one "pull-down" swipe: where it starts, where it ends, how long it takes.
 * No Android classes, so it can be checked on a desktop JVM.
 */
public final class RecoilPlan {
    public static final long MAX_DURATION_MS = 10_000;
    private static final float MARGIN = 2f;

    public final float x0, y0, x1, y1;
    public final long delayMs, durationMs;

    private RecoilPlan(float x0, float y0, float x1, float y1, long delayMs, long durationMs) {
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
        this.delayMs = delayMs;
        this.durationMs = durationMs;
    }

    /**
     * @param startXPct,startYPct where the swipe starts, percent of the screen (0..100)
     * @param rateX,rateY         speed in pixels per second; the sign is the direction (positive Y = down)
     * @return the plan, or null when there is nothing sensible to do (zero speed, empty screen, ...)
     */
    public static RecoilPlan build(int screenW, int screenH, float startXPct, float startYPct,
                                   float rateX, float rateY, long durationMs, long delayMs) {
        if (screenW < 10 || screenH < 10) return null;
        if (durationMs <= 0) return null;
        if (delayMs < 0) delayMs = 0;
        long dur = Math.min(durationMs, MAX_DURATION_MS);

        float x0 = clamp(screenW * startXPct / 100f, MARGIN, screenW - MARGIN);
        float y0 = clamp(screenH * startYPct / 100f, MARGIN, screenH - MARGIN);
        float dx = rateX * dur / 1000f;
        float dy = rateY * dur / 1000f;
        if (Math.abs(dx) < 1f && Math.abs(dy) < 1f) return null;

        // If the swipe would leave the screen, shorten it and keep the same speed (shorter time too).
        float f = 1f;
        if (Math.abs(dx) > 0f) {
            float allowed = dx > 0 ? (screenW - MARGIN) - x0 : x0 - MARGIN;
            f = Math.min(f, allowed / Math.abs(dx));
        }
        if (Math.abs(dy) > 0f) {
            float allowed = dy > 0 ? (screenH - MARGIN) - y0 : y0 - MARGIN;
            f = Math.min(f, allowed / Math.abs(dy));
        }
        if (f <= 0f) return null;
        dx *= f;
        dy *= f;
        if (Math.abs(dx) < 1f && Math.abs(dy) < 1f) return null;
        long d = Math.max(1L, Math.round(dur * (double) f));
        return new RecoilPlan(x0, y0, x0 + dx, y0 + dy, delayMs, d);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
