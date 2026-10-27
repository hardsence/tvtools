package com.clean.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * The "Obraz" panel: a glass sheet on the right with rows changed by Left/Right (applied at
 * once), actions on OK, and sub-pages for calibration. Only options this TV actually supports
 * are listed (checked against Xiaomi's capability flags and by reading values back).
 */
final class PicturePanel {

    private static final int HEADER = 0, CHOICE = 1, SLIDER = 2, ACTION = 3, TOGGLE = 4;

    private static final class Row {
        final int kind;
        final String label;
        int[] values;
        String[] names;
        int min, max, step;
        String unit = "";
        IntSupplier get;
        IntConsumer set;
        Runnable action;
        TextView valueView;
        Controls.Toggle toggle;
        Controls.Slider slider;
        String zeroName;            // slider: text shown for its minimum (e.g. "Off")
        View view;

        Row(int kind, String label) {
            this.kind = kind;
            this.label = label;
        }
    }

    interface Host {
        void closePanel();
    }

    private final Context c;
    private final Typeface font;
    private final Host host;
    private final SharedPreferences prefs;
    final FrameLayout view;
    private final TextView title;
    private final ScrollView scroll;
    private final LinearLayout list;
    private final List<Integer> pages = new ArrayList<>();
    private final List<Integer> returnFocus = new ArrayList<>();
    private List<Row> rows = new ArrayList<>();
    private int focus;
    private int tuneColor = 1;

    private static final int PAGE_MAIN = 0, PAGE_TUNER = 1, PAGE_WB = 2;

    PicturePanel(Context c, Typeface font, int screenH, Host host) {
        this.c = c;
        this.font = font;
        this.host = host;
        prefs = ScreenEffects.prefs(c);

        view = new FrameLayout(c);
        view.setBackground(Ui.rounded(0xCC161618, dp(28), dp(1), 0x38FFFFFF));
        view.setPadding(dp(18), dp(24), dp(18), dp(18));
        view.setVisibility(View.GONE);

        title = text(26, 300, 0xFFFFFFFF);
        title.setPadding(dp(12), 0, 0, 0);
        view.addView(title, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true); // rows fade out under the title
        scroll.setFadingEdgeLength(dp(48));
        scroll.setFocusable(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                screenH - dp(24 + 18 + 50));
        slp.topMargin = dp(50);
        view.addView(scroll, slp);
    }

    private int dp(float v) {
        return Ui.dp(c, v);
    }

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(c);
        t.setTypeface(font);
        t.setFontVariationSettings("'wght' " + weight);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private String s(int id) {
        return c.getString(id);
    }

    boolean isOpen() {
        return view.getVisibility() == View.VISIBLE;
    }

    void open() {
        if (!Picture.available()) {
            Toast.makeText(c, R.string.pic_unavailable, Toast.LENGTH_LONG).show();
            host.closePanel();
            return;
        }
        pages.clear();
        returnFocus.clear();
        pages.add(PAGE_MAIN);
        show();
        view.setVisibility(View.VISIBLE);
        view.setAlpha(0f);
        view.setTranslationX(dp(40));
        view.animate().alpha(1f).translationX(0).setDuration(180).start();
    }

    void close() {
        view.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------- pages

    private void show() {
        int page = pages.get(pages.size() - 1);
        rows = page == PAGE_TUNER ? tunerRows() : page == PAGE_WB ? wbRows() : mainRows();
        title.setText(page == PAGE_TUNER ? s(R.string.pic_tuner) : page == PAGE_WB ? s(R.string.pic_wb) : s(R.string.picture));
        list.removeAllViews();
        for (Row r : rows) list.addView(build(r));
        focus = 0;
        while (focus < rows.size() && rows.get(focus).kind == HEADER) focus++;
        paint();
        scroll.scrollTo(0, 0);
    }

    private static int[] range(int from, int to, int step) {
        int[] v = new int[(to - from) / step + 1];
        for (int i = 0; i < v.length; i++) v[i] = from + i * step;
        return v;
    }

    private Row header(int label) {
        return new Row(HEADER, s(label));
    }

    private Row choice(int label, int[] values, String[] names, IntSupplier get, IntConsumer set) {
        Row r = new Row(CHOICE, s(label));
        r.values = values;
        r.names = names;
        r.get = get;
        r.set = set;
        return r;
    }

    private Row onOff(int label, IntSupplier get, IntConsumer set) {
        Row r = choice(label, new int[] {0, 1}, new String[] {s(R.string.pic_off), s(R.string.pic_on)}, get, set);
        Row t = new Row(TOGGLE, r.label);
        t.values = r.values;
        t.names = r.names;
        t.get = get;
        t.set = set;
        return t;
    }

    private Row slider(int label, int min, int max, int step, String unit, IntSupplier get, IntConsumer set) {
        Row r = new Row(SLIDER, s(label));
        r.min = min;
        r.max = max;
        r.step = step;
        r.unit = unit;
        r.get = get;
        r.set = set;
        return r;
    }

    private Row action(int label, Runnable action) {
        Row r = new Row(ACTION, s(label));
        r.action = action;
        return r;
    }

    private List<Row> mainRows() {
        List<Row> l = new ArrayList<>();
        l.add(header(R.string.pic_comfort));
        l.add(onOff(R.string.pic_dark, () -> prefs.getBoolean(ScreenEffects.DARK, false) ? 1 : 0, v -> {
            ScreenEffects fx = effects();
            if (fx != null) fx.setDark(v == 1);
        }));
        l.add(slider(R.string.pic_dark_strength, 10, 90, 10, "%",
                () -> prefs.getInt(ScreenEffects.DARK_STRENGTH, 50), v -> {
                    prefs.edit().putInt(ScreenEffects.DARK_STRENGTH, v).apply();
                    ScreenEffects fx = effects();
                    if (fx != null) fx.apply();
                }));
        int[] hours = {0, 19, 20, 21, 22, 23};
        String[] hourNames = new String[hours.length];
        hourNames[0] = s(R.string.pic_off);
        for (int i = 1; i < hours.length; i++) hourNames[i] = c.getString(R.string.pic_from_until, hours[i], ScreenEffects.AUTO_END_HOUR);
        l.add(choice(R.string.pic_dark_auto, hours, hourNames, () -> prefs.getInt(ScreenEffects.DARK_AUTO, 0), v -> {
            prefs.edit().putInt(ScreenEffects.DARK_AUTO, v).apply();
            ScreenEffects fx = effects();
            if (fx != null) fx.resetSchedule();
        }));
        Row blue = slider(R.string.pic_blue, 0, 80, 10, "%", () -> prefs.getInt(ScreenEffects.BLUE, 0), v -> {
            prefs.edit().putInt(ScreenEffects.BLUE, v).apply();
            ScreenEffects fx = effects();
            if (fx != null) fx.apply();
        });
        blue.zeroName = s(R.string.pic_off);
        l.add(blue);
        l.add(action(R.string.pic_screen_off, () -> {
            ScreenEffects fx = effects();
            if (fx == null) return;
            Toast.makeText(c, R.string.pic_screen_off_hint, Toast.LENGTH_SHORT).show();
            view.postDelayed(fx::screenOff, 1200);
        }));

        l.add(header(R.string.pic_hidden));
        l.add(onOff(R.string.pic_precision, () -> Picture.get("PreciseDetails", 0), v -> Picture.set("PreciseDetails", v)));
        l.add(onOff(R.string.pic_smart, () -> Picture.get("SmartAdaption", 0), v -> Picture.set("SmartAdaption", v)));
        l.add(choice(R.string.pic_motion, new int[] {0, 2, 3, 4, 6},
                new String[] {s(R.string.pic_off), s(R.string.pic_low), s(R.string.pic_mid), s(R.string.pic_high), s(R.string.pic_custom)},
                () -> Picture.get("MotionSmooth", 0), v -> Picture.set("MotionSmooth", v)));
        l.add(slider(R.string.pic_motion_smooth, 0, 10, 1, "", () -> Picture.motion(Picture.MOTION_SMOOTH),
                v -> Picture.setMotion(Picture.MOTION_SMOOTH, v)));
        l.add(slider(R.string.pic_motion_clear, 0, 10, 1, "", () -> Picture.motion(Picture.MOTION_CLEAR),
                v -> Picture.setMotion(Picture.MOTION_CLEAR, v)));

        l.add(header(R.string.pic_calibration));
        l.add(action(R.string.pic_tuner, () -> push(PAGE_TUNER)));
        l.add(action(R.string.pic_wb, () -> push(PAGE_WB)));
        l.add(onOff(R.string.pic_lock, () -> prefs.getBoolean(ScreenEffects.LOCK, false) ? 1 : 0, v -> {
            prefs.edit().putBoolean(ScreenEffects.LOCK, v == 1).apply();
            ScreenEffects fx = effects();
            if (v == 1 && fx != null) fx.snapshotCalibration();
        }));
        return l;
    }

    private List<Row> tunerRows() {
        List<Row> l = new ArrayList<>();
        l.add(onOff(R.string.pic_tuner_enable, () -> Picture.colorTune(Picture.TUNE_ENABLE, 0) > 0 ? 1 : 0, v -> {
            if (v == 1) Picture.set("AiPqState", 0); // Xiaomi: the tuner and AI PQ exclude each other
            Picture.setColorTune(Picture.TUNE_ENABLE, 0, v);
        }));
        String[] colors = {s(R.string.pic_red), s(R.string.pic_green), s(R.string.pic_blue_ch), s(R.string.pic_cyan),
                s(R.string.pic_magenta), s(R.string.pic_yellow), s(R.string.pic_skin)};
        l.add(choice(R.string.pic_color, range(1, 7, 1), colors, () -> tuneColor, v -> {
            tuneColor = v;
            refreshValues();
        }));
        l.add(slider(R.string.pic_hue, 0, 100, 1, "", () -> Picture.colorTune(Picture.TUNE_HUE, tuneColor),
                v -> Picture.setColorTune(Picture.TUNE_HUE, tuneColor, v)));
        l.add(slider(R.string.pic_saturation, 0, 100, 1, "", () -> Picture.colorTune(Picture.TUNE_SATURATION, tuneColor),
                v -> Picture.setColorTune(Picture.TUNE_SATURATION, tuneColor, v)));
        l.add(slider(R.string.pic_gain, 0, 100, 1, "", () -> Picture.colorTune(Picture.TUNE_GAIN, tuneColor),
                v -> Picture.setColorTune(Picture.TUNE_GAIN, tuneColor, v)));
        l.add(action(R.string.pic_reset, () -> {
            Picture.resetColorTune();
            refreshValues();
        }));
        return l;
    }

    private List<Row> wbRows() {
        List<Row> l = new ArrayList<>();
        l.add(slider(R.string.pic_point, 5, 100, 5, "%", () -> Picture.whiteBalance(Picture.WB_POINT), v -> {
            Picture.setWhiteBalance(Picture.WB_POINT, v);
            refreshValues();
        }));
        l.add(slider(R.string.pic_red, 0, 100, 1, "", () -> Picture.whiteBalance(Picture.WB_RED),
                v -> Picture.setWhiteBalance(Picture.WB_RED, v)));
        l.add(slider(R.string.pic_green, 0, 100, 1, "", () -> Picture.whiteBalance(Picture.WB_GREEN),
                v -> Picture.setWhiteBalance(Picture.WB_GREEN, v)));
        l.add(slider(R.string.pic_blue_ch, 0, 100, 1, "", () -> Picture.whiteBalance(Picture.WB_BLUE),
                v -> Picture.setWhiteBalance(Picture.WB_BLUE, v)));
        l.add(action(R.string.pic_reset, () -> {
            Picture.resetWhiteBalance();
            refreshValues();
        }));
        return l;
    }

    private ScreenEffects effects() {
        ScreenEffects fx = ScreenEffects.get();
        if (fx == null) Toast.makeText(c, R.string.pic_service_off, Toast.LENGTH_LONG).show();
        return fx;
    }

    private void push(int page) {
        returnFocus.add(focus);
        pages.add(page);
        show();
    }

    // ---------------------------------------------------------------- rows

    private View build(Row r) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = r.kind == HEADER ? text(12, 600, 0x80FFFFFF) : text(16, 400, 0xFFFFFFFF);
        label.setText(r.kind == HEADER ? r.label.toUpperCase() : r.label);
        if (r.kind == HEADER) label.setLetterSpacing(0.08f);
        top.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (r.kind == TOGGLE) {
            r.toggle = new Controls.Toggle(c);
            top.addView(r.toggle);
        } else if (r.kind == CHOICE || r.kind == SLIDER || r.kind == ACTION) {
            r.valueView = text(r.kind == SLIDER ? 14 : 16, r.kind == SLIDER ? 600 : 400, 0xCCFFFFFF);
            r.valueView.setPadding(dp(12), 0, 0, 0);
            top.addView(r.valueView);
        }
        v.addView(top);
        int h = 44;
        if (r.kind == SLIDER) {
            r.slider = new Controls.Slider(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            v.addView(r.slider, lp);
            h = 64;
        } else if (r.kind == HEADER) {
            h = 34;
            v.setPadding(dp(12), dp(12), dp(12), 0);
        }
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(h)));
        r.view = v;
        updateValue(r, false);
        return v;
    }

    private void updateValue(Row r) {
        updateValue(r, true);
    }

    private void updateValue(Row r, boolean animate) {
        if (r.kind == TOGGLE) {
            r.toggle.setChecked(r.get.getAsInt() != 0, animate);
            return;
        }
        if (r.valueView == null) return;
        if (r.kind == ACTION) {
            r.valueView.setText("›");
            return;
        }
        int v = r.get.getAsInt();
        if (r.kind == SLIDER) {
            r.valueView.setText(v == r.min && r.zeroName != null ? r.zeroName : v + r.unit);
            r.slider.set(v, r.min, r.max, animate);
            return;
        }
        String text = String.valueOf(v) + r.unit;
        for (int i = 0; i < r.values.length; i++) if (r.values[i] == v) text = r.names[i];
        r.valueView.setText("‹ " + text + " ›");
    }

    private void refreshValues() {
        for (Row r : rows) updateValue(r);
    }

    private void paint() {
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).view.setBackground(i == focus ? Ui.glass(dp(12), dp(1)) : null);
            if (rows.get(i).slider != null) rows.get(i).slider.setActive(i == focus);
        }
        View v = rows.isEmpty() ? null : rows.get(focus).view;
        if (v != null) {
            v.post(() -> {
                int top = v.getTop(), bottom = v.getBottom(), y = scroll.getScrollY(), h = scroll.getHeight();
                if (top < y + dp(40)) scroll.smoothScrollTo(0, Math.max(0, top - dp(40)));
                else if (bottom > y + h - dp(20)) scroll.smoothScrollTo(0, bottom - h + dp(20));
            });
        }
    }

    // ---------------------------------------------------------------- keys

    void move(int dx, int dy) {
        if (rows.isEmpty()) return;
        if (dy != 0) {
            int i = focus;
            do {
                i += dy;
            } while (i >= 0 && i < rows.size() && rows.get(i).kind == HEADER);
            if (i >= 0 && i < rows.size()) focus = i;
            paint();
            return;
        }
        Row r = rows.get(focus);
        if (r.kind == TOGGLE) {
            int want = dx > 0 ? 1 : 0;
            if (r.get.getAsInt() != want) r.set.accept(want);
        } else if (r.kind == CHOICE) {
            int cur = r.get.getAsInt(), idx = 0;
            for (int i = 0; i < r.values.length; i++) if (r.values[i] == cur) idx = i;
            idx = Math.max(0, Math.min(r.values.length - 1, idx + dx));
            if (r.values[idx] != cur) r.set.accept(r.values[idx]);
        } else if (r.kind == SLIDER) {
            int v = Math.max(r.min, Math.min(r.max, r.get.getAsInt() + dx * r.step));
            r.set.accept(v);
        } else if (r.kind == ACTION && dx > 0) {
            r.action.run();
            return;
        }
        updateValue(r);
    }

    void ok() {
        if (rows.isEmpty()) return;
        Row r = rows.get(focus);
        if (r.kind == ACTION) r.action.run();
        else if (r.kind == TOGGLE) {
            r.set.accept(r.get.getAsInt() != 0 ? 0 : 1);
            updateValue(r);
        }
        else if (r.kind == CHOICE && r.values.length == 2) move(r.get.getAsInt() == r.values[0] ? 1 : -1, 0);
    }

    /** Back: up one page, or false when the panel should close. */
    boolean back() {
        int page = pages.get(pages.size() - 1);
        if ((page == PAGE_TUNER || page == PAGE_WB) && prefs.getBoolean(ScreenEffects.LOCK, false)) {
            ScreenEffects fx = ScreenEffects.get();
            if (fx != null) fx.snapshotCalibration(); // keep the locked calibration up to date
        }
        if (pages.size() <= 1) return false;
        pages.remove(pages.size() - 1);
        show();
        focus = returnFocus.remove(returnFocus.size() - 1);
        paint();
        return true;
    }
}
