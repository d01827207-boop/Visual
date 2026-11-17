package com.aero.visual;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the overlay window and, optionally, the screen capture that feeds the enemy detector. */
public final class OverlayService extends Service {
    public static final String ACTION_START = "com.aero.visual.START";
    public static final String ACTION_STOP = "com.aero.visual.STOP";
    public static final String ACTION_SAMPLE = "com.aero.visual.SAMPLE";
    public static final String EXTRA_CAPTURE = "capture";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";

    /** The captured frame is analysed at 1/SCALE of the screen size. */
    private static final int SCALE = 4;
    private static final String TAG = "VisualOverlay";
    private static final String CHANNEL_ID = "visual_overlay";
    private static final int NOTIF_ID = 1;

    public static volatile boolean running;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean sampleRequested = new AtomicBoolean(false);

    private WindowManager windowManager;
    private volatile OverlayView overlay;
    private SharedPreferences prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener prefsListener;
    private volatile Config cfg;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread worker;
    private Handler workerHandler;
    private int[] pixels;
    private long lastProcess;
    private boolean down = true;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            start(intent);
        } else if (ACTION_SAMPLE.equals(action)) {
            sampleRequested.set(true);
        } else {
            shutdown();
        }
        return START_NOT_STICKY; // the capture permission token cannot be reused after a restart
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void start(Intent i) {
        if (running) return;
        down = false;
        boolean capture = i.getBooleanExtra(EXTRA_CAPTURE, false);
        if (!Settings.canDrawOverlays(this)) {
            toast("Нет разрешения «поверх других окон»");
            shutdown();
            return;
        }
        createChannel();
        try {
            Notification n = buildNotification(capture);
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, capture
                        ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        : ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "startForeground failed", e);
            toast("Не удалось запустить сервис: " + e.getMessage());
            shutdown();
            return;
        }

        prefs = getSharedPreferences(Config.PREFS, MODE_PRIVATE);
        cfg = Config.load(prefs);
        prefsListener = (sp, key) -> {
            cfg = Config.load(sp);
            OverlayView v = overlay;
            if (v != null) v.setConfig(cfg);
        };
        prefs.registerOnSharedPreferenceChangeListener(prefsListener);

        if (capture) {
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            Intent data = readData(i);
            try {
                projection = data == null ? null : mpm.getMediaProjection(i.getIntExtra(EXTRA_CODE, 0), data);
            } catch (RuntimeException e) {
                Log.w(TAG, "getMediaProjection failed", e);
                projection = null;
            }
            if (projection == null) {
                toast("Не удалось получить доступ к записи экрана");
                shutdown();
                return;
            }
            // Required before createVirtualDisplay on Android 14+.
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() { mainHandler.post(OverlayService.this::shutdown); }
            }, mainHandler);
            worker = new HandlerThread("detect");
            worker.start();
            workerHandler = new Handler(worker.getLooper());
        }

        windowManager = getSystemService(WindowManager.class);
        OverlayView view = new OverlayView(this);
        view.setConfig(cfg);
        if (capture) view.setSizeListener((w, h) -> mainHandler.post(() -> setupCapture(w, h)));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            windowManager.addView(view, lp);
        } catch (RuntimeException e) {
            Log.w(TAG, "addView failed", e);
            toast("Не удалось создать окно оверлея");
            shutdown();
            return;
        }
        overlay = view;
        running = true;
    }

    @SuppressWarnings("deprecation")
    private static Intent readData(Intent i) {
        return Build.VERSION.SDK_INT >= 33 ? i.getParcelableExtra(EXTRA_DATA, Intent.class)
                                           : i.getParcelableExtra(EXTRA_DATA);
    }

    private void setupCapture(int w, int h) {
        if (projection == null || down) return;
        int cw = Math.max(64, w / SCALE), ch = Math.max(64, h / SCALE);
        try {
            ImageReader reader = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(this::onImage, workerHandler);
            int dpi = getResources().getDisplayMetrics().densityDpi;
            if (virtualDisplay == null) {
                virtualDisplay = projection.createVirtualDisplay("visual-capture", cw, ch, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, workerHandler);
            } else {
                virtualDisplay.resize(cw, ch, dpi);
                virtualDisplay.setSurface(reader.getSurface());
            }
            ImageReader old = imageReader;
            imageReader = reader;
            if (old != null) old.close();
        } catch (RuntimeException e) {
            Log.w(TAG, "capture setup failed", e);
            toast("Не удалось начать захват экрана: " + e.getMessage());
            shutdown();
        }
    }

    /** Runs on the worker thread. */
    private void onImage(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            OverlayView view = overlay;
            if (image == null || view == null) return;
            Config c = cfg;
            boolean sample = sampleRequested.get();
            if (!c.detOn && !sample) {
                view.setBoxes(null, 1f, 1f);
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (!sample && now - lastProcess < c.interval) return;
            lastProcess = now;

            int w = image.getWidth(), h = image.getHeight();
            if (!readPixels(image, w, h)) return;

            if (sample && sampleRequested.compareAndSet(true, false)) {
                takeColorUnderCrosshair(view, c, w, h);
                c = cfg;
            }
            if (c.detOn) {
                List<ColorDetector.Box> boxes = ColorDetector.detect(pixels, w, h, c.detectorParams());
                view.setBoxes(boxes, view.getWidth() / (float) w, view.getHeight() / (float) h);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "frame failed", e);
        } finally {
            if (image != null) image.close();
        }
    }

    /** Converts the RGBA plane into ARGB ints. A short or padded buffer is a failed read, not a crash. */
    private boolean readPixels(Image image, int w, int h) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride(), pixelStride = plane.getPixelStride();
        if (w <= 0 || h <= 0 || pixelStride < 4) return false;
        if (pixels == null || pixels.length != w * h) pixels = new int[w * h];
        int limit = buf.limit();
        for (int y = 0; y < h; y++) {
            int row = y * rowStride;
            for (int x = 0; x < w; x++) {
                int idx = row + x * pixelStride;
                if (idx + 3 >= limit) return false;
                pixels[y * w + x] = 0xFF000000
                        | ((buf.get(idx) & 0xFF) << 16)
                        | ((buf.get(idx + 1) & 0xFF) << 8)
                        | (buf.get(idx + 2) & 0xFF);
            }
        }
        return true;
    }

    private void takeColorUnderCrosshair(OverlayView view, Config c, int w, int h) {
        float density = getResources().getDisplayMetrics().density;
        float sx = view.getWidth() / (float) w, sy = view.getHeight() / (float) h;
        int px = Math.round((view.getWidth() / 2f + c.offX * density) / sx);
        int py = Math.round((view.getHeight() / 2f + c.offY * density) / sy);
        float[] hsv = ColorDetector.averageHsv(pixels, w, h, px, py, 3);
        if (hsv == null || hsv[0] < 0f || hsv[1] < 0.15f) {
            toast("Под прицелом нет яркого цвета: наведи прицел на врага");
            return;
        }
        int hue = Math.round(hsv[0]) % 360;
        prefs.edit()
                .putInt(Config.K_HUE_MIN, ((hue - 12) % 360 + 360) % 360)
                .putInt(Config.K_HUE_MAX, (hue + 12) % 360)
                .putInt(Config.K_SAT_MIN, Math.max(25, Math.round(hsv[1] * 60f)))
                .putInt(Config.K_VAL_MIN, Math.max(20, Math.round(hsv[2] * 60f)))
                .apply();
        toast("Цвет взят: оттенок " + hue + "°");
    }

    private void shutdown() {
        if (down) return;
        down = true;
        running = false;
        if (prefs != null && prefsListener != null) prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
        prefsListener = null;
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (RuntimeException ignored) { }
        virtualDisplay = null;
        try { if (imageReader != null) imageReader.close(); } catch (RuntimeException ignored) { }
        imageReader = null;
        try { if (projection != null) projection.stop(); } catch (RuntimeException ignored) { }
        projection = null;
        if (worker != null) worker.quitSafely();
        worker = null;
        OverlayView v = overlay;
        overlay = null;
        if (v != null && windowManager != null) {
            try { windowManager.removeViewImmediate(v); } catch (RuntimeException ignored) { }
        }
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void toast(String text) {
        mainHandler.post(() -> Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show());
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Visual overlay", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification(boolean capture) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Visual overlay работает")
                .setContentText(capture ? "Прицел, круг и поиск врагов" : "Прицел и круг")
                .setOngoing(true)
                .addAction(action("Стоп", ACTION_STOP, 1));
        if (capture) b.addAction(action("Взять цвет под прицелом", ACTION_SAMPLE, 2));
        return b.build();
    }

    private Notification.Action action(String title, String act, int code) {
        PendingIntent pi = PendingIntent.getService(this, code,
                new Intent(this, OverlayService.class).setAction(act),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), title, pi).build();
    }
}
