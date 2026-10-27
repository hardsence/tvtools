package com.clean.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads launchable apps and splits them into dock and grid. */
final class Apps {

    static final class Entry {
        final String pkg;
        final String label;
        final Intent launch;
        final Drawable art;
        final boolean isBanner;
        final int glow;
        /** The app's icon at the best density it ships, for the round icons on the home screen. */
        final Drawable icon;

        Entry(String pkg, String label, Intent launch, Drawable art, boolean isBanner, int glow, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.launch = launch;
            this.art = art;
            this.isBanner = isBanner;
            this.glow = glow;
            this.icon = icon;
        }
    }

    static final class Split {
        final List<Entry> dock = new ArrayList<>();
        final List<Entry> grid = new ArrayList<>();
    }

    /** Columns in the dock and the grid, chosen in the home screen's Settings menu. */
    static final int MIN_COLS = 4, MAX_COLS = 8, DEFAULT_COLS = 5;

    static int cols(Context c) {
        int n = settings(c).getInt("cols", DEFAULT_COLS);
        return Math.max(MIN_COLS, Math.min(MAX_COLS, n));
    }

    static void setCols(Context c, int n) {
        settings(c).edit().putInt("cols", n).commit();
    }

    private static SharedPreferences settings(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private Apps() {}

    /** Runs off the main thread: loading banners and sampling colours takes a few hundred ms. */
    static Split load(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> hidden = new HashSet<>(Arrays.asList(
                c.getResources().getStringArray(R.array.hidden_packages)));
        hidden.add(c.getPackageName());

        Map<String, Entry> all = new LinkedHashMap<>();
        Intent leanback = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(leanback, 0)) {
            add(pm, ri, Intent.CATEGORY_LEANBACK_LAUNCHER, hidden, all);
        }
        // Phone-only apps the user installed themselves (file managers and the like).
        Intent phone = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(phone, 0)) {
            if ((ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            add(pm, ri, Intent.CATEGORY_LAUNCHER, hidden, all);
        }

        // One ordered list (order.txt, else config.xml's dock then grid_first, then A-Z);
        // the dock is simply its first `cols` apps, so changing the column count never loses one.
        List<String> order = readOrderFile(c);
        if (order.isEmpty()) {
            order.addAll(Arrays.asList(c.getResources().getStringArray(R.array.dock_packages)));
            order.addAll(Arrays.asList(c.getResources().getStringArray(R.array.grid_first)));
        }
        List<Entry> ordered = new ArrayList<>();
        for (String p : order) {
            Entry e = all.remove(p);
            if (e != null) ordered.add(e);
        }
        List<Entry> rest = new ArrayList<>(all.values());
        Collator col = Collator.getInstance();
        Collections.sort(rest, (a, b) -> col.compare(a.label, b.label));
        ordered.addAll(rest);

        int cols = cols(c);
        Split s = new Split();
        s.dock.addAll(ordered.subList(0, Math.min(cols, ordered.size())));
        if (ordered.size() > cols) s.grid.addAll(ordered.subList(cols, ordered.size()));
        return s;
    }

    private static void add(PackageManager pm, ResolveInfo ri, String category,
                            Set<String> hidden, Map<String, Entry> out) {
        String pkg = ri.activityInfo.packageName;
        if (hidden.contains(pkg) || out.containsKey(pkg)) return;
        Drawable art = ri.activityInfo.loadBanner(pm);
        boolean banner = art != null;
        if (!banner) art = ri.activityInfo.loadIcon(pm);
        Intent launch = new Intent(Intent.ACTION_MAIN)
                .addCategory(category)
                .setClassName(pkg, ri.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        Drawable icon = null;
        try {
            int res = ri.activityInfo.getIconResource();
            if (res != 0) icon = pm.getResourcesForApplication(ri.activityInfo.applicationInfo)
                    .getDrawableForDensity(res, android.util.DisplayMetrics.DENSITY_XXXHIGH, null);
        } catch (Exception ignored) {
        }
        if (icon == null) icon = ri.activityInfo.loadIcon(pm);
        out.put(pkg, new Entry(pkg, ri.loadLabel(pm).toString().trim(), launch, art, banner, sample(art), icon));
    }

    /** Most vivid colour of the artwork, brightened for use as an ambient glow. */
    static int sample(Drawable d) {
        int w = 32, h = 18;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(b));
        float[] hsv = new float[3];
        float best = -1f;
        int bestColor = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 160) continue;
                Color.colorToHSV(p, hsv);
                if (hsv[2] < 0.2f) continue;
                float score = hsv[1] * hsv[2];
                if (score > best) {
                    best = score;
                    bestColor = p;
                }
            }
        }
        b.recycle();
        if (best < 0.12f) return 0xFFB8BCC8; // greyscale artwork: neutral silver glow
        Color.colorToHSV(bestColor, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(hsv[2], 0.85f);
        return Color.HSVToColor(hsv);
    }

    /** Saves an order rearranged with the remote; it then takes the place of config.xml. */
    static void saveOrder(Context c, List<String> pkgs) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) return;
        File tmp = new File(dir, "order.txt.tmp");
        try (FileWriter w = new FileWriter(tmp)) {
            w.write("# Arranged with the remote. The first apps fill the dock. Delete this file to reset.\n");
            for (String p : pkgs) w.write(p + "\n");
        } catch (Exception e) {
            return;
        }
        tmp.renameTo(new File(dir, "order.txt"));
    }

    private static List<String> readOrderFile(Context c) {
        List<String> out = new ArrayList<>();
        File dir = c.getExternalFilesDir(null);
        if (dir == null) return out;
        File f = new File(dir, "order.txt");
        if (!f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) out.add(line);
            }
        } catch (Exception ignored) {
            out.clear();
        }
        return out;
    }
}
