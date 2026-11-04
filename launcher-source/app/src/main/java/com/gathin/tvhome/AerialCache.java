package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Aerials kept on the TV, so they play from storage instead of streaming: smooth from the first frame, nothing
 * buffering, no network while they play.
 *
 * <p>Which: the aerial behind Home (first), and a few for the screensaver from the chosen themes, one new one a
 * day while an old one makes room. Which version: 4K on 4K TVs that play it, otherwise 1080p HEVC (smaller), or
 * 1080p H.264. How much: never more than half the free space, and at most 1.6 GB. Downloads resume where they
 * stopped and run one at a time in the background.
 *
 * <p>A TV short on space (under 1.5 GB to work with) streams instead: nothing is downloaded and what was is
 * deleted. An aerial too big for the space left streams too, while the rest play from storage.
 */
final class AerialCache {

    private static final String TAG = "OtvAerialCache";
    private static final long MAX_BYTES = 1600L << 20;
    private static final long LOW_SPACE = 1500L << 20;
    private static final int SCREENSAVER_TARGET = 4;
    private static final long DAY = 24L * 3600 * 1000;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static volatile boolean queued;

    private AerialCache() {
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "aerial-videos");
        d.mkdirs();
        return d;
    }

    static File file(Context c, String id) {
        return new File(dir(c), id + ".mov");
    }

    /** The aerial's video on the TV, or null while it's still downloading. */
    static String local(Context c, String id) {
        File f = file(c, id);
        return f.exists() && f.length() > 0 ? f.getPath() : null;
    }

    /** Streaming instead of downloading: the TV is short on space, or this one didn't fit. */
    static boolean streams(Context c, String id) {
        SharedPreferences p = prefs(c);
        return p.getBoolean("lowSpace", false) || ("," + p.getString("streamed", "") + ",").contains("," + id + ",");
    }

    /**
     * Where to play an aerial from: the TV's copy; a stream when it streams; null while it's downloading.
     * The screensaver asks for its full-quality version (4K on 4K TVs), Home for 1080p (its interface is 1080p).
     */
    static String source(Context c, Aerials.Aerial a, boolean screensaver) {
        String local = local(c, a.id);
        if (local != null) return local;
        if (streams(c, a.id)) return screensaver ? Aerials.videoFor(c, a) : a.url1080;
        return null;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("aerial-cache", Context.MODE_PRIVATE);
    }

    /** Brings the downloads up to date in the background (safe to call often). */
    static void update(Context ctx) {
        if (queued) return;
        queued = true;
        Context c = ctx.getApplicationContext();
        worker.execute(() -> {
            queued = false;
            try {
                maintain(c);
            } catch (Exception e) {
                Log.w(TAG, "aerial downloads stopped", e);
            }
        });
    }

    private static void maintain(Context c) throws Exception {
        List<Aerials.Aerial> all = Aerials.list(c);
        if (all.isEmpty()) return;
        // Short on space: stream everything, and give back what was downloaded.
        long used = 0;
        File[] have = dir(c).listFiles();
        if (have != null) for (File f : have) used += f.length();
        if (dir(c).getUsableSpace() + used < LOW_SPACE) {
            if (have != null) for (File f : have) f.delete();
            prefs(c).edit().putBoolean("lowSpace", true).apply();
            Log.i(TAG, "short on space: aerials stream");
            return;
        }
        prefs(c).edit().putBoolean("lowSpace", false).apply();
        // 1. The aerial behind Home.
        String home = null;
        String kind = Wallpapers.kind(c);
        if (kind.startsWith("aerial:")) home = kind.substring(7);
        // 2. The screensaver's: the ones kept so far, plus a new one a day, from the chosen themes.
        Set<String> saver = new LinkedHashSet<>();
        if (ScreenSaver.type(c).equals(ScreenSaver.AERIALS)) {
            String theme = ScreenSaver.theme(c);
            for (String id : prefs(c).getString("saver", "").split(",")) {
                Aerials.Aerial a = id.isEmpty() ? null : Aerials.byId(c, id);
                if (a != null && (theme.equals(ScreenSaver.ALL) || theme.equals(a.category))) saver.add(id);
            }
            boolean newDay = System.currentTimeMillis() - prefs(c).getLong("added", 0) > DAY;
            if (saver.size() < SCREENSAVER_TARGET || newDay) {
                List<Aerials.Aerial> pick = new ArrayList<>();
                for (Aerials.Aerial a : all) {
                    if ((theme.equals(ScreenSaver.ALL) || theme.equals(a.category)) && !saver.contains(a.id)) pick.add(a);
                }
                Collections.shuffle(pick, new Random());
                if (!pick.isEmpty()) {
                    saver.add(pick.get(0).id);
                    prefs(c).edit().putLong("added", System.currentTimeMillis()).apply();
                }
                // Over the target: the oldest makes room.
                while (saver.size() > SCREENSAVER_TARGET) saver.remove(saver.iterator().next());
            }
        }
        List<String> wanted = new ArrayList<>();
        if (home != null) wanted.add(home);
        for (String id : saver) if (!wanted.contains(id)) wanted.add(id);

        // Anything no longer wanted goes (and half-finished downloads of it).
        File[] files = dir(c).listFiles();
        if (files != null) for (File f : files) {
            String id = f.getName().replaceFirst("\\.(mov|part)$", "");
            if (!wanted.contains(id)) f.delete();
        }

        // Download in order, within the space allowed; one that doesn't fit streams.
        List<String> kept = new ArrayList<>();
        List<String> streamed = new ArrayList<>();
        for (String id : wanted) {
            Aerials.Aerial a = Aerials.byId(c, id);
            if (a == null) continue;
            if (local(c, id) != null) {
                kept.add(id);
                continue;
            }
            String url = versionFor(c, a);
            long size = length(url);
            if (size <= 0 || size > room(c)) {
                Log.i(TAG, "no room for " + a.name + " (" + (size >> 20) + " MB): it streams");
                streamed.add(id);
                kept.add(id);
                continue;
            }
            if (download(url, file(c, id), size)) kept.add(id);
        }
        // The screensaver list remembers what's really on the TV.
        List<String> saverKept = new ArrayList<>();
        for (String id : saver) if (kept.contains(id)) saverKept.add(id);
        prefs(c).edit().putString("saver", String.join(",", saverKept)).putString("streamed", String.join(",", streamed)).apply();
        if (home != null && kept.contains(home)) {
            // Home switches from the still to the aerial on its next look at the wallpaper.
            Wallpapers.prefs(c).edit().putLong("changed", System.currentTimeMillis()).apply();
        }
    }

    /** 4K on 4K TVs that can play it; else 1080p HEVC (about two thirds the size) or H.264. */
    private static String versionFor(Context c, Aerials.Aerial a) {
        if (Wallpapers.is4k(c) && a.url4k != null && Aerials.canPlay4kHevc()) return a.url4k;
        if (a.url1080hevc != null && Aerials.canPlayHevc()) return a.url1080hevc;
        return a.url1080;
    }

    /** Space for aerials: half the free space (counting what they already use), at most 1.6 GB. */
    private static long room(Context c) {
        long used = 0;
        File[] files = dir(c).listFiles();
        if (files != null) for (File f : files) if (f.getName().endsWith(".mov")) used += f.length();
        long budget = Math.min(MAX_BYTES, (dir(c).getUsableSpace() + used) / 2);
        return budget - used;
    }

    private static long length(String url) {
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setRequestMethod("HEAD");
            h.setConnectTimeout(10000);
            long n = h.getContentLengthLong();
            h.disconnect();
            return n;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Downloads (resuming a partial file), then puts it in place in one step. */
    private static boolean download(String url, File to, long size) {
        File part = new File(to.getPath().replaceFirst("\\.mov$", ".part"));
        long have = part.exists() ? part.length() : 0;
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(15000);
            h.setReadTimeout(30000);
            if (have > 0) h.setRequestProperty("Range", "bytes=" + have + "-");
            boolean resumed = h.getResponseCode() == 206;
            if (!resumed) have = 0;
            try (InputStream in = h.getInputStream(); FileOutputStream out = new FileOutputStream(part, resumed)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (part.length() < size) return false;
            return part.renameTo(to);
        } catch (Exception e) {
            Log.w(TAG, "download paused: " + url, e);
            return false;
        }
    }
}
