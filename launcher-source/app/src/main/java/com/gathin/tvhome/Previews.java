package com.gathin.tvhome;

import android.content.Context;

/**
 * Wallpaper previews made ahead of time, in the background, so every card in Settings › Wallpaper (and the
 * iPhone's wallpaper list) shows its picture straight away, downloaded or not. Each preview is made once and
 * kept; this goes through the collections once a day and makes any that are missing.
 */
final class Previews {

    private static final long DAY = 24L * 3600 * 1000;
    private static volatile boolean running;

    private Previews() {
    }

    /** Call off the main thread. */
    static void prefetch(Context c) {
        if (running) return;
        android.content.SharedPreferences p = c.getSharedPreferences("previews", Context.MODE_PRIVATE);
        if (System.currentTimeMillis() - p.getLong("done", 0) < DAY) return;
        running = true;
        try {
            for (Aerials.Aerial a : Aerials.list(c)) Aerials.thumbnail(c, a);
            if (MacOS.supported()) for (MacOS.Item it : MacOS.list(c)) MacOS.thumbnail(c, it);
            p.edit().putLong("done", System.currentTimeMillis()).apply();
        } finally {
            running = false;
        }
    }
}
