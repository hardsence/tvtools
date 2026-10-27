package com.clean.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;

/**
 * Inputs > Home cameras: a 2x2 live grid of the Dahua cameras (sub streams). Arrows pick a view,
 * OK opens its main stream full screen (Left/Right switch camera there), Back returns to the grid,
 * Back again closes. Players exist only while the screen is visible.
 */
@OptIn(markerClass = UnstableApi.class)
public class CamerasActivity extends Activity {

    private static final long RETRY_MS = 5000;
    private static final String TAG = "CleanHomeCam";

    /** URL for logs, without the password. */
    private static String safe(String url) {
        return url.replaceAll("//[^@]*@", "//");
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private String[] hosts;
    private Typeface font;
    private FrameLayout root;
    private final FrameLayout[] cells = new FrameLayout[4];
    private final SurfaceView[] surfaces = new SurfaceView[4];
    private final TextView[] status = new TextView[4];
    private final ExoPlayer[] players = new ExoPlayer[4];
    private FrameLayout full;
    private SurfaceView fullSurface;
    private TextView fullLabel, fullStatus;
    private ExoPlayer fullPlayer;
    private int selected;
    private boolean fullscreen, started;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hosts = getResources().getStringArray(R.array.camera_hosts);
        font = getResources().getFont(R.font.outfit);
        buildUi();
        setContentView(root);
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
        t.setShadowLayer(dp(4), 0, 0, 0xCC000000);
        return t;
    }

    private String label(int i) {
        return getString(R.string.camera_n, i + 1);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        DisplayMetrics m = getResources().getDisplayMetrics();
        int W = Math.max(m.widthPixels, m.heightPixels), H = Math.min(m.widthPixels, m.heightPixels);
        int gap = dp(14), margin = dp(28);
        // 16:9 cells, as large as fit in a 2x2 arrangement, centred
        int cw = (W - 2 * margin - gap) / 2, ch = cw * 9 / 16;
        if (2 * ch + gap > H - 2 * margin) {
            ch = (H - 2 * margin - gap) / 2;
            cw = ch * 16 / 9;
        }
        int left = (W - 2 * cw - gap) / 2, top = (H - 2 * ch - gap) / 2;
        float radius = dp(14);
        for (int i = 0; i < 4 && i < hosts.length; i++) {
            FrameLayout cell = new FrameLayout(this);
            cell.setBackgroundColor(0xFF111113);
            cell.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                }
            });
            cell.setClipToOutline(true);
            SurfaceView sv = new SurfaceView(this);
            cell.addView(sv, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            TextView name = text(16, 500, 0xFFFFFFFF);
            name.setText(label(i));
            FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            nlp.setMargins(dp(14), dp(10), 0, 0); // the cameras print their own name bottom-left
            cell.addView(name, nlp);
            TextView st = text(15, 400, 0xCCFFFFFF);
            st.setText(R.string.camera_connecting);
            cell.addView(st, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cw, ch);
            lp.leftMargin = left + (i % 2) * (cw + gap);
            lp.topMargin = top + (i / 2) * (ch + gap);
            root.addView(cell, lp);
            cells[i] = cell;
            surfaces[i] = sv;
            status[i] = st;
        }

        full = new FrameLayout(this);
        full.setBackgroundColor(Color.BLACK);
        full.setVisibility(View.GONE);
        fullSurface = new SurfaceView(this);
        full.addView(fullSurface, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        fullLabel = text(20, 500, 0xFFFFFFFF);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        flp.setMargins(dp(32), dp(24), 0, 0);
        full.addView(fullLabel, flp);
        fullStatus = text(18, 400, 0xCCFFFFFF);
        full.addView(fullStatus, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(full, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        paintSelection();
    }

    // ---------------------------------------------------------------- playback

    private String url(int i, boolean main) {
        String user = Uri.encode(getString(R.string.camera_user));
        String pass = Uri.encode(getString(R.string.camera_password));
        String path = getString(main ? R.string.camera_path_full : R.string.camera_path_grid);
        return "rtsp://" + user + ":" + pass + "@" + hosts[i] + ":554" + path;
    }

    /** A low-latency, muted RTSP player over TCP (Dahua cameras are most reliable over TCP). */
    private ExoPlayer player(String url, SurfaceView surface, TextView statusView, Runnable retry) {
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(500, 2000, 250, 250).build();
        ExoPlayer p = new ExoPlayer.Builder(this).setLoadControl(load).build();
        p.setVolume(0f);
        // Video only: without an audio track the picture runs on its own clock, so a camera with
        // odd audio timestamps can't hold its video back (that showed up as a black view).
        p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build());
        p.setVideoSurfaceView(surface);
        p.setMediaSource(new RtspMediaSource.Factory().setForceUseRtpTcp(true)
                .createMediaSource(MediaItem.fromUri(url)));
        p.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_READY) statusView.setText("");
            }

            @Override
            public void onRenderedFirstFrame() {
                Log.i(TAG, "first frame " + safe(url));
            }

            @Override
            public void onVideoSizeChanged(@NonNull VideoSize v) {
                Log.i(TAG, "video " + v.width + "x" + v.height + " " + safe(url));
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException e) {
                Log.w(TAG, "error " + safe(url) + ": " + e.getErrorCodeName() + " " + e.getMessage());
                statusView.setText(R.string.camera_offline);
                ui.postDelayed(retry, RETRY_MS);
            }
        });
        p.setPlayWhenReady(true);
        p.prepare();
        return p;
    }

    private void startGrid() {
        for (int i = 0; i < 4 && i < hosts.length; i++) startCell(i);
    }

    private void startCell(int i) {
        if (!started || fullscreen) return;
        if (players[i] != null) players[i].release();
        status[i].setText(R.string.camera_connecting);
        players[i] = player(url(i, false), surfaces[i], status[i], () -> startCell(i));
    }

    private void stopGrid() {
        ui.removeCallbacksAndMessages(null);
        for (int i = 0; i < players.length; i++) {
            if (players[i] != null) players[i].release();
            players[i] = null;
        }
    }

    private void startFull() {
        if (!started || !fullscreen) return;
        if (fullPlayer != null) fullPlayer.release();
        fullLabel.setText(label(selected));
        fullStatus.setText(R.string.camera_connecting);
        fullPlayer = player(url(selected, true), fullSurface, fullStatus, this::startFull);
    }

    private void stopFull() {
        ui.removeCallbacksAndMessages(null);
        if (fullPlayer != null) fullPlayer.release();
        fullPlayer = null;
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (fullscreen) startFull();
        else startGrid();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        stopGrid();
        stopFull();
    }

    // ---------------------------------------------------------------- navigation

    private void paintSelection() {
        for (int i = 0; i < 4 && i < hosts.length; i++) {
            boolean f = i == selected;
            cells[i].setForeground(f ? Ui.focusRing(dp(14), dp(3)) : null);
            float s = f ? 1.03f : 1f;
            cells[i].animate().scaleX(s).scaleY(s).setDuration(160).start();
            cells[i].setElevation(f ? dp(8) : 0);
        }
    }

    /** Full screen shows the main (4K) stream; the grid players are released to free decoders. */
    private void openFull() {
        fullscreen = true;
        stopGrid();
        for (FrameLayout cell : cells) if (cell != null) cell.setVisibility(View.INVISIBLE);
        full.setVisibility(View.VISIBLE);
        startFull();
    }

    private void closeFull() {
        fullscreen = false;
        stopFull();
        full.setVisibility(View.GONE);
        for (FrameLayout cell : cells) if (cell != null) cell.setVisibility(View.VISIBLE);
        paintSelection();
        startGrid();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) {
            return isHandled(e.getKeyCode()) || super.dispatchKeyEvent(e);
        }
        int n = Math.min(4, hosts.length);
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int dx = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                if (fullscreen) {                       // switch camera in full screen
                    selected = (selected + dx + n) % n;
                    startFull();
                } else {
                    int col = selected % 2 + dx;
                    if (col >= 0 && col <= 1 && (selected / 2) * 2 + col < n) selected = (selected / 2) * 2 + col;
                    paintSelection();
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                if (!fullscreen) {
                    int row = selected / 2 + (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                    if (row >= 0 && row <= 1 && row * 2 + selected % 2 < n) selected = row * 2 + selected % 2;
                    paintSelection();
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0 && !fullscreen) openFull();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (e.getRepeatCount() == 0) {
                    if (fullscreen) closeFull();
                    else finish();
                }
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private static boolean isHandled(int k) {
        return k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_UP
                || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BACK;
    }
}
