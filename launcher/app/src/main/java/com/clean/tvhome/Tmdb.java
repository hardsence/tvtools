package com.clean.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Everything the home screen shows from The Movie Database (api.themoviedb.org/3):
 * <ul>
 *   <li>HERO: trending today, then trending this week, without duplicates (backdrops)</li>
 *   <li>MOVIES / SERIES: "What's popular" on streaming in the TV's region (posters)</li>
 *   <li>TRAILERS: newest YouTube trailers of films in cinemas, coming soon and series on air</li>
 * </ul>
 * Each list is stored in files/tmdb/ in our own small JSON with its images next to it and
 * refreshed every few hours, so the home screen fills at once after a restart and works offline.
 */
final class Tmdb {

    private static final String TAG = "CleanHomeTmdb";
    private static final String API = "https://api.themoviedb.org/3/";
    private static final String IMG = "https://image.tmdb.org/t/p/";
    private static final long MAX_AGE_MS = 6 * 3600_000L;

    static final String HERO = "hero", MOVIES = "movies", SERIES = "series", TRAILERS = "trailers";
    static final String[] LISTS = {HERO, MOVIES, SERIES, TRAILERS};
    private static final int HERO_MAX = 15, ROW_MAX = 20, TRAILER_MAX = 15, TRAILER_CANDIDATES = 30;

    static final class Item {
        long id;
        boolean movie;
        String title, overview, year, image, video, date;
        double rating, popularity;
        File file;

        String key() {
            return (movie ? "m" : "t") + id;
        }
    }

    interface Listener {
        void onList(String list, List<Item> items);
    }

    private Tmdb() {}

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "tmdb");
        d.mkdirs();
        return d;
    }

    static boolean enabled(Context c) {
        return !TextUtils.isEmpty(c.getString(R.string.tmdb_key));
    }

    // ---------------------------------------------------------------- cache

    /** Cached list (possibly stale) or empty. Items whose image is missing are skipped. */
    static List<Item> cached(Context c, String list) {
        List<Item> out = new ArrayList<>();
        File f = new File(dir(c), list + ".json");
        if (!f.isFile()) return out;
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.id = o.getLong("id");
                it.movie = o.getBoolean("movie");
                it.title = o.optString("title");
                it.overview = o.optString("overview");
                it.year = o.optString("year");
                it.image = o.optString("image");
                it.video = o.optString("video", null);
                it.date = o.optString("date");
                it.rating = o.optDouble("rating", 0);
                it.file = new File(dir(c), it.image);
                if (it.file.isFile()) out.add(it);
            }
        } catch (Exception e) {
            Log.w(TAG, "cache " + list + ": " + e);
        }
        return out;
    }

    private static void save(Context c, String list, List<Item> items) throws Exception {
        JSONArray a = new JSONArray();
        for (Item it : items) {
            JSONObject o = new JSONObject();
            o.put("id", it.id).put("movie", it.movie).put("title", it.title).put("overview", it.overview)
                    .put("year", it.year).put("image", it.image).put("date", it.date).put("rating", it.rating);
            if (it.video != null) o.put("video", it.video);
            a.put(o);
        }
        File tmp = new File(dir(c), list + ".json.tmp");
        Files.write(tmp.toPath(), a.toString().getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(new File(dir(c), list + ".json"))) throw new java.io.IOException("rename");
    }

    static boolean stale(Context c) {
        File f = new File(dir(c), "updated");
        return !f.isFile() || System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS;
    }

    // ---------------------------------------------------------------- refresh

    /** Downloads every list (and missing images), handing each one over as soon as it is ready. Blocking. */
    static void refresh(Context c, Listener l) {
        if (!enabled(c)) return;
        Set<String> keep = new HashSet<>();
        boolean ok = true;
        for (String list : LISTS) {
            try {
                List<Item> items = list.equals(HERO) ? hero(c) : list.equals(MOVIES) ? popular(c, true)
                        : list.equals(SERIES) ? popular(c, false) : trailers(c);
                for (Item it : items) keep.add(it.image);
                if (items.isEmpty()) continue;
                save(c, list, items);
                l.onList(list, items);
            } catch (Exception e) {
                ok = false;
                Log.w(TAG, list + ": " + e);
                for (Item it : cached(c, list)) keep.add(it.image);   // keep the old images of a failed list
            }
        }
        if (!ok) return;
        try {
            new File(dir(c), "updated").createNewFile();
            new File(dir(c), "updated").setLastModified(System.currentTimeMillis());
        } catch (Exception ignored) {
        }
        File[] old = dir(c).listFiles((d, n) -> n.endsWith(".jpg"));
        if (old != null) for (File f : old) if (!keep.contains(f.getName())) f.delete();
    }

    private static String key(Context c) {
        return c.getString(R.string.tmdb_key);
    }

    private static String lang(Context c) {
        return c.getResources().getConfiguration().getLocales().get(0).toLanguageTag();
    }

    private static String region(Context c) {
        String r = c.getResources().getConfiguration().getLocales().get(0).getCountry();
        return TextUtils.isEmpty(r) ? "PL" : r;
    }

    private static JSONArray results(Context c, String path, String lang) throws Exception {
        String sep = path.contains("?") ? "&" : "?";
        return new JSONObject(get(API + path + sep + "api_key=" + key(c) + "&language=" + lang)).optJSONArray("results");
    }

    /** Trending today first, then this week's titles that were not in today's list. */
    private static List<Item> hero(Context c) throws Exception {
        String lang = lang(c);
        List<JSONObject> raw = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String span : new String[] {"day", "week"}) {
            JSONArray res = results(c, "trending/all/" + span, lang);
            JSONArray en = lang.startsWith("en") ? null : results(c, "trending/all/" + span, "en-US");
            for (int i = 0; res != null && i < res.length(); i++) {
                JSONObject r = res.getJSONObject(i);
                String type = r.optString("media_type");
                if (!type.equals("movie") && !type.equals("tv")) continue;
                if (!seen.add(type + r.optLong("id"))) continue;
                // overviews missing in the local language come from the English list
                if (TextUtils.isEmpty(r.optString("overview")) && en != null) {
                    for (int k = 0; k < en.length(); k++) {
                        JSONObject e = en.getJSONObject(k);
                        if (e.optLong("id") == r.optLong("id")) r.put("overview", e.optString("overview"));
                    }
                }
                raw.add(r);
            }
        }
        List<Item> out = new ArrayList<>();
        for (JSONObject r : raw) {
            if (out.size() >= HERO_MAX) break;
            Item it = item(r, r.optString("media_type").equals("movie"));
            if (image(c, it, r.optString("backdrop_path"), "w1280")) out.add(it);
        }
        return out;
    }

    /** "What's popular" on TMDB, Streaming tab: titles on subscription services in this region. */
    private static List<Item> popular(Context c, boolean movies) throws Exception {
        JSONArray res = results(c, "discover/" + (movies ? "movie" : "tv")
                + "?sort_by=popularity.desc&watch_region=" + region(c) + "&with_watch_monetization_types=flatrate", lang(c));
        List<Item> out = new ArrayList<>();
        for (int i = 0; res != null && i < res.length() && out.size() < ROW_MAX; i++) {
            JSONObject r = res.getJSONObject(i);
            Item it = item(r, movies);
            if (image(c, it, r.optString("poster_path"), "w342")) out.add(it);
        }
        return out;
    }

    /**
     * "Latest trailers": what TMDB's own section is made of (in cinemas, coming soon, on TV), each
     * with its newest YouTube trailer, local language first; newest trailers first.
     */
    private static List<Item> trailers(Context c) throws Exception {
        String lang = lang(c), short2 = lang.length() >= 2 ? lang.substring(0, 2) : "en";
        List<JSONObject> cand = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String[][] sources = {{"movie/now_playing?region=" + region(c), "movie"}, {"movie/upcoming?region=" + region(c), "movie"},
                {"tv/on_the_air", "tv"}};
        for (String[] s : sources) {
            JSONArray res = results(c, s[0], lang);
            for (int i = 0; res != null && i < res.length(); i++) {
                JSONObject r = res.getJSONObject(i);
                if (seen.add(s[1] + r.optLong("id"))) cand.add(r.put("media_type", s[1]));
            }
        }
        Collections.sort(cand, (a, b) -> Double.compare(b.optDouble("popularity"), a.optDouble("popularity")));
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < cand.size() && i < TRAILER_CANDIDATES; i++) {
            JSONObject r = cand.get(i);
            boolean movie = r.optString("media_type").equals("movie");
            String backdrop = r.optString("backdrop_path");
            if (TextUtils.isEmpty(backdrop) || backdrop.equals("null")) continue;
            JSONObject d;
            try {
                d = new JSONObject(get(API + (movie ? "movie/" : "tv/") + r.optLong("id") + "?api_key=" + key(c)
                        + "&language=" + lang + "&append_to_response=videos&include_video_language=" + short2 + ",en"));
            } catch (Exception e) {
                continue;
            }
            JSONArray vids = d.optJSONObject("videos") != null ? d.optJSONObject("videos").optJSONArray("results") : null;
            JSONObject best = null;
            for (int k = 0; vids != null && k < vids.length(); k++) {
                JSONObject v = vids.getJSONObject(k);
                if (!"YouTube".equals(v.optString("site")) || !"Trailer".equals(v.optString("type"))) continue;
                if (best == null || better(v, best, short2)) best = v;
            }
            if (best == null) continue;
            Item it = item(r, movie);
            it.video = best.optString("key");
            it.date = best.optString("published_at");
            out.add(it);
        }
        Collections.sort(out, (a, b) -> b.date.compareTo(a.date));
        List<Item> kept = new ArrayList<>();
        for (Item it : out) {
            if (kept.size() >= TRAILER_MAX) break;
            JSONObject r = null;
            for (JSONObject o : cand) if (o.optLong("id") == it.id && o.optString("media_type").equals(it.movie ? "movie" : "tv")) r = o;
            if (r != null && image(c, it, r.optString("backdrop_path"), "w780")) kept.add(it);
        }
        return kept;
    }

    /** Local language beats English; then the newer one. */
    private static boolean better(JSONObject v, JSONObject best, String lang) {
        boolean vl = lang.equals(v.optString("iso_639_1")), bl = lang.equals(best.optString("iso_639_1"));
        if (vl != bl) return vl;
        return v.optString("published_at").compareTo(best.optString("published_at")) > 0;
    }

    private static Item item(JSONObject r, boolean movie) {
        Item it = new Item();
        it.id = r.optLong("id");
        it.movie = movie;
        it.title = r.optString(movie ? "title" : "name");
        it.overview = r.optString("overview");
        String date = r.optString(movie ? "release_date" : "first_air_date", "");
        it.year = date.length() >= 4 ? date.substring(0, 4) : "";
        it.date = "";
        it.rating = r.optDouble("vote_average", 0);
        it.popularity = r.optDouble("popularity", 0);
        return it;
    }

    /** Points the item at its image file, downloading it when missing. */
    private static boolean image(Context c, Item it, String path, String size) {
        if (TextUtils.isEmpty(path) || path.equals("null")) return false;
        it.image = size + "_" + path.replace("/", "");
        it.file = new File(dir(c), it.image);
        if (it.file.isFile()) return true;
        try {
            fetch(IMG + size + path, it.file);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "image " + path + ": " + e);
            return false;
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The image scaled and centre-cropped to exactly w×h, in RGB_565 to keep memory low. */
    static Bitmap decode(File f, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) return null;
        float s = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
        int cw = Math.round(w / s), ch = Math.round(h / s);
        int x = Math.max(0, (b.getWidth() - cw) / 2), y = Math.max(0, (b.getHeight() - ch) / 2);
        Bitmap crop = Bitmap.createBitmap(b, x, y, Math.min(cw, b.getWidth() - x), Math.min(ch, b.getHeight() - y));
        Bitmap out = Bitmap.createScaledBitmap(crop, w, h, true);
        if (crop != b) b.recycle();
        if (out != crop) crop.recycle();
        return out;
    }

    static String meta(Context c, Item it) {
        StringBuilder sb = new StringBuilder();
        if (it.rating > 0) sb.append("★ ").append(String.format(Locale.getDefault(), "%.1f", it.rating));
        if (!TextUtils.isEmpty(it.year)) sb.append(sb.length() > 0 ? "  ·  " : "").append(it.year);
        sb.append(sb.length() > 0 ? "  ·  " : "").append(c.getString(it.movie ? R.string.hero_movie : R.string.hero_series));
        return sb.toString();
    }

    /** The trailer in SmartTube (YouTube links go there anyway), else whatever handles YouTube. */
    static Intent trailer(Context c, Item it) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + it.video))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent smart = new Intent(i).setPackage("org.smarttube.beta");
        if (!c.getPackageManager().queryIntentActivities(smart, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) return smart;
        return i;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(10000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            con.disconnect();
        }
    }

    private static void fetch(String url, File to) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(15000);
        File tmp = new File(to.getPath() + ".part");
        try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            con.disconnect();
        }
        if (!tmp.renameTo(to)) throw new java.io.IOException("rename");
    }
}
