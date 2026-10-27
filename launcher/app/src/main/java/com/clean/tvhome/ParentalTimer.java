package com.clean.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * TV usage timer. While it runs, a small countdown sits in the top-left corner over every app.
 * When it runs out the TV goes to the home screen and a full-screen lock asks for the 4-digit
 * code; every button but power is swallowed until the right code is entered. The end time is a
 * wall-clock time and the locked state is saved, so switching the TV off neither pauses nor
 * escapes it: an expired timer locks again straight after boot. Hosted by KeyService, which sees
 * keys before any app and can draw over everything.
 */
final class ParentalTimer {

    static final String END = "timer_end", CODE = "timer_code", LOCKED = "timer_locked";

    private static ParentalTimer instance;

    private final AccessibilityService svc;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Typeface font;
    private TextView countdown;
    private FrameLayout lock;
    private CodeBoxes boxes;
    private TextView lockHint;
    private AudioFocusRequest focus;

    private final Runnable tick = this::update;

    ParentalTimer(AccessibilityService svc) {
        this.svc = svc;
        wm = svc.getSystemService(WindowManager.class);
        prefs = ScreenEffects.prefs(svc);
        font = svc.getResources().getFont(R.font.outfit);
    }

    static ParentalTimer get() {
        return instance;
    }

    // ---------------------------------------------------------------- state (also used by TimerActivity)

    static String code(Context c) {
        return ScreenEffects.prefs(c).getString(CODE, null);
    }

    static long end(Context c) {
        return ScreenEffects.prefs(c).getLong(END, 0);
    }

    static boolean locked(Context c) {
        return ScreenEffects.prefs(c).getBoolean(LOCKED, false);
    }

    /** Starts (minutes > 0) or cancels (0) the timer and remembers the code. */
    static void set(Context c, int minutes, String code) {
        long end = minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : 0;
        ScreenEffects.prefs(c).edit().putLong(END, end).putString(CODE, code).putBoolean(LOCKED, false).commit();
        ParentalTimer t = instance;
        if (t != null) t.ui.post(t::update);
    }

    void start() {
        instance = this;
        update();
    }

    void stop() {
        if (instance == this) instance = null;
        ui.removeCallbacksAndMessages(null);
        remove(countdown);
        countdown = null;
        remove(lock);
        lock = null;
    }

    /** Re-reads the saved state: countdown, lock, or nothing. Runs every second while counting. */
    private void update() {
        ui.removeCallbacks(tick);
        if (prefs.getBoolean(LOCKED, false)) {
            hideCountdown();
            showLock();
            return;
        }
        hideLock();
        long end = prefs.getLong(END, 0);
        if (end <= 0) {
            hideCountdown();
            return;
        }
        long left = end - System.currentTimeMillis();
        if (left <= 0) {
            prefs.edit().putBoolean(LOCKED, true).commit();
            hideCountdown();
            lockNow();
            return;
        }
        showCountdown(left);
        ui.postDelayed(tick, 1000 - (System.currentTimeMillis() % 1000) + 5);
    }

    // ---------------------------------------------------------------- countdown pill

    private int dp(float v) {
        return Ui.dp(svc, v);
    }

    private void showCountdown(long left) {
        if (countdown == null) {
            countdown = new TextView(svc);
            countdown.setTypeface(font);
            countdown.setFontVariationSettings("'wght' 600");
            countdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            countdown.setTextColor(0xFFFFFFFF);
            countdown.setGravity(Gravity.CENTER_VERTICAL);
            countdown.setPadding(dp(14), dp(7), dp(16), dp(7));
            countdown.setBackground(Ui.rounded(0xE6161618, dp(18), dp(1), 0x33FFFFFF));
            Drawable ic = svc.getDrawable(R.drawable.ic_timer).mutate();
            ic.setBounds(0, 0, dp(18), dp(18));
            countdown.setCompoundDrawablesRelative(ic, null, null, null);
            countdown.setCompoundDrawablePadding(dp(8));
            WindowManager.LayoutParams lp = params(WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = dp(16);
            lp.y = dp(12);
            wm.addView(countdown, lp);
        }
        long s = (left + 999) / 1000;
        countdown.setText(String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60));
        // Last minute: amber, so it doesn't come as a surprise
        countdown.setTextColor(s <= 60 ? 0xFFFFC94D : 0xFFFFFFFF);
    }

    private void hideCountdown() {
        remove(countdown);
        countdown = null;
    }

    // ---------------------------------------------------------------- lock

    /** Time is up: stop whatever plays, go home, cover the screen. */
    private void lockNow() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            svc.startActivity(home);
        } catch (Exception ignored) {
        }
        showLock();
    }

    private void showLock() {
        if (lock != null) return;
        AudioManager am = svc.getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .build();
        am.requestAudioFocus(focus); // players pause on losing focus

        lock = new FrameLayout(svc);
        lock.setBackgroundColor(0xF20B0B0D);
        LinearLayout col = new LinearLayout(svc);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView icon = new TextView(svc);
        Drawable ic = svc.getDrawable(R.drawable.ic_timer).mutate();
        ic.setBounds(0, 0, dp(44), dp(44));
        icon.setCompoundDrawablesRelative(null, ic, null, null);
        col.addView(icon);
        TextView title = text(34, 300);
        title.setText(R.string.timer_locked_title);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = dp(14);
        col.addView(title, tlp);
        lockHint = text(16, 400);
        lockHint.setTextColor(0xB3FFFFFF);
        lockHint.setText(R.string.timer_locked_hint);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.topMargin = dp(8);
        hlp.bottomMargin = dp(26);
        col.addView(lockHint, hlp);
        boxes = new CodeBoxes(svc, font);
        boxes.setActive(true);
        col.addView(boxes);
        TextView keys = text(13, 400);
        keys.setTextColor(0x80FFFFFF);
        keys.setText(R.string.timer_keys_hint);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(-2, -2);
        klp.topMargin = dp(22);
        col.addView(keys, klp);
        lock.addView(col, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        wm.addView(lock, params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT));
    }

    private void hideLock() {
        if (lock == null) return;
        remove(lock);
        lock = null;
        boxes = null;
        if (focus != null) svc.getSystemService(AudioManager.class).abandonAudioFocusRequest(focus);
        focus = null;
    }

    private TextView text(float sp, int weight) {
        TextView t = new TextView(svc);
        t.setTypeface(font);
        t.setFontVariationSettings("'wght' " + weight);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** While locked, every key comes here first; true = swallowed. Power is never touched. */
    boolean onKey(KeyEvent e) {
        if (lock == null || boxes == null) return false;
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_POWER || k == KeyEvent.KEYCODE_SLEEP || k == KeyEvent.KEYCODE_WAKEUP) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int digit = CodeBoxes.digitOf(e);
        if (digit >= 0) {
            if (boxes.type(digit)) check();
            return true;
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                boxes.change(1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                boxes.change(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                boxes.setCursor(boxes.cursor() - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                boxes.setCursor(boxes.cursor() + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (boxes.complete()) check();
                else boxes.setCursor(boxes.cursor() + 1);
                break;
            default:
                break;                                  // Home, Back, app buttons: ignored
        }
        return true;
    }

    private void check() {
        String code = prefs.getString(CODE, null);
        if (code != null && code.equals(boxes.value())) {
            prefs.edit().putBoolean(LOCKED, false).putLong(END, 0).commit();   // unlocked, timer reset
            update();
        } else {
            boxes.clear();
            lockHint.setText(R.string.timer_wrong_code);
            lockHint.setTextColor(0xFFFF6B6B);
            lock.animate().translationX(dp(12)).setDuration(60).withEndAction(() ->
                    lock.animate().translationX(-dp(12)).setDuration(60).withEndAction(() ->
                            lock.animate().translationX(0).setDuration(60).start()).start()).start();
        }
    }

    // ---------------------------------------------------------------- windows

    private static WindowManager.LayoutParams params(int w, int h) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("CleanHomeTimer");
        return lp;
    }

    private void remove(View v) {
        if (v == null) return;
        try {
            wm.removeView(v);
        } catch (IllegalArgumentException ignored) {
        }
    }
}
