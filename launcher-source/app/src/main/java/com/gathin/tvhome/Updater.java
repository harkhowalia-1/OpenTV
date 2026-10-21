package com.gathin.tvhome;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OtvOS updates from the project's GitHub releases: the newest release's OtvOS.apk (and IPTV.apk, when IPTV is
 * installed) replaces the installed app in place. Releases are signed with the same key as the installed apps,
 * so nothing has to be set up again. A release's tag ("v1.3") is its version; the apps' versionCode is derived
 * from it the same way (build.gradle), so "newer" is a plain number comparison.
 */
final class Updater {

    static final String REPO = "harkhowalia-1/OpenTV";
    static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases";
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    static final String IPTV_PACKAGE = "com.gathin.otvlive";
    private static final String OTVOS_APK = "OtvOS.apk", IPTV_APK = "IPTV.apk";
    /** Look again at most this often in the background. */
    private static final long CHECK_EVERY_MS = 12L * 60 * 60 * 1000;

    static final String ACTION_STATUS = "com.gathin.tvhome.UPDATE_STATUS";

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** One app in a release, to install. */
    static final class Apk {
        final String name;
        final String packageName;
        final String url;
        final long size;

        Apk(String name, String packageName, String url, long size) {
            this.name = name;
            this.packageName = packageName;
            this.url = url;
            this.size = size;
        }
    }

    static final class Release {
        final String version;
        final long code;
        final String notes;
        final String otvosUrl;
        final long otvosSize;
        final String iptvUrl;
        final long iptvSize;

        Release(String version, String notes, String otvosUrl, long otvosSize, String iptvUrl, long iptvSize) {
            this.version = version;
            this.code = codeFor(version);
            this.notes = notes;
            this.otvosUrl = otvosUrl;
            this.otvosSize = otvosSize;
            this.iptvUrl = iptvUrl;
            this.iptvSize = iptvSize;
        }

        /** The apps in this release that are newer than what's installed, IPTV first (OtvOS restarts itself). */
        List<Apk> updates(Context ctx) {
            List<Apk> list = new ArrayList<>();
            long iptv = installedCode(ctx, IPTV_PACKAGE);
            if (iptvUrl != null && iptv >= 0 && code > iptv) list.add(new Apk(IPTV_APK, IPTV_PACKAGE, iptvUrl, iptvSize));
            if (otvosUrl != null && code > installedCode(ctx, ctx.getPackageName())) {
                list.add(new Apk(OTVOS_APK, ctx.getPackageName(), otvosUrl, otvosSize));
            }
            return list;
        }
    }

    interface Callback<T> {
        void done(T result, String error);
    }

    interface Progress {
        /** Downloading <code>apk</code> (percent, or -1 when the size is unknown), or handing it to Android. */
        void update(Apk apk, boolean installing, int percent);
    }

    private Updater() {
    }

    // ---- versions ----------------------------------------------------------------------------------

    /** "1.3" → 10300, "1.3.2" → 10302, "v2" → 20000. The same formula is in build.gradle. */
    static long codeFor(String version) {
        String v = version == null ? "" : version.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        String[] parts = v.split("[.\\-+ ]");
        long code = 0;
        for (int i = 0; i < 3; i++) {
            long n = 0;
            if (i < parts.length) {
                try {
                    n = Math.min(99, Math.max(0, Long.parseLong(parts[i])));
                } catch (NumberFormatException ignored) {
                }
            }
            code = code * 100 + n;
        }
        return code;
    }

    @SuppressWarnings("deprecation")
    static long installedCode(Context ctx, String pkg) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg, 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    static String installedVersion(Context ctx) {
        try {
            String v = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            return v == null ? "" : v;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    // ---- checking ----------------------------------------------------------------------------------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("updates", Context.MODE_PRIVATE);
    }

    /** The newest release seen at the last check, or null. */
    static Release cached(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String version = p.getString("version", null);
        if (version == null) return null;
        return new Release(version, p.getString("notes", ""), p.getString("otvos", null), p.getLong("otvosSize", 0),
                p.getString("iptv", null), p.getLong("iptvSize", 0));
    }

    /** Whether the last check found something newer than what's installed. */
    static boolean available(Context ctx) {
        Release r = cached(ctx);
        return r != null && !r.updates(ctx).isEmpty();
    }

    /** Asks GitHub for the newest release, off the main thread; the callback runs on the main thread. */
    static void check(Context ctx, Callback<Release> callback) {
        Context app = ctx.getApplicationContext();
        worker.execute(() -> {
            Release r = null;
            String error = null;
            try {
                r = fetchLatest();
                prefs(app).edit()
                        .putLong("checkedAt", System.currentTimeMillis())
                        .putString("version", r.version)
                        .putString("notes", r.notes)
                        .putString("otvos", r.otvosUrl)
                        .putLong("otvosSize", r.otvosSize)
                        .putString("iptv", r.iptvUrl)
                        .putLong("iptvSize", r.iptvSize)
                        .apply();
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            Release result = r;
            String err = error;
            main.post(() -> callback.done(result, err));
        });
    }

    /**
     * The background check, at most twice a day: calls <code>onNew</code> once for each release newer than
     * what's installed, so the home screen can mention it once.
     */
    static void checkNowAndThen(Context ctx, Callback<Release> onNew) {
        SharedPreferences p = prefs(ctx);
        if (System.currentTimeMillis() - p.getLong("checkedAt", 0) < CHECK_EVERY_MS) return;
        Context app = ctx.getApplicationContext();
        check(app, (r, error) -> {
            if (r == null || r.updates(app).isEmpty()) return;
            if (r.version.equals(p.getString("announced", null))) return;
            p.edit().putString("announced", r.version).apply();
            onNew.done(r, null);
        });
    }

    private static Release fetchLatest() throws Exception {
        HttpURLConnection c = open(LATEST);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        int status = c.getResponseCode();
        if (status == 404) throw new IOException("No releases yet");
        if (status != 200) throw new IOException("GitHub answered " + status);
        JSONObject o;
        try (InputStream in = c.getInputStream()) {
            o = new JSONObject(readAll(in));
        } finally {
            c.disconnect();
        }
        String tag = o.optString("tag_name", "");
        String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
        String otvos = null, iptv = null;
        long otvosSize = 0, iptvSize = 0;
        JSONArray assets = o.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String name = a.optString("name");
            if (OTVOS_APK.equalsIgnoreCase(name)) {
                otvos = a.optString("browser_download_url", null);
                otvosSize = a.optLong("size");
            } else if (IPTV_APK.equalsIgnoreCase(name)) {
                iptv = a.optString("browser_download_url", null);
                iptvSize = a.optLong("size");
            }
        }
        return new Release(version, o.optString("body", "").trim(), otvos, otvosSize, iptv, iptvSize);
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "OtvOS");
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString(StandardCharsets.UTF_8.name());
    }

    // ---- installing --------------------------------------------------------------------------------

    /** Whether Android lets OtvOS install apps ("Install unknown apps", or granted once over adb). */
    static boolean canInstall(Context ctx) {
        return ctx.getPackageManager().canRequestPackageInstalls();
    }

    /**
     * Downloads <code>apk</code>, checks it's the app it should be and newer than what's installed, and hands
     * it to Android's installer. The result arrives as {@link #ACTION_STATUS} broadcasts (UpdateReceiver).
     * Runs off the main thread; <code>done</code> runs on the main thread with an error, or null once Android
     * has the install.
     */
    static void install(Context ctx, Apk apk, Progress progress, Callback<Void> done) {
        Context app = ctx.getApplicationContext();
        worker.execute(() -> {
            String error = null;
            try {
                File file = download(app, apk, progress);
                main.post(() -> progress.update(apk, true, 100));
                verify(app, apk, file);
                commit(app, apk.packageName, file);
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            String err = error;
            main.post(() -> done.done(null, err));
        });
    }

    private static File download(Context ctx, Apk apk, Progress progress) throws IOException {
        File dir = new File(ctx.getCacheDir(), "updates");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("No room for the download");
        File file = new File(dir, apk.name);
        HttpURLConnection c = open(apk.url);
        try {
            int status = c.getResponseCode();
            if (status != 200) throw new IOException("Download failed (" + status + ")");
            long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : apk.size;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                long got = 0;
                int last = -1, n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    got += n;
                    int pct = total > 0 ? (int) (got * 100 / total) : -1;
                    if (pct != last) {
                        last = pct;
                        main.post(() -> progress.update(apk, false, pct));
                    }
                }
            }
        } finally {
            c.disconnect();
        }
        return file;
    }

    /** The download must be the expected app and newer than what's installed. */
    @SuppressWarnings("deprecation")
    private static void verify(Context ctx, Apk apk, File file) throws IOException {
        PackageInfo info = ctx.getPackageManager().getPackageArchiveInfo(file.getPath(), 0);
        if (info == null) throw new IOException(apk.name + " isn't a valid app");
        if (!apk.packageName.equals(info.packageName)) throw new IOException(apk.name + " is the wrong app");
        long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        if (code <= installedCode(ctx, apk.packageName)) throw new IOException("Already up to date");
    }

    private static void commit(Context ctx, String pkg, File file) throws IOException {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(pkg);
        params.setSize(file.length());
        // Android 12 and later: once OtvOS has installed an app itself, it can update it without asking again.
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(params);
        PackageInstaller.Session session = pi.openSession(id);
        try {
            try (InputStream in = new FileInputStream(file);
                 OutputStream out = session.openWrite(pkg + ".apk", 0, file.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent status = new Intent(ctx, UpdateReceiver.class).setAction(ACTION_STATUS).putExtra("package", pkg);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pending = PendingIntent.getBroadcast(ctx, id, status, flags);
            session.commit(pending.getIntentSender());
        } catch (IOException | RuntimeException e) {
            session.abandon();
            throw e;
        } finally {
            session.close();
        }
    }
}
