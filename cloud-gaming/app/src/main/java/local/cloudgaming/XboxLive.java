package local.cloudgaming;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * The signed-in player's own Xbox: profile (gamertag, gamerpic, Gamerscore), the games they've played with
 * their achievement progress, and their friends with who's online — what the Xbox app shows.
 *
 * <p>It uses the session xbox.com keeps once you're signed in (its Xbox Live tokens, which the site renews by
 * itself). They live only in this app; when they run out, xbox.com is opened out of sight for a moment so it
 * renews them, as it would in a browser. Answers are kept on the TV so the home screen shows them at once.
 */
final class XboxLive {

    static final class Profile {
        String xuid, gamertag, pic;
        long gamerscore;
    }

    static final class Title {
        String productId, name;
        int achieved, achievements, percent;
        long score, totalScore, lastPlayed;
        boolean streamable;
    }

    static final class Friend {
        String xuid, gamertag, pic, presence;
        boolean online;
        long gamerscore;
    }

    private static final String PREFS = "xboxlive";
    private static final Handler main = new Handler(Looper.getMainLooper());

    private XboxLive() {
    }

    // ---- the session ------------------------------------------------------------------------------------

    /** The Xbox Live sign-in to send with requests, or null when there's none that's still good. */
    static String auth(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String token = p.getString("token", null), uhs = p.getString("uhs", null);
        if (token == null || uhs == null || p.getLong("expires", 0) < System.currentTimeMillis() + 5 * 60_000) return null;
        return "XBL3.0 x=" + uhs + ";" + token;
    }

    /** Keeps the Xbox Live token from xbox.com's saved session ("Auth.User…": {tokens:[…]}). True if it's good. */
    static boolean keep(Context c, String authUser) {
        try {
            JSONArray tokens = new JSONObject(authUser).getJSONArray("tokens");
            for (int i = 0; i < tokens.length(); i++) {
                JSONObject t = tokens.getJSONObject(i);
                if (!"http://xboxlive.com".equals(t.optString("relyingParty"))) continue;
                JSONObject d = t.getJSONObject("tokenData");
                long expires = time(d.optString("expiration"));
                if (expires < System.currentTimeMillis() + 5 * 60_000) return false;
                c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString("token", d.getString("token")).putString("uhs", d.getString("userHash"))
                        .putLong("expires", expires).apply();
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** From a session handed over by the iPhone (its saved Xbox site data). */
    static void keepFromStorage(Context c, JSONObject storage) {
        if (storage == null) return;
        for (java.util.Iterator<String> k = storage.keys(); k.hasNext(); ) {
            String key = k.next();
            if (key.startsWith("Auth.User")) keep(c, storage.optString(key));
        }
    }

    /** From a handed-over session the Xbox site hasn't opened yet (kept by {@link Account} until it does). */
    static void keepPending(Context c) {
        String pending = Account.prefs(c).getString("pendingStorage", null);
        if (pending == null) return;
        try {
            keepFromStorage(c, new JSONObject(pending));
        } catch (Exception ignored) {
        }
    }

    private static final String READ =
            "(function(){for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);"
                    + "if(k.indexOf('Auth.User')===0)return localStorage.getItem(k);}return '';})()";

    /** Takes a renewed session from an open xbox.com page. */
    static void capture(Context c, WebView web, Runnable kept) {
        web.evaluateJavascript(READ, v -> {
            try {
                String s = v == null || v.equals("null") ? "" : new JSONObject("{\"v\":" + v + "}").getString("v");
                if (!s.isEmpty() && keep(c, s) && kept != null) kept.run();
            } catch (Exception ignored) {
            }
        });
    }

    private static WebView renewing;
    private static long lastRenew;

    /**
     * Renews an expired session: xbox.com opens out of sight (signed in, from this app's cookies) and renews
     * its own tokens; this keeps them. At most every 10 minutes; gives up after 45 seconds. Main thread.
     */
    @SuppressLint("SetJavaScriptEnabled")
    static void renew(Activity a, Runnable done) {
        if (renewing != null || System.currentTimeMillis() - lastRenew < 10 * 60_000 || !Account.signedIn(a)) return;
        lastRenew = System.currentTimeMillis();
        Account.restoreCookies(a);
        WebView web = new WebView(a);
        renewing = web;
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(StreamActivity.desktopUserAgent(s.getUserAgentString()));
        s.setBlockNetworkImage(true);   // nothing is shown; only the sign-in matters
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        String session = Account.storageScript(a);
        if (session != null && androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, session, java.util.Collections.singleton("https://www.xbox.com"));
        }
        ViewGroup root = (ViewGroup) a.getWindow().getDecorView();
        root.addView(web, new ViewGroup.LayoutParams(1, 1));
        web.setAlpha(0f);
        web.setFocusable(false);
        final Runnable[] poll = new Runnable[1];
        final long started = System.currentTimeMillis();
        Runnable finish = () -> {
            if (renewing != web) return;
            renewing = null;
            main.removeCallbacks(poll[0]);
            Account.saveCookies();
            root.removeView(web);
            web.destroy();
        };
        poll[0] = () -> {
            if (renewing != web) return;
            if (a.isDestroyed() || System.currentTimeMillis() - started > 45_000) {
                finish.run();
                return;
            }
            capture(a, web, () -> {
                if (session != null) Account.storageApplied(a);
                finish.run();
                done.run();
            });
            main.postDelayed(poll[0], 2500);
        };
        web.setWebViewClient(new WebViewClient());
        web.loadUrl("https://www.xbox.com/" + Xbox.language() + "/play");
        main.postDelayed(poll[0], 6000);
    }

    static void forget(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        for (String f : new String[]{"profile", "titles", "friends"}) file(c, f).delete();
        synchronized (titles) {
            titles.clear();
        }
    }

    // ---- the player's data ------------------------------------------------------------------------------

    /** Network calls below: off the main thread. With fetch false, only what's kept on the TV. */
    static Profile profile(Context c, boolean fetch) {
        JSONObject o = load(c, "profile", fetch ? "https://profile.xboxlive.com/users/me/profile/settings?settings="
                + "GameDisplayPicRaw,Gamerscore,Gamertag,ModernGamertag" : null, 3);
        if (o == null) return null;
        try {
            JSONObject u = o.getJSONArray("profileUsers").getJSONObject(0);
            Profile p = new Profile();
            p.xuid = u.optString("id");
            JSONArray s = u.getJSONArray("settings");
            for (int i = 0; i < s.length(); i++) {
                String id = s.getJSONObject(i).optString("id"), v = s.getJSONObject(i).optString("value");
                switch (id) {
                    case "ModernGamertag": if (!v.isEmpty()) p.gamertag = v; break;
                    case "Gamertag": if (p.gamertag == null) p.gamertag = v; break;
                    case "GameDisplayPicRaw": p.pic = v; break;
                    case "Gamerscore": p.gamerscore = parseLong(v); break;
                    default: break;
                }
            }
            if (p.gamertag != null) {
                Account.prefs(c).edit().putString("gamertag", p.gamertag).putString("gamerpic", picture(p.pic, 128)).apply();
            }
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("xuid", p.xuid).apply();
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    private static final Map<String, Title> titles = new HashMap<>();

    /** The games this player has played, most recent first, with their achievement progress. */
    static List<Title> titles(Context c, boolean fetch) {
        String xuid = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("xuid", null);
        JSONObject o = load(c, "titles", fetch && xuid != null ? "https://titlehub.xboxlive.com/users/xuid(" + xuid
                + ")/titles/titlehistory/decoration/Achievement,Image,ProductId" : null, 2);
        List<Title> out = new ArrayList<>();
        if (o == null) return out;
        JSONArray a = o.optJSONArray("titles");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject t = a.optJSONObject(i);
            if (t == null || t.optString("productId").isEmpty() || !"Game".equals(t.optString("type", "Game"))) continue;
            Title g = new Title();
            g.productId = t.optString("productId");
            g.name = t.optString("name");
            g.streamable = t.optBoolean("isStreamable");
            JSONObject ach = t.optJSONObject("achievement");
            if (ach != null) {
                g.achieved = ach.optInt("currentAchievements");
                g.achievements = ach.optInt("totalAchievements");
                g.score = ach.optLong("currentGamerscore");
                g.totalScore = ach.optLong("totalGamerscore");
                g.percent = (int) Math.round(ach.optDouble("progressPercentage", 0));
            }
            JSONObject h = t.optJSONObject("titleHistory");
            if (h != null) g.lastPlayed = time(h.optString("lastTimePlayed"));
            out.add(g);
        }
        synchronized (titles) {
            titles.clear();
            for (Title t : out) titles.put(t.productId, t);
        }
        return out;
    }

    /** This player's progress in a game, if they've played it (from what's kept on the TV). */
    static Title title(String productId) {
        synchronized (titles) {
            return productId == null ? null : titles.get(productId);
        }
    }

    /** Friends: online first, then by gamertag. */
    static List<Friend> friends(Context c, boolean fetch) {
        JSONObject o = load(c, "friends", fetch ? "https://peoplehub.xboxlive.com/users/me/people/social/decoration/presencedetail" : null, 5);
        List<Friend> out = new ArrayList<>();
        if (o == null) return out;
        JSONArray a = o.optJSONArray("people");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            if (p == null) continue;
            Friend f = new Friend();
            f.xuid = p.optString("xuid");
            f.gamertag = p.optString("modernGamertag", p.optString("gamertag"));
            if (f.gamertag.isEmpty()) f.gamertag = p.optString("gamertag");
            f.pic = picture(p.optString("displayPicRaw", null), 160);
            f.online = "Online".equalsIgnoreCase(p.optString("presenceState"));
            f.presence = p.optString("presenceText");
            f.gamerscore = parseLong(p.optString("gamerScore"));
            out.add(f);
        }
        out.sort((x, y) -> x.online != y.online ? (x.online ? -1 : 1) : x.gamertag.compareToIgnoreCase(y.gamertag));
        return out;
    }

    /** A gamerpic at the size it's shown (Xbox's image service resizes it). */
    static String picture(String url, int px) {
        if (url == null || url.isEmpty()) return null;
        return url.contains("images-eds") ? url + (url.contains("?") ? "&" : "?") + "w=" + px + "&h=" + px : url;
    }

    // ---- web ----------------------------------------------------------------------------------------------

    /** The kept answer, or a fresh one when asked to (and signed in). Null when there's neither. */
    private static JSONObject load(Context c, String name, String url, int contract) {
        File f = file(c, name);
        String auth = url == null ? null : auth(c);
        if (auth != null) {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
                h.setConnectTimeout(10000);
                h.setReadTimeout(20000);
                h.setRequestProperty("Authorization", auth);
                h.setRequestProperty("x-xbl-contract-version", String.valueOf(contract));
                h.setRequestProperty("Accept", "application/json");
                h.setRequestProperty("Accept-Language", Xbox.language());
                if (h.getResponseCode() == 401) {
                    // Expired early: renew next time.
                    c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("expires").apply();
                } else {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    try (InputStream in = h.getInputStream()) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                    }
                    new JSONObject(b.toString("UTF-8"));   // only a whole answer replaces the kept one
                    File tmp = new File(f.getPath() + ".new");
                    try (FileOutputStream out = new FileOutputStream(tmp)) {
                        out.write(b.toByteArray());
                    }
                    if (!tmp.renameTo(f)) tmp.delete();
                }
            } catch (Exception ignored) {   // offline: what's kept
            }
        }
        if (!f.exists()) return null;
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new JSONObject(b.toString("UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    private static File file(Context c, String name) {
        return new File(c.getFilesDir(), "xbl-" + name + ".json");
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** ISO 8601 times as Xbox sends them (with or without fractions of a second). */
    static long time(String s) {
        if (s == null || s.isEmpty()) return 0;
        String t = s.replaceAll("\\.\\d+", "").replace("Z", "+0000").replaceAll("([+-]\\d\\d):(\\d\\d)$", "$1$2");
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(t).getTime();
        } catch (Exception e) {
            return 0;
        }
    }
}
