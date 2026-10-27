package com.clean.tvhome;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

/**
 * Diagnostic: logs the MediaTek picture-quality database (tag CleanHomePq).
 * Read only. Run with: am start -n com.clean.tvhome/.HomeActivity --es com.clean.tvhome.ACTION pqdump
 */
final class PqProbe {
    private static final String TAG = "CleanHomePq";
    private static final String[] TABLES = {"general", "source", "stream", "hdr", "gamemode",
            "Picture_Format_By_Source", "restriction_settings", "resolution"};

    private PqProbe() {}

    /** Calls every no-argument int/boolean getter of Xiaomi's PictureSettingsManager (read only). */
    static void dumpMitv() {
        try {
            Class<?> k = Class.forName("mitv.display.PictureSettingsManager");
            Object m = k.getMethod("getInstance").invoke(null);
            for (java.lang.reflect.Method g : k.getMethods()) {
                String n = g.getName();
                if (g.getParameterCount() != 0 || !(n.startsWith("get") || n.startsWith("is"))
                        || n.equals("getInstance") || n.equals("getClass")) continue;
                Object v;
                try {
                    v = g.invoke(m);
                } catch (Exception e) {
                    v = "ERR " + (e.getCause() != null ? e.getCause() : e);
                }
                Log.i(TAG, "mitv " + n + " = " + v);
            }
        } catch (Throwable t) {
            Log.i(TAG, "mitv unavailable: " + t);
        }
    }

    /** Write test that changes nothing: sets the backlight to the value it already has. */
    static void writeSameBacklight() {
        try {
            Class<?> k = Class.forName("mitv.display.PictureSettingsManager");
            Object m = k.getMethod("getInstance").invoke(null);
            int v = (int) k.getMethod("getBacklight").invoke(m);
            Object r = k.getMethod("setBacklight", int.class).invoke(m, v);
            Log.i(TAG, "mitv setBacklight(" + v + ") -> " + r + ", now " + k.getMethod("getBacklight").invoke(m));
        } catch (Throwable t) {
            Log.i(TAG, "mitv write failed: " + (t.getCause() != null ? t.getCause() : t));
        }
    }

    /** Logs Xiaomi's per-model capability flags (what Settings uses to hide options). */
    static void dumpCaps() {
        String[] keys = "support_4h_put_device_to_sleep,support_adaptive_luma_control_list,support_air_play,support_allm_mode,support_black_stretch,support_color_sensor,support_decontour,support_display_AISR,support_display_color_space,support_display_memc,support_display_memc_plus,support_display_peak_bright,support_dolby_atmos,support_dolby_audio_24,support_dolby_audio_26,support_dolby_sound,support_dolbyvision_film_maker,support_dts_virtualx,support_dynamic_backlight,support_dynamic_sharpness,support_earc,support_expand_local_dimming,support_eye_care,support_film_maker,support_game_mode,support_gamma_list,support_gamma_shift,support_harman_sound,support_hdr_adjust,support_hdr_monitor,support_intelligent_switch,support_local_contrast_control,support_local_dimming,support_memc_list,support_mi_sound_mode,support_one_touch_play,support_picture_clear_motion_smoothing,support_picture_hdr_tone_mapping,support_picture_off,support_picture_precision_rendering,support_picture_video_aipq,support_select_format_manual,support_select_format_spdif,support_show_cec_list,support_show_edid_version,support_show_erp_dialog,support_show_wol_info_summary,support_sleep_timer,support_spdif,support_standby_light,support_tv_mode,support_wake_config,support_wake_config_woc,support_wakeconfig_power_confirmed,support_white_balance_20".split(",");
        try {
            Class<?> k = Class.forName("mitv.common.ConfigurationManager");
            Object m = k.getMethod("getInstance").invoke(null);
            java.lang.reflect.Method q = k.getMethod("queryTvConfigByKey", String.class);
            for (String key : keys) Log.i(TAG, "cap " + key + " = " + q.invoke(m, key));
        } catch (Throwable t) {
            Log.i(TAG, "caps unavailable: " + t);
        }
    }

    /** Calibration values and every method signature (read only). */
    static void dumpCalibration() {
        try {
            Class<?> k = Class.forName("mitv.display.PictureSettingsManager");
            Object m = k.getMethod("getInstance").invoke(null);
            for (java.lang.reflect.Method g : k.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isPublic(g.getModifiers()))
                    Log.i(TAG, "sig " + g.getName() + java.util.Arrays.toString(g.getParameterTypes()) + " -> " + g.getReturnType().getSimpleName());
            }
            java.lang.reflect.Method ct = k.getMethod("getColorTuneValue", int.class, int.class);
            for (int t = 0; t <= 3; t++) {
                StringBuilder sb = new StringBuilder("colortune type " + t + ":");
                for (int col = 0; col <= 7; col++) sb.append(' ').append(ct.invoke(m, t, col));
                Log.i(TAG, sb.toString());
            }
            java.lang.reflect.Method ms = k.getMethod("getMotionSmoothByType", int.class);
            Log.i(TAG, "motion 0..3: " + ms.invoke(m, 0) + " " + ms.invoke(m, 1) + " " + ms.invoke(m, 2) + " " + ms.invoke(m, 3));
            java.lang.reflect.Method wb = k.getMethod("getWhiteBalanceValue", int.class);
            StringBuilder sb = new StringBuilder("whitebalance 0..12:");
            for (int i = 0; i <= 12; i++) sb.append(' ').append(wb.invoke(m, i));
            Log.i(TAG, sb.toString());
        } catch (Throwable t) {
            Log.i(TAG, "calibration probe failed: " + (t.getCause() != null ? t.getCause() : t));
        }
    }

    /** For each setter: try values 0..12, record which ones read back, then restore the original. */
    static void probeRanges() {
        String[][] pairs = {{"PreciseDetails", "PreciseDetails"}, {"MotionSmooth", "MotionSmooth"},
                {"SmartAdaption", "SmartAdaption"}, {"NeoGamma", "NeoGamma"}, {"ColorBooster", "ColorBooster"},
                {"BlueStretch", "BlueStretch"}, {"DynamicContrastEnable", "DynamicContrastEnable"},
                {"GlobalDimming", "GlobalDimming"}};
        try {
            Class<?> k = Class.forName("mitv.display.PictureSettingsManager");
            Object m = k.getMethod("getInstance").invoke(null);
            for (String[] p : pairs) {
                java.lang.reflect.Method g = k.getMethod("get" + p[0]);
                java.lang.reflect.Method s = k.getMethod("set" + p[1], int.class);
                int orig = (int) g.invoke(m);
                StringBuilder ok = new StringBuilder();
                for (int v = 0; v <= 12; v++) {
                    Object r = s.invoke(m, v);
                    int back = (int) g.invoke(m);
                    if (back == v) ok.append(v).append(r.equals(Boolean.FALSE) ? "(false)" : "").append(' ');
                }
                s.invoke(m, orig);
                Log.i(TAG, "range " + p[0] + " orig=" + orig + " accepted: " + ok + " restored=" + g.invoke(m));
            }
            // 20-point white balance: select points and read RGB back, then restore the point.
            java.lang.reflect.Method wg = k.getMethod("getWhiteBalanceValue", int.class);
            java.lang.reflect.Method ws = k.getMethod("setWhiteBalanceValue", int.class, int.class);
            int p0 = (int) wg.invoke(m, 1);
            StringBuilder sb = new StringBuilder("wb points:");
            for (int pt = 0; pt <= 21; pt++) {
                ws.invoke(m, 1, pt);
                sb.append(' ').append(pt).append("->").append(wg.invoke(m, 1)).append('/')
                        .append(wg.invoke(m, 2)).append(',').append(wg.invoke(m, 3)).append(',').append(wg.invoke(m, 4));
            }
            ws.invoke(m, 1, p0);
            Log.i(TAG, sb + " restored point=" + wg.invoke(m, 1));
        } catch (Throwable t) {
            Log.i(TAG, "range probe failed: " + (t.getCause() != null ? t.getCause() : t));
        }
    }

    static void dump(Context c) {
        dumpCalibration();
        dumpCaps();
        dumpMitv();
        for (String t : TABLES) {
            Uri uri = Uri.parse("content://com.mediatek.tv.settingspqdb/" + t);
            try (Cursor cur = c.getContentResolver().query(uri, null, null, null, null)) {
                if (cur == null) {
                    Log.i(TAG, t + ": null cursor");
                    continue;
                }
                Log.i(TAG, t + ": " + cur.getCount() + " rows, columns " + String.join(",", cur.getColumnNames()));
                while (cur.moveToNext()) {
                    StringBuilder sb = new StringBuilder(t).append(" |");
                    for (int i = 0; i < cur.getColumnCount(); i++) {
                        String v;
                        try {
                            v = cur.getString(i);
                        } catch (Exception e) {
                            v = "<blob>";
                        }
                        if (v != null && v.length() > 300) v = v.substring(0, 300) + "...";
                        sb.append(' ').append(cur.getColumnName(i)).append('=').append(v);
                    }
                    Log.i(TAG, sb.toString());
                }
            } catch (Exception e) {
                Log.i(TAG, t + ": " + e);
            }
        }
    }
}
