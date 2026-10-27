package com.clean.tvhome;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/** HDMI / AV sources from the TV input framework, plus a Live TV entry. */
final class Inputs {

    static final int HDMI = 0, AV = 1, LIVE = 2;

    static final class Source {
        final String label;
        final Intent intent;
        final int type;
        /** Something is plugged in and talking: the TV reports the input connected, or a CEC
         *  device sits behind it. (This firmware reports HDMI cables as "unknown".) */
        final boolean connected;

        Source(String label, Intent intent, int type, boolean connected) {
            this.label = label;
            this.intent = intent;
            this.type = type;
            this.connected = connected;
        }
    }

    private Inputs() {}

    static List<Source> list(Context c) {
        List<Source> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) {
            List<TvInputInfo> hdmi = new ArrayList<>();
            List<TvInputInfo> other = new ArrayList<>();
            java.util.Set<String> withDevice = new java.util.HashSet<>();
            for (TvInputInfo i : tim.getTvInputList()) if (i.getParentId() != null) withDevice.add(i.getParentId());
            for (TvInputInfo i : tim.getTvInputList()) {
                // CEC devices hang off an HDMI port as children; list the ports only.
                if (!i.isPassthroughInput() || i.getParentId() != null) continue;
                switch (i.getType()) {
                    case TvInputInfo.TYPE_HDMI:
                        hdmi.add(i);
                        break;
                    case TvInputInfo.TYPE_COMPOSITE:
                    case TvInputInfo.TYPE_COMPONENT:
                    case TvInputInfo.TYPE_SVIDEO:
                    case TvInputInfo.TYPE_SCART:
                    case TvInputInfo.TYPE_VGA:
                    case TvInputInfo.TYPE_DVI:
                    case TvInputInfo.TYPE_DISPLAY_PORT:
                        other.add(i);
                        break;
                    default:
                        break;
                }
            }
            hdmi.sort((a, b) -> label(c, a).compareTo(label(c, b)));
            for (TvInputInfo i : hdmi) out.add(passthrough(c, i, HDMI, connected(tim, i, withDevice)));
            for (TvInputInfo i : other) out.add(passthrough(c, i, AV, connected(tim, i, withDevice)));
        }
        Intent live = c.getPackageManager()
                .getLeanbackLaunchIntentForPackage(c.getString(R.string.live_tv_package));
        if (live != null) out.add(new Source(c.getString(R.string.live_tv), live, LIVE, false));
        return out;
    }

    private static boolean connected(TvInputManager tim, TvInputInfo i, java.util.Set<String> withDevice) {
        return tim.getInputState(i.getId()) == TvInputManager.INPUT_STATE_CONNECTED || withDevice.contains(i.getId());
    }

    private static Source passthrough(Context c, TvInputInfo i, int type, boolean connected) {
        Intent view = new Intent(Intent.ACTION_VIEW,
                TvContract.buildChannelUriForPassthroughInput(i.getId()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return new Source(label(c, i), view, type, connected);
    }

    private static String label(Context c, TvInputInfo i) {
        CharSequence l = i.loadCustomLabel(c);
        if (TextUtils.isEmpty(l)) l = i.loadLabel(c);
        return l == null ? i.getId() : l.toString();
    }
}
