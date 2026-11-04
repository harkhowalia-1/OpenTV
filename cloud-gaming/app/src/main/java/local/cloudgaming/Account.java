package local.cloudgaming;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Staying signed in, and who's signed in.
 *
 * <p>Microsoft's sign-in keeps some cookies only for the browsing session, so a TV app that restarts would sign
 * you out. This keeps a copy of the Microsoft and Xbox cookies and puts back any that are missing when the app
 * starts. The gamertag and gamerpic come from the signed-in Xbox page itself.
 */
final class Account {

    private static final String[] SITES = {
            "https://www.xbox.com", "https://login.live.com", "https://account.xbox.com", "https://login.microsoftonline.com",
    };
    private static SharedPreferences prefs;

    private Account() {
    }

    static SharedPreferences prefs(Context c) {
        if (prefs == null) prefs = c.getApplicationContext().getSharedPreferences("account", Context.MODE_PRIVATE);
        return prefs;
    }

    static void saveCookies() {
        if (prefs == null) return;
        CookieManager cm = CookieManager.getInstance();
        SharedPreferences.Editor e = prefs.edit();
        for (String site : SITES) {
            String c = cm.getCookie(site);
            if (c != null && !c.isEmpty()) e.putString("cookies:" + site, c);
        }
        e.apply();
        cm.flush();
    }

    /** Puts back sign-in cookies the WebView dropped when the app last closed (kept for 30 days). */
    static void restoreCookies(Context c) {
        SharedPreferences p = prefs(c);
        CookieManager cm = CookieManager.getInstance();
        for (String site : SITES) {
            String saved = p.getString("cookies:" + site, null);
            if (saved == null) continue;
            Set<String> have = new HashSet<>();
            String now = cm.getCookie(site);
            if (now != null) for (String kv : now.split(";\\s*")) have.add(kv.split("=", 2)[0]);
            for (String kv : saved.split(";\\s*")) {
                String name = kv.split("=", 2)[0];
                if (name.isEmpty() || have.contains(name)) continue;
                cm.setCookie(site, kv + "; Max-Age=2592000; Path=/; Secure");
            }
        }
        cm.flush();
    }

    static String gamertag(Context c) {
        return prefs(c).getString("gamertag", null);
    }

    /** Signed in: by handover from the iPhone, or on this TV (Microsoft's sign-in finished back on Xbox). */
    static boolean signedIn(Context c) {
        return gamertag(c) != null || prefs(c).getBoolean("signedIn", false);
    }

    static void markSignedIn(Context c) {
        prefs(c).edit().putBoolean("signedIn", true).apply();
    }

    /**
     * Takes a sign-in made on the iPhone: {"cookies":[{name,value,domain,path,expires,secure,httpOnly}],
     * "storage":{key:value}, "gamertag", "gamerpic"}. Cookies go into this app's browser; the Xbox site's
     * saved session is put back the next time an Xbox page opens. Returns the gamertag.
     */
    static String importSession(Context c, String json) {
        try {
            JSONObject o = new JSONObject(json);
            CookieManager cm = CookieManager.getInstance();
            org.json.JSONArray cookies = o.optJSONArray("cookies");
            for (int i = 0; cookies != null && i < cookies.length(); i++) {
                JSONObject k = cookies.getJSONObject(i);
                String domain = k.optString("domain");
                String host = domain.startsWith(".") ? domain.substring(1) : domain;
                if (host.isEmpty()) continue;
                StringBuilder v = new StringBuilder(k.optString("name")).append('=').append(k.optString("value"));
                if (domain.startsWith(".")) v.append("; Domain=").append(domain);
                v.append("; Path=").append(k.optString("path", "/"));
                long expires = k.optLong("expires", 0);
                // Session cookies are kept for a month, so a restart doesn't sign you out.
                if (expires > 0) v.append("; Max-Age=").append(Math.max(60, expires - System.currentTimeMillis() / 1000));
                else v.append("; Max-Age=2592000");
                if (k.optBoolean("secure", true)) v.append("; Secure; SameSite=None");
                if (k.optBoolean("httpOnly", false)) v.append("; HttpOnly");
                cm.setCookie("https://" + host + k.optString("path", "/"), v.toString());
            }
            cm.flush();
            JSONObject storage = o.optJSONObject("storage");
            SharedPreferences.Editor e = prefs(c).edit();
            if (storage != null && storage.length() > 0) e.putString("pendingStorage", storage.toString());
            XboxLive.keepFromStorage(c, storage);   // the Xbox Live sign-in in it: your profile and games, at once
            String tag = o.optString("gamertag", "");
            if (!tag.isEmpty()) e.putString("gamertag", tag);
            String pic = o.optString("gamerpic", "");
            if (!pic.isEmpty()) e.putString("gamerpic", pic);
            e.putBoolean("signedIn", true);
            e.apply();
            prefs = null;   // the next read sees what was just saved, even in another process start
            return tag.isEmpty() ? null : tag;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * The Xbox site's saved session from the iPhone, put back once, before the site's own scripts run (they find
     * you signed in). Null when there's nothing to put back.
     */
    static String storageScript(Context c) {
        String pending = prefs(c).getString("pendingStorage", null);
        if (pending == null) return null;
        String id = Integer.toHexString(pending.hashCode());
        return "(function(){try{if(localStorage.getItem('__otvSession')==='" + id + "')return;var d=" + pending
                + ";for(var k in d)localStorage.setItem(k,d[k]);localStorage.setItem('__otvSession','" + id + "');}catch(e){}})();";
    }

    static void storageApplied(Context c) {
        prefs(c).edit().remove("pendingStorage").apply();
    }

    static String gamerpic(Context c) {
        return prefs(c).getString("gamerpic", null);
    }

    static void signedOut(Context c) {
        XboxLive.forget(c);
        prefs(c).edit().remove("gamertag").remove("gamerpic").remove("signedIn").remove("pendingStorage").apply();
        CookieManager.getInstance().removeAllCookies(null);
        SharedPreferences.Editor e = prefs(c).edit();
        for (String site : SITES) e.remove("cookies:" + site);
        e.apply();
        android.webkit.WebStorage.getInstance().deleteAllData();
    }

    /** Reads the gamertag and gamerpic from the signed-in Xbox page (its stored profile, or its header). */
    private static final String PROBE = "(function(){try{var o={};"
            + "function walk(x,d){if(!x||typeof x!=='object'||d>6)return;for(var k in x){var v=x[k];"
            + "if(/^gamertag$|^modernGamertag$/i.test(k)&&typeof v==='string'&&!o.gamertag)o.gamertag=v;"
            + "if(/gamerpic|displaypic|avatarurl/i.test(k)&&typeof v==='string'&&/^https?:/.test(v)&&!o.pic)o.pic=v;"
            + "walk(v,d+1);}}"
            + "for(var i=0;i<localStorage.length;i++){var s=localStorage.getItem(localStorage.key(i))||'';"
            + "if(/gamertag/i.test(s)){try{walk(JSON.parse(s),0);}catch(e){}}}"
            + "if(!o.pic){var im=document.querySelector('img[src*=\"gamerpic\"],img[src*=\"images-eds\"],img[src*=\"xboxlive.com/image\"]');"
            + "if(im)o.pic=im.src;if(im&&!o.gamertag&&im.alt)o.gamertag=im.alt;}"
            + "return JSON.stringify(o);}catch(e){return '{}';}})()";

    static void probe(Context c, WebView web) {
        web.evaluateJavascript(PROBE, value -> {
            try {
                String json = value == null ? "{}" : new JSONObject("{\"v\":" + value + "}").getString("v");
                JSONObject o = new JSONObject(json);
                String tag = o.optString("gamertag", "");
                if (tag.isEmpty()) return;
                prefs(c).edit().putString("gamertag", tag).putString("gamerpic", o.optString("pic", null)).apply();
            } catch (Exception ignored) {
            }
        });
    }
}
