package com.gathin.tvhome;

import android.content.Context;

import java.io.File;

/**
 * Keeps OtvOS small: cached files nobody has used for a while go (previews come back when they're needed),
 * and leftovers from finished jobs (update downloads, temporary pictures) are removed. Run hourly, in the
 * background; aerial videos and wallpapers are tidied by their own code (only what's in use is kept). Wallpaper
 * previews stay: they're tiny and slow to make again.
 */
final class Cleanup {

    private static final long WEEK = 7L * 24 * 3600 * 1000;
    private static final long CACHE_LIMIT = 150L << 20;

    private Cleanup() {
    }

    static void run(Context c) {
        long now = System.currentTimeMillis();
        // The app's cache: anything a week old; and the oldest first while it's over 150 MB.
        File cache = c.getCacheDir();
        File[] files = cache.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) {
            if (f.isFile() && now - f.lastModified() > WEEK) f.delete();
            else total += size(f);
        }
        if (total > CACHE_LIMIT) {
            files = cache.listFiles();
            if (files == null) return;
            java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : files) {
                if (total <= CACHE_LIMIT) break;
                total -= size(f);
                delete(f);
            }
        }
    }

    private static long size(File f) {
        if (f.isFile()) return f.length();
        long n = 0;
        File[] k = f.listFiles();
        if (k != null) for (File x : k) n += size(x);
        return n;
    }

    private static void delete(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File x : k) delete(x);
        f.delete();
    }
}
