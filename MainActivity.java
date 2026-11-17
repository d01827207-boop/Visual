package com.aero.visual;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView;

/** Settings screen and the start/stop buttons. Everything is built in code to keep the project free of resources. */
public final class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 100;

    private SharedPreferences prefs;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(Config.PREFS, MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        status = new TextView(this);
        status.setTextSize(16);
        root.addView(status);

        addButton(root, "1. Разрешить «поверх других окон»", v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        addButton(root, "2a. Запустить: прицел и круг", v -> {
            if (!overlayAllowed()) return;
            startOverlay(false, 0, null);
        });
        addButton(root, "2b. Запустить: прицел, круг и поиск врагов", v -> {
            if (!overlayAllowed()) return;
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        });
        addButton(root, "Остановить", v -> startService(
                new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_STOP)));

        addTitle(root, "Прицел");
        addSwitch(root, "Показывать прицел", Config.K_CH_ON, Config.D_CH_ON);
        addShapeSpinner(root);
        addSeek(root, "Размер (dp)", Config.K_CH_SIZE, 2, 100, Config.D_CH_SIZE);
        addSeek(root, "Толщина (dp)", Config.K_CH_THICK, 1, 12, Config.D_CH_THICK);
        addSeek(root, "Смещение по X (dp)", Config.K_OFF_X, -300, 300, Config.D_OFF_X);
        addSeek(root, "Смещение по Y (dp)", Config.K_OFF_Y, -300, 300, Config.D_OFF_Y);
        addColor(root, "Цвет прицела", Config.K_CH_COLOR, Config.D_CH_COLOR);

        addTitle(root, "Круг FOV");
        addSwitch(root, "Показывать круг", Config.K_FOV_ON, Config.D_FOV_ON);
        addSeek(root, "Радиус (dp)", Config.K_FOV_R, 20, 500, Config.D_FOV_R);
        addSeek(root, "Толщина (dp)", Config.K_FOV_THICK, 1, 12, Config.D_FOV_THICK);
        addColor(root, "Цвет круга", Config.K_FOV_COLOR, Config.D_FOV_COLOR);

        addTitle(root, "Поиск врагов по цвету");
        TextView note = new TextView(this);
        note.setText("Цвет врага по умолчанию НЕ откалиброван. Запусти режим 2b, наведи прицел на цветную часть "
                + "врага и нажми в шторке уведомлений «Взять цвет под прицелом». Потом при необходимости "
                + "подстрой ползунки.");
        root.addView(note);
        addSwitch(root, "Искать и обводить врагов", Config.K_DET_ON, Config.D_DET_ON);
        addSwitch(root, "Показывать только рамки внутри круга FOV", Config.K_INSIDE_FOV, Config.D_INSIDE_FOV);
        addSeek(root, "Оттенок от (°)", Config.K_HUE_MIN, 0, 359, Config.D_HUE_MIN);
        addSeek(root, "Оттенок до (°) (если «до» меньше «от», диапазон идёт через 0)", Config.K_HUE_MAX, 0, 359, Config.D_HUE_MAX);
        addSeek(root, "Мин. насыщенность (%)", Config.K_SAT_MIN, 0, 100, Config.D_SAT_MIN);
        addSeek(root, "Мин. яркость (%)", Config.K_VAL_MIN, 0, 100, Config.D_VAL_MIN);
        addSeek(root, "Мин. размер пятна (пикс. уменьшенного кадра)", Config.K_MIN_AREA, 1, 400, Config.D_MIN_AREA);
        addSeek(root, "Макс. рамок", Config.K_MAX_BOXES, 1, 16, Config.D_MAX_BOXES);
        addSeek(root, "Интервал анализа (мс)", Config.K_INTERVAL, 20, 300, Config.D_INTERVAL);
        addSeek(root, "Толщина рамки (dp)", Config.K_BOX_THICK, 1, 8, Config.D_BOX_THICK);
    }

    @Override
    protected void onResume() {
        super.onResume();
        status.setText(OverlayService.running ? "Состояние: работает" : "Состояние: остановлено");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode == RESULT_OK && data != null) {
            startOverlay(true, resultCode, data);
        } else {
            Toast.makeText(this, "Доступ к записи экрана не выдан", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean overlayAllowed() {
        if (Settings.canDrawOverlays(this)) return true;
        Toast.makeText(this, "Сначала нажми кнопку 1 и разреши «поверх других окон»", Toast.LENGTH_LONG).show();
        return false;
    }

    private void startOverlay(boolean capture, int code, Intent data) {
        Intent i = new Intent(this, OverlayService.class)
                .setAction(OverlayService.ACTION_START)
                .putExtra(OverlayService.EXTRA_CAPTURE, capture);
        if (capture) {
            i.putExtra(OverlayService.EXTRA_CODE, code);
            i.putExtra(OverlayService.EXTRA_DATA, data);
        }
        startForegroundService(i);
        status.setText("Состояние: запускается");
    }

    // ---- small UI helpers -------------------------------------------------------------------------------

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void addTitle(LinearLayout root, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(18);
        t.setPadding(0, dp(20), 0, dp(4));
        root.addView(t);
    }

    private void addButton(LinearLayout root, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        root.addView(b);
    }

    private void addSwitch(LinearLayout root, String label, final String key, boolean def) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setChecked(prefs.getBoolean(key, def));
        s.setOnCheckedChangeListener((CompoundButton b, boolean on) -> prefs.edit().putBoolean(key, on).apply());
        root.addView(s);
    }

    private void addSeek(LinearLayout root, final String label, final String key, int min, int max, int def) {
        final TextView tv = new TextView(this);
        SeekBar sb = new SeekBar(this);
        sb.setMin(min);
        sb.setMax(max);
        int cur = Math.max(min, Math.min(max, prefs.getInt(key, def)));
        sb.setProgress(cur);
        tv.setText(label + ": " + cur);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                tv.setText(label + ": " + p);
                if (fromUser) prefs.edit().putInt(key, p).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        root.addView(tv);
        root.addView(sb);
    }

    private void addColor(LinearLayout root, String label, final String key, int def) {
        final int color = prefs.getInt(key, def);
        final int[] rgb = {(color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF};
        final View swatch = new View(this);
        swatch.setBackgroundColor(0xFF000000 | color);
        TextView tv = new TextView(this);
        tv.setText(label + " (R, G, B)");
        root.addView(tv);
        root.addView(swatch, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));
        for (int c = 0; c < 3; c++) {
            final int channel = c;
            SeekBar sb = new SeekBar(this);
            sb.setMax(255);
            sb.setProgress(rgb[c]);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    rgb[channel] = p;
                    int v = 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
                    swatch.setBackgroundColor(v);
                    if (fromUser) prefs.edit().putInt(key, v).apply();
                }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) { }
            });
            root.addView(sb);
        }
    }

    private void addShapeSpinner(LinearLayout root) {
        TextView tv = new TextView(this);
        tv.setText("Форма прицела");
        root.addView(tv);
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[] {"Крест", "Точка", "Кольцо", "Крест и точка"}));
        sp.setSelection(prefs.getInt(Config.K_CH_SHAPE, Config.D_CH_SHAPE));
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putInt(Config.K_CH_SHAPE, pos).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        root.addView(sp);
    }
}
