package com.clean.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Google TV style hero: a full-screen backdrop of one of today's or this week's trending titles, its title,
 * rating and overview on the left, and a row of dots (one per title) that the remote walks through.
 * The background lives under everything (root), the text and dots scroll with the home screen.
 */
final class Hero {

    static final long ROTATE_MS = 9000;

    private final Context c;
    private final Typeface font;
    private final int screenW, screenH;
    private final ExecutorService bg;
    private final Handler ui;

    final FrameLayout background;
    final LinearLayout text;
    final LinearLayout dotsRow;
    private final ImageView[] layers = new ImageView[2];
    private final View scrim;
    private int front;
    private final TextView title, meta, overview;
    private final LinearLayout dots;
    private final TextView hint, credit;

    private List<Tmdb.Item> items = new ArrayList<>();
    private int index = -1, loading = -1;
    private boolean focused;

    Hero(Context c, Typeface font, int w, int h, ExecutorService bg, Handler ui) {
        this.c = c;
        this.font = font;
        this.screenW = w;
        this.screenH = h;
        this.bg = bg;
        this.ui = ui;

        background = new FrameLayout(c);
        background.setAlpha(0f);
        for (int i = 0; i < 2; i++) {
            ImageView v = new ImageView(c);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setAlpha(0f);
            background.addView(v, new FrameLayout.LayoutParams(w, h));
            layers[i] = v;
        }
        scrim = new View(c);
        scrim.setBackground(scrim(h));
        background.addView(scrim, new FrameLayout.LayoutParams(w, h));

        text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setAlpha(0f);
        title = text(30, 500, 0xFFFFFFFF);
        title.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(title, new LinearLayout.LayoutParams(-1, -2));
        meta = text(12, 500, 0xCCFFFFFF);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
        ml.topMargin = dp(6);
        text.addView(meta, ml);
        overview = text(12, 400, 0xB3FFFFFF);
        overview.setSingleLine(false);
        overview.setMaxLines(2);
        overview.setEllipsize(TextUtils.TruncateAt.END);
        overview.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-1, -2);
        ol.topMargin = dp(6);
        text.addView(overview, ol);

        // dots on the right, with "OK  Search" and the section name + TMDB credit beside them
        dotsRow = new LinearLayout(c);
        dotsRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        dotsRow.setAlpha(0f);
        // the section name, swapped for "OK  Search" while the dots have focus
        FrameLayout labels = new FrameLayout(c);
        hint = text(11, 500, 0xE6FFFFFF);
        hint.setText(R.string.hero_hint);
        hint.setAlpha(0f);
        labels.addView(hint, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        credit = text(10, 600, 0x80FFFFFF);
        credit.setLetterSpacing(0.14f);
        credit.setText(c.getString(R.string.hero_label).toUpperCase(c.getResources().getConfiguration().getLocales().get(0)) + "  ·  TMDB");
        labels.addView(credit, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.rightMargin = dp(12);
        dotsRow.addView(labels, cl);
        dots = new LinearLayout(c);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        dots.setPadding(dp(8), dp(5), dp(8), dp(5));
        dotsRow.addView(dots, new LinearLayout.LayoutParams(-2, -2));
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
        t.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        return t;
    }

    /** Darkens the left (text), the bottom (rows, dock) and a little of the top (clock, pills). */
    private static Drawable scrim(int h) {
        GradientDrawable left = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xE6000000, 0x8C000000, 0x1A000000, 0x00000000});
        GradientDrawable bottom = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x00000000, 0x00000000, 0x99000000, 0xF2000000});
        GradientDrawable top = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x99000000, 0x00000000});
        LayerDrawable l = new LayerDrawable(new Drawable[] {left, bottom, top});
        l.setLayerInset(2, 0, 0, 0, h * 2 / 3);
        return l;
    }

    int count() {
        return items.size();
    }

    int index() {
        return index;
    }

    Tmdb.Item current() {
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    void bind(List<Tmdb.Item> list) {
        String keep = current() != null ? current().key() : null;
        items = list;
        dots.removeAllViews();
        for (int i = 0; i < list.size(); i++) {
            View d = new View(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(7), dp(7));
            if (i > 0) lp.leftMargin = dp(8);
            dots.addView(d, lp);
        }
        int start = 0;
        for (int i = 0; keep != null && i < list.size(); i++) if (list.get(i).key().equals(keep)) start = i;
        boolean has = !list.isEmpty();
        background.animate().alpha(has ? 1f : 0f).setDuration(400).start();
        text.animate().alpha(has ? 1f : 0f).setDuration(400).start();
        dotsRow.animate().alpha(has ? 1f : 0f).setDuration(400).start();
        index = -1;
        if (has) show(start);
    }

    void next() {
        if (!items.isEmpty()) show((index + 1) % items.size());
    }

    /** Moves to slide i: the backdrop is decoded off the UI thread, then cross-fades in. */
    void show(int i) {
        if (items.isEmpty()) return;
        i = Math.max(0, Math.min(items.size() - 1, i));
        if (i == index) return;
        index = i;
        Tmdb.Item it = items.get(i);
        title.setText(it.title);
        meta.setText(Tmdb.meta(c, it));
        overview.setText(it.overview);
        overview.setVisibility(TextUtils.isEmpty(it.overview) ? View.GONE : View.VISIBLE);
        paintDots();
        loading = i;
        bg.execute(() -> {
            Bitmap b = Tmdb.decode(it.file, screenW, screenH);
            ui.post(() -> {
                if (loading != index || b == null) return;   // the user moved on meanwhile
                ImageView in = layers[1 - front], out = layers[front];
                in.setImageBitmap(b);
                in.bringToFront();
                scrim.bringToFront();                         // scrim stays on top
                in.setAlpha(0f);
                in.animate().alpha(1f).setDuration(600).withEndAction(() -> {
                    out.setAlpha(0f);
                    out.setImageDrawable(null);
                }).start();
                front = 1 - front;
            });
        });
    }

    void setFocused(boolean f) {
        if (f == focused) return;
        focused = f;
        hint.animate().alpha(f ? 1f : 0f).setDuration(160).start();
        credit.animate().alpha(f ? 0f : 1f).setDuration(160).start();
        dots.setBackground(f ? Ui.glassFocus(dp(12)) : null);
        paintDots();
    }

    private void paintDots() {
        for (int i = 0; i < dots.getChildCount(); i++) {
            View d = dots.getChildAt(i);
            boolean sel = i == index;
            int color = sel ? 0xFFFFFFFF : (focused ? 0x99FFFFFF : 0x66FFFFFF);
            d.setBackground(Ui.rounded(color, dp(4), 0, 0));
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) d.getLayoutParams();
            int w = sel ? dp(20) : dp(7);
            if (lp.width != w) {
                lp.width = w;
                d.setLayoutParams(lp);
            }
        }
    }
}
