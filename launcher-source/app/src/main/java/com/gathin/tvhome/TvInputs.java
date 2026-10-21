package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The TV's own inputs: HDMI ports (with whether something is plugged in), AV and the like, and Live TV. One list
 * for the status pill's Inputs panel, the home screen and the remotes (/api/inputs). Streaming sticks such as a
 * Chromecast have none, so everything Inputs-related stays hidden there.
 */
final class TvInputs {

    static final int HDMI = 0, OTHER = 1, TUNER = 2;
    static final int UNKNOWN = -1, DISCONNECTED = 0, CONNECTED = 1;
    /** The id of the Live TV entry (the TV's tuner app), which is not a passthrough input. */
    static final String LIVE_TV = "livetv";
    private static final String CHANNEL_TYPE = "vnd.android.cursor.item/channel";

    static final class Input {
        final String id;
        final String label;
        final int kind;
        final int state;
        final Intent intent;

        Input(String id, String label, int kind, int state, Intent intent) {
            this.id = id;
            this.label = label;
            this.kind = kind;
            this.state = state;
            this.intent = intent;
        }

        int icon() {
            return kind == HDMI ? R.drawable.ic_input : kind == TUNER ? R.drawable.ic_livetv : R.drawable.ic_av;
        }
    }

    private TvInputs() {
    }

    private static String liveTvPackage(Context ctx) {
        return ctx.getString(R.string.live_tv_package).trim();
    }

    /** Whether this is a TV with inputs to switch between (false on a Chromecast and other sticks). */
    static boolean available(Context ctx) {
        if (!liveTvPackage(ctx).isEmpty()) return true;
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return false;
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                // Only physical inputs count; apps such as VLC register non-passthrough "tuner" inputs.
                if (info.isPassthroughInput()) return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    /** HDMI ports first in port order, then AV and the rest, then Live TV. */
    static List<Input> list(Context ctx) {
        List<Input> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        String tunerId = null;
        if (tim != null) {
            List<TvInputInfo> inputs;
            try {
                inputs = new ArrayList<>(tim.getTvInputList());
            } catch (RuntimeException e) {
                inputs = new ArrayList<>();
            }
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return label(ctx, a).compareToIgnoreCase(label(ctx, b));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in, so
            // the HDMI status is dropped then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (state(tim, info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER && info.getServiceInfo() != null
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (tunerId == null || info.getId().compareTo(tunerId) < 0)) {
                    tunerId = info.getId();
                }
                if (!info.isPassthroughInput()) continue;
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                String label = label(ctx, info);
                if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                int raw = hdmi && hdmiStateBogus ? -1 : state(tim, info.getId());
                int state = raw == TvInputManager.INPUT_STATE_CONNECTED ? CONNECTED
                        : raw == TvInputManager.INPUT_STATE_DISCONNECTED ? DISCONNECTED : UNKNOWN;
                Intent open = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(TvContract.buildChannelUriForPassthroughInput(info.getId()), CHANNEL_TYPE);
                out.add(new Input(info.getId(), label, hdmi ? HDMI : OTHER, state, open));
            }
        }
        Intent liveTv = liveTvIntent(ctx, tunerId);
        if (liveTv != null) out.add(new Input(LIVE_TV, ctx.getString(R.string.live_tv), TUNER, UNKNOWN, liveTv));
        return out;
    }

    private static Intent liveTvIntent(Context ctx, String tunerId) {
        String pkg = liveTvPackage(ctx);
        if (pkg.isEmpty()) return null;
        Intent launch = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) return null;
        if (tunerId == null) return launch;
        return new Intent(Intent.ACTION_VIEW)
                .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), CHANNEL_TYPE)
                .setPackage(pkg);
    }

    private static String label(Context ctx, TvInputInfo info) {
        try {
            CharSequence l = info.loadLabel(ctx);
            return l == null ? info.getId() : l.toString();
        } catch (RuntimeException e) {
            return info.getId();
        }
    }

    @SuppressWarnings("deprecation")
    private static int state(TvInputManager tim, String id) {
        try {
            return tim.getInputState(id);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Switches the TV to <code>input</code>. Returns false when nothing on the TV could open it. */
    static boolean open(Context ctx, Input input) {
        Intent i = new Intent(input.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    /** Switches by id (from a remote). */
    static boolean open(Context ctx, String id) {
        for (Input in : list(ctx)) {
            if (in.id.equals(id)) return open(ctx, in);
        }
        return false;
    }

    /** For /api/inputs: [{id, name, kind: "hdmi"|"av"|"tv", connected: true|false|null}]. */
    static JSONArray json(Context ctx) throws JSONException {
        JSONArray a = new JSONArray();
        for (Input in : list(ctx)) {
            JSONObject o = new JSONObject();
            o.put("id", in.id);
            o.put("name", in.label);
            o.put("kind", in.kind == HDMI ? "hdmi" : in.kind == TUNER ? "tv" : "av");
            o.put("connected", in.state == UNKNOWN ? JSONObject.NULL : in.state == CONNECTED);
            a.put(o);
        }
        return a;
    }
}
