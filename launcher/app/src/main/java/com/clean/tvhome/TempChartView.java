package com.clean.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.util.Locale;

/**
 * 24-hour temperature curve with a soft fill. Hours with precipitation get a mark under the
 * curve (blue drop = rain, white flake = snow, yellow = storm). Every 3 hours a column below
 * shows the time, the sky (icon, moon at night), the wind (arrow pointing where it blows to,
 * speed in km/h) and the precipitation of those 3 hours in mm (= litres per m²).
 */
final class TempChartView extends View {

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint area = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final WeatherIconView icon;
    private Weather.Hours h;

    TempChartView(Context c, Typeface font) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Ui.dp(c, 2.5f));
        line.setColor(0xFF7DB3FF);
        line.setStrokeCap(Paint.Cap.ROUND);
        dot.setColor(0xFF7DB3FF);
        label.setColor(0xB3FFFFFF);
        label.setTypeface(font);
        label.setTextSize(Ui.dp(c, 10));
        label.setTextAlign(Paint.Align.CENTER);
        value.setColor(0xE6FFFFFF);
        value.setTypeface(font);
        value.setTextSize(Ui.dp(c, 11));
        value.setTextAlign(Paint.Align.CENTER);
        small.setColor(0xCCFFFFFF);
        small.setTypeface(font);
        small.setTextSize(Ui.dp(c, 10));
        small.setTextAlign(Paint.Align.CENTER);
        icon = new WeatherIconView(c);
    }

    void set(Weather.Hours hours) {
        h = hours;
        invalidate();
    }

    private float dp(float v) {
        return Ui.dp(getContext(), v);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (h == null || h.temps.length < 2) return;
        int n = h.temps.length;
        float w = getWidth(), ht = getHeight();
        // rows from the bottom: precip, wind, icon, time
        float precipY = ht - dp(1), windY = precipY - dp(12), iconBottom = windY - dp(10);
        float iconSize = dp(16), iconTop = iconBottom - iconSize, timeY = iconTop - dp(3);
        float padX = dp(20), top = dp(18), bottom = timeY - dp(18);

        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int t : h.temps) {
            min = Math.min(min, t);
            max = Math.max(max, t);
        }
        float span = Math.max(4, max - min);
        float[] x = new float[n], y = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = padX + (w - 2 * padX) * i / (n - 1);
            y[i] = bottom - (h.temps[i] - min) / span * (bottom - top);
        }
        Path p = new Path();
        p.moveTo(x[0], y[0]);
        for (int i = 1; i < n; i++) {
            float mx = (x[i - 1] + x[i]) / 2;
            p.cubicTo(mx, y[i - 1], mx, y[i], x[i], y[i]);
        }
        Path fill = new Path(p);
        fill.lineTo(x[n - 1], bottom);
        fill.lineTo(x[0], bottom);
        fill.close();
        area.setShader(new LinearGradient(0, top, 0, bottom, 0x557DB3FF, 0x007DB3FF, Shader.TileMode.CLAMP));
        c.drawPath(fill, area);
        c.drawPath(p, line);

        // Precipitation marks along the baseline, one per wet hour.
        float markY = bottom + dp(7), r = dp(2.8f);
        for (int i = 0; i < n && i < h.wet.length; i++) {
            if (h.wet[i] == Weather.WET_NONE) continue;
            if (h.wet[i] == Weather.WET_SNOW) snowflake(c, x[i], markY, r);
            else drop(c, x[i], markY, r, h.wet[i] == Weather.WET_STORM ? 0xFFFFD45C : 0xFF5AA9FF);
        }

        // 3-hour columns
        for (int i = 0; i < n; i += 3) {
            c.drawCircle(x[i], y[i], dp(3), dot);
            c.drawText(h.temps[i] + "°", x[i], y[i] - dp(8), value);
            c.drawText(String.format(Locale.US, "%02d:00", h.hours[i]), x[i], timeY, label);

            icon.set(Weather.kind(h.codes[i]), h.day[i] == 1);
            int s = (int) iconSize;
            icon.layout(0, 0, s, s);
            c.save();
            c.translate(x[i] - s / 2f, iconTop);
            icon.draw(c);
            c.restore();

            // wind: arrow points where the wind blows to (meteo direction is where it comes from)
            String speed = String.valueOf(h.wind[i]);
            float tw = small.measureText(speed), aw = dp(9), gap = dp(3);
            float startX = x[i] - (aw + gap + tw) / 2;
            arrow(c, startX + aw / 2, windY - dp(3.5f), aw, h.windDir[i] + 180);
            small.setTextAlign(Paint.Align.LEFT);
            c.drawText(speed, startX + aw + gap, windY, small);
            small.setTextAlign(Paint.Align.CENTER);

            double mm = 0;
            for (int k = i; k < Math.min(n, i + 3); k++) mm += h.precip[k];
            small.setColor(mm >= 0.1 ? 0xFF7DB3FF : 0x66FFFFFF);
            c.drawText(mm >= 0.1 ? String.format(Locale.getDefault(), "%.1f mm", mm) : "0 mm", x[i], precipY, small);
            small.setColor(0xCCFFFFFF);
        }
    }

    private void arrow(Canvas c, float cx, float cy, float size, float degrees) {
        c.save();
        c.rotate(degrees, cx, cy);
        Path a = new Path();
        a.moveTo(cx, cy - size / 2);
        a.lineTo(cx + size * 0.38f, cy + size / 2);
        a.lineTo(cx, cy + size * 0.25f);
        a.lineTo(cx - size * 0.38f, cy + size / 2);
        a.close();
        mark.setStyle(Paint.Style.FILL);
        mark.setColor(0xE6FFFFFF);
        c.drawPath(a, mark);
        c.restore();
    }

    private void drop(Canvas c, float cx, float cy, float r, int color) {
        mark.setStyle(Paint.Style.FILL);
        mark.setColor(color);
        Path p = new Path();
        p.moveTo(cx, cy - r * 2.2f);
        p.quadTo(cx + r * 1.1f, cy - r * 0.6f, cx + r, cy);
        p.arcTo(cx - r, cy - r, cx + r, cy + r, 0, 180, false);
        p.quadTo(cx - r * 1.1f, cy - r * 0.6f, cx, cy - r * 2.2f);
        p.close();
        c.drawPath(p, mark);
    }

    private void snowflake(Canvas c, float cx, float cy, float r) {
        mark.setStyle(Paint.Style.STROKE);
        mark.setStrokeWidth(r * 0.45f);
        mark.setStrokeCap(Paint.Cap.ROUND);
        mark.setColor(0xFFFFFFFF);
        for (int i = 0; i < 3; i++) {
            double a = Math.PI / 3 * i;
            float dx = (float) Math.cos(a) * r * 1.3f, dy = (float) Math.sin(a) * r * 1.3f;
            c.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, mark);
        }
    }
}
