package com.clean.tvhome;

import android.app.Activity;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Search over installed apps: matching apps first, then one row per app that exposes searchable
 * content (SmartTube, VLC, Spotify…), each result a card opening the item inside that app.
 * Typing (on-screen keyboard or the remote) searches after a short pause.
 */
public class SearchActivity extends Activity {

    private static final long DEBOUNCE_MS = 450;
    private static final int CARD_W = 200, CARD_H = 112, LIMIT = 12;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private Typeface font;
    private EditText field;
    private LinearLayout results;
    private TextView status;
    private List<SearchSources.Source> sources = new ArrayList<>();
    private CancellationSignal cancel;
    private int generation;
    /** Opened with a title (from the home screen's hero): show results, not the keyboard. */
    private boolean autoFocus;

    private final Runnable run = this::search;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        font = getResources().getFont(R.font.outfit);
        buildUi();
        io.execute(() -> {
            List<SearchSources.Source> s = SearchSources.discover(this);
            ui.post(() -> {
                sources = s;
                if (field.length() > 0) search();
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cancel != null) cancel.cancel();
        io.shutdownNow();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setTypeface(font);
        t.setFontVariationSettings("'wght' " + weight);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        // frosted: the blurred home screen (handed over like the timer window's), heavily tinted
        root.setBackground(new Glass.Pane(root, TimerActivity.backdrop, 0, 0xD90B0B0D, 0x00000000));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(56), dp(40), dp(56), 0);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), 0, dp(18), 0);
        bar.setBackground(Ui.glassButton(dp(26)));
        ImageView mag = new ImageView(this);
        mag.setImageDrawable(getDrawable(R.drawable.ic_search));
        bar.addView(mag, new LinearLayout.LayoutParams(dp(22), dp(22)));
        field = new EditText(this);
        field.setTypeface(font);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        field.setTextColor(0xFFFFFFFF);
        field.setHintTextColor(0x80FFFFFF);
        field.setHint(R.string.search_hint);
        field.setBackground(null);
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setPadding(dp(12), 0, 0, 0);
        bar.addView(field, new LinearLayout.LayoutParams(0, dp(52), 1f));
        col.addView(bar, new LinearLayout.LayoutParams(-1, dp(52)));

        status = text(14, 400, 0x99FFFFFF);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = dp(14);
        col.addView(status, slp);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(8), 0, dp(40));
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(results, new ViewGroup.LayoutParams(-1, -2));
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable e) {
                ui.removeCallbacks(run);
                ui.postDelayed(run, DEBOUNCE_MS);
            }
        });
        field.setOnEditorActionListener((v, id, ev) -> {
            ui.removeCallbacks(run);
            search();
            hideKeyboard();
            View first = results.findFocus() == null ? firstCard() : null;
            if (first != null) first.requestFocus();
            return true;
        });
        String q = getIntent().getStringExtra(SearchManager.QUERY);
        field.requestFocus();
        if (q != null) {
            autoFocus = true;
            // results, not the keyboard; OK on the field still brings it up
            getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            field.setText(q);
            field.setSelection(q.length());
            ui.removeCallbacks(run);
            search();
        } else {
            field.post(() -> getSystemService(InputMethodManager.class).showSoftInput(field, 0));
        }
    }

    private void hideKeyboard() {
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    private View firstCard() {
        if (results.getChildCount() == 0) return null;
        View row = results.getChildAt(0);
        View strip = row.findViewWithTag("strip");
        return strip instanceof ViewGroup && ((ViewGroup) strip).getChildCount() > 0 ? ((ViewGroup) strip).getChildAt(0) : null;
    }

    // ---------------------------------------------------------------- searching

    private void search() {
        String q = field.getText().toString().trim();
        if (cancel != null) cancel.cancel();
        int gen = ++generation;
        results.removeAllViews();
        if (q.length() < 2) {
            status.setText(q.isEmpty() ? getString(R.string.search_sources, sources.size()) : "");
            return;
        }
        cancel = new CancellationSignal();
        CancellationSignal sig = cancel;
        status.setText(R.string.search_searching);

        // installed apps whose name matches
        List<Apps.Entry> apps = new ArrayList<>();
        Apps.Split all = Apps.load(this);
        List<Apps.Entry> every = new ArrayList<>(all.dock);
        every.addAll(all.grid);
        String lq = q.toLowerCase(Locale.ROOT);
        for (Apps.Entry e : every) if (e.label.toLowerCase(Locale.ROOT).contains(lq)) apps.add(e);
        if (!apps.isEmpty()) addAppRow(apps);
        focusFirstResult();

        int[] pending = {sources.size() + 1};
        for (SearchSources.Source s : sources) {
            io.execute(() -> {
                List<SearchSources.Result> r = SearchSources.query(this, s, q, LIMIT, sig);
                ui.post(() -> {
                    if (gen != generation) return;
                    if (!r.isEmpty()) addResultRow(s, r);
                    focusFirstResult();
                    if (--pending[0] == 0) finishStatus(gen);
                });
            });
        }
        io.execute(() -> {
            List<SearchSources.Result> r = Tmdb.searchDisney(this, q, sig);
            ui.post(() -> {
                if (gen != generation) return;
                if (!r.isEmpty()) addDisneyRow(r);
                focusFirstResult();
                if (--pending[0] == 0) finishStatus(gen);
            });
        });
    }

    private void focusFirstResult() {
        if (!autoFocus) return;
        View first = firstCard();
        if (first == null) return;
        autoFocus = false;
        first.requestFocus();
    }

    private void finishStatus(int gen) {
        if (gen != generation) return;
        status.setText(results.getChildCount() == 0 ? getString(R.string.search_none) : "");
    }

    private LinearLayout section(String title, Drawable icon) {
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(20), dp(20));
            ilp.rightMargin = dp(10);
            head.addView(iv, ilp);
        }
        TextView t = text(15, 600, 0xCCFFFFFF);
        t.setText(title);
        head.addView(t);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.topMargin = dp(18);
        hlp.bottomMargin = dp(10);
        sec.addView(head, hlp);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout strip = new LinearLayout(this);
        strip.setTag("strip");
        hs.addView(strip);
        sec.addView(hs);
        results.addView(sec);
        return strip;
    }

    private View card(String title, String subtitle, Drawable image, boolean banner) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setPadding(dp(4), dp(4), dp(4), dp(6));
        FrameLayout cover = new FrameLayout(this);
        float r = dp(10);
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        cover.setClipToOutline(true);
        cover.setBackgroundColor(0xFF1C1C1E);
        ImageView iv = new ImageView(this);
        iv.setScaleType(banner ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.CENTER_INSIDE);
        iv.setImageDrawable(image);
        cover.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        card.addView(cover, new LinearLayout.LayoutParams(dp(CARD_W), dp(CARD_H)));
        TextView t = text(14, 500, 0xFFFFFFFF);
        t.setText(title);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(dp(CARD_W), -2);
        tl.topMargin = dp(8);
        card.addView(t, tl);
        if (!TextUtils.isEmpty(subtitle)) {
            TextView s = text(12, 400, 0x99FFFFFF);
            s.setText(subtitle);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(CARD_W), -2);
            sl.topMargin = dp(3);
            card.addView(s, sl);
        }
        card.setOnFocusChangeListener((v, f) -> {
            cover.setForeground(f ? Ui.focusRing(r, dp(3)) : null);
            v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(140).start();
        });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.rightMargin = dp(18);
        card.setLayoutParams(cl);
        return card;
    }

    private void addAppRow(List<Apps.Entry> apps) {
        LinearLayout strip = section(getString(R.string.search_apps), null);
        for (Apps.Entry e : apps) {
            View c = card(e.label, null, e.art, e.isBanner);
            c.setOnClickListener(v -> open(e.launch));
            strip.addView(c);
        }
    }

    private void addResultRow(SearchSources.Source s, List<SearchSources.Result> rs) {
        Drawable icon = null;
        try {
            icon = getPackageManager().getApplicationIcon(s.pkg);
        } catch (Exception ignored) {
        }
        LinearLayout strip = section(s.label, icon);
        for (SearchSources.Result r : rs) {
            ImageView[] holder = new ImageView[1];
            View c = card(r.title, r.subtitle, icon, false);
            holder[0] = (ImageView) ((ViewGroup) ((ViewGroup) c).getChildAt(0)).getChildAt(0);
            c.setOnClickListener(v -> open(r.intent));
            strip.addView(c);
            if (!TextUtils.isEmpty(r.image)) loadImage(r.image, holder[0]);
        }
    }

    private void addDisneyRow(List<SearchSources.Result> rs) {
        Drawable icon = null;
        try {
            icon = getPackageManager().getApplicationIcon("com.disney.disneyplus");
        } catch (Exception ignored) {
        }
        LinearLayout strip = section(getString(R.string.search_disney_tmdb), icon);
        for (SearchSources.Result r : rs) {
            View c = card(r.title, r.subtitle, icon, false);
            c.setOnClickListener(v -> open(r.intent));
            strip.addView(c);
            if (!TextUtils.isEmpty(r.image)) {
                ImageView holder = (ImageView) ((ViewGroup) ((ViewGroup) c).getChildAt(0)).getChildAt(0);
                loadImage(r.image, holder);
            }
        }
    }

    private void loadImage(String uri, ImageView into) {
        io.execute(() -> {
            Bitmap b = null;
            try {
                Uri u = Uri.parse(uri);
                InputStream in = "http".equals(u.getScheme()) || "https".equals(u.getScheme())
                        ? new URL(uri).openStream() : getContentResolver().openInputStream(u);
                try (InputStream s = in) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = 2;
                    b = BitmapFactory.decodeStream(s, null, o);
                }
            } catch (Exception ignored) {
            }
            Bitmap bm = b;
            if (bm != null) ui.post(() -> {
                into.setScaleType(ImageView.ScaleType.CENTER_CROP);
                into.setImageBitmap(bm);
            });
        });
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.search_cant_open, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Up from the first row of results goes back to the field
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
            View f = getCurrentFocus();
            if (f != null && f != field && results.getChildCount() > 0
                    && ((View) f.getParent().getParent().getParent()) == results.getChildAt(0)) {
                field.requestFocus();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }
}
