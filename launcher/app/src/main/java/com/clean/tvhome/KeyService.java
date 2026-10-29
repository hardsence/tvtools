package com.clean.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Remote buttons the stock Google TV launcher used to answer (Settings/dashboard, app keys,
 * Apps) do nothing once it is disabled. This service sees keys before the system's global-key
 * handling and runs the action configured in config.xml (key_actions). Unmapped keys pass
 * through untouched. Every key is logged under the tag CleanHomeKeys to find new codes.
 */
public class KeyService extends AccessibilityService {

    static final String TAG = "CleanHomeKeys";
    static final String EXTRA_ACTION = "com.clean.tvhome.ACTION";

    private final Map<String, String> actions = new HashMap<>();

    @Override
    protected void onServiceConnected() {
        actions.clear();
        // Entries look like "KEYCODE_SETTINGS=settings" or "scan:425=app:org.smarttube.beta".
        for (String entry : getResources().getStringArray(R.array.key_actions)) {
            int eq = entry.indexOf('=');
            if (eq > 0) actions.put(entry.substring(0, eq).trim(), entry.substring(eq + 1).trim());
        }
        Log.i(TAG, "connected, " + actions.size() + " mapped keys");
        if (effects == null) {
            effects = new ScreenEffects(this);
            effects.start();
        }
        if (timer == null) {
            timer = new ParentalTimer(this);
            timer.start();                    // an expired timer locks again right after boot
        }
    }

    private ScreenEffects effects;
    private ParentalTimer timer;

    @Override
    public void onDestroy() {
        if (effects != null) effects.stop();
        effects = null;
        if (timer != null) timer.stop();
        timer = null;
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int keyCode = e.getKeyCode();
        boolean dpad = keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (dpad && e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() > 0) return true;
        if (timer != null && timer.onKey(e)) return true;     // usage-timer lock swallows keys
        if (effects != null && effects.onKey(e)) return true; // wakes the screen after "screen off"
        String name = KeyEvent.keyCodeToString(e.getKeyCode());
        String action = actions.get(name);
        if (action == null) action = actions.get("scan:" + e.getScanCode());
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            String dev = e.getDevice() == null ? "?" : e.getDevice().getName();
            Log.i(TAG, name + " scan=" + e.getScanCode() + " device=" + dev
                    + (action == null ? "" : " -> " + action));
        }
        if (action == null) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) run(action);
        return true;
    }

    private void run(String action) {
        Intent i;
        if (action.equals("settings")) {
            i = new Intent(Settings.ACTION_SETTINGS);
        } else if (action.startsWith("app:")) {
            String pkg = action.substring(4);
            i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return;
        } else { // "apps", "inputs", "home": handled by the home screen itself
            i = new Intent(this, HomeActivity.class).putExtra(EXTRA_ACTION, action);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException ex) {
            Log.w(TAG, "cannot run " + action, ex);
        }
    }

    /**
     * Android removes an accessibility service from the enabled list when its app is
     * force-stopped. Put it back if we hold WRITE_SECURE_SETTINGS (granted by install.ps1).
     */
    static void ensureEnabled(Context c) {
        if (c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) return;
        ContentResolver cr = c.getContentResolver();
        String me = new ComponentName(c, KeyService.class).flattenToString();
        String cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (cur != null) {
            for (String s : cur.split(":")) if (s.equalsIgnoreCase(me)) return;
        }
        String next = cur == null || cur.isEmpty() ? me : cur + ":" + me;
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Log.i(TAG, "re-enabled key service");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}
}
