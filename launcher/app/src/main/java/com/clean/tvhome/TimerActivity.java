package com.clean.tvhome;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Settings > TV usage timer: hours : minutes, a 4-digit code and Start. Left/Right walk through
 * hours, minutes, the four code boxes and the button; Up/Down change the value; number keys type
 * digits; OK on the button applies. With no timer running the boxes show the saved code (dots)
 * and whatever is in them becomes the code. While a timer runs, the current code is required to
 * change or cancel it, so it can't simply be extended. 00:00 cancels.
 */
public class TimerActivity extends Activity {

    private static final int HOURS = 0, MINUTES = 1, BOX0 = 2, BUTTON = 2 + CodeBoxes.LENGTH;

    /** Blurred home screen, set by the home screen just before it opens this window. */
    static Glass.Backdrop backdrop;

    private Typeface font;
    private int hours, minutes, focus;
    private boolean running;
    private TextView hoursView, minutesView, button, status, codeLabel;
    private CodeBoxes boxes;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        font = getResources().getFont(R.font.outfit);
        running = ParentalTimer.end(this) > System.currentTimeMillis();
        buildUi();
        paint();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setTypeface(font);
        t.setFontVariationSettings("'wght' " + weight);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        return t;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x33000000);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(40), dp(30), dp(40), dp(28));
        card.setBackground(new Glass.Pane(card, backdrop, dp(28), 0x8C161618, 0x40FFFFFF));

        TextView title = text(26, 400, 0xFFFFFFFF);
        title.setText(R.string.timer_title);
        card.addView(title);
        status = text(14, 400, 0x99FFFFFF);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = dp(6);
        card.addView(status, slp);

        LinearLayout time = new LinearLayout(this);
        time.setGravity(Gravity.CENTER);
        hoursView = text(54, 250, 0xFFFFFFFF);
        minutesView = text(54, 250, 0xFFFFFFFF);
        TextView colon = text(54, 250, 0xFFFFFFFF);
        colon.setText(":");
        time.addView(hoursView, new LinearLayout.LayoutParams(dp(110), dp(84)));
        time.addView(colon, new LinearLayout.LayoutParams(dp(30), dp(84)));
        time.addView(minutesView, new LinearLayout.LayoutParams(dp(110), dp(84)));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = dp(22);
        card.addView(time, tlp);
        LinearLayout units = new LinearLayout(this);
        units.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView uh = text(12, 500, 0x80FFFFFF), um = text(12, 500, 0x80FFFFFF);
        uh.setText(R.string.timer_hours);
        um.setText(R.string.timer_minutes);
        units.addView(uh, new LinearLayout.LayoutParams(dp(110), -2));
        units.addView(new TextView(this), new LinearLayout.LayoutParams(dp(30), -2));
        units.addView(um, new LinearLayout.LayoutParams(dp(110), -2));
        card.addView(units);

        codeLabel = text(14, 500, 0xB3FFFFFF);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = dp(26);
        clp.bottomMargin = dp(10);
        card.addView(codeLabel, clp);
        boxes = new CodeBoxes(this, font);
        if (!running) boxes.prefill(ParentalTimer.code(this));
        card.addView(boxes);

        button = text(18, 600, 0xFFFFFFFF);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(280), dp(52));
        blp.topMargin = dp(28);
        card.addView(button, blp);
        TextView keys = text(12, 400, 0x66FFFFFF);
        keys.setText(R.string.timer_setup_keys);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(-2, -2);
        klp.topMargin = dp(14);
        card.addView(keys, klp);

        root.addView(card, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        setContentView(root);
    }

    private void paint() {
        long end = ParentalTimer.end(this);
        if (running) {
            long left = Math.max(0, end - System.currentTimeMillis()) / 60000;
            status.setText(getString(R.string.timer_running, new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(end)),
                    left / 60, left % 60));
            codeLabel.setText(R.string.timer_code_current);
        } else {
            status.setText(R.string.timer_off);
            codeLabel.setText(ParentalTimer.code(this) == null ? R.string.timer_code_new : R.string.timer_code_saved);
        }
        hoursView.setText(String.format(Locale.US, "%02d", hours));
        minutesView.setText(String.format(Locale.US, "%02d", minutes));
        field(hoursView, focus == HOURS);
        field(minutesView, focus == MINUTES);
        boxes.setActive(focus >= BOX0 && focus < BUTTON);
        if (focus >= BOX0 && focus < BUTTON) boxes.setCursor(focus - BOX0);
        boolean f = focus == BUTTON;
        button.setText(hours == 0 && minutes == 0 ? (running ? R.string.timer_cancel : R.string.timer_close) : R.string.timer_start);
        button.setBackground(f ? Ui.glassFocus(dp(26)) : Ui.glassButton(dp(26)));
        button.setTextColor(f ? Ui.FOCUS_CONTENT : 0xFFFFFFFF);
    }

    private void field(TextView v, boolean focused) {
        v.setBackground(focused ? Ui.glass(dp(16), dp(2)) : Ui.glassButton(dp(16)));
    }

    private void apply() {
        int total = hours * 60 + minutes;
        if (!boxes.complete()) {
            if (total == 0 && !running) {
                finish();                           // nothing to start, nothing running
                return;
            }
            error(R.string.timer_need_code);
            return;
        }
        String entered = boxes.value();
        if (running && !entered.equals(ParentalTimer.code(this))) {
            boxes.clear();
            focus = BOX0;
            error(R.string.timer_wrong_code);
            paint();
            return;
        }
        ParentalTimer.set(this, total, entered);
        if (ParentalTimer.get() == null) error(R.string.pic_service_off);
        else finish();
    }

    private void error(int msg) {
        codeLabel.setText(msg);
        codeLabel.setTextColor(0xFFFF6B6B);
        codeLabel.postDelayed(() -> {
            codeLabel.setTextColor(0xB3FFFFFF);
            paint();
        }, 2500);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        int digit = CodeBoxes.digitOf(e);
        boolean ours = digit >= 0 || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_CENTER
                || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (!ours) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (digit >= 0) {
            if (focus < BOX0 || focus >= BUTTON) focus = BOX0 + (focus >= BUTTON ? CodeBoxes.LENGTH - 1 : 0);
            boxes.setCursor(focus - BOX0);
            boolean done = boxes.type(digit);
            focus = done ? BUTTON : BOX0 + boxes.cursor();
            paint();
            return true;
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                focus = Math.max(0, focus - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                focus = Math.min(BUTTON, focus + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                int d = k == KeyEvent.KEYCODE_DPAD_UP ? 1 : -1;
                if (focus == HOURS) hours = (hours + d + 13) % 13;            // 0..12 h
                else if (focus == MINUTES) minutes = (minutes + d * 5 + 60) % 60; // 5-minute steps
                else if (focus < BUTTON) boxes.change(d);
                break;
            }
            default:                                   // OK
                if (focus == BUTTON) apply();
                else focus = Math.min(BUTTON, focus + 1);
                break;
        }
        paint();
        return true;
    }
}
