package com.clean.tvhome;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.HashMap;
import java.util.Map;

/**
 * RainViewer's free tiles always use its "Universal Blue" palette, where everything under
 * 15 dBZ (mist, drizzle, ground clutter) is a beige wash that hides the map. This maps each pixel
 * back to dBZ and repaints it on the classic TV-weather scale: weak returns nearly invisible,
 * rain green, heavy rain yellow to red, hail magenta.
 */
final class RadarPalette {

    /** Universal Blue, dBZ -10 .. 64 (from rainviewer_api_colors_table.csv), ARGB. */
    private static final int[] UB = {
            0x14636159, 0x1966635A, 0x1E69665C, 0x246C685D, 0x296F6B5F, 0x2E726E61, 0x34757062,
            0x39787364, 0x3E7C7565, 0x447F7867, 0x49827B69, 0x4E857D6A, 0x5488806C, 0x598B826D,
            0x5E8E856F, 0x64928871, 0x6E9E9375, 0x78AA9E79, 0x82B6A97E, 0x8CC2B482, 0x96CEC087,
            0xA0D2C48B, 0xAAD6C88F, 0xB4DACC93, 0xBEDED097, 0xFF88DDEE, 0xFF6CD1EB, 0xFF51C5E8,
            0xFF36BAE5, 0xFF1BAEE2, 0xFF00A3E0, 0xFF009AD5, 0xFF0091CA, 0xFF0088BF, 0xFF007FB4,
            0xFF0077AA, 0xFF0070A3, 0xFF00699C, 0xFF006295, 0xFF005B8E, 0xFF005588, 0xFF005180,
            0xFF004E78, 0xFF004A70, 0xFF004768, 0xFFFFEE00, 0xFFFFE000, 0xFFFFD200, 0xFFFFC500,
            0xFFFFB700, 0xFFFFAA00, 0xFFFF9F00, 0xFFFF9500, 0xFFFF8B00, 0xFFFF8100, 0xFFFF4400,
            0xFFF23600, 0xFFE62800, 0xFFD91B00, 0xFFCD0D00, 0xFFC10000, 0xFFA80000, 0xFF8F0000,
            0xFF760000, 0xFF5D0000, 0xFFFFAAFF, 0xFFFF9FFF, 0xFFFF95FF, 0xFFFF8BFF, 0xFFFF81FF,
            0xFFFF77FF, 0xFFFF6CFF, 0xFFFF62FF, 0xFFFF58FF, 0xFFFF4EFF
    };
    private static final int MIN_DBZ = -10;
    private static final Map<Integer, Integer> DBZ = new HashMap<>();

    static {
        for (int i = 0; i < UB.length; i++) DBZ.put(UB[i], MIN_DBZ + i);
    }

    private RadarPalette() {}

    /** Output colour for a reflectivity value. */
    static int color(int dbz) {
        if (dbz < 10) return 0;                                   // noise, mist
        if (dbz < 15) return Color.argb(70, 120, 200, 120);       // drizzle: just a hint
        if (dbz < 20) return Color.argb(190, 120, 220, 120);      // light rain
        if (dbz < 25) return Color.argb(215, 60, 190, 90);
        if (dbz < 30) return Color.argb(230, 20, 150, 60);        // moderate
        if (dbz < 35) return Color.argb(240, 10, 115, 45);
        if (dbz < 40) return Color.argb(245, 250, 230, 40);       // heavy
        if (dbz < 45) return Color.argb(250, 250, 160, 30);
        if (dbz < 50) return Color.argb(255, 240, 60, 30);        // very heavy / storm
        if (dbz < 55) return Color.argb(255, 190, 20, 20);
        return Color.argb(255, 210, 40, 210);                     // hail
    }

    /** Colours for the legend, light to heavy. */
    static final int[] LEGEND = {color(17), color(27), color(32), color(37), color(42), color(47), color(52), color(57)};

    private static int dbz(int argb) {
        Integer d = DBZ.get(argb);
        if (d != null) return d;
        // Not an exact palette colour (decoder rounding): take the nearest one, alpha included.
        int best = 0, bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < UB.length; i++) {
            int c = UB[i];
            int da = Color.alpha(c) - Color.alpha(argb), dr = Color.red(c) - Color.red(argb),
                    dg = Color.green(c) - Color.green(argb), db = Color.blue(c) - Color.blue(argb);
            int dist = da * da + dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        DBZ.put(argb, MIN_DBZ + best);
        return MIN_DBZ + best;
    }

    static synchronized Bitmap recolor(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            px[i] = Color.alpha(p) == 0 ? 0 : color(dbz(p));
        }
        Bitmap out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        src.recycle();
        return out;
    }
}
