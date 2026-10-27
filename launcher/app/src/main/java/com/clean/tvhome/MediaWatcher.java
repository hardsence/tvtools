package com.clean.tvhome;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;

import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Records what plays in other apps for the home screen's "Continue watching" row, from their
 * media sessions (a notification listener is the only way an ordinary app may see them).
 * Saves title, subtitle, cover, position and duration while playing (every 15 s and on pause).
 * The session's activity (deep link back into the item) is kept in memory until reboot.
 */
public class MediaWatcher extends NotificationListenerService {

    private static final long SAVE_MS = 15_000;
    /** Deep links of recent items, by item key (PendingIntents can't be stored on disk). */
    static final Map<String, PendingIntent> links = new HashMap<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<MediaController.Callback, MediaController> watched = new HashMap<>();
    private MediaSessionManager msm;
    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::watch;

    private final Runnable periodic = new Runnable() {
        @Override
        public void run() {
            for (MediaController mc : watched.values()) {
                PlaybackState s = mc.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) record(mc);
            }
            ui.postDelayed(this, SAVE_MS);
        }
    };

    @Override
    public void onListenerConnected() {
        msm = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, MediaWatcher.class);
        try {
            msm.addOnActiveSessionsChangedListener(sessions, me);
            watch(msm.getActiveSessions(me));
        } catch (SecurityException ignored) {
            return;
        }
        ui.postDelayed(periodic, SAVE_MS);
    }

    @Override
    public void onListenerDisconnected() {
        ui.removeCallbacks(periodic);
        if (msm != null) msm.removeOnActiveSessionsChangedListener(sessions);
        for (Map.Entry<MediaController.Callback, MediaController> e : watched.entrySet())
            e.getValue().unregisterCallback(e.getKey());
        watched.clear();
    }

    private void watch(List<MediaController> list) {
        if (list == null) return;
        for (MediaController mc : list) {
            if (mc.getPackageName().equals(getPackageName()) || watched.containsValue(mc)) continue;
            boolean known = false;
            for (MediaController w : watched.values()) if (w.getSessionToken().equals(mc.getSessionToken())) known = true;
            if (known) continue;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override
                public void onPlaybackStateChanged(PlaybackState s) {
                    if (s == null) return;
                    int st = s.getState();
                    if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_PAUSED
                            || st == PlaybackState.STATE_STOPPED) record(mc);
                }

                @Override
                public void onMetadataChanged(MediaMetadata m) {
                    PlaybackState s = mc.getPlaybackState();
                    if (s != null && s.getState() == PlaybackState.STATE_PLAYING) record(mc);
                }

                @Override
                public void onSessionDestroyed() {
                    record(mc);
                    mc.unregisterCallback(this);
                    watched.remove(this);
                }
            };
            mc.registerCallback(cb, ui);
            watched.put(cb, mc);
        }
    }

    private static String text(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (!TextUtils.isEmpty(v)) return v.toString();
        }
        return "";
    }

    private void record(MediaController mc) {
        MediaMetadata m = mc.getMetadata();
        PlaybackState s = mc.getPlaybackState();
        if (m == null) return;
        String title = text(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        if (title.isEmpty()) return;
        ContinueStore.Item it = new ContinueStore.Item();
        it.pkg = mc.getPackageName();
        it.title = title;
        it.subtitle = text(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION);
        it.key = it.pkg + "|" + title;
        it.duration = Math.max(0, m.getLong(MediaMetadata.METADATA_KEY_DURATION));
        if (s != null) {
            long pos = s.getPosition();
            if (s.getState() == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() > 0)
                pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
            it.position = Math.max(0, pos);
        }
        it.time = System.currentTimeMillis();
        PendingIntent link = mc.getSessionActivity();
        if (link != null) links.put(it.key, link);

        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        String uri = text(m, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
        Bitmap bm = art == null ? null : art.copy(Bitmap.Config.ARGB_8888, false);
        boolean needArt = bm == null && !ContinueStore.artFile(this, it.key).isFile();
        io.execute(() -> {
            Bitmap a = bm;
            if (a == null && needArt && uri.startsWith("http")) {
                try (InputStream in = new URL(uri).openStream()) {
                    a = BitmapFactory.decodeStream(in);
                } catch (Exception ignored) {
                }
            }
            ContinueStore.put(this, it, a);
        });
    }
}
