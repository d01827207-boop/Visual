package com.aero.visual;

import android.content.SharedPreferences;
import android.graphics.Color;

/** Immutable snapshot of the settings. One place for keys and defaults. */
public final class Config {
    public static final String PREFS = "visual";

    public static final int SHAPE_CROSS = 0, SHAPE_DOT = 1, SHAPE_RING = 2, SHAPE_CROSS_DOT = 3;

    public static final String K_CH_ON = "ch_on", K_CH_SHAPE = "ch_shape", K_CH_SIZE = "ch_size",
            K_CH_THICK = "ch_thick", K_CH_COLOR = "ch_color", K_OFF_X = "off_x", K_OFF_Y = "off_y",
            K_FOV_ON = "fov_on", K_FOV_R = "fov_r", K_FOV_THICK = "fov_thick", K_FOV_COLOR = "fov_color",
            K_DET_ON = "det_on", K_HUE_MIN = "hue_min", K_HUE_MAX = "hue_max", K_SAT_MIN = "sat_min",
            K_VAL_MIN = "val_min", K_MIN_AREA = "min_area", K_MAX_BOXES = "max_boxes",
            K_INTERVAL = "interval", K_INSIDE_FOV = "inside_fov", K_BOX_THICK = "box_thick";

    // Sizes are in dp. Defaults for the detection colour are UNCALIBRATED: the real enemy colour in the
    // game is not known to the author. Calibrate with the "take colour under crosshair" notification action.
    public static final boolean D_CH_ON = true;
    public static final int D_CH_SHAPE = SHAPE_CROSS_DOT, D_CH_SIZE = 10, D_CH_THICK = 2;
    public static final int D_CH_COLOR = 0xFFFFFFFF;
    public static final int D_OFF_X = 0, D_OFF_Y = 0;
    public static final boolean D_FOV_ON = true;
    public static final int D_FOV_R = 120, D_FOV_THICK = 1;
    public static final int D_FOV_COLOR = 0xFF00E5FF;
    public static final boolean D_DET_ON = true;
    public static final int D_HUE_MIN = 345, D_HUE_MAX = 15;      // degrees, wraps through 0 (reds)
    public static final int D_SAT_MIN = 55, D_VAL_MIN = 45;       // percent
    public static final int D_MIN_AREA = 12;                      // pixels of the downscaled frame
    public static final int D_MAX_BOXES = 8, D_INTERVAL = 60;     // boxes; ms between analysed frames
    public static final boolean D_INSIDE_FOV = false;
    public static final int D_BOX_THICK = 2;

    public final boolean chOn;
    public final int chShape, chSize, chThick, chColor, offX, offY;
    public final boolean fovOn;
    public final int fovR, fovThick, fovColor;
    public final boolean detOn;
    public final int hueMin, hueMax, satMin, valMin, minArea, maxBoxes, interval;
    public final boolean insideFov;
    public final int boxThick;

    private Config(SharedPreferences p) {
        chOn = p.getBoolean(K_CH_ON, D_CH_ON);
        chShape = p.getInt(K_CH_SHAPE, D_CH_SHAPE);
        chSize = p.getInt(K_CH_SIZE, D_CH_SIZE);
        chThick = p.getInt(K_CH_THICK, D_CH_THICK);
        chColor = p.getInt(K_CH_COLOR, D_CH_COLOR);
        offX = p.getInt(K_OFF_X, D_OFF_X);
        offY = p.getInt(K_OFF_Y, D_OFF_Y);
        fovOn = p.getBoolean(K_FOV_ON, D_FOV_ON);
        fovR = p.getInt(K_FOV_R, D_FOV_R);
        fovThick = p.getInt(K_FOV_THICK, D_FOV_THICK);
        fovColor = p.getInt(K_FOV_COLOR, D_FOV_COLOR);
        detOn = p.getBoolean(K_DET_ON, D_DET_ON);
        hueMin = p.getInt(K_HUE_MIN, D_HUE_MIN);
        hueMax = p.getInt(K_HUE_MAX, D_HUE_MAX);
        satMin = p.getInt(K_SAT_MIN, D_SAT_MIN);
        valMin = p.getInt(K_VAL_MIN, D_VAL_MIN);
        minArea = p.getInt(K_MIN_AREA, D_MIN_AREA);
        maxBoxes = p.getInt(K_MAX_BOXES, D_MAX_BOXES);
        interval = p.getInt(K_INTERVAL, D_INTERVAL);
        insideFov = p.getBoolean(K_INSIDE_FOV, D_INSIDE_FOV);
        boxThick = p.getInt(K_BOX_THICK, D_BOX_THICK);
    }

    public static Config load(SharedPreferences p) { return new Config(p); }

    public ColorDetector.Params detectorParams() {
        return new ColorDetector.Params(hueMin, hueMax, satMin / 100f, valMin / 100f, minArea, maxBoxes, 3);
    }

    /**
     * Boxes are drawn in the complementary hue of the detection range, so the overlay's own boxes
     * (which end up in the screen capture) do not get detected again.
     */
    public int boxColor() {
        float mid = hueMin <= hueMax ? (hueMin + hueMax) / 2f : ((hueMin + hueMax + 360) / 2f) % 360f;
        return Color.HSVToColor(new float[] {(mid + 180f) % 360f, 1f, 1f});
    }
}
