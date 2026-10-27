package com.clean.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Four digit boxes for a code, driven by the remote: Up/Down change the digit in the selected
 * box, Left/Right move, number keys type and advance. Entered digits show as dots except in the
 * box being edited, so the code isn't readable over someone's shoulder.
 */
final class CodeBoxes extends LinearLayout {

    static final int LENGTH = 4;

    private final TextView[] boxes = new TextView[LENGTH];
    private final int[] digits = new int[LENGTH];
    private int cursor;
    private boolean active;
    private boolean prefilled;

    CodeBoxes(Context c, Typeface font) {
        super(c);
        setGravity(Gravity.CENTER);
        for (int i = 0; i < LENGTH; i++) {
            TextView t = new TextView(c);
            t.setTypeface(font);
            t.setFontVariationSettings("'wght' 500");
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
            t.setTextColor(0xFFFFFFFF);
            t.setGravity(Gravity.CENTER);
            LayoutParams lp = new LayoutParams(Ui.dp(c, 58), Ui.dp(c, 70));
            if (i > 0) lp.leftMargin = Ui.dp(c, 14);
            addView(t, lp);
            boxes[i] = t;
        }
        clear();
    }

    void clear() {
        for (int i = 0; i < LENGTH; i++) digits[i] = -1;
        cursor = 0;
        prefilled = false;
        paint();
    }

    /** Shows a saved code as dots; typing into any box replaces that digit. */
    void prefill(String code) {
        clear();
        if (code == null || code.length() != LENGTH) return;
        for (int i = 0; i < LENGTH; i++) digits[i] = code.charAt(i) - '0';
        prefilled = true;
        paint();
    }

    boolean complete() {
        for (int d : digits) if (d < 0) return false;
        return true;
    }

    String value() {
        StringBuilder sb = new StringBuilder();
        for (int d : digits) sb.append(d < 0 ? "" : String.valueOf(d));
        return sb.toString();
    }

    void setActive(boolean a) {
        active = a;
        paint();
    }

    int cursor() {
        return cursor;
    }

    void setCursor(int i) {
        cursor = Math.max(0, Math.min(LENGTH - 1, i));
        paint();
    }

    /** Up/Down on the selected box. */
    void change(int delta) {
        prefilled = false;
        int d = digits[cursor] < 0 ? (delta > 0 ? 0 : 9) : (digits[cursor] + delta + 10) % 10;
        digits[cursor] = d;
        paint();
    }

    /** Number key: sets the digit and moves on. Returns true when that filled the last box. */
    boolean type(int digit) {
        prefilled = false;
        digits[cursor] = digit;
        boolean last = cursor == LENGTH - 1;
        if (!last) cursor++;
        paint();
        return last && complete();
    }

    static int digitOf(KeyEvent e) {
        int k = e.getKeyCode();
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) return k - KeyEvent.KEYCODE_0;
        if (k >= KeyEvent.KEYCODE_NUMPAD_0 && k <= KeyEvent.KEYCODE_NUMPAD_9) return k - KeyEvent.KEYCODE_NUMPAD_0;
        return -1;
    }

    private void paint() {
        Context c = getContext();
        for (int i = 0; i < LENGTH; i++) {
            boolean sel = active && i == cursor;
            String s;
            if (digits[i] < 0) s = sel ? "–" : "";
            else if (sel && !prefilled) s = String.valueOf(digits[i]);
            else s = "●";
            boxes[i].setText(s);
            boxes[i].setBackground(sel ? Ui.glass(Ui.dp(c, 14), Ui.dp(c, 2)) : Ui.glassButton(Ui.dp(c, 14)));
        }
    }
}
