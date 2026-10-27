package com.clean.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Precipitation radar around the TV's location: RainViewer frames (last 2 hours, every 10 min)
 * over Esri's dark grey map, with place names above the rain, ~360 x 200 km on a Full HD screen.
 * Opens from the disk cache the home screen keeps warm ({@link RadarCache}) and fetches only
 * what is missing. Bottom bar: zoom in, zoom out, play/stop, then the timeline. Left/Right walk
 * along all of them; landing on a time stops playback and shows that frame. OK presses a button
 * (on a time: plays from there), Back closes. Two zoom levels, both from the same download.
 */
public class RadarActivity extends Activity {

    private static final long FRAME_MS = 600, LAST_FRAME_HOLD_MS = 1800;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Typeface font;
    private RadarCache.View view;
    private int W, H;
    private final Bitmap[] bases = new Bitmap[RadarCache.LEVELS], labelMaps = new Bitmap[RadarCache.LEVELS];
    private int level;                     // 0 = normal view .. 3 = 8x wider
    private final List<Bitmap> farFrames = new ArrayList<>();   // serve levels 2 and 3
    private final List<Bitmap> frames = new ArrayList<>();
    private final List<Long> times = new ArrayList<>();
    private int frame;
    private boolean playing, loaded;
    private static final int ZOOM_IN = 0, ZOOM_OUT = 1, PLAY = 2, FIRST_FRAME = 3;
    private int focus = PLAY;              // a button, or FIRST_FRAME + frame index
    private MapView map;
    private TextView timeLabel, status;
    private final FrameLayout[] buttons = new FrameLayout[3];
    private final ImageView[] icons = new ImageView[3];
    private LinearLayout timeline, head, barView;
    private Thread loader;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!playing || frames.isEmpty()) return;
            frame = (frame + 1) % frames.size();
            showFrame();
            ui.postDelayed(this, frame == frames.size() - 1 ? LAST_FRAME_HOLD_MS : FRAME_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        font = getResources().getFont(R.font.outfit);
        view = RadarCache.view(this);
        W = view.w;
        H = view.h;
        buildUi();
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (loader != null) loader.interrupt();
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
        return t;
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E0E10);
        map = new MapView();
        root.addView(map, new FrameLayout.LayoutParams(W, H));

        head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(22), dp(14), dp(22), dp(14));
        head.setBackground(Ui.rounded(0x8C161618, dp(20), dp(1), 0x38FFFFFF));
        android.content.SharedPreferences wp = Weather.prefs(this);
        LinearLayout place = new LinearLayout(this);
        place.setGravity(Gravity.BOTTOM);
        TextView title = text(20, 500, 0xFFFFFFFF);
        String city = wp.getString("city", "");
        title.setText(city.isEmpty() ? getString(R.string.radar_title) : city);
        place.addView(title);
        float lat = wp.getFloat("lat", 0), lon = wp.getFloat("lon", 0);
        TextView coords = text(13, 500, 0x99FFFFFF);
        coords.setText(String.format(Locale.getDefault(), "%.2f° %s  %.2f° %s",
                Math.abs(lat), lat >= 0 ? "N" : "S", Math.abs(lon), lon >= 0 ? "E" : "W"));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.leftMargin = dp(12);
        clp.bottomMargin = dp(2);
        place.addView(coords, clp);
        head.addView(place, new LinearLayout.LayoutParams(-2, -2)); // own width, not the time line's
        timeLabel = text(24, 300, 0xFFFFFFFF);
        timeLabel.setText("—");
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = dp(6);
        head.addView(timeLabel, tl);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        hlp.setMargins(dp(28), dp(24), 0, 0);
        root.addView(head, hlp);

        status = text(16, 400, 0xE6FFFFFF);
        status.setPadding(dp(16), dp(8), dp(16), dp(8));
        status.setBackground(Ui.rounded(0x8C161618, dp(16), dp(1), 0x38FFFFFF));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        slp.topMargin = dp(28);
        root.addView(status, slp);
        setStatus(getString(R.string.radar_loading));

        // Bottom bar: icon buttons (drawn, so they sit exactly centred) + timeline
        LinearLayout bar = new LinearLayout(this);
        barView = bar;
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(10), dp(18), dp(10));
        bar.setBackground(Ui.rounded(0x8C161618, dp(24), dp(1), 0x38FFFFFF));
        int[] res = {R.drawable.ic_plus, R.drawable.ic_minus, R.drawable.ic_play};
        for (int i = 0; i < 3; i++) {
            FrameLayout b = new FrameLayout(this);
            ImageView ic = new ImageView(this);
            ic.setImageDrawable(getDrawable(res[i]).mutate());
            b.addView(ic, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(i == PLAY ? 64 : 48), dp(44));
            blp.rightMargin = dp(6);
            bar.addView(b, blp);
            buttons[i] = b;
            icons[i] = ic;
        }
        timeline = new LinearLayout(this);
        timeline.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        tlp.leftMargin = dp(12);
        bar.addView(timeline, tlp);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(W - 2 * dp(28), FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        blp.bottomMargin = dp(22);
        root.addView(bar, blp);
        setContentView(root);
        paintButtons();
    }

    private void paintButtons() {
        icons[PLAY].setImageDrawable(getDrawable(playing ? R.drawable.ic_stop : R.drawable.ic_play).mutate());
        for (int i = 0; i < 3; i++) {
            boolean f = i == focus;
            buttons[i].setBackground(f ? Ui.glassFocus(dp(22)) : Ui.glassButton(dp(22)));
            Drawable d = icons[i].getDrawable();
            d.setTint(f ? Ui.FOCUS_CONTENT : 0xFFFFFFFF);
            // the zoom step that is already active is dimmed
            boolean inactive = (i == ZOOM_IN && level == 0) || (i == ZOOM_OUT && level == RadarCache.LEVELS - 1);
            buttons[i].setAlpha(inactive && !f ? 0.45f : 1f);
        }
        paintTimeline();
    }

    private void buildTimeline() {
        timeline.removeAllViews();
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.getDefault());
        for (int i = 0; i < times.size(); i++) {
            TextView t = text(13, 500, 0x99FFFFFF);
            t.setGravity(Gravity.CENTER);
            t.setText(i == times.size() - 1 ? getString(R.string.radar_now) : f.format(new Date(times.get(i) * 1000)));
            timeline.addView(t, new LinearLayout.LayoutParams(0, dp(36), 1f));
        }
    }

    /** The status pill only shows while there is something to say. */
    private void setStatus(String text) {
        status.setText(text);
        status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showFrame() {
        if (frames.isEmpty()) return;
        map.glassDirty = true;
        map.invalidate();
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.getDefault());
        // the newest frame is just "Now"; older ones show their time
        timeLabel.setText(frame == frames.size() - 1 ? getString(R.string.radar_now) : f.format(new Date(times.get(frame) * 1000)));
        paintTimeline();
    }

    /** Current frame: glass chip. A time with the remote's focus: solid white, like the buttons. */
    private void paintTimeline() {
        for (int i = 0; i < timeline.getChildCount(); i++) {
            TextView v = (TextView) timeline.getChildAt(i);
            boolean cur = i == frame, focused = focus == FIRST_FRAME + i;
            v.setBackground(focused ? Ui.glassFocus(dp(12)) : cur ? Ui.glass(dp(12), dp(1)) : null);
            v.setTextColor(focused ? Ui.FOCUS_CONTENT : cur ? 0xFFFFFFFF : i < frame ? 0xB3FFFFFF : 0x66FFFFFF);
            v.setFontVariationSettings("'wght' " + (cur || focused ? 650 : 500));
        }
    }

    // ---------------------------------------------------------------- data

    /** Adds a frame in time order (frames arrive newest first from the network). */
    private void addFrame(long t, Bitmap b, Bitmap far) {
        int i = 0;
        while (i < times.size() && times.get(i) < t) i++;
        if (i < times.size() && times.get(i) == t) {
            if (farFrames.get(i) == null) farFrames.set(i, far);
            return;
        }
        times.add(i, t);
        frames.add(i, b);
        farFrames.add(i, far);
    }

    private void loadMaps() {
        for (int lv = 0; lv < RadarCache.LEVELS; lv++) {
            final int k = lv;
            Bitmap b = bases[k] != null ? null : RadarCache.load(RadarCache.baseFile(this, view, k));
            Bitmap l = labelMaps[k] != null ? null : RadarCache.load(RadarCache.labelsFile(this, view, k));
            ui.post(() -> {
                if (b != null) bases[k] = b;
                if (l != null) labelMaps[k] = l;
                map.glassDirty = true;
                map.invalidate();
            });
        }
    }

    private void load() {
        loader = new Thread(() -> {
            // 1. Whatever the home screen already cached: usually everything.
            boolean haveMaps = RadarCache.baseFile(this, view, RadarCache.LEVELS - 1).isFile();
            loadMaps();
            List<Long> cached = RadarCache.cachedTimes(this, view);
            List<Bitmap> imgs = new ArrayList<>(), far = new ArrayList<>();
            for (long t : cached) {
                imgs.add(RadarCache.loadFrame(this, view, t));
                far.add(RadarCache.loadFarFrame(this, view, t));
            }
            ui.post(() -> {
                for (int i = 0; i < cached.size(); i++) if (imgs.get(i) != null) addFrame(cached.get(i), imgs.get(i), far.get(i));
                frame = Math.max(0, frames.size() - 1);
                showFrame();
                map.glassDirty = true;
                map.invalidate();
            });
            if (Thread.interrupted()) return;
            // 2. Anything newer or missing.
            boolean farMissing = far.contains(null);
            if (!RadarCache.fresh(this) || cached.isEmpty() || !haveMaps || farMissing) {
                RadarCache.update(this, view, t -> {
                    Bitmap f = RadarCache.loadFrame(this, view, t);
                    Bitmap g = RadarCache.loadFarFrame(this, view, t);
                    loadMaps();
                    ui.post(() -> {
                        if (f == null) return;
                        addFrame(t, f, g);
                        if (!playing) {
                            frame = frames.size() - 1;
                            showFrame();
                        }
                        setStatus(getString(R.string.radar_history_n, frames.size(), Math.max(frames.size(), 13)));
                    });
                });
            }
            loadMaps();
            ui.post(() -> {
                // Drop frames the update aged out, then finish the timeline.
                List<Long> now = RadarCache.cachedTimes(this, view);
                for (int i = times.size() - 1; i >= 0; i--) {
                    if (!now.contains(times.get(i))) {
                        times.remove(i);
                        frames.remove(i);
                        farFrames.remove(i);
                    }
                }
                setStatus(frames.isEmpty() ? getString(R.string.radar_failed) : "");
                loaded = !frames.isEmpty();
                buildTimeline();
                if (!playing) frame = Math.max(0, frames.size() - 1);
                showFrame();
                map.invalidate();
            });
        });
        loader.start();
    }

    // ---------------------------------------------------------------- keys

    private void setPlaying(boolean on) {
        playing = on && loaded;
        ui.removeCallbacks(tick);
        if (playing) ui.postDelayed(tick, FRAME_MS);
        paintButtons();
    }

    private void setLevel(int lv) {
        lv = Math.max(0, Math.min(RadarCache.LEVELS - 1, lv));
        if (lv == level) return;
        level = lv;
        map.glassDirty = true;
        map.invalidate();
        paintButtons();
    }

    /** Left/Right along buttons and times; arriving on a time stops playback and shows it. */
    private void moveFocus(int d) {
        int last = loaded ? FIRST_FRAME + frames.size() - 1 : PLAY;
        int next = Math.max(ZOOM_IN, Math.min(last, focus + d));
        if (next == focus) return;
        focus = next;
        if (focus >= FIRST_FRAME) {
            if (playing) setPlaying(false);
            frame = focus - FIRST_FRAME;
            showFrame();
        }
        paintButtons();
    }

    private void press() {
        if (focus == ZOOM_IN) setLevel(level - 1);
        else if (focus == ZOOM_OUT) setLevel(level + 1);
        else if (focus == PLAY) setPlaying(!playing);
        else setPlaying(true);                     // OK on a time: play on from there
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean ours = k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_CENTER
                || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BACK
                || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN;
        if (!ours) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveFocus(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveFocus(1);
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                setPlaying(!playing);
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0) press();
                break;
            case KeyEvent.KEYCODE_BACK:
                if (e.getRepeatCount() == 0) finish();
                break;
            default:
                break;
        }
        return true;
    }

    // ---------------------------------------------------------------- map drawing

    /** Base map, current radar frame, place names, location marker, scale bar and legend. */
    private final class MapView extends View {
        private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint pin = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint soft = new Paint(Paint.FILTER_BITMAP_FLAG);
        private Bitmap small;
        boolean glassDirty = true;

        MapView() {
            super(RadarActivity.this);
            txt.setTypeface(font);
            txt.setFontVariationSettings("'wght' 600");
            txt.setColor(0xFFFFFFFF);
            txt.setTextSize(dp(13));
        }

        /** Blurred map (1/8 scale) under each overlay box: the frosted-glass look. */
        private void frosted(Canvas c) {
            if (glassDirty || small == null) {
                if (small == null) small = Bitmap.createBitmap(W / Glass.SCALE, H / Glass.SCALE, Bitmap.Config.ARGB_8888);
                Canvas s = new Canvas(small);
                s.drawColor(0xFF0E0E10);
                Rect dst = new Rect(0, 0, small.getWidth(), small.getHeight());
                if (mapBase() != null) s.drawBitmap(mapBase(), null, dst, soft);
                if (radarBitmap() != null) s.drawBitmap(radarBitmap(), frameSrc(), dst, soft);
                if (mapLabels() != null) s.drawBitmap(mapLabels(), null, dst, soft);
                Glass.blur(small, 3);
                glassDirty = false;
            }
            float plateR = W - dp(28), plateL = plateR - dp(260), plateT = dp(24);
            float plateB = head.getHeight() > 0 ? head.getBottom() : plateT + dp(104);
            slice(c, new RectF(plateL, plateT, plateR, plateB), dp(20));
            if (head.getWidth() > 0) slice(c, new RectF(head.getLeft(), head.getTop(), head.getRight(), head.getBottom()), dp(20));
            if (barView.getWidth() > 0) slice(c, new RectF(barView.getLeft(), barView.getTop(), barView.getRight(), barView.getBottom()), dp(24));
            if (status.getVisibility() == View.VISIBLE && status.getWidth() > 0)
                slice(c, new RectF(status.getLeft(), status.getTop(), status.getRight(), status.getBottom()), dp(16));
        }

        private void slice(Canvas c, RectF r, float radius) {
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            Rect src = new Rect((int) (r.left / Glass.SCALE), (int) (r.top / Glass.SCALE),
                    (int) Math.ceil(r.right / Glass.SCALE), (int) Math.ceil(r.bottom / Glass.SCALE));
            c.drawBitmap(small, src, r, soft);
            c.restore();
        }

        private Bitmap mapBase() {
            return bases[level];
        }

        /** Near set for levels 0-1, far set for 2-3 (null while it is still downloading). */
        private Bitmap radarBitmap() {
            if (frames.isEmpty() || frame >= frames.size()) return null;
            return level < 2 ? frames.get(frame) : farFrames.get(frame);
        }

        private Bitmap mapLabels() {
            return labelMaps[level];
        }

        /** Frames cover the zoomed-out area; the normal view is their middle quarter. */
        private Rect frameSrc() {
            Bitmap f = radarBitmap();
            int w = f.getWidth(), h = f.getHeight();
            return level % 2 == 1 ? null : new Rect(w / 4, h / 4, w * 3 / 4, h * 3 / 4);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (mapBase() != null) c.drawBitmap(mapBase(), null, new Rect(0, 0, W, H), bmp);
            if (radarBitmap() != null) c.drawBitmap(radarBitmap(), frameSrc(), new Rect(0, 0, W, H), bmp);
            if (mapLabels() != null) c.drawBitmap(mapLabels(), null, new Rect(0, 0, W, H), bmp); // towns over rain
            frosted(c);

            // Location marker
            float x = W / 2f, y = H / 2f;
            pin.setStyle(Paint.Style.FILL);
            pin.setColor(0x55FFFFFF);
            c.drawCircle(x, y, dp(14), pin);
            pin.setColor(0xFFFFFFFF);
            c.drawCircle(x, y, dp(6), pin);
            pin.setColor(0xFFFF4D5E);
            c.drawCircle(x, y, dp(4), pin);

            // Plate (top right), styled and sized like the title box on the left:
            // "50 km" + scale bar, legend gradient, then its labels, evenly spaced.
            float plateR = W - dp(28), plateL = plateR - dp(260), plateT = dp(24);
            float plateB = head.getHeight() > 0 ? head.getBottom() : plateT + dp(104);
            RectF plate = new RectF(plateL, plateT, plateR, plateB);
            pin.setStyle(Paint.Style.FILL);
            pin.setColor(0x8C161618);
            c.drawRoundRect(plate, dp(20), dp(20), pin);
            pin.setStyle(Paint.Style.STROKE);
            pin.setStrokeWidth(dp(1));
            pin.setColor(0x38FFFFFF);
            float in = dp(0.5f);
            c.drawRoundRect(new RectF(plateL + in, plateT + in, plateR - in, plateB - in), dp(20), dp(20), pin);
            pin.setStyle(Paint.Style.FILL);

            float padX = dp(22), padY = dp(16);
            float left = plateL + padX, right = plateR - padX;
            Paint.FontMetrics fm = txt.getFontMetrics();
            float textH = fm.descent - fm.ascent;
            // three rows: scale (text height), gradient (8dp), labels (text height); equal gaps
            float lh = dp(8);
            float gap = ((plateB - padY) - (plateT + padY) - 2 * textH - lh) / 2;
            float row1Top = plateT + padY, row1Y = row1Top + textH / 2;
            float ly = row1Top + textH + gap;
            float labelBase = ly + lh + gap - fm.ascent;

            double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * view.cy / (256 * Math.pow(2, RadarCache.VIEW_ZOOM))))));
            double mPerPx = 156543.03 * Math.cos(Math.toRadians(lat)) / Math.pow(2, RadarCache.VIEW_ZOOM - level);
            int km = 50 << level;                              // same bar length, 50/100/200/400 km
            float len = (float) (km * 1000 / mPerPx);
            txt.setTextAlign(Paint.Align.LEFT);
            c.drawText(km + " km", left, row1Y - (fm.ascent + fm.descent) / 2, txt);
            float sx = right - len;
            bar.setColor(0xFFFFFFFF);
            bar.setStrokeWidth(dp(2));
            bar.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(sx, row1Y, sx + len, row1Y, bar);
            c.drawLine(sx, row1Y - dp(5), sx, row1Y + dp(5), bar);
            c.drawLine(sx + len, row1Y - dp(5), sx + len, row1Y + dp(5), bar);

            // Legend: the repainted scale (RadarPalette), light -> heavy
            int[] leg = RadarPalette.LEGEND.clone();
            for (int i = 0; i < leg.length; i++) leg[i] |= 0xFF000000;
            bar.setShader(new LinearGradient(left, 0, right, 0, leg, null, Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(left, ly, right, ly + lh), lh / 2, lh / 2, bar);
            bar.setShader(null);
            c.drawText(getString(R.string.radar_light), left, labelBase, txt);
            txt.setTextAlign(Paint.Align.RIGHT);
            c.drawText(getString(R.string.radar_heavy), right, labelBase, txt);

            txt.setTextAlign(Paint.Align.LEFT);
            txt.setColor(0x99FFFFFF);
            txt.setShadowLayer(dp(2), 0, 0, 0xFF000000);
            c.drawText("Esri, HERE, Garmin, © OpenStreetMap · RainViewer", dp(28), H - dp(96), txt);
            txt.setShadowLayer(0, 0, 0, 0);
            txt.setColor(0xFFFFFFFF);
        }
    }
}
