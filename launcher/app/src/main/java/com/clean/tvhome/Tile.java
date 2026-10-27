package com.clean.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** A banner card with its name underneath, shown only while focused. */
final class Tile extends FrameLayout {

    static final float FOCUS_SCALE = 1.09f;

    final FrameLayout card;
    private final ImageView banner;
    private final ImageView icon;
    private final TextView label;
    private boolean focused;

    Tile(Context c, int cardW, int cardH, boolean withLabel, Typeface font) {
        super(c);
        setClipChildren(false);
        setClipToPadding(false);
        float radius = Ui.dp(c, 10);

        card = new FrameLayout(c);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);
        card.setBackgroundColor(0xFF1C1C1E);
        banner = new ImageView(c);
        banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(banner, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        int iconSize = cardH / 2;
        icon = new ImageView(c);
        card.addView(icon, new LayoutParams(iconSize, iconSize, Gravity.CENTER));
        addView(card, new LayoutParams(cardW, cardH));

        if (withLabel) {
            label = new TextView(c);
            label.setTypeface(font);
            label.setFontVariationSettings("'wght' 400");
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            label.setTextColor(0xEBFFFFFF);
            label.setGravity(Gravity.CENTER_HORIZONTAL);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setAlpha(0f);
            LayoutParams lp = new LayoutParams(cardW, LayoutParams.WRAP_CONTENT);
            lp.topMargin = cardH + Ui.dp(c, 12);
            addView(label, lp);
        } else {
            label = null;
        }
    }

    void bind(Apps.Entry e) {
        if (e.isBanner) {
            banner.setImageDrawable(e.art);
            icon.setImageDrawable(null);
            card.setBackgroundColor(0xFF1C1C1E);
        } else {
            banner.setImageDrawable(null);
            icon.setImageDrawable(e.art);
            float[] hsv = new float[3];
            Color.colorToHSV(e.glow, hsv);
            hsv[2] *= 0.32f;
            card.setBackgroundColor(Color.HSVToColor(hsv));
        }
        if (label != null) label.setText(e.label);
    }

    /** Steps back while another tile is being moved. Dims the card, not the whole tile, so the
     *  rounded-corner clip still applies (an alpha layer on the parent drops it). */
    void setDimmed(boolean d) {
        card.setForeground(d ? new ColorDrawable(0x99000000) : null);
    }

    void setFocused(boolean f, boolean animate) {
        if (f == focused && animate) return;
        focused = f;
        float s = f ? FOCUS_SCALE : 1f;
        float a = f ? 1f : 0f;
        if (animate) {
            card.animate().scaleX(s).scaleY(s).setDuration(200).start();
            if (label != null) label.animate().alpha(a).setDuration(f ? 220 : 120).start();
        } else {
            card.animate().cancel();
            card.setScaleX(s);
            card.setScaleY(s);
            if (label != null) {
                label.animate().cancel();
                label.setAlpha(a);
            }
        }
    }
}
