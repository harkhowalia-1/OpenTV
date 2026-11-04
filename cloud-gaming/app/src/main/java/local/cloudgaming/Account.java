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

    static String gamerpic(Context c) {
        return prefs(c).getString("gamerpic", null);
    }

    static void signedOut(Context c) {
        prefs(c).edit().remove("gamertag").remove("gamerpic").apply();
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
