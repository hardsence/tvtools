package com.clean.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * "Continue watching" kept by the launcher itself (the system Watch Next list is only readable by
 * privileged launchers). Filled by {@link MediaWatcher} from apps' media sessions; newest first.
 */
final class ContinueStore {

    static final int MAX = 12;
    static final int ART_W = 480, ART_H = 270;

    static final class Item {
        String key, pkg, title, subtitle;
        long position, duration, time;

        boolean hasProgress() {
            return duration > 0 && position > 0;
        }

        float progress() {
            return duration > 0 ? Math.max(0f, Math.min(1f, position / (float) duration)) : 0f;
        }
    }

    interface Listener {
        void onContinueChanged();
    }

    static Listener listener;

    private ContinueStore() {}

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "continue");
        d.mkdirs();
        return d;
    }

    static File artFile(Context c, String key) {
        return new File(dir(c), Integer.toHexString(key.hashCode()) + ".png");
    }

    static synchronized List<Item> load(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            File f = new File(dir(c), "list.json");
            if (!f.isFile()) return out;
            JSONArray a = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.key = o.getString("key");
                it.pkg = o.getString("pkg");
                it.title = o.optString("title");
                it.subtitle = o.optString("subtitle");
                it.position = o.optLong("pos");
                it.duration = o.optLong("dur");
                it.time = o.optLong("time");
                out.add(it);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void save(Context c, List<Item> items) {
        try {
            JSONArray a = new JSONArray();
            for (Item it : items) {
                a.put(new JSONObject().put("key", it.key).put("pkg", it.pkg).put("title", it.title)
                        .put("subtitle", it.subtitle).put("pos", it.position).put("dur", it.duration).put("time", it.time));
            }
            File tmp = new File(dir(c), "list.json.tmp");
            Files.write(tmp.toPath(), a.toString().getBytes(StandardCharsets.UTF_8));
            tmp.renameTo(new File(dir(c), "list.json"));
        } catch (Exception ignored) {
        }
    }

    /** Adds or refreshes an item and moves it to the front; finished items (>95 %) drop out. */
    static synchronized void put(Context c, Item item, Bitmap art) {
        List<Item> items = load(c);
        for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).key.equals(item.key)) items.remove(i);
        boolean finished = item.duration > 60_000 && item.position > item.duration * 0.95;
        if (!finished) items.add(0, item);
        while (items.size() > MAX) {
            Item gone = items.remove(items.size() - 1);
            artFile(c, gone.key).delete();
        }
        if (finished) artFile(c, item.key).delete();
        if (art != null && !finished) saveArt(c, item.key, art);
        save(c, items);
        if (listener != null) listener.onContinueChanged();
    }

    static synchronized void remove(Context c, String key) {
        List<Item> items = load(c);
        for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).key.equals(key)) items.remove(i);
        artFile(c, key).delete();
        save(c, items);
        if (listener != null) listener.onContinueChanged();
    }

    /** Covers are stored 16:9, centre-cropped. */
    private static void saveArt(Context c, String key, Bitmap src) {
        try {
            Bitmap out = Bitmap.createBitmap(ART_W, ART_H, Bitmap.Config.ARGB_8888);
            float s = Math.max(ART_W / (float) src.getWidth(), ART_H / (float) src.getHeight());
            int sw = Math.round(ART_W / s), sh = Math.round(ART_H / s);
            int sx = (src.getWidth() - sw) / 2, sy = (src.getHeight() - sh) / 2;
            new Canvas(out).drawBitmap(src, new Rect(sx, sy, sx + sw, sy + sh), new Rect(0, 0, ART_W, ART_H),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            try (FileOutputStream fo = new FileOutputStream(artFile(c, key))) {
                out.compress(Bitmap.CompressFormat.PNG, 100, fo);
            }
            out.recycle();
        } catch (Exception ignored) {
        }
    }

    static Bitmap art(Context c, String key) {
        File f = artFile(c, key);
        return f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }
}
