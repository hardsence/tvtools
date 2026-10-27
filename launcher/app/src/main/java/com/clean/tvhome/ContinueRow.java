package com.clean.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
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

/** "Continue watching": a header and a horizontally scrolling row of cover cards. */
final class ContinueRow extends LinearLayout {

    static final int CARD_W = 144, COVER_H = 81, GAP = 12;

    private final Typeface font;
    private final LinearLayout strip;
    private final List<View> cards = new ArrayList<>();
    private final List<Integer> colors = new ArrayList<>();
    private List<ContinueStore.Item> items = new ArrayList<>();
    private int focus = -1;

    ContinueRow(Context c, Typeface font) {
        super(c);
        this.font = font;
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        TextView header = text(11, 600, 0xB3FFFFFF);
        header.setLetterSpacing(0.14f);
        header.setText(c.getString(R.string.continue_watching).toUpperCase(c.getResources().getConfiguration().getLocales().get(0)));
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

    int count() {
        return items.size();
    }

    ContinueStore.Item item(int i) {
        return items.get(i);
    }

    int color(int i) {
        return i >= 0 && i < colors.size() ? colors.get(i) : 0xFF3A3A3C;
    }

    void bind(List<ContinueStore.Item> list) {
        Context c = getContext();
        items = list;
        strip.removeAllViews();
        cards.clear();
        colors.clear();
        PackageManager pm = c.getPackageManager();
        for (ContinueStore.Item it : list) {
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
            Bitmap art = ContinueStore.art(c, it.key);
            Drawable d = art != null ? new BitmapDrawable(c.getResources(), art) : null;
            if (d == null) {
                try {
                    d = pm.getApplicationBanner(it.pkg);
                    if (d == null) d = pm.getApplicationIcon(it.pkg);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
            img.setImageDrawable(d);
            cover.addView(img, new FrameLayout.LayoutParams(-1, -1));
            colors.add(d != null ? Apps.sample(d) : 0xFF3A3A3C);
            if (it.hasProgress()) {
                View track = new View(c);
                track.setBackgroundColor(0x66000000);
                cover.addView(track, new FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM));
                View bar = new View(c);
                bar.setBackgroundColor(0xFFFF453A);
                cover.addView(bar, new FrameLayout.LayoutParams(Math.max(dp(4), (int) (dp(CARD_W) * it.progress())), dp(3),
                        Gravity.BOTTOM | Gravity.START));
            }
            card.addView(cover, new LayoutParams(dp(CARD_W), dp(COVER_H)));

            TextView t = text(11, 500, 0xFFFFFFFF);
            t.setText(it.title);
            LayoutParams tl = new LayoutParams(dp(CARD_W), -2);
            tl.topMargin = dp(6);
            card.addView(t, tl);
            TextView sub = text(10, 400, 0x99FFFFFF);
            String app;
            try {
                app = pm.getApplicationLabel(pm.getApplicationInfo(it.pkg, 0)).toString();
            } catch (PackageManager.NameNotFoundException e) {
                app = it.pkg;
            }
            sub.setText(TextUtils.isEmpty(it.subtitle) ? app : it.subtitle + " · " + app);
            LayoutParams sl = new LayoutParams(dp(CARD_W), -2);
            sl.topMargin = dp(2);
            card.addView(sub, sl);

            LayoutParams cl = new LayoutParams(dp(CARD_W), -2);
            cl.rightMargin = dp(GAP);
            strip.addView(card, cl);
            cards.add(card);
        }
        setVisibility(list.isEmpty() ? GONE : VISIBLE);
        setFocus(Math.min(focus, list.size() - 1), false);
    }

    /** -1 = no card focused. Keeps the focused card in view by sliding the strip. */
    void setFocus(int i, boolean animate) {
        focus = i;
        for (int k = 0; k < cards.size(); k++) {
            View cover = ((ViewGroup) cards.get(k)).getChildAt(0);
            boolean f = k == i;
            cover.setForeground(f ? Ui.focusRing(dp(10), dp(2.5f)) : null);
            float s = f ? 1.06f : 1f;
            if (animate) cards.get(k).animate().scaleX(s).scaleY(s).setDuration(160).start();
            else {
                cards.get(k).setScaleX(s);
                cards.get(k).setScaleY(s);
            }
            cards.get(k).setPivotY(0);
        }
        int step = dp(CARD_W + GAP), visible = Math.max(1, (getWidth() > 0 ? getWidth() : dp(840)) / step);
        int first = i < 0 ? 0 : Math.max(0, i - visible + 1);
        float tx = -first * step;
        if (animate) strip.animate().translationX(tx).setDuration(200).start();
        else strip.setTranslationX(tx);
    }
}
