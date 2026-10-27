package com.clean.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * All apps as round icons in one row that slides sideways, Google TV style. The focused circle
 * grows and gets a glass ring; there are no names.
 */
final class AppRow extends FrameLayout {

    static final int SIZE = 60, GAP = 22, HEIGHT = SIZE + 16;   // dp, room for the focused circle to grow
    private static final float FOCUS_SCALE = 1.14f, LIFT_SCALE = 1.24f;

    private final Typeface font;
    private final LinearLayout strip;
    private final List<FrameLayout> cells = new ArrayList<>();
    private int focus = -1;
    private boolean lifted;

    AppRow(Context c, Typeface font) {
        super(c);
        this.font = font;
        setClipChildren(false);
        setClipToPadding(false);
        strip = new LinearLayout(c);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);       // the focused circle grows past the padding
        strip.setPadding(0, dp(8), 0, 0);
        addView(strip, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
    }

    private int dp(float v) {
        return Ui.dp(getContext(), v);
    }

    int count() {
        return cells.size();
    }

    void bind(List<Apps.Entry> apps) {
        Context c = getContext();
        strip.removeAllViews();
        cells.clear();
        int s = dp(SIZE);
        for (Apps.Entry e : apps) {
            FrameLayout cell = new FrameLayout(c);
            cell.setClipChildren(false);
            ImageView img = new ImageView(c);
            img.setImageBitmap(circle(e.icon != null ? e.icon : e.art, s));
            cell.addView(img, new LayoutParams(s, s, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(s, LinearLayout.LayoutParams.MATCH_PARENT);
            clp.rightMargin = dp(GAP);
            strip.addView(cell, clp);
            cells.add(cell);
        }
        int f = focus;
        focus = -2;
        setFocus(Math.min(f, cells.size() - 1), false);
    }

    /** -1 = nothing focused. Slides the row so the focused circle stays in view. */
    void setFocus(int i, boolean animate) {
        int old = focus;
        focus = i;
        for (int k = 0; k < cells.size(); k++) {
            if (k != i && k != old && old != -2) continue;   // only the two that change
            paint(k, animate);
        }
        int step = dp(SIZE + GAP), visible = Math.max(1, (getWidth() > 0 ? getWidth() : dp(840)) / step);
        int first = i < 0 ? 0 : Math.max(0, i - visible + 1);
        float tx = -first * step;
        if (animate) strip.animate().translationX(tx).setDuration(200).start();
        else strip.setTranslationX(tx);
    }

    /** Rearranging: two circles trade places without redrawing the icons. */
    void swap(int a, int b) {
        java.util.Collections.swap(cells, a, b);
        List<LinearLayout.LayoutParams> lps = new ArrayList<>();
        for (FrameLayout c : cells) lps.add((LinearLayout.LayoutParams) c.getLayoutParams());
        strip.removeAllViews();
        for (int k = 0; k < cells.size(); k++) strip.addView(cells.get(k), lps.get(k));
        if (focus == a || focus == b) {
            paint(a, false);
            paint(b, false);
        }
    }

    void setLifted(boolean l) {
        lifted = l;
        if (focus >= 0) paint(focus, true);
    }

    private void paint(int k, boolean animate) {
        if (k < 0 || k >= cells.size()) return;   // before the apps are loaded
        FrameLayout cell = cells.get(k);
        View img = cell.getChildAt(0);
        boolean f = k == focus;
        img.setForeground(f ? (lifted ? Ui.lifted(dp(SIZE) / 2f, dp(3)) : Ui.focusRing(dp(SIZE) / 2f, dp(2.5f))) : null);
        float sc = f ? (lifted ? LIFT_SCALE : FOCUS_SCALE) : 1f;
        img.setPivotX(dp(SIZE) / 2f);
        img.setPivotY(dp(SIZE) / 2f);
        if (animate) {
            img.animate().scaleX(sc).scaleY(sc).setDuration(160).start();
        } else {
            img.animate().cancel();          // a running animation would undo the jump
            img.setScaleX(sc);
            img.setScaleY(sc);
        }
    }

    /**
     * The icon clipped to a circle: adaptive icons fill it edge to edge; square icons are cropped
     * round; shaped icons (round, with transparent corners) sit on a dark disc.
     */
    static Bitmap circle(Drawable d, int s) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Path clip = new Path();
        clip.addCircle(s / 2f, s / 2f, s / 2f, Path.Direction.CW);
        c.clipPath(clip);
        if (d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
            int p = Math.round(s * 0.25f);   // layers are 108 dp, the visible part 72 dp
            for (Drawable layer : new Drawable[] {a.getBackground(), a.getForeground()}) {
                if (layer == null) continue;
                layer.setBounds(-p, -p, s + p, s + p);
                layer.draw(c);
            }
        } else if (d != null && opaqueCorners(d)) {
            int p = Math.round(s * 0.04f);   // a touch of zoom hides rounded-square corners
            d.setBounds(-p, -p, s + p, s + p);
            d.draw(c);
        } else {
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(0xFF2C2C2E);
            c.drawCircle(s / 2f, s / 2f, s / 2f, bg);
            if (d != null) {
                int p = Math.round(s * 0.16f);
                d.setBounds(p, p, s - p, s - p);
                d.draw(c);
            }
        }
        return b;
    }

    private static boolean opaqueCorners(Drawable d) {
        int n = 32;
        Bitmap t = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, n, n);
        d.draw(new Canvas(t));
        int[] pts = {3, n - 4};
        for (int x : pts) for (int y : pts) if (Color.alpha(t.getPixel(x, y)) < 200) return false;
        return true;
    }
}
