package com.clean.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Frosted glass without system window blur (this TV has it disabled): a {@link Backdrop} keeps a
 * small, blurred snapshot of what lies under the glass, and each {@link Pane} draws its slice of
 * it, then a light tint, a top sheen and a hairline border.
 */
final class Glass {

    static final int SCALE = 8;          // snapshot = screen / 8, blurred at that size (cheap)

    private Glass() {}

    /** A blurred, downscaled picture of the layers beneath the glass. */
    static final class Backdrop {
        Bitmap small;
        final int screenW, screenH;

        Backdrop(int w, int h) {
            screenW = w;
            screenH = h;
        }

        /** Draw the layers into the canvas handed over (already scaled by 1/SCALE), then blur. */
        void update(Painter p) {
            if (small == null) small = Bitmap.createBitmap(Math.max(1, screenW / SCALE), Math.max(1, screenH / SCALE), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(small);
            c.drawColor(0xFF000000);
            c.save();
            c.scale(1f / SCALE, 1f / SCALE);
            p.paint(c);
            c.restore();
            blur(small, 3);
        }

        interface Painter {
            void paint(Canvas c);
        }
    }

    /**
     * Glass background for a view: blurred backdrop slice (where the view sits on screen), tint,
     * sheen and border, clipped to a rounded rectangle.
     */
    static final class Pane extends Drawable {
        private final View host;
        private final Backdrop backdrop;
        private final float radius;
        private final int tint, border;
        private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final int[] loc = new int[2];

        Pane(View host, Backdrop backdrop, float radius, int tint, int border) {
            this.host = host;
            this.backdrop = backdrop;
            this.radius = radius;
            this.tint = tint;
            this.border = border;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(1f, radius / 14f));
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            RectF r = new RectF(b);
            clip.reset();
            clip.addRoundRect(r, radius, radius, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            if (backdrop != null && backdrop.small != null) {
                host.getLocationInWindow(loc);
                float sx = host.getScaleX(), sy = host.getScaleY();
                // getLocationInWindow is the scaled top-left; undo the scale around the pivot
                float x = loc[0], y = loc[1];      // transformed top-left
                float w = host.getWidth() * sx, h = host.getHeight() * sy;
                Rect src = new Rect((int) (x / SCALE), (int) (y / SCALE),
                        (int) Math.ceil((x + w) / SCALE), (int) Math.ceil((y + h) / SCALE));
                c.drawBitmap(backdrop.small, src, r, bmp);
            }
            fill.setShader(null);
            fill.setColor(tint);
            c.drawRect(r, fill);
            // soft top sheen
            fill.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * 0.6f,
                    0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(r, fill);
            fill.setShader(null);
            c.restore();
            stroke.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                    Ui.withAlpha(0xFFFFFF, Math.min(255, Math.max(0x59, (border >>> 24) * 2))),
                    Ui.withAlpha(0xFFFFFF, Math.max(0x10, (border >>> 24) / 3)), Shader.TileMode.CLAMP));
            float in = stroke.getStrokeWidth() / 2;
            c.drawRoundRect(new RectF(r.left + in, r.top + in, r.right - in, r.bottom - in), radius, radius, stroke);
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

    /**
     * Blurred picture of what {@code source} shows under the screen rectangle (x, y, w, h),
     * at 1/4 scale. Taken once, when a menu or panel opens (what lies under it doesn't move).
     */
    static Bitmap snapshot(View source, float x, float y, int w, int h) {
        int s = 4;
        Bitmap bm = Bitmap.createBitmap(Math.max(1, w / s), Math.max(1, h / s), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        c.drawColor(0xFF000000);
        c.scale(1f / s, 1f / s);
        c.translate(-x, -y);
        try {
            source.draw(c);
        } catch (RuntimeException e) {
            // e.g. a hardware bitmap somewhere: fall back to plain tint
            return null;
        }
        blur(bm, 4);
        return bm;
    }

    /** A rounded pane showing a pre-blurred snapshot, a tint, a top sheen and a hairline border. */
    static final class Frosted extends Drawable {
        private final Bitmap bm;
        private final float radius;
        private final int tint, border;
        private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();

        Frosted(Bitmap bm, float radius, int tint, int border) {
            this.bm = bm;
            this.radius = radius;
            this.tint = tint;
            this.border = border;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(1f, radius / 18f));
        }

        @Override
        public void draw(Canvas c) {
            RectF r = new RectF(getBounds());
            clip.reset();
            clip.addRoundRect(r, radius, radius, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            if (bm != null) c.drawBitmap(bm, null, r, bmp);
            fill.setShader(null);
            fill.setColor(tint);
            c.drawRect(r, fill);
            fill.setShader(new LinearGradient(0, r.top, 0, r.top + Math.min(r.height(), radius * 6),
                    0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(r, fill);
            c.restore();
            stroke.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                    Ui.withAlpha(0xFFFFFF, Math.min(255, Math.max(0x59, (border >>> 24) * 2))),
                    Ui.withAlpha(0xFFFFFF, Math.max(0x10, (border >>> 24) / 3)), Shader.TileMode.CLAMP));
            float in = stroke.getStrokeWidth() / 2;
            c.drawRoundRect(new RectF(r.left + in, r.top + in, r.right - in, r.bottom - in), radius, radius, stroke);
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

    /** Three passes of a box blur (≈ gaussian) on a small bitmap, in place. */
    static void blur(Bitmap bm, int radius) {
        int w = bm.getWidth(), h = bm.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        bm.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            box(px, tmp, w, h, radius, true);
            box(tmp, px, w, h, radius, false);
        }
        bm.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        for (int line = 0; line < lines; line++) {
            int a = 0, rr = 0, g = 0, bb = 0;
            int n = 2 * r + 1;
            for (int i = -r; i <= r; i++) {
                int p = in[idx(line, Math.max(0, Math.min(len - 1, i)), w, horizontal)];
                a += p >>> 24; rr += (p >> 16) & 255; g += (p >> 8) & 255; bb += p & 255;
            }
            for (int i = 0; i < len; i++) {
                out[idx(line, i, w, horizontal)] = ((a / n) << 24) | ((rr / n) << 16) | ((g / n) << 8) | (bb / n);
                int pOut = in[idx(line, Math.max(0, i - r), w, horizontal)];
                int pIn = in[idx(line, Math.min(len - 1, i + r + 1), w, horizontal)];
                a += (pIn >>> 24) - (pOut >>> 24);
                rr += ((pIn >> 16) & 255) - ((pOut >> 16) & 255);
                g += ((pIn >> 8) & 255) - ((pOut >> 8) & 255);
                bb += (pIn & 255) - (pOut & 255);
            }
        }
    }

    private static int idx(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }
}
