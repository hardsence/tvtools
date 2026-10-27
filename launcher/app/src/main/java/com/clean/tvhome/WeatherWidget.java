package com.clean.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Glass weather card for the home screen, after skyWatch. Shows one day at a time: today
 * (current conditions, next 24 hours) or one of the next five days, picked with Left/Right while
 * the card has focus. A strip at the bottom lists all six days.
 */
final class WeatherWidget extends FrameLayout {

    private final Typeface font;
    private final WeatherIconView icon;
    private final TextView temp, city, condition, details, arrowLeft, arrowRight;
    private TextView radar;
    private final TempChartView chart;
    private final LinearLayout days;
    private Weather.Data data;
    private int day;
    /** Day strip cells, built once per forecast; switching days only repaints the two that change. */
    private LinearLayout[] dayCells = new LinearLayout[0];
    private TextView[] dayNames = new TextView[0];
    private Weather.Data cellsFor;
    private int paintedDay = -1;
    private boolean focused;

    WeatherWidget(Context c, Typeface font) {
        super(c);
        this.font = font;
        setClipChildren(false);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(8), dp(14), dp(6));
        addView(body, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        paintFrame();

        LinearLayout now = new LinearLayout(c);
        now.setGravity(Gravity.CENTER_VERTICAL);
        icon = new WeatherIconView(c);
        now.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
        temp = text(26, 250, 0xFFFFFFFF);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(6);
        tlp.rightMargin = dp(10);
        now.addView(temp, tlp);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        city = text(13, 600, 0xFFFFFFFF);
        condition = text(11, 500, 0xCCFFFFFF);
        details = text(10, 500, 0x99FFFFFF);
        col.addView(city);
        col.addView(condition);
        col.addView(details);
        now.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(now);

        chart = new TempChartView(c, font);
        body.addView(chart, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        days = new LinearLayout(c);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        dlp.topMargin = dp(2);
        body.addView(days, dlp);

        // Radar button, top right: turns solid while the card has focus (OK opens the radar)
        radar = text(12, 500, 0xFFFFFFFF);
        radar.setText(R.string.radar);
        android.graphics.drawable.Drawable ri = c.getDrawable(R.drawable.ic_radar).mutate();
        ri.setBounds(0, 0, dp(14), dp(14));
        radar.setCompoundDrawablesRelative(ri, null, null, null);
        radar.setCompoundDrawablePadding(dp(5));
        radar.setGravity(Gravity.CENTER_VERTICAL);
        radar.setPadding(dp(9), 0, dp(11), 0);
        LayoutParams rlp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26), Gravity.TOP | Gravity.END);
        rlp.setMargins(0, dp(8), dp(10), 0);
        addView(radar, rlp);
        paintRadar();

        // Left/Right hints, shown while the card has focus
        arrowLeft = text(26, 300, 0xCCFFFFFF);
        arrowLeft.setText("‹");
        arrowRight = text(26, 300, 0xCCFFFFFF);
        arrowRight.setText("›");
        addView(arrowLeft, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL));
        addView(arrowRight, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL));
        arrowLeft.setTranslationX(-dp(18));
        arrowRight.setTranslationX(dp(18));
        arrowLeft.setAlpha(0f);
        arrowRight.setAlpha(0f);
    }

    private int dp(float v) {
        return Ui.dp(getContext(), v);
    }

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(getContext());
        t.setTypeface(font);
        t.setFontVariationSettings("'wght' " + weight);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private android.graphics.drawable.Drawable glassNormal, glassFocused;

    /** Frosted-glass frames (normal / focused) supplied by the home screen. */
    void setGlass(android.graphics.drawable.Drawable normal, android.graphics.drawable.Drawable focus) {
        glassNormal = normal;
        glassFocused = focus;
        paintFrame();
    }

    /** Used as a dropdown: one fixed (frosted) background instead of the normal/focused frames. */
    void setPanelBackground(android.graphics.drawable.Drawable d) {
        glassNormal = d;
        glassFocused = d;
        paintFrame();
    }

    boolean hasData() {
        return data != null;
    }

    private void paintFrame() {
        if (glassNormal != null) {
            setBackground(focused ? glassFocused : glassNormal);
            return;
        }
        setBackground(focused ? Ui.rounded(0x26FFFFFF, dp(24), dp(2), 0xB3FFFFFF)
                : Ui.rounded(0x1AFFFFFF, dp(24), dp(1), 0x24FFFFFF));
    }

    int dayCount() {
        return data == null ? 1 : Math.min(Weather.DAYS, data.dayDate.length);
    }

    int day() {
        return day;
    }

    /** Focus from the D-pad: brighter frame, arrows, slight lift. Leaving it returns to today. */
    void setFocused(boolean f) {
        if (f == focused) return;
        focused = f;
        paintFrame();
        paintRadar();
        float a = f ? 1f : 0f;
        arrowLeft.animate().alpha(a).setDuration(160).start();
        arrowRight.animate().alpha(a).setDuration(160).start();
        animate().scaleX(f ? 1.02f : 1f).scaleY(f ? 1.02f : 1f).setDuration(180).start();
        if (!f && day != 0) showDay(0);
        else paintArrows();
    }

    void showDay(int d) {
        d = Math.max(0, Math.min(dayCount() - 1, d));
        if (d == day) return;
        day = d;
        render();
    }

    void bind(Weather.Data d) {
        data = d;
        day = Math.min(day, dayCount() - 1);
        render();
    }

    private void paintRadar() {
        if (radar == null) return;
        radar.setBackground(focused ? Ui.glassFocus(dp(15)) : Ui.glassButton(dp(15)));
        radar.setTextColor(focused ? Ui.FOCUS_CONTENT : 0xFFFFFFFF);
        radar.getCompoundDrawablesRelative()[0].setTint(focused ? Ui.FOCUS_CONTENT : 0xFFFFFFFF);
    }

    private void paintArrows() {
        arrowLeft.setTextColor(day > 0 ? 0xCCFFFFFF : 0x33FFFFFF);
        arrowRight.setTextColor(day < dayCount() - 1 ? 0xCCFFFFFF : 0x33FFFFFF);
    }

    private String dayName(int i, boolean full) {
        Context c = getContext();
        if (i == 0) return c.getString(R.string.wx_today);
        if (i == 1 && full) return c.getString(R.string.wx_tomorrow);
        Locale loc = c.getResources().getConfiguration().getLocales().get(0);
        try {
            Date date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(data.dayDate[i]);
            String n = new SimpleDateFormat(full ? "EEEE, d MMMM" : "EEE", loc).format(date);
            return n.substring(0, 1).toUpperCase(loc) + n.substring(1);
        } catch (Exception e) {
            return data.dayDate[i];
        }
    }

    private void render() {
        if (data == null) return;
        Context c = getContext();
        Weather.Data d = data;
        if (day == 0) {
            icon.set(Weather.kind(d.code), d.day);
            temp.setText(d.temp + "°");
            city.setText(TextUtils.isEmpty(d.city) ? c.getString(R.string.weather) : d.city);
            condition.setText(Weather.describe(d.code));
            details.setText(c.getString(R.string.wx_details, d.humidity, d.wind, d.pressure));
        } else {
            icon.set(Weather.kind(d.dayCode[day]), true);
            temp.setText(d.dayMax[day] + "°");
            city.setText(dayName(day, true));
            String rain = d.dayPrecip[day] >= 0.1
                    ? c.getString(R.string.wx_precip, String.format(Locale.getDefault(), "%.1f", d.dayPrecip[day]), d.dayProb[day])
                    : c.getString(R.string.wx_dry);
            condition.setText(c.getString(Weather.describe(d.dayCode[day])) + " · "
                    + c.getString(R.string.wx_min, d.dayMin[day]) + " · " + rain);
            details.setText(c.getString(R.string.wx_day_details, d.dayHumidity[day], d.dayWind[day]));
        }
        Weather.Hours h = Weather.hours(d, day);
        chart.set(h);

        if (cellsFor != d || dayCells.length != dayCount()) buildDays(d);
        paintDay(paintedDay, false);
        paintDay(day, true);
        paintedDay = day;
        paintArrows();
    }

    private void paintDay(int i, boolean selected) {
        if (i < 0 || i >= dayCells.length) return;
        dayCells[i].setBackground(selected ? Ui.glass(dp(10), dp(1)) : null);
        dayNames[i].setFontVariationSettings("'wght' " + (selected ? 600 : 400));
        dayNames[i].setTextColor(selected ? 0xFFFFFFFF : 0xB3FFFFFF);
    }

    private void buildDays(Weather.Data d) {
        Context c = getContext();
        cellsFor = d;
        paintedDay = -1;
        days.removeAllViews();
        dayCells = new LinearLayout[dayCount()];
        dayNames = new TextView[dayCount()];
        for (int i = 0; i < dayCount(); i++) {
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            TextView n = text(10, 400, 0xB3FFFFFF);
            n.setText(dayName(i, false));
            dayCells[i] = cell;
            dayNames[i] = n;
            n.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.addView(n);
            WeatherIconView ic = new WeatherIconView(c);
            ic.set(Weather.kind(d.dayCode[i]), true);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(14), dp(14));
            ilp.topMargin = dp(1);
            ilp.bottomMargin = dp(1);
            cell.addView(ic, ilp);
            TextView t = text(10, 400, 0xE6FFFFFF);
            t.setText(d.dayMax[i] + "° / " + d.dayMin[i] + "°");
            t.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.addView(t);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            clp.leftMargin = dp(2);
            clp.rightMargin = dp(2);
            days.addView(cell, clp);
        }
    }
}
