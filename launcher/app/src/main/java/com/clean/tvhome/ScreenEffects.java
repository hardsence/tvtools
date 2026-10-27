package com.clean.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.Calendar;

/**
 * Dark mode (backlight 0 plus a dimming layer), blue-light filter, screen off and the
 * calibration lock. Hosted by KeyService: an accessibility overlay sits above every app without
 * extra permissions. Settings live in the "settings" preferences, written by the Picture panel.
 */
final class ScreenEffects {

    static final String DARK = "dark", DARK_STRENGTH = "dark_strength", DARK_AUTO = "dark_auto",
            BLUE = "blue_filter", LOCK = "calibration_lock";
    private static final String SAVED_BACKLIGHT = "saved_backlight", AUTO_STATE = "dark_auto_state",
            LOCK_DATA = "calibration_data";
    static final int AUTO_END_HOUR = 7;

    private static ScreenEffects instance;

    private final AccessibilityService svc;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout overlay;
    private View amber, black;
    private View blackout;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            checkSchedule();
        }
    };

    private final ContentObserver pictureMode = new ContentObserver(ui) {
        @Override
        public void onChange(boolean self) {
            // Firmware reloads calibration per picture mode; put the locked one back.
            if (prefs.getBoolean(LOCK, false)) ui.postDelayed(ScreenEffects.this::reapplyCalibration, 800);
        }
    };

    ScreenEffects(AccessibilityService svc) {
        this.svc = svc;
        wm = svc.getSystemService(WindowManager.class);
        prefs = svc.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** The running instance, or null while the remote-button service is off. */
    static ScreenEffects get() {
        return instance;
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    void start() {
        instance = this;
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) svc.registerReceiver(tick, f, Context.RECEIVER_NOT_EXPORTED);
        else svc.registerReceiver(tick, f);
        svc.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor("picture_mode"), false, pictureMode);
        checkSchedule();
        apply();
        if (prefs.getBoolean(LOCK, false)) reapplyCalibration();
    }

    void stop() {
        if (instance == this) instance = null;
        try {
            svc.unregisterReceiver(tick);
        } catch (IllegalArgumentException ignored) {
        }
        svc.getContentResolver().unregisterContentObserver(pictureMode);
        removeView(overlay);
        overlay = null;
        removeView(blackout);
        blackout = null;
    }

    // ---------------------------------------------------------------- dark mode and filter

    /** Turns dark mode on or off: remembers the backlight, drops it to 0, adds the dim layer. */
    void setDark(boolean on) {
        boolean was = prefs.getBoolean(DARK, false);
        SharedPreferences.Editor e = prefs.edit().putBoolean(DARK, on);
        if (on && !was) {
            e.putInt(SAVED_BACKLIGHT, Picture.get("Backlight", 50));
            Picture.set("Backlight", 0);
        } else if (!on && was) {
            Picture.set("Backlight", prefs.getInt(SAVED_BACKLIGHT, 50));
        }
        e.apply();
        apply();
    }

    /** Redraws the overlay from the saved settings. */
    void apply() {
        ui.post(() -> {
            boolean dark = prefs.getBoolean(DARK, false);
            int dim = dark ? prefs.getInt(DARK_STRENGTH, 50) : 0;
            int blue = prefs.getInt(BLUE, 0);
            if (dim == 0 && blue == 0) {
                removeView(overlay);
                overlay = null;
                return;
            }
            if (overlay == null) {
                overlay = new FrameLayout(svc);
                amber = new View(svc);
                amber.setBackgroundColor(Color.rgb(255, 140, 30));
                black = new View(svc);
                black.setBackgroundColor(Color.BLACK);
                overlay.addView(amber);
                overlay.addView(black);
                wm.addView(overlay, layoutParams());
            }
            amber.setAlpha(blue / 100f * 0.45f);   // 80 % filter = a strong warm tint, still readable
            black.setAlpha(dim / 100f);
        });
    }

    private void checkSchedule() {
        int start = prefs.getInt(DARK_AUTO, 0);
        if (start == 0) return;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        boolean inWindow = h >= start || h < AUTO_END_HOUR;
        // Act only when the window opens or closes, so a manual change in between sticks.
        if (prefs.getBoolean(AUTO_STATE, false) != inWindow) {
            prefs.edit().putBoolean(AUTO_STATE, inWindow).apply();
            setDark(inWindow);
        }
    }

    /** Called when the schedule setting changes: the next check acts on the current window. */
    void resetSchedule() {
        prefs.edit().putBoolean(AUTO_STATE, prefs.getBoolean(DARK, false)).apply();
        checkSchedule();
    }

    // ---------------------------------------------------------------- screen off

    /** Picture off, sound on. Any remote button brings the picture back (see onKey). */
    void screenOff() {
        ui.post(() -> {
            if (blackout != null) return;
            blackout = new View(svc);
            blackout.setBackgroundColor(Color.BLACK);
            wm.addView(blackout, layoutParams());
            if (!prefs.getBoolean(DARK, false)) prefs.edit().putInt(SAVED_BACKLIGHT, Picture.get("Backlight", 50)).apply();
            Picture.set("Backlight", 0);
        });
    }

    /** Returns true when the key was used to wake the screen and must not reach the app. */
    boolean onKey(KeyEvent e) {
        if (blackout == null) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            removeView(blackout);
            blackout = null;
            if (!prefs.getBoolean(DARK, false)) Picture.set("Backlight", prefs.getInt(SAVED_BACKLIGHT, 50));
        }
        return true;
    }

    // ---------------------------------------------------------------- calibration lock

    /** Saves the current color tuner and 20-point white balance as the locked calibration. */
    void snapshotCalibration() {
        StringBuilder sb = new StringBuilder();
        sb.append(Picture.colorTune(Picture.TUNE_ENABLE, 0));
        for (int c = 1; c <= 7; c++) {
            for (int t = Picture.TUNE_HUE; t <= Picture.TUNE_GAIN; t++) sb.append(',').append(Picture.colorTune(t, c));
        }
        int point = Picture.whiteBalance(Picture.WB_POINT);
        for (int p = 5; p <= 100; p += 5) {
            Picture.setWhiteBalance(Picture.WB_POINT, p);
            for (int ch = Picture.WB_RED; ch <= Picture.WB_BLUE; ch++) sb.append(',').append(Picture.whiteBalance(ch));
        }
        Picture.setWhiteBalance(Picture.WB_POINT, point);
        prefs.edit().putString(LOCK_DATA, sb.toString()).apply();
    }

    void reapplyCalibration() {
        String data = prefs.getString(LOCK_DATA, null);
        if (data == null) return;
        String[] v = data.split(",");
        if (v.length != 1 + 7 * 3 + 20 * 3) return;
        int i = 0;
        Picture.setColorTune(Picture.TUNE_ENABLE, 0, Integer.parseInt(v[i++]));
        for (int c = 1; c <= 7; c++) {
            for (int t = Picture.TUNE_HUE; t <= Picture.TUNE_GAIN; t++) Picture.setColorTune(t, c, Integer.parseInt(v[i++]));
        }
        int point = Picture.whiteBalance(Picture.WB_POINT);
        for (int p = 5; p <= 100; p += 5) {
            Picture.setWhiteBalance(Picture.WB_POINT, p);
            for (int ch = Picture.WB_RED; ch <= Picture.WB_BLUE; ch++) Picture.setWhiteBalance(ch, Integer.parseInt(v[i++]));
        }
        Picture.setWhiteBalance(Picture.WB_POINT, point);
    }

    // ---------------------------------------------------------------- windows

    private static WindowManager.LayoutParams layoutParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("CleanHomeScreenEffects");
        return lp;
    }

    private void removeView(View v) {
        if (v == null) return;
        try {
            wm.removeView(v);
        } catch (IllegalArgumentException ignored) {
        }
    }
}
