package com.aero.visual;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;
import android.widget.Toast;

/**
 * EXPERIMENTAL. A small draggable "AR" button floats over the screen. Tapping it makes this accessibility
 * service perform one swipe (default: downwards in the camera area), which is meant to counter weapon recoil.
 * It does not read the game and does not know when you shoot: you start the swipe yourself by tapping the button.
 * Whether the game accepts such injected touches is NOT verified.
 */
public final class AntiRecoilService extends AccessibilityService {
    public static final String K_AR_ON = "ar_on", K_START_X = "ar_start_x", K_START_Y = "ar_start_y",
            K_RATE_X = "ar_rate_x", K_RATE_Y = "ar_rate_y", K_DUR = "ar_dur", K_DELAY = "ar_delay",
            K_BTN_X = "ar_btn_x", K_BTN_Y = "ar_btn_y";

    public static final boolean D_AR_ON = true;
    public static final int D_START_X = 70, D_START_Y = 40;   // percent of the screen
    public static final int D_RATE_X = 0, D_RATE_Y = 300;     // px per second, positive Y = down. UNCALIBRATED
    public static final int D_DUR = 1500, D_DELAY = 0;        // ms. UNCALIBRATED

    /** True while the system has this service switched on. Read by the settings screen. */
    public static volatile boolean connected = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private SharedPreferences prefs;
    private TextView button;
    private WindowManager.LayoutParams lp;
    private boolean running;

    private float downRawX, downRawY;
    private int downX, downY;
    private boolean dragging;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (p, key) -> { if (K_AR_ON.equals(key)) applyVisibility(); };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        prefs = getSharedPreferences(Config.PREFS, MODE_PRIVATE);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        addButton();
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        shutdown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        connected = false;
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        if (button != null && wm != null) {
            try { wm.removeView(button); } catch (RuntimeException ignored) { }
        }
        button = null;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void addButton() {
        int size = dp(56);
        button = new TextView(this);
        button.setText("AR");
        button.setTextSize(16);
        button.setTextColor(0xFFFFFFFF);
        button.setGravity(Gravity.CENTER);
        paintButton(false);

        lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = prefs.getInt(K_BTN_X, dp(24));
        lp.y = prefs.getInt(K_BTN_Y, dp(120));

        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        button.setOnTouchListener((View v, MotionEvent ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = ev.getRawX();
                    downRawY = ev.getRawY();
                    downX = lp.x;
                    downY = lp.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - downRawX;
                    float dy = ev.getRawY() - downRawY;
                    if (!dragging && Math.hypot(dx, dy) > slop) dragging = true;
                    if (dragging) {
                        lp.x = downX + Math.round(dx);
                        lp.y = downY + Math.round(dy);
                        wm.updateViewLayout(button, lp);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        prefs.edit().putInt(K_BTN_X, lp.x).putInt(K_BTN_Y, lp.y).apply();
                    } else {
                        trigger();
                    }
                    return true;
                default:
                    return true;
            }
        });

        wm.addView(button, lp);
        applyVisibility();
    }

    private void applyVisibility() {
        if (button == null || prefs == null) return;
        button.setVisibility(prefs.getBoolean(K_AR_ON, D_AR_ON) ? View.VISIBLE : View.GONE);
    }

    private void paintButton(boolean busy) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(busy ? 0xCCB00020 : 0xAA202020);
        bg.setStroke(dp(2), 0xFFFFFFFF);
        button.setBackground(bg);
    }

    private void setRunning(boolean value) {
        running = value;
        if (button != null) paintButton(value);
    }

    @SuppressWarnings("deprecation")
    private int[] screenSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            return new int[] {b.width(), b.height()};
        }
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        return new int[] {m.widthPixels, m.heightPixels};
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    /** Performs one swipe. A swipe already in progress cannot be cancelled, so a second tap is ignored. */
    private void trigger() {
        if (running) return;
        int[] size = screenSize();
        RecoilPlan plan = RecoilPlan.build(size[0], size[1],
                prefs.getInt(K_START_X, D_START_X), prefs.getInt(K_START_Y, D_START_Y),
                prefs.getInt(K_RATE_X, D_RATE_X), prefs.getInt(K_RATE_Y, D_RATE_Y),
                prefs.getInt(K_DUR, D_DUR), prefs.getInt(K_DELAY, D_DELAY));
        if (plan == null) {
            toast("Нечего выполнять: проверь скорость, длительность и точку старта");
            return;
        }
        try {
            Path path = new Path();
            path.moveTo(plan.x0, plan.y0);
            path.lineTo(plan.x1, plan.y1);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, plan.delayMs, plan.durationMs))
                    .build();
            boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) { setRunning(false); }
                @Override public void onCancelled(GestureDescription g) { setRunning(false); }
            }, main);
            if (accepted) {
                setRunning(true);
            } else {
                toast("Система не приняла жест");
            }
        } catch (IllegalArgumentException e) {
            toast("Жест отклонён: " + e.getMessage());
        }
    }
}
