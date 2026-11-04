package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A tiny HTTP server on the TV for the phone/Mac remote (http://&lt;tv-ip&gt;:8800). It serves the remote web
 * app from assets/remote and a small JSON API. Every API call except pairing needs a token, which a device
 * gets by typing the 4-digit code the TV shows on screen; tokens are kept in the launcher's preferences.
 * Runs inside HomeKeyService, which the system keeps alive, so the remote works whatever is on screen.
 */
final class RemoteServer {

    static final int PORT = 8800;
    private static final String TAG = "TvRemote";

    interface Actions {
        void key(String name);

        void text(String value);

        void showCode(String code);

        void hideCode();

        /** Opens a link sent from a phone or Mac; returns what opened it, or null. */
        String open(String url, double seconds);
    }

    private final Context ctx;
    private final Actions actions;
    private final SharedPreferences prefs;
    private final SecureRandom random = new SecureRandom();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private ServerSocket server;
    private volatile boolean running;
    private volatile String pendingCode;
    private volatile long pendingUntil;
    private NsdManager.RegistrationListener nsdListener;
    private final android.os.Handler retry = new android.os.Handler(android.os.Looper.getMainLooper());
    /** The running server, for the Control Center's Remote App tile. */
    static volatile RemoteServer current;

    RemoteServer(Context ctx, Actions actions) {
        this.ctx = ctx.getApplicationContext();
        this.actions = actions;
        this.prefs = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE);
    }

    void start() {
        if (running) return;
        running = true;
        current = this;
        advertise();
        Thread t = new Thread(() -> {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(PORT));
                while (running) {
                    Socket s = server.accept();
                    pool.execute(() -> handle(s));
                }
            } catch (IOException e) {
                if (running) Log.w(TAG, "server stopped", e);
            }
        }, "tv-remote-server");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        if (current == this) current = null;
        retry.removeCallbacksAndMessages(null);
        NsdManager nsd = ctx.getSystemService(NsdManager.class);
        if (nsd != null && nsdListener != null) {
            try {
                nsd.unregisterService(nsdListener);
            } catch (RuntimeException ignored) {
            }
        }
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    // ---- HTTP ------------------------------------------------------------------------------------

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(15000);
            java.io.BufferedInputStream in = new java.io.BufferedInputStream(sock.getInputStream(), 16384);
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path = parts[1];
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
            }
            // Wallpapers are pictures (up to 64 MB for a dynamic set); everything else is small JSON.
            int limit = path.startsWith("/api/wallpaper") ? 64 << 20 : path.startsWith("/api/tv/xbox") ? 4 << 20 : 65536;
            int len = parseInt(headers.get("content-length"));
            byte[] raw = new byte[len > 0 && len <= limit ? len : 0];
            int got = 0;
            while (got < raw.length) {
                int n = in.read(raw, got, raw.length - got);
                if (n < 0) break;
                got += n;
            }
            route(sock.getOutputStream(), method, path, headers, new String(raw, 0, got, StandardCharsets.UTF_8), raw);
        } catch (Exception e) {
            Log.w(TAG, "request failed", e);
        }
    }

    /** One CRLF-terminated header line, as bytes off the stream (so a binary body after it stays intact). */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) break;
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static String query(String path, String key) {
        int q = path.indexOf('?');
        if (q < 0) return null;
        for (String kv : path.substring(q + 1).split("&")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).equals(key)) {
                try {
                    return java.net.URLDecoder.decode(kv.substring(e + 1), "UTF-8");
                } catch (Exception ex) {
                    return kv.substring(e + 1);
                }
            }
        }
        return null;
    }

    private void route(OutputStream out, String method, String path, Map<String, String> headers, String body,
                       byte[] raw) throws IOException {
        String fullPath = path;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);

        if (!path.startsWith("/api/")) {
            serveAsset(out, path.equals("/") ? "index.html" : path.substring(1));
            return;
        }
        JSONObject in = new JSONObject();
        try {
            if (!body.isEmpty()) in = new JSONObject(body);
        } catch (Exception ignored) {
        }

        try {
            switch (path) {
                case "/api/hello": {
                    JSONObject o = new JSONObject();
                    o.put("name", MainActivity.tvName(ctx));
                    o.put("id", tvId(ctx));
                    o.put("paired", authorized(headers));
                    o.put("control", ControlServer.PORT);
                    o.put("display", ScreenReceiver.PORT);
                    int[] screen = Wallpapers.screen(ctx);
                    o.put("screenWidth", screen[0]);
                    o.put("screenHeight", screen[1]);
                    json(out, 200, o);
                    return;
                }
                case "/api/pair/start": {
                    // Keep a code the TV is already showing (from Control Center), so it stays valid.
                    if (pendingCode == null || System.currentTimeMillis() > pendingUntil) beginPairing();
                    json(out, 200, new JSONObject().put("ok", true));
                    return;
                }
                case "/api/pair/finish": {
                    String code = in.optString("code");
                    if (pendingCode == null || System.currentTimeMillis() > pendingUntil || !pendingCode.equals(code)) {
                        json(out, 403, new JSONObject().put("error", "wrong code"));
                        return;
                    }
                    pendingCode = null;
                    actions.hideCode();
                    String token = newToken();
                    Set<String> tokens = new HashSet<>(prefs.getStringSet("tokens", new HashSet<>()));
                    tokens.add(token);
                    prefs.edit().putStringSet("tokens", tokens).apply();
                    json(out, 200, new JSONObject().put("token", token));
                    return;
                }
                default:
                    break;
            }
            if (!authorized(headers)) {
                json(out, 401, new JSONObject().put("error", "pair first"));
                return;
            }
            if (path.startsWith("/api/key/")) {
                actions.key(path.substring("/api/key/".length()));
                json(out, 200, new JSONObject().put("ok", true));
            } else if (path.equals("/api/open")) {
                String what = actions.open(in.optString("url"), in.optDouble("time", 0));
                json(out, what != null ? 200 : 422, new JSONObject().put("opened", what == null ? JSONObject.NULL : what));
            } else if (path.equals("/api/text")) {
                actions.text(in.optString("text"));
                json(out, 200, new JSONObject().put("ok", true));
            } else if (path.equals("/api/state")) {
                json(out, 200, state());
            } else if (path.equals("/api/wallpaper") && raw.length > 0) {
                // A picture from the iPhone or Mac becomes the wallpaper (kept in Your Pictures).
                Wallpapers.saveUpload(ctx, query(fullPath, "name"), raw);
                json(out, 200, new JSONObject().put("ok", true));
            } else if (path.equals("/api/wallpaper/dynamic") && raw.length > 0) {
                // A macOS dynamic wallpaper from the Mac: pictures for each time of day.
                Wallpapers.saveDynamic(ctx, query(fullPath, "name"), raw);
                json(out, 200, new JSONObject().put("ok", true));
            } else if (path.startsWith("/api/tv/")) {
                // The iPhone remote's Settings: inputs, wallpapers, Bluetooth, screensaver, updates.
                Map<String, String> qs = new java.util.HashMap<>();
                for (String key : new String[]{"kind"}) {
                    String v = query(fullPath, key);
                    if (v != null) qs.put(key, v);
                }
                TvApi.Reply r = TvApi.handle(ctx, path, qs, in);
                if (r.jpeg != null) raw(out, r.code, "image/jpeg", r.jpeg);
                else json(out, r.code, r.json);
            } else if (path.equals("/api/apps")) {
                json(out, 200, new JSONObject().put("apps", apps()));
            } else if (path.startsWith("/api/icon/")) {
                byte[] png = icon(path.substring("/api/icon/".length()));
                if (png != null) raw(out, 200, "image/png", png);
                else json(out, 404, new JSONObject().put("error", "no icon"));
            } else {
                json(out, 404, new JSONObject().put("error", "not found"));
            }
        } catch (Exception e) {
            json(out, 500, new JSONObject());
        }
    }

    /**
     * Every installed app for OpenTV Companion's manager: name, package, version, whether it is built in,
     * turned off, or has a home-screen entry. (Changes go through adb from the Mac, which can do them silently.)
     */
    private org.json.JSONArray apps() throws Exception {
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        org.json.JSONArray a = new org.json.JSONArray();
        for (android.content.pm.PackageInfo p : pm.getInstalledPackages(android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)) {
            android.content.pm.ApplicationInfo ai = p.applicationInfo;
            if (ai == null) continue;
            JSONObject o = new JSONObject();
            o.put("id", p.packageName);
            o.put("name", ai.loadLabel(pm).toString());
            o.put("version", p.versionName == null ? "" : p.versionName);
            o.put("system", (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0);
            o.put("updatedSystem", (ai.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);
            o.put("enabled", ai.enabled);
            o.put("launchable", pm.getLeanbackLaunchIntentForPackage(p.packageName) != null
                    || pm.getLaunchIntentForPackage(p.packageName) != null);
            o.put("installed", p.firstInstallTime);
            a.put(o);
        }
        return a;
    }

    /** An app's banner (or icon), as PNG, at most 320 px wide. */
    private byte[] icon(String pkg) {
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
            android.graphics.drawable.Drawable d = ai.loadBanner(pm);
            if (d == null) d = ai.loadIcon(pm);
            int w = Math.max(1, d.getIntrinsicWidth()), h = Math.max(1, d.getIntrinsicHeight());
            float k = Math.min(1f, 320f / Math.max(w, h));
            w = Math.max(1, Math.round(w * k));
            h = Math.max(1, Math.round(h * k));
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(b);
            d.setBounds(0, 0, w, h);
            d.draw(c);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png);
            return png.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** Puts a fresh 4-digit code on the TV; a device pairs by sending it to /api/pair/finish. */
    void beginPairing() {
        pendingCode = String.format(Locale.ROOT, "%04d", random.nextInt(10000));
        pendingUntil = System.currentTimeMillis() + 120_000;
        actions.showCode(pendingCode);
    }

    /**
     * Advertises the remote on the local network as "_tvremote._tcp" (Bonjour), so the iPhone and Mac apps
     * find the TV without typing its address.
     */
    private void advertise() {
        advertise(0);
    }

    private void advertise(int attempt) {
        NsdManager nsd = ctx.getSystemService(NsdManager.class);
        if (nsd == null || !running) return;
        NsdServiceInfo info = new NsdServiceInfo();
        // Google's own remote service already uses the TV's name, and Android 14 won't reuse a name across
        // service types, so this one gets a suffix; the apps show the "name" attribute instead.
        String name = MainActivity.tvName(ctx);
        info.setServiceName(name + " Remote" + (attempt >= 2 ? " " + attempt : ""));
        info.setAttribute("name", name);
        info.setAttribute("id", tvId(ctx));
        info.setServiceType("_tvremote._tcp");
        info.setPort(PORT);
        info.setAttribute("model", Brand.MODEL);
        nsdListener = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo s, int error) {
                Log.w(TAG, "bonjour registration failed " + error);
                nsdListener = null;
                if (attempt < 4) retry.postDelayed(() -> advertise(attempt + 1), 4000);
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo s, int error) {
            }

            @Override
            public void onServiceRegistered(NsdServiceInfo s) {
                Log.i(TAG, "advertised as " + s.getServiceName());
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo s) {
            }
        };
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (RuntimeException e) {
            Log.w(TAG, "bonjour unavailable", e);
        }
    }

    /** "http://192.168.1.50:8800" for the TV's Wi-Fi or Ethernet address, or null when offline. */
    static String address(Context ctx) {
        android.net.ConnectivityManager cm = ctx.getSystemService(android.net.ConnectivityManager.class);
        if (cm == null) return null;
        android.net.LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
        if (lp == null) return null;
        for (android.net.LinkAddress la : lp.getLinkAddresses()) {
            if (la.getAddress() instanceof java.net.Inet4Address) {
                return "http://" + la.getAddress().getHostAddress() + ":" + PORT;
            }
        }
        return null;
    }

    private boolean authorized(Map<String, String> headers) {
        String t = headers.get("x-token");
        return t != null && prefs.getStringSet("tokens", new HashSet<>()).contains(t);
    }

    private String newToken() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    /** A stable ID for this TV, so apps keep their pairing when its address changes or it's reached by USB. */
    static String tvId(Context ctx) {
        String id = android.provider.Settings.Secure.getString(ctx.getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID);
        return id != null ? id : "tv";
    }

    private JSONObject state() throws Exception {
        return nowPlaying(ctx);
    }

    /** What's playing, for the remote's Now Playing strip. */
    static JSONObject nowPlaying(Context ctx) throws Exception {
        JSONObject o = new JSONObject();
        MediaController mc = MediaListener.current(ctx);
        if (mc != null && mc.getMetadata() != null) {
            MediaMetadata md = mc.getMetadata();
            o.put("title", md.getString(MediaMetadata.METADATA_KEY_TITLE));
            String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            o.put("artist", artist != null ? artist : "");
            PlaybackState ps = mc.getPlaybackState();
            o.put("playing", ps != null && ps.getState() == PlaybackState.STATE_PLAYING);
        }
        return o;
    }

    private void serveAsset(OutputStream out, String name) throws IOException {
        if (name.contains("..")) {
            raw(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream a = ctx.getAssets().open("remote/" + name)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = a.read(b)) > 0) buf.write(b, 0, n);
            raw(out, 200, mime(name), buf.toByteArray());
        } catch (IOException e) {
            raw(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String mime(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".json") || name.endsWith(".webmanifest")) return "application/manifest+json";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".js")) return "text/javascript";
        return "application/octet-stream";
    }

    private static void json(OutputStream out, int status, JSONObject o) throws IOException {
        raw(out, status, "application/json", o.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void raw(OutputStream out, int status, String type, byte[] body) throws IOException {
        String reason = status == 200 ? "OK" : status == 401 ? "Unauthorized" : status == 403 ? "Forbidden"
                : status == 404 ? "Not Found" : "Error";
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static int parseInt(String s) {
        if (TextUtils.isEmpty(s)) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
