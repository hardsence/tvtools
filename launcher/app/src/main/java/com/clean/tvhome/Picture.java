package com.clean.tvhome;

import android.util.Log;

import java.lang.reflect.Method;

/**
 * Xiaomi's picture API (mitv.display.PictureSettingsManager from the mitvmiddlewareimpl shared
 * library), called by reflection so the app still builds and runs without it.
 * Value meanings were read from Xiaomi's own Settings app and checked on the TV.
 */
final class Picture {

    private static final String TAG = "CleanHomePicture";
    private static Object manager;
    private static Class<?> cls;

    // Color tuner: parameter types and colours 1..7 (red, green, blue, cyan, magenta, yellow, skin).
    static final int TUNE_ENABLE = 0, TUNE_HUE = 1, TUNE_SATURATION = 2, TUNE_GAIN = 3, TUNE_RESET = 4;
    // 20-point white balance: 1 = selected point (5..100 % in steps of 5), 2/3/4 = R/G/B (50 neutral).
    static final int WB_POINT = 1, WB_RED = 2, WB_GREEN = 3, WB_BLUE = 4;
    static final int MOTION_SMOOTH = 1, MOTION_CLEAR = 2;

    private Picture() {}

    static synchronized boolean available() {
        if (manager != null) return true;
        try {
            cls = Class.forName("mitv.display.PictureSettingsManager");
            manager = cls.getMethod("getInstance").invoke(null);
        } catch (Throwable t) {
            Log.w(TAG, "picture API unavailable: " + t);
        }
        return manager != null;
    }

    private static Object call(String name, Class<?>[] types, Object... args) {
        if (!available()) return null;
        try {
            Method m = cls.getMethod(name, types);
            return m.invoke(manager, args);
        } catch (Throwable t) {
            Log.w(TAG, name + " failed: " + (t.getCause() != null ? t.getCause() : t));
            return null;
        }
    }

    /** get<Name>() as int, or fallback when the call is unavailable. */
    static int get(String name, int fallback) {
        Object r = call("get" + name, new Class<?>[0]);
        return r instanceof Integer ? (Integer) r : fallback;
    }

    static void set(String name, int value) {
        call("set" + name, new Class<?>[] {int.class}, value);
    }

    static int colorTune(int type, int color) {
        Object r = call("getColorTuneValue", new Class<?>[] {int.class, int.class}, type, color);
        return r instanceof Integer ? (Integer) r : 50;
    }

    static void setColorTune(int type, int color, int value) {
        call("setColorTuneValue", new Class<?>[] {int.class, int.class, int.class}, type, color, value);
    }

    static int motion(int type) {
        Object r = call("getMotionSmoothByType", new Class<?>[] {int.class}, type);
        return r instanceof Integer ? (Integer) r : 0;
    }

    static void setMotion(int type, int value) {
        call("setMotionSmoothByType", new Class<?>[] {int.class, int.class}, type, value);
    }

    static int whiteBalance(int index) {
        Object r = call("getWhiteBalanceValue", new Class<?>[] {int.class}, index);
        return r instanceof Integer ? (Integer) r : 50;
    }

    static void setWhiteBalance(int index, int value) {
        call("setWhiteBalanceValue", new Class<?>[] {int.class, int.class}, index, value);
    }

    /** What Xiaomi's "reset 20-point white balance" does. */
    static void resetWhiteBalance() {
        setWhiteBalance(0, 5);
    }

    static void resetColorTune() {
        setColorTune(TUNE_RESET, -1, -1);
    }
}
