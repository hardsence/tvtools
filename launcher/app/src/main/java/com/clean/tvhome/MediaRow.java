package com.clean.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.os.Handler;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * One row of TMDB titles under the apps: posters (popular films / series) or landscape stills
 * with a play mark and the title underneath (trailers). The focused title is named in the header.
 */
final class MediaRow extends LinearLayout {

    static final int POSTER_W = 92, POSTER_H = 138, STILL_W = 196, STILL_H = 110, GAP = 14;   // dp

    final String list;
    private final boolean trailers;
    private final String name;
    private final Typeface font;
    private final ExecutorService bg;
    private final Handler ui;
    private final TextView header;
    private final LinearLayout strip;
    private final List<View> cards = new ArrayList<>();
    private List<Tmdb.Item> items = new ArrayList<>();
    private int focus = -1, generation;

    MediaRow(Context c, Typeface font, String list, String name, ExecutorService bg, Handler ui) {
        super(c);
        this.list = list;
        this.trailers = list.equals(Tmdb.TRAILERS);
        this.name = name.toUpperCase(c.getResources().getConfiguration().getLocales().get(0));
        this.font = font;
        this.bg = bg;
        this.ui = ui;
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        header = text(11, 600, 0xB3FFFFFF);
        header.setLetterSpacing(0.14f);
        header.setText(this.name);
        addView(header);
        FrameLayout clip = new FrameLayout(c);
        clip.setClipChildren(false);
        strip = new LinearLayout(c);
        strip.setClipChildren(false);
        clip.addView(strip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        addView(clip, lp);
    }

    /** Height of the whole row in dp, header included. */
    static int heightDp(boolean trailers) {
        return 14 + 8 + (trailers ? STILL_H + 6 + 14 + 2 + 12 : POSTER_H) + 6;
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
        t.setShadowLayer(Ui.dp(getContext(), 4), 0, Ui.dp(getContext(), 1), 0x99000000);
        return t;
    }

    int count() {
        return items.size();
    }

    Tmdb.Item item(int i) {
        return i >= 0 && i < items.size() ? items.get(i) : null;
    }

    void bind(List<Tmdb.Item> list) {
        Context c = getContext();
        items = list;
        int gen = ++generation;
        strip.removeAllViews();
        cards.clear();
        int w = dp(trailers ? STILL_W : POSTER_W), h = dp(trailers ? STILL_H : POSTER_H);
        for (int i = 0; i < list.size(); i++) {
            Tmdb.Item it = list.get(i);
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(VERTICAL);
            card.setClipChildren(false);
            FrameLayout cover = new FrameLayout(c);
            float r = dp(10);
            cover.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
                }
            });
            cover.setClipToOutline(true);
            cover.setBackgroundColor(0xFF1C1C1E);
            ImageView img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.addView(img, new FrameLayout.LayoutParams(-1, -1));
            if (trailers) {
                ImageView play = new ImageView(c);
                play.setImageResource(R.drawable.ic_play);
                play.setBackground(Ui.rounded(0x8C000000, dp(17), dp(1), 0x66FFFFFF));
                play.setPadding(dp(9), dp(9), dp(8), dp(9));
                cover.addView(play, new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER));
            }
            card.addView(cover, new LayoutParams(w, h));
            if (trailers) {
                TextView t = text(11, 500, 0xFFFFFFFF);
                t.setText(it.title);
                LayoutParams tl = new LayoutParams(w, -2);
                tl.topMargin = dp(6);
                card.addView(t, tl);
                TextView sub = text(10, 400, 0x99FFFFFF);
                sub.setText(subtitle(it));
                LayoutParams sl = new LayoutParams(w, -2);
                sl.topMargin = dp(2);
                card.addView(sub, sl);
            }
            LayoutParams cl = new LayoutParams(w, -2);
            cl.rightMargin = dp(GAP);
            strip.addView(card, cl);
            cards.add(card);
            // decode off the UI thread, exactly at card size
            bg.execute(() -> {
                Bitmap b = Tmdb.decode(it.file, w, h);
                ui.post(() -> {
                    if (gen == generation && b != null) img.setImageBitmap(b);
                });
            });
        }
        setVisibility(list.isEmpty() ? GONE : VISIBLE);
        setFocus(Math.min(focus, list.size() - 1), false);
    }

    private String subtitle(Tmdb.Item it) {
        Context c = getContext();
        String kind = c.getString(it.movie ? R.string.hero_movie : R.string.hero_series);
        String d = it.date != null && it.date.length() >= 10 ? it.date.substring(8, 10) + "." + it.date.substring(5, 7) : "";
        return c.getString(R.string.trailer) + " · " + kind + (d.isEmpty() ? "" : " · " + d);
    }

    int focus() {
        return focus;
    }

    /** -1 = no card focused. Keeps the focused card in view and names it in the header. */
    void setFocus(int i, boolean animate) {
        focus = i;
        for (int k = 0; k < cards.size(); k++) {
            View cover = ((ViewGroup) cards.get(k)).getChildAt(0);
            boolean f = k == i;
            cover.setForeground(f ? Ui.focusRing(dp(10), dp(2.5f)) : null);
            float s = f ? 1.07f : 1f;
            View card = cards.get(k);
            card.setPivotY(trailers ? 0 : dp(POSTER_H) / 2f);
            card.setPivotX(dp(trailers ? STILL_W : POSTER_W) / 2f);
            if (animate) card.animate().scaleX(s).scaleY(s).setDuration(160).start();
            else {
                card.setScaleX(s);
                card.setScaleY(s);
            }
        }
        Tmdb.Item it = item(i);
        if (it != null && !trailers) {
            String rating = it.rating > 0 ? String.format(java.util.Locale.getDefault(), "  ★ %.1f", it.rating) : "";
            header.setText(name + "   ·   " + it.title + (TextUtils.isEmpty(it.year) ? "" : " (" + it.year + ")") + rating);
        } else {
            header.setText(name);
        }
        int step = dp((trailers ? STILL_W : POSTER_W) + GAP), visible = Math.max(1, (getWidth() > 0 ? getWidth() : dp(840)) / step);
        int first = i < 0 ? 0 : Math.max(0, i - visible + 1);
        float tx = -first * step;
        if (animate) strip.animate().translationX(tx).setDuration(200).start();
        else strip.setTranslationX(tx);
    }
}
