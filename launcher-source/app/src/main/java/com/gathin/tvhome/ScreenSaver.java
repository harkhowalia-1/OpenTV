package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * OtvOS's screensaver settings, as on tvOS (Settings › General › Screen Saver): the type (Aerials, Wallpapers or
 * Clock), which aerial themes play, when it starts, whether it shows during music, plus a clock over it. OtvOS's
 * screensaver replaces Android's: it's made the TV's screensaver whenever OtvOS is allowed to (the installer
 * grants that), so Google's ambient mode never shows.
 */
final class ScreenSaver {

    static final String AERIALS = "aerials", WALLPAPERS = "wallpapers", CLOCK = "clock";
    static final String ALL = "All";
    /** Start After, in minutes (0: never), as tvOS offers. */
    static final int[] START_AFTER = {2, 5, 10, 15, 30, 0};

    private ScreenSaver() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("screensaver", Context.MODE_PRIVATE);
    }

    static String type(Context c) {
        return prefs(c).getString("type", AERIALS);
    }

    static String typeName(Context c) {
        switch (type(c)) {
            case WALLPAPERS: return c.getString(R.string.ss_wallpapers);
            case CLOCK: return c.getString(R.string.ss_clock);
            default: return c.getString(R.string.ss_aerials);
        }
    }

    static String theme(Context c) {
        return prefs(c).getString("theme", ALL);
    }

    static boolean clock(Context c) {
        return prefs(c).getBoolean("clock", true);
    }

    static boolean duringMusic(Context c) {
        return prefs(c).getBoolean("music", true);
    }

    /** Minutes before the screensaver starts (0: never), or -1 if unknown. */
    static int startAfter(Context c) {
        try {
            if (Settings.Secure.getInt(c.getContentResolver(), "screensaver_enabled", 1) == 0) return 0;
            int ms = Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT);
            return ms >= Integer.MAX_VALUE / 2 ? 0 : Math.max(1, ms / 60000);
        } catch (Exception e) {
            return -1;
        }
    }

    static boolean canSetStartAfter(Context c) {
        return Settings.System.canWrite(c);
    }

    static void setStartAfter(Context c, int minutes) {
        if (minutes > 0) Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, minutes * 60000);
        if (canSecure(c)) Settings.Secure.putInt(c.getContentResolver(), "screensaver_enabled", minutes > 0 ? 1 : 0);
    }

    private static boolean canSecure(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    static String component(Context c) {
        return new ComponentName(c, ClockDream.class).flattenToString();
    }

    /** Is OtvOS's screensaver the TV's screensaver? */
    static boolean isOurs(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "screensaver_components");
        return s != null && s.contains(c.getPackageName());
    }

    /** Makes OtvOS's screensaver the TV's (turning Android's off), where OtvOS is allowed to. */
    static void enforce(Context c) {
        if (!canSecure(c) || isOurs(c)) return;
        try {
            Settings.Secure.putString(c.getContentResolver(), "screensaver_components", component(c));
            Settings.Secure.putString(c.getContentResolver(), "screensaver_default_component", component(c));
            Settings.Secure.putInt(c.getContentResolver(), "screensaver_activate_on_sleep", 1);
        } catch (Exception ignored) {
        }
    }
}
