package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.util.Locale;

/**
 * "Send to TV": opens a link shared from the iPhone or Mac in the right TV app, at the same moment when the
 * sender knows it, so the phone or Mac is free again while the TV plays. YouTube, Netflix and Spotify go to
 * their TV apps; a direct video or audio file plays in {@link PlayerActivity}; anything else opens in the TV's
 * web browser.
 */
final class MediaHandoff {

    private static final String TAG = "TvHandoff";

    private MediaHandoff() {
    }

    /** Returns what it opened ("YouTube", "Player", ...) or null when nothing could. */
    static String open(Context ctx, String url, double seconds) {
        if (url == null || url.isEmpty()) return null;
        Uri u = Uri.parse(url.trim());
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        String path = u.getPath() == null ? "" : u.getPath();
        try {
            String yt = youtubeId(u, host, path);
            if (yt != null) {
                int t = seconds > 0 ? (int) seconds : parseTime(u.getQueryParameter("t"));
                Uri watch = Uri.parse("https://www.youtube.com/watch?v=" + yt + (t > 0 ? "&t=" + t + "s" : ""));
                return start(ctx, new Intent(Intent.ACTION_VIEW, watch).setPackage("com.google.android.youtube.tv"),
                        "YouTube");
            }
            if (host.endsWith("netflix.com") && path.startsWith("/watch/")) {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.netflix.com" + path))
                        .setPackage("com.netflix.ninja").putExtra("source", "30");
                return start(ctx, i, "Netflix");
            }
            if (host.equals("open.spotify.com")) {
                String[] parts = path.split("/");
                if (parts.length >= 3) {
                    Uri sp = Uri.parse("spotify:" + parts[parts.length - 2] + ":" + parts[parts.length - 1]);
                    return start(ctx, new Intent(Intent.ACTION_VIEW, sp).setPackage("com.spotify.tv.android"), "Spotify");
                }
            }
            if (isMedia(path)) {
                Intent i = new Intent(ctx, PlayerActivity.class).setData(u).putExtra(PlayerActivity.EXTRA_START, seconds);
                return start(ctx, i, "Player");
            }
            if (url.startsWith("http")) {
                return start(ctx, new Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE), "Browser");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "can't open " + url, e);
        }
        return null;
    }

    private static String start(Context ctx, Intent i, String what) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            return what;
        } catch (ActivityNotFoundException e) {
            return null;
        }
    }

    private static String youtubeId(Uri u, String host, String path) {
        if (host.equals("youtu.be")) return path.length() > 1 ? path.substring(1) : null;
        if (!host.endsWith("youtube.com")) return null;
        if (path.equals("/watch")) return u.getQueryParameter("v");
        if (path.startsWith("/shorts/") || path.startsWith("/live/") || path.startsWith("/embed/")) {
            String[] p = path.split("/");
            return p.length > 2 ? p[2] : null;
        }
        return null;
    }

    /** "90", "90s", "1m30s" or "1h2m3s" to seconds. */
    static int parseTime(String t) {
        if (t == null || t.isEmpty()) return 0;
        try {
            if (t.matches("\\d+s?")) return Integer.parseInt(t.replace("s", ""));
            int total = 0, n = 0;
            for (char c : t.toCharArray()) {
                if (Character.isDigit(c)) n = n * 10 + (c - '0');
                else {
                    total += c == 'h' ? n * 3600 : c == 'm' ? n * 60 : n;
                    n = 0;
                }
            }
            return total + n;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isMedia(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        return p.endsWith(".mp4") || p.endsWith(".m4v") || p.endsWith(".mov") || p.endsWith(".webm")
                || p.endsWith(".mkv") || p.endsWith(".m3u8") || p.endsWith(".mp3") || p.endsWith(".m4a")
                || p.endsWith(".aac") || p.endsWith(".flac") || p.endsWith(".ts");
    }
}
