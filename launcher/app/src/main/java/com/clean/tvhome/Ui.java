package com.clean.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Shared look, after the Android XR "glasses" buttons (first row of the buttons anatomy):
 * translucent glass with a rim of light that is bright at the top and fades towards the bottom,
 * and focus simply lights the glass up (more fill, brighter rim); content stays white.
 */
final class Ui {
    private Ui() {}

    /** Icon and text colour on a focused element: focus brightens the glass, content stays white. */
    static final int FOCUS_CONTENT = 0xFFFFFFFF;
    /** Rim width in px (about 1.5 dp on this TV). */
    private static final float RIM = 3f;

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /**
     * Rounded surface. With a stroke it becomes glass: the stroke colour's alpha sets how bright
     * the rim of light is (strong at the top, a third of it at the bottom) plus a soft top sheen.
     */
    static Drawable rounded(int color, float radius, int strokeW, int strokeColor) {
        if (strokeW <= 0) return new GlassShape(color, radius, 0, 0, 0, false);
        int a = Color.alpha(strokeColor);
        int top = Math.min(255, Math.max(a * 2, 0x59)), bottom = Math.max(0x10, a / 3);
        return new GlassShape(color, radius, strokeW, withAlpha(0xFFFFFF, top), withAlpha(0xFFFFFF, bottom), true);
    }

    /** Unfocused glass button / pill (row 1, left). */
    static Drawable glassButton(float radius) {
        return new GlassShape(0x2EFFFFFF, radius, RIM, 0xB3FFFFFF, 0x26FFFFFF, true);
    }

    /** Focused button: the same glass, lit up. */
    static Drawable glassFocus(float radius) {
        return new GlassShape(0x61FFFFFF, radius, RIM, 0xFFFFFFFF, 0x73FFFFFF, true);
    }

    /** Selection on glass (tiles, list rows, chips): lit-up glass with a bright rim. */
    static Drawable glass(float radius, int strokeW) {
        return new GlassShape(0x33FFFFFF, radius, Math.max(RIM, strokeW), 0xF2FFFFFF, 0x4DFFFFFF, true);
    }

    /** The highlight while a tile is lifted for rearranging: brighter still. */
    static Drawable lifted(float radius, int strokeW) {
        return new GlassShape(0x47FFFFFF, radius, strokeW, 0xFFFFFFFF, 0xB3FFFFFF, true);
    }

    /** Focus over artwork (covers, camera views): a glass rim plus a light milky veil. */
    static Drawable focusRing(float radius, float strokeW) {
        return new GlassShape(0x26FFFFFF, radius, strokeW, 0xBFFFFFFF, 0x33FFFFFF, true);
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** Fill + optional sheen + a vertical-gradient rim. */
    static final class GlassShape extends Drawable {
        private final int fill, rimTop, rimBottom;
        private final float radius, stroke;
        private final boolean sheen;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        GlassShape(int fill, float radius, float stroke, int rimTop, int rimBottom, boolean sheen) {
            this.fill = fill;
            this.radius = radius;
            this.stroke = stroke;
            this.rimTop = rimTop;
            this.rimBottom = rimBottom;
            this.sheen = sheen;
        }

        @Override
        public void draw(Canvas c) {
            RectF r = new RectF(getBounds());
            float rad = Math.min(radius, Math.min(r.width(), r.height()) / 2);
            p.setStyle(Paint.Style.FILL);
            p.setShader(null);
            if (Color.alpha(fill) > 0) {
                p.setColor(fill);
                c.drawRoundRect(r, rad, rad, p);
            }
            if (sheen) {
                // brightens towards the top, like light falling on glass
                p.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                        0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                c.drawRoundRect(r, rad, rad, p);
            }
            if (stroke > 0) {
                float in = stroke / 2;
                RectF s = new RectF(r.left + in, r.top + in, r.right - in, r.bottom - in);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(stroke);
                p.setShader(new LinearGradient(0, r.top, 0, r.bottom, rimTop, rimBottom, Shader.TileMode.CLAMP));
                c.drawRoundRect(s, Math.max(0, rad - in), Math.max(0, rad - in), p);
            }
            p.setShader(null);
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(ColorFilter cf) {}

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
