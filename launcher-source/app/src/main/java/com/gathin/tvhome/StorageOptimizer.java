package com.gathin.tvhome;

import android.app.AppOpsManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the TV's storage healthy on its own. OtvOS tidies its own files all the time (Cleanup, aerial and
 * wallpaper budgets); this does the same for other apps: it finds the ones holding big caches (Android's
 * storage statistics) and clears them through each app's Settings page, the only way Android allows.
 *
 * <p>Only while nobody is watching: Home in front and no key pressed for three minutes. Behind a cover card;
 * any key stops it. Weekly, or every few hours while space is short.
 */
final class StorageOptimizer {

    private static final String TAG = "OtvStorage";
    private static final long IDLE_MS = 3 * 60_000;
    private static final long WEEK = 7L * 24 * 3600 * 1000, FEW_HOURS = 6L * 3600 * 1000;
    private static final long LOW_SPACE = 1500L << 20, BIG_CACHE = 30L << 20;

    /** The last key press (remote, phone, keyboard), from HomeKeyService. */
    static volatile long lastInput = SystemClock.uptimeMillis();

    private StorageOptimizer() {
    }

    static void maybeRun(HomeKeyService s, String foreground) {
        String wait = !s.getPackageName().equals(foreground) ? "Home isn't in front (" + foreground + ")"
                : SystemClock.uptimeMillis() - lastInput < IDLE_MS ? "someone's using the TV"
                : !canMeasure(s) ? "no usage access" : null;
        PowerManager pm = s.getSystemService(PowerManager.class);
        if (wait == null && (pm == null || !pm.isInteractive())) wait = "the screen is off";
        if (wait != null) {
            Log.d(TAG, "waiting: " + wait);
            return;
        }
        SharedPreferences p = s.getSharedPreferences("storage", Context.MODE_PRIVATE);
        boolean low = new File(s.getFilesDir().getPath()).getUsableSpace() < LOW_SPACE;
        long since = System.currentTimeMillis() - p.getLong("lastRun", 0);
        if (since < (low ? FEW_HOURS : WEEK)) return;
        p.edit().putLong("lastRun", System.currentTimeMillis()).apply();
        Cleanup.run(s);
        List<String> apps = bigCaches(s);
        Log.i(TAG, (low ? "short on space; " : "") + "clearing caches of " + apps);
        if (!apps.isEmpty()) AppCloser.clearCaches(s, apps);
    }

    /** Usage access (the installer allows it): needed to see other apps' cache sizes. */
    private static boolean canMeasure(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        return ops != null && ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(),
                c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    /** Apps holding more than 30 MB of cache, biggest first (at most 12 a time). */
    private static List<String> bigCaches(Context c) {
        StorageStatsManager ssm = c.getSystemService(StorageStatsManager.class);
        PackageManager pm = c.getPackageManager();
        String ime = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        List<long[]> sizes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            String pkg = ai.packageName;
            if (pkg.equals(c.getPackageName()) || pkg.equals("android") || pkg.startsWith("com.android.")
                    || (ime != null && ime.startsWith(pkg + "/"))) continue;
            // Apps people use (with a Home entry) and apps they installed; never system services.
            boolean user = (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                    || pm.getLeanbackLaunchIntentForPackage(pkg) != null;
            if (!user) continue;
            try {
                StorageStats st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle());
                if (st.getCacheBytes() > BIG_CACHE) {
                    sizes.add(new long[]{st.getCacheBytes(), names.size()});
                    names.add(pkg);
                }
            } catch (Exception ignored) {
            }
        }
        sizes.sort((a, b) -> Long.compare(b[0], a[0]));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(12, sizes.size()); i++) out.add(names.get((int) sizes.get(i)[1]));
        return out;
    }
}
