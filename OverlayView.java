package com.aero.visual;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.List;

/** Full-screen, non-touchable view: crosshair, FOV circle and enemy boxes. */
public final class OverlayView extends View {
    public interface SizeListener { void onSize(int w, int h); }

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private volatile Config cfg;
    private volatile List<ColorDetector.Box> boxes;
    private volatile float scaleX = 1f, scaleY = 1f; // view pixels per analysed-frame pixel
    private SizeListener sizeListener;

    public OverlayView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);
    }

    public void setSizeListener(SizeListener l) { sizeListener = l; }

    public void setConfig(Config c) {
        cfg = c;
        postInvalidate();
    }

    public void setBoxes(List<ColorDetector.Box> list, float sx, float sy) {
        scaleX = sx;
        scaleY = sy;
        boxes = list;
        postInvalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (sizeListener != null && w > 0 && h > 0) sizeListener.onSize(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        Config k = cfg;
        if (k == null) return;
        float cx = getWidth() / 2f + k.offX * density;
        float cy = getHeight() / 2f + k.offY * density;
        float fovPx = k.fovR * density;

        if (k.fovOn) {
            stroke.setColor(k.fovColor);
            stroke.setStrokeWidth(Math.max(1f, k.fovThick * density));
            c.drawCircle(cx, cy, fovPx, stroke);
        }
        if (k.chOn) drawCrosshair(c, k, cx, cy);

        List<ColorDetector.Box> list = boxes;
        if (list == null || !k.detOn) return;
        float sx = scaleX, sy = scaleY;
        int boxColor = k.boxColor();
        stroke.setStrokeWidth(Math.max(1f, k.boxThick * density));
        for (ColorDetector.Box b : list) {
            boolean inside = ColorDetector.insideCircle(b.centerX() * sx, b.centerY() * sy, cx, cy, fovPx);
            if (k.insideFov && k.fovOn && !inside) continue;
            stroke.setColor(boxColor);
            stroke.setAlpha(inside ? 255 : 120);
            c.drawRect(b.left * sx, b.top * sy, b.right * sx, b.bottom * sy, stroke);
        }
    }

    private void drawCrosshair(Canvas c, Config k, float cx, float cy) {
        float half = k.chSize * density;
        float thick = Math.max(1f, k.chThick * density);
        stroke.setColor(k.chColor);
        stroke.setStrokeWidth(thick);
        fill.setColor(k.chColor);
        switch (k.chShape) {
            case Config.SHAPE_DOT:
                c.drawCircle(cx, cy, thick * 1.5f, fill);
                break;
            case Config.SHAPE_RING:
                c.drawCircle(cx, cy, half, stroke);
                break;
            case Config.SHAPE_CROSS_DOT:
                c.drawLine(cx - half, cy, cx + half, cy, stroke);
                c.drawLine(cx, cy - half, cx, cy + half, stroke);
                c.drawCircle(cx, cy, thick * 1.5f, fill);
                break;
            case Config.SHAPE_CROSS:
            default:
                c.drawLine(cx - half, cy, cx + half, cy, stroke);
                c.drawLine(cx, cy - half, cx, cy + half, stroke);
                break;
        }
    }
}
