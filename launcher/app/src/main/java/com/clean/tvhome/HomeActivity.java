package com.clean.tvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple TV style home screen. Focus is a small state machine driven from dispatchKeyEvent,
 * not Android's focus search, so every D-pad press has exactly one outcome.
 */
public class HomeActivity extends Activity {

    private int cols; // dock and grid columns, from the Settings menu

    private enum Zone { TOP, HERO, APPS, CONTINUE, MEDIA, GRID, MENU, PANEL, WEATHER }

    private static final int HERO_TEXT_H = 104, HERO_DOTS_H = 22;
    private static final int PILL_SEARCH = 0, PILL_WEATHER = 1, PILL_INPUTS = 2, PILL_WIFI = 3, PILL_SETTINGS = 4;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final ExecutorService radarBg = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean radarBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private final ExecutorService heroBg = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean tmdbBusy = new java.util.concurrent.atomic.AtomicBoolean();

    // Metrics (px)
    private int W, H, side, gap, tileW, tileH, rowH, gridTop, appsTop;
    private Typeface font;

    // Views
    private FrameLayout root, content;
    private ImageView wallpaper;
    private ContinueRow continueRow;
    private int continueIdx, contH;
    private Hero hero;
    // the hero changes slide by itself, but not while it is being browsed or covered by a window
    private final Runnable heroTick = new Runnable() {
        @Override
        public void run() {
            if (!away && (zone == Zone.TOP || zone == Zone.APPS || zone == Zone.CONTINUE)) hero.next();
            ui.postDelayed(this, Hero.ROTATE_MS);
        }
    };
    private View dim, glow, highlight;
    private GradientDrawable glowDrawable;
    private TextView greeting, date;
    private LinearLayout clockBox;
    private final TextView[] pills = new TextView[5];
    private AppRow appRow;
    // TMDB rows under continue watching; each remembers its own position
    private final List<MediaRow> mediaRows = new ArrayList<>();
    private final int[] mediaPos = new int[3], mediaTop = new int[3];
    private int mediaIdx;
    // The Apps window (remote's Apps button): every app as a tile in a grid
    private FrameLayout appsWin, gridHolder;
    private SpringAnimation gridScroll;
    private RecyclerView grid;
    private GridAdapter adapter;
    private LinearLayout menu, pillRow;
    // Glass: blurred snapshot of what lies under the pills, card and dock band
    private Glass.Backdrop backdrop;
    private final List<View> glassHosts = new ArrayList<>();
    private Glass.Pane[] pillGlass;
    private boolean backdropQueued;
    private int underBlur;
    private PicturePanel panel;

    // State
    private List<Apps.Entry> apps = new ArrayList<>();   // one ordered list: the round icons and the grid
    private final List<MenuItem> menuItems = new ArrayList<>();
    private int menuPill, pendingCols;
    private TextView columnsValue;
    private WeatherWidget weather;
    private Zone zone = Zone.APPS;
    private int topIdx, appsIdx, gridIdx, menuIdx;
    private Tile focusedTile;
    private boolean highlightShown;
    private String pendingFocusPkg;
    private int glowColor = 0xFF3A3A3C;
    private ValueAnimator glowAnim;
    private SpringAnimation hlX, hlY, scroll;
    private boolean away;
    private boolean pillWired;

    // Rearranging: hold OK on a tile to lift it, arrows swap it with its neighbour,
    // OK drops it (saved), Back/Home puts everything back.
    private boolean editing;
    private boolean okHeld;
    private final List<Apps.Entry> editBackup = new ArrayList<>();
    private Zone editZone0;
    private int editIdx0;
    private TextView editHint;
    private final Runnable longPress = () -> {
        if (okHeld) {
            okHeld = false;
            startEdit();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            reloadApps();
        }
    };

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateDateTexts();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        DisplayMetrics m = getResources().getDisplayMetrics();
        W = Math.max(m.widthPixels, m.heightPixels);
        H = Math.min(m.widthPixels, m.heightPixels);
        font = getResources().getFont(R.font.outfit);
        cols = Apps.cols(this);
        computeMetrics();
        buildUi();
        setContentView(root);

        SharedPreferences p = getPreferences(MODE_PRIVATE);
        pendingFocusPkg = p.getString("pkg", null);
        topIdx = p.getInt("top", 0);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        register(packageReceiver, f);

        watchNetwork();
        loadWallpaper();
        reloadApps();
        if ("inputs".equals(getIntent().getStringExtra(KeyService.EXTRA_ACTION))) root.post(this::openInputsMenu);
        if ("apps".equals(getIntent().getStringExtra(KeyService.EXTRA_ACTION))) root.post(this::openAppsWindow);
        bg.execute(() -> deleteTree(new java.io.File(getFilesDir(), "trending")));   // the old hero cache
    }

    private void register(BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(r, f);
    }

    // Home pressed on the home screen vs. returning from an app: Android pauses an activity before
    // every new intent, and some apps open translucent over it, so track leaving explicitly.
    @Override
    protected void onStop() {
        super.onStop();
        away = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        away = false;
        bg.execute(() -> KeyService.ensureEnabled(this));
        startWeather();
        loadTmdb();
        ui.removeCallbacks(heroTick);
        ui.postDelayed(heroTick, Hero.ROTATE_MS);
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(Intent.ACTION_DATE_CHANGED);
        register(timeReceiver, f);
        updateDateTexts();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(timeReceiver);
        ui.removeCallbacks(longPress);
        ui.removeCallbacks(weatherTick);
        ui.removeCallbacks(heroTick);
        okHeld = false;
        finishEdit(false);
        Apps.Entry e = focusedApp();
        getPreferences(MODE_PRIVATE).edit()
                .putString("pkg", e == null ? null : e.pkg)
                .putInt("top", topIdx)
                .apply();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finishEdit(false);
        String action = intent.getStringExtra(KeyService.EXTRA_ACTION);
        if ("pqdump".equals(action)) {
            bg.execute(() -> PqProbe.dump(this));
            return;
        }
        if (panel.isOpen()) closePanel();
        if ("apps".equals(action)) {
            if (zone == Zone.MENU) closeMenu();
            if (zone == Zone.WEATHER) closeWeatherWindow();
            if (zone != Zone.GRID) openAppsWindow();
        } else if ("inputs".equals(action)) {
            if (zone == Zone.GRID) closeAppsWindow();
            if (zone != Zone.MENU) openInputsMenu();
        } else if (!away || "home".equals(action) || zone == Zone.GRID) {
            // Home pressed while already home (or coming back from an app opened in the Apps
            // window): back to the round app icons.
            if (zone == Zone.MENU) closeMenu();
            if (zone == Zone.WEATHER) closeWeatherWindow();
            if (zone == Zone.GRID) closeAppsWindow();
            if (zone != Zone.APPS) {
                zone = apps.isEmpty() ? Zone.TOP : Zone.APPS;
                applyFocus(true);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        bg.shutdownNow();
        radarBg.shutdownNow();
        heroBg.shutdownNow();
        ContinueStore.listener = null;
    }

    // ---------------------------------------------------------------- layout

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private void computeMetrics() {
        side = dp(56);
        gap = dp(22);
        tileW = (W - 2 * side - (cols - 1) * gap) / cols;
        tileH = tileW * 9 / 16;
        rowH = tileH + gap;                    // no app names under the tiles
        gridTop = dp(104);                     // in the Apps window, under its title
        appsTop = H - dp(AppRow.HEIGHT + 20);
    }

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(this);
        style(t, sp, weight, color);
        return t;
    }

    private void style(TextView t, float sp, int weight, int color) {
        t.setTypeface(font);
        t.setFontVariationSettings("'wght' " + weight);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
    }

    private static FrameLayout.LayoutParams at(int w, int h, int left, int top) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = left;
        lp.topMargin = top;
        return lp;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        wallpaper = new ImageView(this);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wallpaper, new FrameLayout.LayoutParams(W, H));
        // Trending backdrop over the wallpaper; the grid's dim still covers it
        hero = new Hero(this, font, W, H, bg, ui);   // decodes on bg; downloads run on heroBg
        root.addView(hero.background, new FrameLayout.LayoutParams(W, H));
        dim = new View(this);
        dim.setBackgroundColor(Color.BLACK);
        dim.setAlpha(0f);
        root.addView(dim, new FrameLayout.LayoutParams(W, H));

        content = new FrameLayout(this);
        content.setClipChildren(false);
        root.addView(content, new FrameLayout.LayoutParams(W, H * 3));

        // Ambient glow behind the dock title: a radial gradient squashed into an ellipse.
        int glowR = dp(330);
        glowDrawable = new GradientDrawable();
        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientRadius(glowR);
        glowDrawable.setColors(new int[] {Ui.withAlpha(glowColor, 0x70), 0x00000000});
        glow = new View(this);
        glow.setBackground(glowDrawable);
        glow.setScaleY(0.42f);
        int glowY = H - dp(190);
        content.addView(glow, at(2 * glowR, 2 * glowR, side + dp(150) - glowR, glowY - glowR));

        // Greeting, clock, date
        clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        // GREETING (small caps) above; time | DATE on one line
        greeting = text(13, 500, 0x99FFFFFF);
        greeting.setLetterSpacing(0.18f);
        TextClock clock = new TextClock(this);
        style(clock, 56, 250, 0xFFFFFFFF);
        clock.setFormat24Hour("H:mm");
        clock.setFormat12Hour("h:mm");
        date = text(14, 500, 0x99FFFFFF);
        date.setLetterSpacing(0.1f);
        clockBox.addView(greeting);
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(clock);
        View bar = new View(this);
        bar.setBackgroundColor(0x40FFFFFF);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(1), dp(30));
        blp.setMargins(dp(14), dp(6), dp(14), 0);
        line.addView(bar, blp);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(6);
        line.addView(date, dlp);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(4);
        clockBox.addView(line, clp);
        content.addView(clockBox, at(W / 2 - side - dp(36), ViewGroup.LayoutParams.WRAP_CONTENT, side, dp(40)));

        // Weather card: right of the clock, under the pills, above the dock title
        weather = new WeatherWidget(this, font);
        weather.setVisibility(View.GONE);         // opened from the weather pill, like a menu
        root.addView(weather, at(dp(460), dp(280), 0, dp(40 + 38 + 8)));

        // Frosted pills, top right
        pillRow = new LinearLayout(this);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        pillRow.setClipChildren(false);
        int[] icons = {R.drawable.ic_search, R.drawable.ic_weather, R.drawable.ic_input, R.drawable.ic_wifi, R.drawable.ic_settings};
        String[] labels = {"", getString(R.string.weather), getString(R.string.inputs), getString(R.string.wifi), getString(R.string.settings)};
        for (int i = 0; i < 5; i++) {
            TextView pill = text(15, 400, 0xFFFFFFFF);
            Drawable icon = getDrawable(icons[i]).mutate(); // tinted per pill
            icon.setBounds(0, 0, dp(18), dp(18));
            pill.setCompoundDrawablesRelative(icon, null, null, null);
            pill.setCompoundDrawablePadding(dp(8));
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setPadding(dp(16), 0, dp(18), 0);
            pill.setText(labels[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
            if (i > 0) lp.leftMargin = dp(12);
            pillRow.addView(pill, lp);
            pills[i] = pill;
        }
        pills[PILL_WEATHER].setVisibility(Weather.enabled(this) ? View.VISIBLE : View.GONE);
        // search: just the magnifier, a round pill
        pills[PILL_SEARCH].setCompoundDrawablePadding(0);
        pills[PILL_SEARCH].setPadding(dp(10), 0, dp(10), 0);
        pills[PILL_SEARCH].setMinWidth(dp(38));
        pills[PILL_SEARCH].setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        plp.topMargin = dp(40);
        plp.rightMargin = side;
        content.addView(pillRow, plp);

        // Focused dock app's name
        // Every app as a round icon, in one row that slides sideways
        appRow = new AppRow(this, font);
        content.addView(appRow, at(W - side, dp(AppRow.HEIGHT), side, appsTop));

        // Continue watching: under the round icons, at the bottom of the screen
        continueRow = new ContinueRow(this, font);
        contH = dp(14 + 8 + ContinueRow.COVER_H + 6 + 14 + 2 + 12 + 6);
        content.addView(continueRow, at(W - 2 * side, contH, side, H - dp(14) - contH));

        // Hero text on the left and its dots on the right, right above the round icons
        hero.text.setGravity(Gravity.BOTTOM);
        content.addView(hero.text, at(dp(400), dp(HERO_TEXT_H), side, 0));
        content.addView(hero.dotsRow, at(W - 2 * side, dp(HERO_DOTS_H), side, 0));

        // Below the screen: TMDB rows (popular films, popular series, latest trailers)
        String[] lists = {Tmdb.MOVIES, Tmdb.SERIES, Tmdb.TRAILERS};
        int[] names = {R.string.popular_movies, R.string.popular_series, R.string.latest_trailers};
        for (int i = 0; i < lists.length; i++) {
            MediaRow r = new MediaRow(this, font, lists[i], getString(names[i]), bg, ui);
            r.setVisibility(View.GONE);
            content.addView(r, at(W - side, ViewGroup.LayoutParams.WRAP_CONTENT, side, H));
            mediaRows.add(r);
        }
        layoutHome();
        loadContinue();
        ContinueStore.listener = () -> ui.post(this::loadContinue);

        // Apps window: frosted full screen, a title and the grid of tiles (scrolls inside)
        appsWin = new FrameLayout(this);
        appsWin.setVisibility(View.GONE);
        appsWin.setClipChildren(false);
        TextView appsTitle = text(13, 600, 0xB3FFFFFF);
        appsTitle.setLetterSpacing(0.16f);
        appsTitle.setText(getString(R.string.all_apps).toUpperCase(getResources().getConfiguration().getLocales().get(0)));
        appsWin.addView(appsTitle, at(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, side, dp(56)));
        gridHolder = new FrameLayout(this);
        gridHolder.setClipChildren(false);
        appsWin.addView(gridHolder, new FrameLayout.LayoutParams(W, H * 2));
        root.addView(appsWin, new FrameLayout.LayoutParams(W, H));

        // App grid
        adapter = new GridAdapter(tileW, tileH, rowH, gap, font);
        grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, cols));
        grid.setItemAnimator(null);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setFocusable(false);
        grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        grid.setAdapter(adapter);
        gridHolder.addView(grid, at(cols * (tileW + gap), rowH, side - gap / 2, gridTop));

        // The single glass highlight that springs between tiles
        highlight = new View(this);
        highlight.setBackground(Ui.glass(dp(10), dp(1.5f)));
        highlight.setScaleX(Tile.FOCUS_SCALE);
        highlight.setScaleY(Tile.FOCUS_SCALE);
        highlight.setAlpha(0f);
        gridHolder.addView(highlight, at(tileW, tileH, 0, 0));
        hlX = spring(highlight, DynamicAnimation.TRANSLATION_X, 520f, 0.72f);
        hlY = spring(highlight, DynamicAnimation.TRANSLATION_Y, 520f, 0.72f);
        scroll = spring(content, DynamicAnimation.TRANSLATION_Y, 300f, 0.9f);
        gridScroll = spring(gridHolder, DynamicAnimation.TRANSLATION_Y, 300f, 0.9f);

        // Inputs dropdown
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(6), dp(6), dp(6), dp(6));
        menu.setBackground(Ui.rounded(0xA61C1C1E, dp(20), dp(1), 0x38FFFFFF));
        menu.setVisibility(View.GONE);
        root.addView(menu, at(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT, 0, dp(40 + 38 + 10)));

        // Shown while a tile is lifted
        editHint = text(16, 400, 0xFFFFFFFF);
        editHint.setText(R.string.edit_hint);
        editHint.setGravity(Gravity.CENTER);
        editHint.setPadding(dp(22), 0, dp(22), 0);
        editHint.setBackground(Ui.rounded(0xE61E1E21, dp(20), dp(1), 0x33FFFFFF));
        editHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hlp.topMargin = dp(20);
        root.addView(editHint, hlp);

        // Picture settings sheet on the right
        panel = new PicturePanel(this, font, H, this::closePanel);
        FrameLayout.LayoutParams plp2 = new FrameLayout.LayoutParams(dp(470), H, Gravity.END);
        root.addView(panel.view, plp2);
    }

    private static SpringAnimation spring(View v, DynamicAnimation.ViewProperty p, float stiffness, float damping) {
        SpringAnimation a = new SpringAnimation(v, p);
        a.setSpring(new SpringForce().setStiffness(stiffness).setDampingRatio(damping));
        return a;
    }

    private void resizeGrid() {
        int rows = (apps.size() + cols - 1) / cols;
        ViewGroup.LayoutParams lp = grid.getLayoutParams();
        lp.height = Math.max(rowH, rows * rowH);
        grid.setLayoutParams(lp);
        ViewGroup.LayoutParams hlp = gridHolder.getLayoutParams();
        hlp.height = gridTop + lp.height + H;
        gridHolder.setLayoutParams(hlp);
    }

    // ---------------------------------------------------------------- data

    private void reloadApps() {
        bg.execute(() -> {
            Apps.Split s = Apps.load(this);
            ui.post(() -> setApps(s));
        });
    }

    private void setApps(Apps.Split s) {
        Apps.Entry before = focusedApp();
        String keep = pendingFocusPkg != null ? pendingFocusPkg : (before == null ? null : before.pkg);
        pendingFocusPkg = null;

        List<Apps.Entry> all = new ArrayList<>(s.dock);
        all.addAll(s.grid);
        apps = all;
        appRow.bind(apps);
        focusedTile = null;
        adapter.focused = -1;
        adapter.setApps(apps);
        resizeGrid();

        // Restore the remembered app by package, so it survives reordering and restarts.
        for (int i = 0; keep != null && i < apps.size(); i++) {
            if (!apps.get(i).pkg.equals(keep)) continue;
            if (zone == Zone.GRID) gridIdx = i;
            else appsIdx = i;
        }
        appsIdx = clamp(appsIdx, 0, Math.max(0, apps.size() - 1));
        gridIdx = clamp(gridIdx, 0, Math.max(0, apps.size() - 1));
        if ((zone == Zone.APPS || zone == Zone.GRID) && apps.isEmpty()) zone = Zone.TOP;
        // Grid tiles are bound on the next layout pass; place focus once they exist.
        grid.post(() -> applyFocus(false));
        applyFocus(false);
    }

    private Apps.Entry focusedApp() {
        if (zone == Zone.APPS && appsIdx < apps.size()) return apps.get(appsIdx);
        if (zone == Zone.GRID && gridIdx < apps.size()) return apps.get(gridIdx);
        return null;
    }

    private void loadWallpaper() {
        bg.execute(() -> {
            File dir = getExternalFilesDir(null);
            if (dir == null) return;
            File f = null;
            for (String n : new String[] {"wallpaper.jpg", "wallpaper.png", "wallpaper.webp"}) {
                File c = new File(dir, n);
                if (c.isFile()) {
                    f = c;
                    break;
                }
            }
            if (f == null) return;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= W) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            if (b != null) ui.post(() -> wallpaper.setImageBitmap(b));
        });
    }

    private void updateDateTexts() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        int g = h >= 5 && h < 12 ? R.string.good_morning
                : h >= 12 && h < 18 ? R.string.good_afternoon
                : h >= 18 && h < 23 ? R.string.good_evening
                : R.string.good_night;
        greeting.setText(getString(g).toUpperCase(getResources().getConfiguration().getLocales().get(0)));
        // Make room for the usage-timer countdown, which sits in the top-left corner over everything
        boolean timer = ParentalTimer.end(this) > System.currentTimeMillis();
        clockBox.animate().translationY(timer ? dp(34) : 0).setDuration(200).start();
        Locale loc = getResources().getConfiguration().getLocales().get(0);
        String pattern = DateFormat.getBestDateTimePattern(loc, "EEEEdMMMM");
        String d = new SimpleDateFormat(pattern, loc).format(new Date());
        date.setText(d.replace(",", "").toUpperCase(loc));
    }

    private void watchNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
                ui.post(() -> updateNetwork(caps));
            }

            @Override
            public void onLost(Network n) {
                ui.post(() -> updateNetwork(null));
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void updateNetwork(NetworkCapabilities caps) {
        String s;
        boolean wired = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        if (caps == null) {
            s = getString(R.string.offline);
        } else if (wired) {
            s = getString(R.string.ethernet);
        } else {
            s = getString(R.string.wifi);
            WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
            WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            String ssid = info == null ? null : info.getSSID();
            if (ssid != null && !ssid.equals(WifiManager.UNKNOWN_SSID) && !ssid.isEmpty()) {
                s = ssid.replaceAll("^\"|\"$", "");
            }
        }
        pills[PILL_WIFI].setText(s);
        if (wired != pillWired) {
            pillWired = wired;
            Drawable old = pills[PILL_WIFI].getCompoundDrawablesRelative()[0];
            Drawable icon = getDrawable(wired ? R.drawable.ic_ethernet : R.drawable.ic_wifi).mutate();
            icon.setBounds(old.getBounds());
            icon.setTintList(null);
            pills[PILL_WIFI].setCompoundDrawablesRelative(icon, null, null, null);
            applyFocus(false);
        }
    }

    // ---------------------------------------------------------------- focus

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void applyFocus(boolean animate) {
        // Tiles
        Tile now = null;
        float tx = 0, ty = 0;
        if (zone == Zone.GRID && gridIdx < apps.size()) {
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(gridIdx);
            now = vh == null ? null : ((GridAdapter.Holder) vh).tile;
            tx = side + (gridIdx % cols) * (tileW + gap);
            ty = gridTop + (gridIdx / cols) * rowH;
        }
        adapter.focused = zone == Zone.GRID ? gridIdx : -1;
        if (focusedTile != now) {
            if (focusedTile != null) focusedTile.setFocused(false, animate);
            if (now != null) now.setFocused(true, animate);
            focusedTile = now;
        }
        // While rearranging, every tile but the lifted one steps back.
        adapter.editing = editing && zone == Zone.GRID;
        appRow.setLifted(editing && zone == Zone.APPS);
        for (int i = 0; i < grid.getChildCount(); i++) {
            Tile t = (Tile) grid.getChildAt(i);
            t.setDimmed(editing && zone == Zone.GRID && t != now);
        }
        pillRow.animate().alpha(editing ? 0f : 1f).setDuration(animate ? 160 : 0).start();
        // The hint sits where it covers nothing: over the hidden pills, or at the bottom in the grid.
        editHint.setTranslationY(zone == Zone.GRID ? H - dp(80) : 0);

        boolean onTile = zone == Zone.GRID && gridIdx < apps.size();
        if (onTile) {
            if (!animate || !highlightShown) {
                hlX.cancel();
                hlY.cancel();
                highlight.setTranslationX(tx);
                highlight.setTranslationY(ty);
            } else {
                hlX.animateToFinalPosition(tx);
                hlY.animateToFinalPosition(ty);
            }
        }
        if (onTile != highlightShown) {
            highlightShown = onTile;
            highlight.animate().alpha(onTile ? 1f : 0f).setDuration(animate ? 160 : 0).start();
        }

        // Pills: the focused one turns solid white
        for (int i = 0; i < pills.length; i++) {
            boolean f = (zone == Zone.TOP && i == topIdx) || (zone == Zone.MENU && i == menuPill)
                    || (zone == Zone.WEATHER && i == PILL_WEATHER);
            if (pillGlass == null) {
                pillGlass = new Glass.Pane[pills.length];
                for (int k = 0; k < pills.length; k++) {
                    pillGlass[k] = new Glass.Pane(pills[k], null, dp(19), 0x1FFFFFFF, 0x38FFFFFF);
                    glassHosts.add(pills[k]);
                }
            }
            pills[i].setBackground(f ? Ui.glassFocus(dp(19)) : Ui.glassButton(dp(19)));
            pills[i].setTextColor(f ? Ui.FOCUS_CONTENT : 0xFFFFFFFF);
            Drawable pi = pills[i].getCompoundDrawablesRelative()[0];
            // the weather pill's sky icon keeps its colours unless focused, then goes dark like the rest
            if (i == PILL_WEATHER && weather.hasData()) pi.setTintList(null);   // sky icon keeps its colours
            else pi.setTint(f ? Ui.FOCUS_CONTENT : 0xFFFFFFFF);
            float s = f ? 1.06f : 1f;
            if (animate) pills[i].animate().scaleX(s).scaleY(s).setDuration(160).start();
            else {
                pills[i].setScaleX(s);
                pills[i].setScaleY(s);
            }
        }

        // Rows: round icons, continue watching, TMDB rows. The glow (shown without the hero
        // backdrop) follows the focused card, else the focused app.
        appRow.setFocus(zone == Zone.APPS ? appsIdx : -1, animate);
        continueRow.setFocus(zone == Zone.CONTINUE ? continueIdx : -1, animate);
        for (int k = 0; k < mediaRows.size(); k++) mediaRows.get(k).setFocus(zone == Zone.MEDIA && k == mediaIdx ? mediaPos[k] : -1, animate);
        if (zone == Zone.CONTINUE) setGlow(continueRow.color(continueIdx), animate);
        else if (appsIdx < apps.size()) setGlow(apps.get(appsIdx).glow, animate);

        // Scroll: the home rows at rest, or the focused TMDB row near the top
        float target = 0;
        if (zone == Zone.MEDIA) {
            // the last rows stop scrolling once the bottom one is fully in view
            int last = 0;
            for (int k = 0; k < mediaRows.size(); k++) if (mediaRows.get(k).count() > 0) last = k;
            boolean tr = mediaRows.get(last).list.equals(Tmdb.TRAILERS);
            int end = mediaTop[last] + dp(MediaRow.heightDp(tr) + 30);
            target = -Math.min(mediaTop[mediaIdx] - dp(64), end - H);
        }
        if (animate) scroll.animateToFinalPosition(target);
        else {
            scroll.cancel();
            content.setTranslationY(target);
        }
        // Apps window: keep the focused row of tiles in view
        int visibleRows = Math.max(1, (H - gridTop - dp(24)) / rowH);
        float gt = zone == Zone.GRID ? -Math.max(0, gridIdx / cols - visibleRows + 1) * rowH : 0;
        if (animate && zone == Zone.GRID) gridScroll.animateToFinalPosition(gt);
        else {
            gridScroll.cancel();
            gridHolder.setTranslationY(gt);
        }
        weather.setFocused(zone == Zone.WEATHER);
        hero.setFocused(zone == Zone.HERO);
        float dimTarget = zone == Zone.MEDIA ? 0.6f : 0f;      // the backdrop steps back behind the rows
        content.animate().alpha(1f).setDuration(animate ? 200 : 0).start();
        dim.animate().alpha(dimTarget).setDuration(animate ? 250 : 0).start();
    }

    private void setGlow(int color, boolean animate) {
        if (color == glowColor) return;
        if (glowAnim != null) glowAnim.cancel();
        if (!animate) {
            glowColor = color;
            glowDrawable.setColors(new int[] {Ui.withAlpha(color, 0x70), 0});
            return;
        }
        glowAnim = ValueAnimator.ofArgb(glowColor, color);
        glowAnim.setDuration(350);
        glowAnim.addUpdateListener(a -> {
            glowColor = (int) a.getAnimatedValue();
            glowDrawable.setColors(new int[] {Ui.withAlpha(glowColor, 0x70), 0});
        });
        glowAnim.start();
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down && e.getRepeatCount() == 0) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && e.getRepeatCount() == 0) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && e.getRepeatCount() == 0)
                    move(k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                ok(e);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down && e.getRepeatCount() == 0) back();
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (down && e.getRepeatCount() == 0 && zone == Zone.CONTINUE && continueIdx < continueRow.count()) {
                    ContinueStore.remove(this, continueRow.item(continueIdx).key);   // Menu: forget this item
                } else if (down && e.getRepeatCount() == 0 && !editing) openAppInfo();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    /** OK on a tile opens it on release, or lifts it when held; OK while lifted drops it. */
    private void ok(KeyEvent e) {
        if (zone == Zone.PANEL) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) panel.ok();
            return;
        }
        boolean onTile = focusedApp() != null;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                if (editing) finishEdit(true);
                else if (onTile) {
                    okHeld = true;
                    ui.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                } else activate();
            } else if (e.isLongPress() && okHeld) {
                ui.removeCallbacks(longPress);
                longPress.run();
            }
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            ui.removeCallbacks(longPress);
            if (okHeld) {
                okHeld = false;
                activate();
            }
        }
    }

    private void move(int dx, int dy) {
        if (zone == Zone.PANEL) {
            panel.move(dx, dy);
            return;
        }
        if (zone == Zone.WEATHER && dx != 0) {
            weather.showDay(weather.day() + dx);   // only the window changes, the rest of the focus stays
            return;
        }
        if (zone == Zone.HERO && dx != 0) {
            hero.show(hero.index() + dx);
            ui.removeCallbacks(heroTick);
            ui.postDelayed(heroTick, Hero.ROTATE_MS);
            return;
        }
        if (zone == Zone.MENU) {
            if (dy != 0) {
                menuIdx = clamp(menuIdx + dy, 0, menuItems.size() - 1);
                paintMenu();
            } else menuSideways(dx);
            return;
        }
        Zone z0 = zone;
        int idx0 = zone == Zone.APPS ? appsIdx : gridIdx;
        navigate(dx, dy);
        if (editing) {
            if (zone != z0) {
                zone = z0; // the lifted app stays in its row / grid
                if (z0 == Zone.APPS) appsIdx = idx0;
                else gridIdx = idx0;
            } else {
                int idx = zone == Zone.APPS ? appsIdx : gridIdx;
                if (idx != idx0) swap(idx0, idx);
            }
        }
        applyFocus(true);
    }

    private void navigate(int dx, int dy) {
        switch (zone) {
            case TOP:
                if (dy > 0) zone = hero.count() > 0 ? Zone.HERO : below(Zone.HERO);
                else if (dx != 0) {
                    int next = topIdx + dx;
                    while (next >= 0 && next < pills.length && pills[next].getVisibility() != View.VISIBLE) next += dx;
                    if (next >= 0 && next < pills.length) topIdx = next;   // hidden pills are skipped
                }
                break;
            case HERO:
                if (dy < 0) zone = Zone.TOP;
                else if (dy > 0) zone = below(Zone.HERO);
                break;
            case APPS:
                if (dx != 0) appsIdx = clamp(appsIdx + dx, 0, apps.size() - 1);
                else if (dy < 0) zone = hero.count() > 0 ? Zone.HERO : Zone.TOP;
                else zone = below(Zone.APPS);
                break;
            case CONTINUE:
                if (dx != 0) continueIdx = clamp(continueIdx + dx, 0, continueRow.count() - 1);
                else if (dy < 0) zone = !apps.isEmpty() ? Zone.APPS : hero.count() > 0 ? Zone.HERO : Zone.TOP;
                else zone = below(Zone.CONTINUE);
                break;
            case MEDIA:
                if (dx != 0) {
                    MediaRow r = mediaRows.get(mediaIdx);
                    mediaPos[mediaIdx] = clamp(mediaPos[mediaIdx] + dx, 0, r.count() - 1);
                } else if (dy > 0) {
                    for (int k = mediaIdx + 1; k < mediaRows.size(); k++) {
                        if (mediaRows.get(k).count() > 0) {
                            mediaIdx = k;
                            break;
                        }
                    }
                } else {
                    int prev = -1;
                    for (int k = mediaIdx - 1; k >= 0 && prev < 0; k--) if (mediaRows.get(k).count() > 0) prev = k;
                    if (prev >= 0) mediaIdx = prev;
                    else zone = continueRow.count() > 0 ? Zone.CONTINUE : !apps.isEmpty() ? Zone.APPS : Zone.TOP;
                }
                break;
            case WEATHER:
                // Left/Right walk through today and the next five days.
                if (dx != 0) weather.showDay(weather.day() + dx);
                else if (dy < 0) closeWeatherWindow();
                break;
            case GRID: {
                int col = gridIdx % cols, row = gridIdx / cols;
                if (dx != 0) {
                    int c = col + dx, i = row * cols + c;
                    if (c >= 0 && c < cols && i < apps.size()) gridIdx = i;
                } else if (dy < 0) {
                    if (row > 0) gridIdx -= cols;
                } else if ((row + 1) * cols < apps.size()) {
                    gridIdx = Math.min((row + 1) * cols + col, apps.size() - 1);
                }
                break;
            }
            default:
                break;
        }
    }

    /** The next row down from z that has something in it (or z itself when nothing is below). */
    private Zone below(Zone z) {
        if (z == Zone.HERO && !apps.isEmpty()) return Zone.APPS;
        if ((z == Zone.HERO || z == Zone.APPS) && continueRow.count() > 0) return Zone.CONTINUE;
        for (int k = 0; k < mediaRows.size(); k++) {
            if (mediaRows.get(k).count() > 0) {
                mediaIdx = k;
                return Zone.MEDIA;
            }
        }
        return z;
    }

    // ---------------------------------------------------------------- rearranging

    /** The lifted app trades places with its neighbour, in the round icons and the grid alike. */
    private void swap(int a, int b) {
        java.util.Collections.swap(apps, a, b);
        appRow.swap(a, b);
        adapter.notifyItemChanged(a);
        adapter.notifyItemChanged(b);
        // The tile under focus now shows the lifted app; refresh its focus state.
        focusedTile = null;
        grid.post(() -> applyFocus(false));
    }

    private void startEdit() {
        if (focusedApp() == null) return;
        editing = true;
        editBackup.clear();
        editBackup.addAll(apps);
        editZone0 = zone;
        editIdx0 = zone == Zone.APPS ? appsIdx : gridIdx;
        highlight.setBackground(Ui.lifted(dp(10), dp(3)));
        editHint.setAlpha(0f);
        editHint.setVisibility(View.VISIBLE);
        editHint.animate().alpha(1f).setDuration(160).start();
        highlight.animate().scaleX(Tile.FOCUS_SCALE + 0.05f).scaleY(Tile.FOCUS_SCALE + 0.05f)
                .setDuration(160).start();
        applyFocus(true);
    }

    /** Drop the lifted tile: keep the new order (and save it) or put everything back. */
    private void finishEdit(boolean keep) {
        if (!editing) return;
        editing = false;
        if (!keep) {
            apps.clear();
            apps.addAll(editBackup);
            appRow.bind(apps);
            adapter.notifyDataSetChanged();
            zone = editZone0;
            if (zone == Zone.APPS) appsIdx = editIdx0;
            else gridIdx = editIdx0;
        } else {
            List<String> order = new ArrayList<>();
            for (Apps.Entry e : apps) order.add(e.pkg);
            bg.execute(() -> Apps.saveOrder(this, order));
        }
        highlight.setBackground(Ui.glass(dp(10), dp(1.5f)));
        highlight.animate().scaleX(Tile.FOCUS_SCALE).scaleY(Tile.FOCUS_SCALE).setDuration(160).start();
        editHint.setVisibility(View.GONE);
        focusedTile = null;
        grid.post(() -> applyFocus(false));
        applyFocus(true);
    }

    private void back() {
        if (zone == Zone.PANEL) {
            if (!panel.back()) closePanel();
        } else if (editing) finishEdit(false);
        else if (zone == Zone.MENU) closeMenu();
        else if (zone == Zone.WEATHER) closeWeatherWindow();
        else if (zone == Zone.GRID) closeAppsWindow();
        else if (zone == Zone.TOP || zone == Zone.CONTINUE || zone == Zone.HERO || zone == Zone.MEDIA) {
            zone = apps.isEmpty() ? zone : Zone.APPS;
            applyFocus(true);
        }
    }

    private void activate() {
        switch (zone) {
            case TOP:
                if (topIdx == PILL_SEARCH) start(new Intent(this, SearchActivity.class), getString(R.string.search));
                else if (topIdx == PILL_WEATHER) openWeatherWindow();
                else if (topIdx == PILL_INPUTS) openInputsMenu();
                else if (topIdx == PILL_WIFI) openNetworkMenu();
                else openSettingsMenu();
                break;
            case MENU:
                if (menuIdx < menuItems.size()) activateMenuItem();
                break;
            case WEATHER:
                start(new Intent(this, RadarActivity.class), getString(R.string.radar));
                break;
            case CONTINUE:
                if (continueIdx < continueRow.count()) openContinue(continueRow.item(continueIdx));
                break;
            case HERO:
                // look the title up in the apps' search, straight away
                if (hero.current() != null) start(new Intent(this, SearchActivity.class)
                        .putExtra(android.app.SearchManager.QUERY, hero.current().title), getString(R.string.search));
                break;
            case MEDIA: {
                Tmdb.Item it = mediaRows.get(mediaIdx).item(mediaPos[mediaIdx]);
                if (it == null) break;
                if (it.video != null) start(Tmdb.trailer(this, it), it.title);   // trailers play in SmartTube
                else start(new Intent(this, SearchActivity.class)
                    .putExtra(android.app.SearchManager.QUERY,
                        it.searchTitle == null || it.searchTitle.isEmpty() ? it.title : it.searchTitle),
                    getString(R.string.search));
                break;
            }
            default:
                Apps.Entry a = focusedApp();
                if (a != null) start(a.launch, a.label);
        }
    }

    // ---------------------------------------------------------------- apps window

    private void openAppsWindow() {
        if (apps.isEmpty()) return;
        if (editing) finishEdit(false);
        gridIdx = zone == Zone.APPS ? appsIdx : clamp(gridIdx, 0, apps.size() - 1);
        appsWin.setBackground(frosted(0, 0, W, H, 0, 0xD10E0E10));
        zone = Zone.GRID;
        focusedTile = null;
        appsWin.setVisibility(View.VISIBLE);
        appsWin.setAlpha(0f);
        appsWin.animate().alpha(1f).setDuration(180).start();
        grid.post(() -> applyFocus(false));
        applyFocus(false);
    }

    private void closeAppsWindow() {
        if (editing) finishEdit(false);
        appsWin.setVisibility(View.GONE);
        appsIdx = clamp(gridIdx, 0, Math.max(0, apps.size() - 1));
        zone = apps.isEmpty() ? Zone.TOP : Zone.APPS;
        applyFocus(true);
    }

    private void openAppInfo() {
        Apps.Entry a = focusedApp();
        if (a == null) return;
        start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)), a.label);
    }

    private void start(Intent i, String what) {
        try {
            if (i.getComponent() != null && (TimerActivity.class.getName().equals(i.getComponent().getClassName())
                    || SearchActivity.class.getName().equals(i.getComponent().getClassName()))) {
                // translucent window: hand it a blurred picture of the home screen for its card
                Glass.Backdrop b = new Glass.Backdrop(W, H);
                b.update(c -> root.draw(c));
                TimerActivity.backdrop = b;
            }
            startActivity(i);
            away = true;
        } catch (ActivityNotFoundException | SecurityException ex) {
            Toast.makeText(this, getString(R.string.cannot_open, what), Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- dropdown menus

    /**
     * A dropdown row: icon + label, and on the right a status (green dot), a "›", a switch
     * (weather) or ‹ value › (columns). Opens an intent or runs an action on OK.
     */
    private static final class MenuItem {
        final String label;
        final Intent intent;
        final boolean columns;
        final Runnable action;
        boolean weatherToggle, dividerBefore, connected;
        int icon;
        String status;

        MenuItem(String label, Intent intent, boolean columns) {
            this(label, intent, columns, null);
        }

        MenuItem(String label, Intent intent, boolean columns, Runnable action) {
            this.label = label;
            this.intent = intent;
            this.columns = columns;
            this.action = action;
        }

        MenuItem icon(int res) {
            icon = res;
            return this;
        }

        MenuItem divider() {
            dividerBefore = true;
            return this;
        }
    }

    private final List<View> menuRows = new ArrayList<>();
    private Controls.Toggle weatherSwitch;

    private void openInputsMenu() {
        menuItems.clear();
        int lastType = -1;
        for (Inputs.Source s : Inputs.list(this)) {
            MenuItem m = new MenuItem(s.label, s.intent, false)
                    .icon(s.type == Inputs.HDMI ? R.drawable.ic_hdmi : s.type == Inputs.AV ? R.drawable.ic_av : R.drawable.ic_tv);
            if (lastType != -1 && s.type != lastType) m.divider();
            if (s.connected) {
                m.connected = true;
                m.status = getString(R.string.connected);
            }
            lastType = s.type;
            menuItems.add(m);
        }
        menuItems.add(new MenuItem(getString(R.string.cameras), new Intent(this, CamerasActivity.class), false)
                .icon(R.drawable.ic_camera).divider());
        showMenu(PILL_INPUTS);
    }

    private void openSettingsMenu() {
        menuItems.clear();
        menuItems.add(new MenuItem(getString(R.string.tv_settings), new Intent(Settings.ACTION_SETTINGS), false)
                .icon(R.drawable.ic_settings));
        menuItems.add(new MenuItem(getString(R.string.picture), null, false, this::openPicturePanel)
                .icon(R.drawable.ic_picture).divider());
        menuItems.add(new MenuItem(getString(R.string.timer_title), null, false,
                () -> start(new Intent(this, TimerActivity.class), getString(R.string.timer_title)))
                .icon(R.drawable.ic_timer));
        MenuItem wx = new MenuItem(getString(R.string.weather), null, false).icon(R.drawable.ic_weather).divider();
        wx.weatherToggle = true;
        menuItems.add(wx);
        menuItems.add(new MenuItem(getString(R.string.columns), null, true).icon(R.drawable.ic_columns));
        pendingCols = cols;
        showMenu(PILL_SETTINGS);
    }

    /** Ethernet / Wi-Fi pill: connection status and a way into the network settings. */
    private void openNetworkMenu() {
        menuItems.clear();
        Intent net = new Intent(Settings.ACTION_WIFI_SETTINGS);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network n = cm == null ? null : cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        String ip = null;
        if (n != null && cm.getLinkProperties(n) != null) {
            for (android.net.LinkAddress la : cm.getLinkProperties(n).getLinkAddresses()) {
                if (la.getAddress() instanceof java.net.Inet4Address) ip = la.getAddress().getHostAddress();
            }
        }
        boolean wired = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        boolean wifi = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        MenuItem eth = new MenuItem(getString(R.string.ethernet), net, false).icon(R.drawable.ic_ethernet);
        if (wired) {
            eth.connected = true;
            eth.status = ip != null ? ip : getString(R.string.connected);
        }
        menuItems.add(eth);
        MenuItem wf = new MenuItem(getString(R.string.wifi), net, false).icon(R.drawable.ic_wifi);
        if (wifi) {
            wf.connected = true;
            wf.status = pills[PILL_WIFI].getText().toString();
        }
        menuItems.add(wf);
        menuItems.add(new MenuItem(getString(R.string.network_settings), net, false).icon(R.drawable.ic_settings).divider());
        showMenu(PILL_WIFI);
    }

    private String columnsValue() {
        return "‹ " + pendingCols + " ›";
    }

    private TextView menuText(float sp, int weight, int color) {
        TextView t = text(sp, weight, color);
        t.setSingleLine(true);
        return t;
    }

    private void showMenu(int pill) {
        if (menuItems.isEmpty()) return;
        menuPill = pill;
        menu.removeAllViews();
        menuRows.clear();
        weatherSwitch = null;
        for (MenuItem item : menuItems) {
            if (item.dividerBefore && !menuRows.isEmpty()) {
                View line = new View(this);
                line.setBackgroundColor(0x1FFFFFFF);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                dlp.setMargins(dp(14), dp(5), dp(14), dp(5));
                menu.addView(line, dlp);
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), 0, dp(14), 0);
            ImageView icon = new ImageView(this);
            if (item.icon != 0) icon.setImageDrawable(getDrawable(item.icon).mutate());
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(18), dp(18));
            ilp.rightMargin = dp(12);
            row.addView(icon, ilp);
            TextView label = menuText(15, 500, 0xFFFFFFFF);
            label.setText(item.label);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (item.columns) {
                columnsValue = menuText(15, 500, 0xFFFFFFFF);
                columnsValue.setText(columnsValue());
                row.addView(columnsValue);
            } else if (item.weatherToggle) {
                weatherSwitch = new Controls.Toggle(this);
                weatherSwitch.setChecked(Weather.enabled(this), false);
                row.addView(weatherSwitch);
            } else if (item.status != null) {
                View dot = new View(this);
                dot.setBackground(Ui.rounded(item.connected ? 0xFF34C759 : 0x66FFFFFF, dp(4), 0, 0));
                LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7), dp(7));
                dotLp.rightMargin = dp(7);
                row.addView(dot, dotLp);
                TextView st = menuText(12, 400, 0x80FFFFFF);
                st.setText(item.status);
                row.addView(st);
            } else if (item.action != null) {
                TextView more = menuText(15, 400, 0xB3FFFFFF);
                more.setText("›");
                row.addView(more);
            }
            menu.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
            menuRows.add(row);
        }
        // Under the pill, kept inside the screen for the pills on the right.
        int[] loc = new int[2];
        int[] rootLoc = new int[2];
        pills[pill].getLocationInWindow(loc);
        root.getLocationInWindow(rootLoc);
        int x = loc[0] - rootLoc[0];
        int menuW = menu.getLayoutParams().width;
        menu.setTranslationX(Math.min(x, W - side - menuW));
        menuIdx = 0;
        zone = Zone.MENU;
        paintMenu();
        menu.setVisibility(View.VISIBLE);
        menu.setAlpha(0f);                      // laid out but not drawn: the snapshot skips it
        menu.setScaleY(0.92f);
        menu.setPivotY(0);
        applyFocus(true);
        menu.post(() -> {
            menu.setBackground(frosted(menu.getX(), menu.getY(), menu.getWidth(), menu.getHeight(), dp(20), 0x8C1C1C1E));
            menu.animate().alpha(1f).scaleY(1f).setDuration(160).start();
        });
    }

    /** Focused row: solid white with dark text and icon, like the pills. */
    private void paintMenu() {
        for (int i = 0; i < menuRows.size(); i++) {
            LinearLayout row = (LinearLayout) menuRows.get(i);
            boolean f = i == menuIdx;
            row.setBackground(f ? Ui.glassFocus(dp(12)) : null);
            int fg = f ? Ui.FOCUS_CONTENT : 0xFFFFFFFF;
            for (int k = 0; k < row.getChildCount(); k++) {
                View v = row.getChildAt(k);
                if (v instanceof ImageView && ((ImageView) v).getDrawable() != null) {
                    ((ImageView) v).getDrawable().setTint(fg);
                } else if (v instanceof TextView) {
                    TextView t = (TextView) v;
                    boolean secondary = t.getTextSize() < dp(14);
                    t.setTextColor(secondary ? (f ? 0xB3FFFFFF : 0x80FFFFFF) : fg);
                }
            }
        }
    }

    private void closeMenu() {
        menu.setVisibility(View.GONE);
        zone = Zone.TOP;
        topIdx = menuPill;
        applyFocus(true);
    }

    /** Left/Right in a dropdown: columns and weather change in place, "›" rows open, others close. */
    private void menuSideways(int dx) {
        MenuItem item = menuItems.get(menuIdx);
        if (item.weatherToggle) {
            if (Weather.enabled(this) != dx > 0) toggleWeather();
            return;
        }
        if (item.action != null && dx > 0) {
            activateMenuItem();
            return;
        }
        if (!item.columns) {
            closeMenu();
            return;
        }
        pendingCols = clamp(pendingCols + dx, Apps.MIN_COLS, Apps.MAX_COLS);
        columnsValue.setText(columnsValue());
    }

    private void activateMenuItem() {
        MenuItem item = menuItems.get(menuIdx);
        if (item.weatherToggle) {
            toggleWeather(); // stays in the menu, like the columns row
            return;
        }
        closeMenu();
        if (item.action != null) {
            item.action.run();
        } else if (!item.columns) {
            start(item.intent, item.label);
        } else if (pendingCols != cols) {
            Apps.setCols(this, pendingCols);
            recreate(); // rebuilds the layout for the new tile size
        }
    }

    // ---------------------------------------------------------------- picture panel

    private void openPicturePanel() {
        zone = Zone.PANEL;
        applyFocus(true);
        int pw = panel.view.getLayoutParams().width;
        panel.view.setBackground(frosted(W - pw, 0, pw, H, dp(28), 0xB3161618));
        panel.open();
    }

    private void closePanel() {
        panel.close();
        if (zone == Zone.PANEL) {
            zone = Zone.TOP;
            topIdx = PILL_SETTINGS;
            applyFocus(true);
        }
    }

    // ---------------------------------------------------------------- weather

    private final Runnable weatherTick = this::startWeather;

    /** Shows the cached forecast, refreshes it when older than 30 minutes, and re-arms. */
    private void startWeather() {
        ui.removeCallbacks(weatherTick);
        if (!Weather.enabled(this)) {
            pills[PILL_WEATHER].setVisibility(View.GONE);
            if (zone == Zone.WEATHER) closeWeatherWindow();
            if (topIdx == PILL_WEATHER) topIdx = PILL_INPUTS;
            applyFocus(false);
            return;
        }
        if (pills[PILL_WEATHER].getVisibility() != View.VISIBLE) {
            pills[PILL_WEATHER].setVisibility(View.VISIBLE);
            applyFocus(false);
        }
        Weather.Data cached = Weather.cached(this);
        if (cached != null) showWeather(cached);
        if (cached == null || Weather.stale(this) || cached.dayDate.length < Weather.DAYS
                || !Weather.prefs(this).getString("forecast", "").contains("wind_direction_10m")) {
            bg.execute(() -> {
                Weather.Data d = Weather.fetch(this);
                if (d != null) ui.post(() -> showWeather(d));
            });
        }
        // Keep the radar images warm on disk so the Radar button opens instantly. Its own thread:
        // a full refresh takes ~20 s and must not hold up apps or the forecast.
        if (!RadarCache.fresh(this) && radarBusy.compareAndSet(false, true)) {
            radarBg.execute(() -> {
                try {
                    RadarCache.update(this, RadarCache.view(this), null);
                } finally {
                    radarBusy.set(false);
                }
            });
        }
        ui.postDelayed(weatherTick, RadarCache.REFRESH_MS); // forecast itself refreshes every 30 min
    }

    private void showWeather(Weather.Data d) {
        if (!Weather.enabled(this)) return;
        weather.bind(d);
        // pill: today's sky + current temperature
        int sz = dp(20);
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(sz, sz, android.graphics.Bitmap.Config.ARGB_8888);
        WeatherIconView iv = new WeatherIconView(this);
        iv.set(Weather.kind(d.code), d.day);
        iv.layout(0, 0, sz, sz);
        iv.draw(new android.graphics.Canvas(bm));
        Drawable icon = new android.graphics.drawable.BitmapDrawable(getResources(), bm);
        icon.setBounds(0, 0, sz, sz);
        pills[PILL_WEATHER].setCompoundDrawablesRelative(icon, null, null, null);
        pills[PILL_WEATHER].setText(d.temp + "°");
    }

    private int firstPill() {
        return pills[PILL_WEATHER].getVisibility() == View.VISIBLE ? PILL_WEATHER : PILL_INPUTS;
    }

    /** The forecast window opens centred under the top bar, like a menu, frosted underneath. */
    private void openWeatherWindow() {
        if (!weather.hasData()) return;
        int w = weather.getLayoutParams().width;
        weather.setTranslationX((W - w) / 2f);    // centred on screen
        zone = Zone.WEATHER;
        weather.setVisibility(View.VISIBLE);
        weather.setAlpha(0f);
        weather.setScaleY(0.94f);
        weather.setPivotY(0);
        applyFocus(true);
        weather.post(() -> {
            weather.setPanelBackground(frosted(weather.getX(), weather.getY(), weather.getWidth(), weather.getHeight(), dp(24), 0x8C1C1C1E));
            weather.animate().alpha(1f).scaleY(1f).setDuration(160).start();
        });
    }

    private void closeWeatherWindow() {
        weather.setVisibility(View.GONE);
        zone = Zone.TOP;
        topIdx = PILL_WEATHER;
        applyFocus(true);
    }

    private void toggleWeather() {
        Weather.setEnabled(this, !Weather.enabled(this));
        if (weatherSwitch != null) weatherSwitch.setChecked(Weather.enabled(this), true);
        startWeather();
    }

    private boolean weatherFocusable() {
        return weather.getVisibility() == View.VISIBLE;
    }

    // ---------------------------------------------------------------- glass

    /** Frosted background for a menu or panel: a blurred snapshot of just the area it covers. */
    private android.graphics.drawable.Drawable frosted(float x, float y, int w, int h, float radius, int tint) {
        return new Glass.Frosted(Glass.snapshot(root, x, y, w, h), radius, tint, 0x40FFFFFF);
    }

    // ---------------------------------------------------------------- continue watching

    /**
     * Vertical layout of the home rows: continue watching at the bottom (when it has cards), the
     * round icons above it, the hero's dots and text above those; TMDB rows below the screen.
     */
    private void layoutHome() {
        boolean cont = continueRow.count() > 0;
        appsTop = (cont ? H - dp(14) - contH - dp(6) : H - dp(24)) - dp(AppRow.HEIGHT);
        ((FrameLayout.LayoutParams) appRow.getLayoutParams()).topMargin = appsTop;
        int dotsTop = appsTop - dp(2) - dp(HERO_DOTS_H);
        ((FrameLayout.LayoutParams) hero.dotsRow.getLayoutParams()).topMargin = dotsTop;
        ((FrameLayout.LayoutParams) hero.text.getLayoutParams()).topMargin = dotsTop - dp(4) - dp(HERO_TEXT_H);   // text ends above the dots line
        int y = H + dp(28);
        for (int k = 0; k < mediaRows.size(); k++) {
            MediaRow r = mediaRows.get(k);
            mediaTop[k] = y;
            ((FrameLayout.LayoutParams) r.getLayoutParams()).topMargin = y;
            if (r.count() > 0) y += dp(MediaRow.heightDp(r.list.equals(Tmdb.TRAILERS)) + 20);
        }
        ViewGroup.LayoutParams clp = content.getLayoutParams();
        clp.height = y + H;
        content.setLayoutParams(clp);
        appRow.requestLayout();
        hero.dotsRow.requestLayout();
        hero.text.requestLayout();
    }

    /** Cached TMDB lists at once, then fresh ones when they are a few hours old. */
    private void loadTmdb() {
        if (!Tmdb.enabled(this) || !tmdbBusy.compareAndSet(false, true)) return;
        heroBg.execute(() -> {
            try {
                if (hero.count() == 0) {
                    for (String list : Tmdb.LISTS) {
                        List<Tmdb.Item> cached = Tmdb.cached(this, list);
                        if (!cached.isEmpty()) ui.post(() -> setTmdb(list, cached));
                    }
                }
                if (Tmdb.stale(this)) Tmdb.refresh(this, (list, items) -> ui.post(() -> setTmdb(list, items)));
            } finally {
                tmdbBusy.set(false);
            }
        });
    }

    private void setTmdb(String list, List<Tmdb.Item> items) {
        if (list.equals(Tmdb.HERO)) {
            hero.bind(items);
            glow.setVisibility(hero.count() > 0 ? View.GONE : View.VISIBLE);   // the backdrop replaces the glow
            if (hero.count() == 0 && zone == Zone.HERO) zone = Zone.APPS;
        } else {
            for (int k = 0; k < mediaRows.size(); k++) {
                MediaRow r = mediaRows.get(k);
                if (!r.list.equals(list)) continue;
                r.bind(items);
                mediaPos[k] = clamp(mediaPos[k], 0, Math.max(0, items.size() - 1));
            }
            layoutHome();
        }
        applyFocus(false);
    }

    private static void deleteTree(java.io.File f) {
        java.io.File[] kids = f.listFiles();
        if (kids != null) for (java.io.File k : kids) deleteTree(k);
        f.delete();
    }

    private void loadContinue() {
        bg.execute(() -> {
            List<ContinueStore.Item> list = ContinueStore.load(this);
            ui.post(() -> {
                continueRow.bind(list);
                continueIdx = clamp(continueIdx, 0, Math.max(0, list.size() - 1));
                if (list.isEmpty() && zone == Zone.CONTINUE) zone = Zone.APPS;
                layoutHome();
                applyFocus(false);
            });
        });
    }

    /** Back into the item via the app's own session link; after a reboot, just the app. */
    private void openContinue(ContinueStore.Item it) {
        android.app.PendingIntent link = MediaWatcher.links.get(it.key);
        if (link != null) {
            try {
                android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                if (Build.VERSION.SDK_INT >= 34) o.setPendingIntentBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                link.send(this, 0, null, null, null, null, o.toBundle());
                away = true;
                return;
            } catch (Exception ignored) {
            }
        }
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(it.pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(it.pkg);
        if (i != null) start(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), it.title);
    }
}
