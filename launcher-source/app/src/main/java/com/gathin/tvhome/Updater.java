package com.gathin.tvhome;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenTV's updates from GitHub releases: what's newer than what's installed, downloading it, and installing it.
 *
 * <p>Automatic updates: a few minutes after the TV starts and then every six hours, OtvOS checks for a new
 * release and installs it in the background (IPTV first, then OtvOS itself, which restarts). Android installs
 * silently once OtvOS has installed the app before; otherwise the update waits in Settings › Software Update
 * for one confirmation.
 */
final class Updater {

    static final String LATEST = "https://api.github.com/repos/harkhowalia-1/OpenTV/releases/latest";
    /** Release file, package, name. OtvOS last: installing it restarts this app. */
    static final String[][] APPS = {{"IPTV.apk", "com.gathin.otvlive", "IPTV"}, {"Browser.apk", "com.gathin.otvbrowser", "Browser"},
            {"CloudGaming.apk", "local.cloudgaming", "Cloud Gaming"}, {"OtvOS.apk", "com.gathin.tvhome", "OtvOS"}};
    private static final String TAG = "OtvUpdate";
    static final long EVERY_MS = 6L * 3600 * 1000;

    static final class Release {
        String version = "", notes = "";
        final List<String[]> apps = new ArrayList<>();   // url, package, name
    }

    static String installedVersion(Context c, String pkg) {
        try {
            String v = c.getPackageManager().getPackageInfo(pkg, 0).versionName;
            return v == null ? "" : v;
        } catch (Exception e) {
            return null;
        }
    }

    /** Is version a newer than b ("1.10" > "1.9")? */
    static boolean newer(String a, String b) {
        if (b == null || b.isEmpty()) return true;
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The newest release, with only the apps that are newer than what's installed (and installed at all). */
    static Release check(Context c) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(LATEST).openConnection();
        h.setRequestProperty("Accept", "application/vnd.github+json");
        h.setConnectTimeout(10000);
        h.setReadTimeout(15000);
        String body;
        try (InputStream in = h.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            body = out.toString("UTF-8");
        }
        JSONObject o = new JSONObject(body);
        Release r = new Release();
        r.version = o.optString("tag_name").replaceFirst("^v", "");
        r.notes = o.optString("body");
        JSONArray assets = o.optJSONArray("assets");
        for (String[] app : APPS) {
            String have = installedVersion(c, app[1]);
            if (have == null && !app[1].equals(c.getPackageName())) continue;
            if (!newer(r.version, have)) continue;
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (app[0].equals(a.optString("name"))) r.apps.add(new String[]{a.optString("browser_download_url"), app[1], app[2]});
            }
        }
        return r;
    }

    interface Progress {
        void on(int percent);
    }

    static void download(String url, File f, Progress p) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setInstanceFollowRedirects(true);
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        long total = h.getContentLengthLong(), got = 0;
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                got += n;
                if (p != null && total > 0) p.on((int) (got * 100 / total));
            }
        }
    }

    /** Installs without asking where Android allows it (OtvOS installed the app before); else asks once. */
    static void install(Context c, File apk, String pkg, android.content.IntentSender done) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(pkg);
        if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream out = s.openWrite("app.apk", 0, apk.length()); InputStream in = new FileInputStream(apk)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            s.commit(done);
        }
    }

    // ---- automatic updates ------------------------------------------------------------------------------

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("update", Context.MODE_PRIVATE);
    }

    /** Checks and, if there's something new, installs it in the background. Safe to call often. */
    static void autoUpdate(Context ctx) {
        Context c = ctx.getApplicationContext();
        if (!c.getPackageManager().canRequestPackageInstalls()) return;   // not allowed to install apps
        new Thread(() -> {
            try {
                Release r = check(c);
                if (r.apps.isEmpty()) return;
                Log.i(TAG, "installing " + r.version + " in the background");
                StringBuilder queue = new StringBuilder();
                for (String[] app : r.apps) {
                    File f = new File(c.getCacheDir(), "auto-" + app[1] + ".apk");
                    download(app[0], f, null);
                    if (queue.length() > 0) queue.append(',');
                    queue.append(app[1]);
                }
                prefs(c).edit().putString("queue", queue.toString()).putString("version", r.version).apply();
                installNext(c);
            } catch (Exception e) {
                Log.w(TAG, "automatic update didn't finish", e);
            }
        }, "otv-auto-update").start();
    }

    /** Installs the next downloaded app in the queue; the result comes back to {@link Result}. */
    static void installNext(Context c) {
        SharedPreferences p = prefs(c);
        String queue = p.getString("queue", "");
        if (queue.isEmpty()) return;
        String pkg = queue.split(",")[0];
        String rest = queue.contains(",") ? queue.substring(queue.indexOf(',') + 1) : "";
        p.edit().putString("queue", rest).apply();
        try {
            Intent i = new Intent(c, Result.class).putExtra("pkg", pkg);
            PendingIntent pi = PendingIntent.getBroadcast(c, pkg.hashCode(), i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            install(c, new File(c.getCacheDir(), "auto-" + pkg + ".apk"), pkg, pi.getIntentSender());
        } catch (Exception e) {
            Log.w(TAG, "couldn't install " + pkg, e);
        }
    }

    /** Where Android reports how each background install went. */
    public static final class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String pkg = i.getStringExtra("pkg");
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // Android wants a confirmation this time: wait for Settings › Software Update.
                prefs(c).edit().putBoolean("needsConfirm", true).apply();
                Log.i(TAG, pkg + " is waiting for a confirmation in Software Update");
                return;
            }
            new File(c.getCacheDir(), "auto-" + pkg + ".apk").delete();
            if (status == PackageInstaller.STATUS_SUCCESS) {
                Log.i(TAG, pkg + " updated");
                installNext(c);
            } else {
                Log.w(TAG, pkg + " update failed: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            }
        }
    }
}
