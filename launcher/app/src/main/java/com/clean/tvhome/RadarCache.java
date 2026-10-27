package com.clean.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.DisplayMetrics;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Ready-made radar images on disk (app cache), so the radar opens instantly: the base map and
 * place names once per location, and one recoloured precipitation frame per RainViewer time
 * (last 2 hours, 10-minute steps). {@link #update} is run in the background by the home screen
 * together with the weather, and again by the radar screen for anything still missing.
 */
final class RadarCache {

    private static final String TAG = "CleanHomeRadar";
    static final long REFRESH_MS = 10 * 60 * 1000L;
    // Display scale = web-mercator zoom 9. Base map: zoom 9 tiles at 1:1. Place names: zoom 8 tiles
    // drawn 2x (fewer, larger labels that read on a TV). Radar: zoom 7 512-px tiles (RainViewer's
    // free limit) drawn 2x into half-resolution frames (the data is ~1 km per pixel anyway).
    static final int VIEW_ZOOM = 9;
    private static final int LABEL_ZOOM = 8, RADAR_ZOOM = 7;
    private static final String ESRI = "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/";

    interface Listener {
        /** A new frame is on disk (called on the updating thread). */
        void onFrame(long time);
    }

    /** Where the map is: view size in pixels and centre in zoom-9 world pixels. */
    static final class View {
        final int w, h;
        final double cx, cy;
        final String key;

        View(int w, int h, double cx, double cy) {
            this.w = w;
            this.h = h;
            this.cx = cx;
            this.cy = cy;
            key = String.format(Locale.US, "z2_%dx%d_%.0f_%.0f", w, h, cx, cy);
        }
    }

    private static final ExecutorService IO = Executors.newFixedThreadPool(4);

    private RadarCache() {}

    static View view(Context c) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        int w = Math.max(m.widthPixels, m.heightPixels), h = Math.min(m.widthPixels, m.heightPixels);
        SharedPreferences p = Weather.prefs(c);
        double lat = p.getFloat("lat", 52.23f), lon = p.getFloat("lon", 21.01f);
        double scale = 256 * Math.pow(2, VIEW_ZOOM);
        double cx = (lon + 180) / 360 * scale;
        double cy = (1 - Math.log(Math.tan(Math.toRadians(lat)) + 1 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2 * scale;
        return new View(w, h, Math.round(cx), Math.round(cy));
    }

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), "radar");
        d.mkdirs();
        return d;
    }

    static File baseFile(Context c, View v) {
        return new File(dir(c), "base_" + v.key + ".png");
    }

    /** Map and place names for the zoomed-out view (zoom 8: twice the width and height). */
    static File wideBaseFile(Context c, View v) {
        return new File(dir(c), "wbase_" + v.key + ".png");
    }

    static File wideLabelsFile(Context c, View v) {
        return new File(dir(c), "wlabels_" + v.key + ".png");
    }

    static final int LEVELS = 4;              // view zoom 9, 8, 7, 6 (scale 50/100/200/400 km)

    /** Map for zoom level 0..3 (level n = 2^n times the width and height of the normal view). */
    static File baseFile(Context c, View v, int level) {
        if (level == 0) return baseFile(c, v);
        if (level == 1) return wideBaseFile(c, v);
        return new File(dir(c), "base" + level + "_" + v.key + ".png");
    }

    static File labelsFile(Context c, View v, int level) {
        if (level == 0) return labelsFile(c, v);
        if (level == 1) return wideLabelsFile(c, v);
        return new File(dir(c), "labels" + level + "_" + v.key + ".png");
    }

    static File labelsFile(Context c, View v) {
        return new File(dir(c), "labels_" + v.key + ".png");
    }

    private static File frameFile(Context c, View v, long time) {
        return new File(dir(c), "f_" + time + "_" + v.key + ".png");
    }

    /** Times of the frames already on disk for this view, oldest first. */
    static List<Long> cachedTimes(Context c, View v) {
        List<Long> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files == null) return out;
        String suffix = "_" + v.key + ".png";
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith("f_") && n.endsWith(suffix)) {
                try {
                    out.add(Long.parseLong(n.substring(2, n.length() - suffix.length())));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        out.sort(Long::compare);
        return out;
    }

    static Bitmap loadFrame(Context c, View v, long time) {
        return BitmapFactory.decodeFile(frameFile(c, v, time).getPath());
    }

    /** Far set: covers zoom level 3 (8x the normal width/height); serves levels 2 and 3. */
    private static File farFrameFile(Context c, View v, long time) {
        return new File(dir(c), "g_" + time + "_" + v.key + ".png");
    }

    static Bitmap loadFarFrame(Context c, View v, long time) {
        File f = farFrameFile(c, v, time);
        return f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    static Bitmap load(File f) {
        return f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    static boolean fresh(Context c) {
        return System.currentTimeMillis() - Weather.prefs(c).getLong("radar_checked", 0) < REFRESH_MS;
    }

    /**
     * Brings the cache up to date: base map and labels if missing, every current RainViewer frame
     * (newest first), and drops frames that fell out of the 2-hour window. Blocking; one at a time.
     */
    static synchronized void update(Context c, View v, Listener l) {
        try {
            if (!baseFile(c, v).isFile()) {
                Bitmap b = Bitmap.createBitmap(v.w, v.h, Bitmap.Config.ARGB_8888);
                composite(b, v, VIEW_ZOOM, VIEW_ZOOM, (z, x, y) -> ESRI + "World_Dark_Gray_Base/MapServer/tile/" + z + "/" + y + "/" + x, 1f, false);
                save(b, baseFile(c, v));
            }
            if (!labelsFile(c, v).isFile()) {
                Bitmap b = Bitmap.createBitmap(v.w, v.h, Bitmap.Config.ARGB_8888);
                composite(b, v, VIEW_ZOOM, LABEL_ZOOM, (z, x, y) -> ESRI + "World_Dark_Gray_Reference/MapServer/tile/" + z + "/" + y + "/" + x, 1f, false);
                save(b, labelsFile(c, v));
            }
            if (!wideBaseFile(c, v).isFile()) {
                Bitmap b = Bitmap.createBitmap(v.w, v.h, Bitmap.Config.ARGB_8888);
                composite(b, v, VIEW_ZOOM - 1, VIEW_ZOOM - 1, (z, x, y) -> ESRI + "World_Dark_Gray_Base/MapServer/tile/" + z + "/" + y + "/" + x, 1f, false);
                save(b, wideBaseFile(c, v));
            }
            if (!wideLabelsFile(c, v).isFile()) {
                Bitmap b = Bitmap.createBitmap(v.w, v.h, Bitmap.Config.ARGB_8888);
                composite(b, v, VIEW_ZOOM - 1, LABEL_ZOOM - 1, (z, x, y) -> ESRI + "World_Dark_Gray_Reference/MapServer/tile/" + z + "/" + y + "/" + x, 1f, false);
                save(b, wideLabelsFile(c, v));
            }
            for (int level = 2; level < LEVELS; level++) {
                int vz = VIEW_ZOOM - level;
                if (!baseFile(c, v, level).isFile()) {
                    Bitmap b = Bitmap.createBitmap(v.w, v.h, Bitmap.Config.ARGB_8888);
                    composite(b, v, vz, vz, (z, x, y) -> ESRI + "World_Dark_Gray_Base/MapServer/tile/" + z + "/" + y + "/" + x, 1f, false);
                    save(b, baseFile(c, v, level));
                }
                if (!labelsFile(c, v, level).isFile()) {
                    Bitmap b = Bitmap.createBitmap(v.w, v.h, Bitmap.Config.ARGB_8888);
                    composite(b, v, vz, vz - 1, (z, x, y) -> ESRI + "World_Dark_Gray_Reference/MapServer/tile/" + z + "/" + y + "/" + x, 1f, false);
                    save(b, labelsFile(c, v, level));
                }
            }
            JSONObject j = new JSONObject(fetchText("https://api.rainviewer.com/public/weather-maps.json"));
            String host = j.getString("host");
            JSONArray past = j.getJSONObject("radar").getJSONArray("past");
            Set<Long> keep = new HashSet<>();
            for (int i = past.length() - 1; i >= 0; i--) {
                JSONObject fr = past.getJSONObject(i);
                long t = fr.getLong("time");
                keep.add(t);
                File f = frameFile(c, v, t), g = farFrameFile(c, v, t);
                if (f.isFile() && g.isFile()) continue;
                String path = fr.getString("path");
                if (!f.isFile()) {
                    Bitmap b = Bitmap.createBitmap(v.w / 2, v.h / 2, Bitmap.Config.ARGB_8888);
                    // Covers zoom level 1 (twice the width and height) at W/2 x H/2: level 0
                    // uses its middle quarter, so zooming needs no new download.
                    composite(b, v, VIEW_ZOOM - 1, RADAR_ZOOM, (z, x, y) -> host + path + "/512/" + z + "/" + x + "/" + y + "/2/0_0.png", 0.5f, true);
                    save(b, f);
                }
                if (!g.isFile()) {
                    // Same for levels 2 and 3, from zoom 5 tiles (as coarse as the view needs).
                    Bitmap b = Bitmap.createBitmap(v.w / 2, v.h / 2, Bitmap.Config.ARGB_8888);
                    composite(b, v, VIEW_ZOOM - 3, RADAR_ZOOM - 2, (z, x, y) -> host + path + "/512/" + z + "/" + x + "/" + y + "/2/0_0.png", 0.5f, true);
                    save(b, g);
                }
                if (l != null) l.onFrame(t);
            }
            // Drop frames that left the window, and everything for other locations or sizes.
            File[] files = dir(c).listFiles();
            if (files != null) {
                for (File f : files) {
                    String n = f.getName();
                    boolean ours = n.endsWith("_" + v.key + ".png");
                    boolean oldFrame = ours && (n.startsWith("f_") || n.startsWith("g_")) && !keep.contains(timeOf(n, v));
                    if (!ours || oldFrame) f.delete();
                }
            }
            Weather.prefs(c).edit().putLong("radar_checked", System.currentTimeMillis()).apply();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.w(TAG, "radar update failed: " + e);
        }
    }

    private static long timeOf(String name, View v) {
        try {
            return Long.parseLong(name.substring(2, name.length() - ("_" + v.key + ".png").length()));
        } catch (Exception e) {
            return -1;
        }
    }

    private static void save(Bitmap b, File f) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        b.recycle();
        if (!tmp.renameTo(f)) throw new Exception("rename failed: " + f);
    }

    interface TileUrl {
        String get(int z, int x, int y);
    }

    /**
     * Draws every tile of zoom z overlapping a W x H view at zoom viewZoom (centred on the TV's
     * location) into out, at the view scale times scaleOut.
     */
    private static void composite(Bitmap out, View v, int viewZoom, int z, TileUrl url, float scaleOut, boolean radar)
            throws InterruptedException {
        double f = Math.pow(2, viewZoom - z) * 256;           // view pixels per tile of zoom z
        double k = Math.pow(2, VIEW_ZOOM - viewZoom);           // v.cx/cy are zoom-9 pixels
        double left = v.cx / k - v.w / 2.0, top = v.cy / k - v.h / 2.0;
        int x0 = (int) Math.floor(left / f), x1 = (int) Math.floor((left + v.w) / f);
        int y0 = (int) Math.floor(top / f), y1 = (int) Math.floor((top + v.h) / f);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        List<Future<Bitmap>> jobs = new ArrayList<>();
        List<int[]> pos = new ArrayList<>();
        for (int ty = y0; ty <= y1; ty++) {
            for (int tx = x0; tx <= x1; tx++) {
                final String u = url.get(z, tx, ty);
                jobs.add(IO.submit(() -> {
                    Bitmap t = fetchBitmap(u);
                    return t != null && radar ? RadarPalette.recolor(t) : t;
                }));
                pos.add(new int[] {tx, ty});
            }
        }
        for (int i = 0; i < jobs.size(); i++) {
            Bitmap b;
            try {
                b = jobs.get(i).get();
            } catch (java.util.concurrent.ExecutionException e) {
                b = null;
            }
            if (b == null) continue;
            float dx = (float) ((pos.get(i)[0] * f - left) * scaleOut), dy = (float) ((pos.get(i)[1] * f - top) * scaleOut);
            c.drawBitmap(b, new Rect(0, 0, b.getWidth(), b.getHeight()),
                    new RectF(dx, dy, dx + (float) (f * scaleOut), dy + (float) (f * scaleOut)), p);
            b.recycle();
        }
    }

    private static Bitmap fetchBitmap(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "CleanHome-TV (home screen radar)");
            try (InputStream in = c.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "tile " + url + ": " + e);
            return null;
        }
    }

    private static String fetchText(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }
}
