package com.clean.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Flat white weather icons drawn with a few primitives (no image assets). */
final class WeatherIconView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Weather.Kind kind = Weather.Kind.CLEAR;
    private boolean day = true;

    WeatherIconView(Context c) {
        super(c);
        fill.setColor(0xFFFFFFFF);
        stroke.setColor(0xFFFFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        accent.setColor(0xFFFFD45C);
    }

    void set(Weather.Kind k, boolean isDay) {
        kind = k;
        day = isDay;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        float ox = (getWidth() - s) / 2f, oy = (getHeight() - s) / 2f;
        c.save();
        c.translate(ox, oy);
        stroke.setStrokeWidth(s * 0.06f);
        switch (kind) {
            case CLEAR:
                if (day) sun(c, s * 0.5f, s * 0.5f, s * 0.2f, true);
                else moon(c, s * 0.5f, s * 0.5f, s * 0.28f);
                break;
            case PARTLY:
                if (day) sun(c, s * 0.36f, s * 0.36f, s * 0.15f, true);
                else moon(c, s * 0.38f, s * 0.36f, s * 0.2f);
                cloud(c, s * 0.08f, s * 0.36f, s * 0.84f, fill);
                break;
            case CLOUDY:
                cloud(c, s * 0.04f, s * 0.22f, s * 0.92f, fill);
                break;
            case FOG:
                cloud(c, s * 0.08f, s * 0.1f, s * 0.84f, fill);
                for (int i = 0; i < 3; i++) {
                    float y = s * (0.72f + i * 0.1f);
                    c.drawLine(s * (0.18f + i * 0.04f), y, s * (0.82f - i * 0.04f), y, stroke);
                }
                break;
            case DRIZZLE:
            case RAIN:
                cloud(c, s * 0.06f, s * 0.08f, s * 0.88f, fill);
                int drops = kind == Weather.Kind.RAIN ? 3 : 2;
                for (int i = 0; i < drops; i++) {
                    float x = s * (drops == 3 ? 0.3f + i * 0.2f : 0.38f + i * 0.24f);
                    c.drawLine(x, s * 0.72f, x - s * 0.06f, s * 0.9f, stroke);
                }
                break;
            case SNOW:
                cloud(c, s * 0.06f, s * 0.08f, s * 0.88f, fill);
                for (int i = 0; i < 3; i++) c.drawCircle(s * (0.3f + i * 0.2f), s * (0.8f + (i % 2) * 0.08f), s * 0.05f, fill);
                break;
            case STORM:
                cloud(c, s * 0.06f, s * 0.06f, s * 0.88f, fill);
                Path bolt = new Path();
                bolt.moveTo(s * 0.52f, s * 0.6f);
                bolt.lineTo(s * 0.38f, s * 0.8f);
                bolt.lineTo(s * 0.5f, s * 0.8f);
                bolt.lineTo(s * 0.42f, s * 0.98f);
                bolt.lineTo(s * 0.64f, s * 0.72f);
                bolt.lineTo(s * 0.52f, s * 0.72f);
                bolt.lineTo(s * 0.6f, s * 0.6f);
                bolt.close();
                c.drawPath(bolt, accent);
                break;
        }
        c.restore();
    }

    private void sun(Canvas c, float cx, float cy, float r, boolean rays) {
        c.drawCircle(cx, cy, r, accent);
        if (!rays) return;
        Paint ray = new Paint(stroke);
        ray.setColor(accent.getColor());
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float x1 = cx + (float) Math.cos(a) * r * 1.45f, y1 = cy + (float) Math.sin(a) * r * 1.45f;
            float x2 = cx + (float) Math.cos(a) * r * 1.9f, y2 = cy + (float) Math.sin(a) * r * 1.9f;
            c.drawLine(x1, y1, x2, y2, ray);
        }
    }

    private void moon(Canvas c, float cx, float cy, float r) {
        Path p = new Path();
        p.addCircle(cx, cy, r, Path.Direction.CW);
        Path cut = new Path();
        cut.addCircle(cx + r * 0.55f, cy - r * 0.35f, r * 0.85f, Path.Direction.CW);
        p.op(cut, Path.Op.DIFFERENCE);
        c.drawPath(p, fill);
    }

    /** A cloud filling the box (x, y, width w, height ~0.55w). */
    private void cloud(Canvas c, float x, float y, float w, Paint p) {
        float h = w * 0.55f;
        c.drawRoundRect(new RectF(x, y + h * 0.45f, x + w, y + h), h * 0.28f, h * 0.28f, p);
        c.drawCircle(x + w * 0.32f, y + h * 0.5f, h * 0.3f, p);
        c.drawCircle(x + w * 0.58f, y + h * 0.38f, h * 0.38f, p);
    }
}
