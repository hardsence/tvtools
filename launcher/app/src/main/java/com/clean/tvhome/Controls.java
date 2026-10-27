package com.clean.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Small custom controls for TV menus (drawn, no focus handling of their own). */
final class Controls {

    private Controls() {}

    /** iOS-style switch: green track and the knob slides right when on. */
    static final class Toggle extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float pos;          // 0 = off, 1 = on
        private ValueAnimator anim;

        Toggle(Context c) {
            super(c);
        }

        void setChecked(boolean on, boolean animate) {
            float target = on ? 1f : 0f;
            if (anim != null) anim.cancel();
            if (!animate || !isAttachedToWindow()) {
                pos = target;
                invalidate();
                return;
            }
            anim = ValueAnimator.ofFloat(pos, target);
            anim.setDuration(180);
            anim.addUpdateListener(a -> {
                pos = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(Ui.dp(getContext(), 44), Ui.dp(getContext(), 26));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            int off = 0x8C78787E, on = 0xFF34C759;   // grey track: visible on white and dark rows
            p.setColor(blend(off, on, pos));
            c.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
            float inset = Ui.dp(getContext(), 2.5f), kr = r - inset;
            float cx = r + (w - 2 * r) * pos;
            p.setColor(0x33000000);
            c.drawCircle(cx, r + Ui.dp(getContext(), 0.8f), kr, p);
            p.setColor(Color.WHITE);
            c.drawCircle(cx, r, kr, p);
        }
    }

    /** Full-width slider: track, filled part and a knob; value set from outside. */
    static final class Slider extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float frac, shown;
        private ValueAnimator anim;
        private boolean active;

        Slider(Context c) {
            super(c);
        }

        void set(int value, int min, int max, boolean animate) {
            frac = max == min ? 0 : (value - min) / (float) (max - min);
            if (anim != null) anim.cancel();
            if (!animate || !isAttachedToWindow()) {
                shown = frac;
                invalidate();
                return;
            }
            anim = ValueAnimator.ofFloat(shown, frac);
            anim.setDuration(120);
            anim.addUpdateListener(a -> {
                shown = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        /** The focused row's slider is brighter. */
        void setActive(boolean a) {
            active = a;
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(getContext(), 16));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cy = h / 2f;
            float kr = Ui.dp(getContext(), active ? 7 : 6), th = Ui.dp(getContext(), 4);
            float left = kr, right = w - kr, x = left + (right - left) * shown;
            p.setColor(0x33FFFFFF);
            c.drawRoundRect(new RectF(left, cy - th / 2, right, cy + th / 2), th / 2, th / 2, p);
            p.setColor(active ? 0xFFFFFFFF : 0xCCFFFFFF);
            c.drawRoundRect(new RectF(left, cy - th / 2, x, cy + th / 2), th / 2, th / 2, p);
            p.setColor(Color.WHITE);
            c.drawCircle(x, cy, kr, p);
        }
    }

    static int blend(int a, int b, float t) {
        return Color.argb(
                (int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}
