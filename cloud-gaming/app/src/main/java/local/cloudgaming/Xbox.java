package local.cloudgaming;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Xbox Cloud Gaming's catalog, live from Microsoft: the same lists xbox.com/play shows (Game Pass's catalog,
 * for the cloud) and each game's title, publisher, description and art from the Microsoft Store catalog.
 * Kept on the TV for a few hours so the app opens instantly; refreshed in the background.
 */
final class Xbox {

    static final class Game {
        String id, title, publisher, description, poster, hero, logo;
    }

    /** The rows, in xbox.com/play's order: list id, title. */
    static final String[][] ROWS = {
            {"eab7757c-ff70-45af-bfa6-79d3cfb2bf81", "Most Popular"},
            {"f13cf6b4-57e6-4459-89df-6aec18cf0538", "Recently Added"},
            {"3aa7a358-f15b-476b-af7e-134a250c08a0", "Play with Mouse and Keyboard"},
            {"393f05bf-e596-4ef6-9487-6d4fa0eab987", "Leaving Soon"},
            {"29a81209-df6f-41fd-a528-2ae6b91f719c", "All Games"},
    };

    private static final long FRESH = 6L * 3600 * 1000;

    static String market() {
        String c = Locale.getDefault().getCountry();
        return c.isEmpty() ? "US" : c;
    }

    static String language() {
        return Locale.getDefault().toLanguageTag();
    }

    /** The product ids in one list. Network: call off the main thread. */
    static List<String> list(Context c, String sigl, boolean refresh) {
        File f = new File(c.getCacheDir(), "sigl-" + sigl + ".json");
        if (refresh || !f.exists() || System.currentTimeMillis() - f.lastModified() > FRESH) {
            try {
                save(f, get("https://catalog.gamepass.com/sigls/v3?id=" + sigl + "&market=" + market() + "&language="
                        + language() + "&subscriptionContext=none&platformContext=Cloud%3AXGPUWEB"));
            } catch (Exception ignored) {   // offline: the copy we have
            }
        }
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(read(f));
            for (int i = 0; i < a.length(); i++) {
                String id = a.getJSONObject(i).optString("id", "");
                if (!id.isEmpty()) out.add(id);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static final Map<String, Game> games = new LinkedHashMap<>();

    /** Details for these games (from the TV's copy, fetching the ones it doesn't have). Off the main thread. */
    static Map<String, Game> details(Context c, List<String> ids) {
        File f = new File(c.getFilesDir(), "games-" + market() + ".json");
        synchronized (games) {
            if (games.isEmpty() && f.exists()) {
                try {
                    JSONObject o = new JSONObject(read(f));
                    for (java.util.Iterator<String> k = o.keys(); k.hasNext(); ) {
                        String id = k.next();
                        games.put(id, fromJson(o.getJSONObject(id)));
                    }
                } catch (Exception ignored) {
                }
            }
        }
        List<String> missing = new ArrayList<>();
        synchronized (games) {
            for (String id : ids) if (!games.containsKey(id)) missing.add(id);
        }
        boolean added = false;
        for (int i = 0; i < missing.size(); i += 20) {
            List<String> batch = missing.subList(i, Math.min(missing.size(), i + 20));
            try {
                JSONObject o = new JSONObject(get("https://displaycatalog.mp.microsoft.com/v7.0/products?bigIds="
                        + String.join(",", batch) + "&market=" + market() + "&languages=" + language() + ",en-us"));
                JSONArray ps = o.optJSONArray("Products");
                for (int p = 0; ps != null && p < ps.length(); p++) {
                    Game g = parse(ps.getJSONObject(p));
                    if (g != null) {
                        synchronized (games) {
                            games.put(g.id, g);
                        }
                        added = true;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (added) {
            try {
                JSONObject all = new JSONObject();
                synchronized (games) {
                    for (Game g : games.values()) all.put(g.id, toJson(g));
                }
                save(f, all.toString());
            } catch (Exception ignored) {
            }
        }
        Map<String, Game> out = new LinkedHashMap<>();
        synchronized (games) {
            for (String id : ids) {
                Game g = games.get(id);
                if (g != null) out.put(id, g);
            }
        }
        return out;
    }

    static Game cached(String id) {
        synchronized (games) {
            return games.get(id);
        }
    }

    private static Game parse(JSONObject p) throws Exception {
        JSONArray lps = p.optJSONArray("LocalizedProperties");
        if (lps == null || lps.length() == 0) return null;
        JSONObject lp = lps.getJSONObject(0);
        Game g = new Game();
        g.id = p.getString("ProductId");
        g.title = lp.optString("ProductTitle");
        g.publisher = lp.optString("PublisherName");
        g.description = lp.optString("ShortDescription");
        if (g.description.isEmpty()) g.description = lp.optString("ProductDescription");
        JSONArray imgs = lp.optJSONArray("Images");
        for (int i = 0; imgs != null && i < imgs.length(); i++) {
            JSONObject im = imgs.getJSONObject(i);
            String uri = im.optString("Uri");
            if (uri.startsWith("//")) uri = "https:" + uri;
            switch (im.optString("ImagePurpose")) {
                case "Poster": if (g.poster == null) g.poster = uri; break;
                case "SuperHeroArt": g.hero = uri; break;
                case "TitledHeroArt": if (g.hero == null) g.hero = uri; break;
                case "Logo": if (g.logo == null) g.logo = uri; break;
                case "BoxArt": if (g.poster == null) g.poster = uri; break;
                default: break;
            }
        }
        return g.title.isEmpty() ? null : g;
    }

    private static JSONObject toJson(Game g) throws Exception {
        return new JSONObject().put("id", g.id).put("title", g.title).put("publisher", g.publisher)
                .put("description", g.description).put("poster", g.poster).put("hero", g.hero).put("logo", g.logo);
    }

    private static Game fromJson(JSONObject o) {
        Game g = new Game();
        g.id = o.optString("id");
        g.title = o.optString("title");
        g.publisher = o.optString("publisher");
        g.description = o.optString("description");
        g.poster = o.optString("poster", null);
        g.hero = o.optString("hero", null);
        g.logo = o.optString("logo", null);
        return g;
    }

    /** The address that starts the game in Xbox's player (xbox.com/play's own launch link). */
    static String launchUrl(Game g) {
        String slug = g.title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) slug = "game";
        return "https://www.xbox.com/" + language() + "/play/launch/" + slug + "/" + g.id;
    }

    /** A Microsoft Store image at a given width (the store resizes it), so the TV downloads only what it shows. */
    static String sized(String url, int width) {
        if (url == null) return null;
        return url + (url.contains("?") ? "&" : "?") + "q=90&w=" + width;
    }

    // ---- recently played (on this TV) -------------------------------------------------------------------

    static List<String> recent(Context c) {
        List<String> out = new ArrayList<>();
        String s = c.getSharedPreferences("cloud", Context.MODE_PRIVATE).getString("recent", "");
        for (String id : s.split(",")) if (!id.isEmpty()) out.add(id);
        return out;
    }

    static void played(Context c, String id) {
        List<String> r = recent(c);
        r.remove(id);
        r.add(0, id);
        while (r.size() > 20) r.remove(r.size() - 1);
        c.getSharedPreferences("cloud", Context.MODE_PRIVATE).edit().putString("recent", String.join(",", r)).apply();
    }

    // ---- web ----------------------------------------------------------------------------------------------

    static String get(String url) throws Exception {
        return new String(getBytes(url), "UTF-8");
    }

    static byte[] getBytes(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(20000);
        h.setRequestProperty("MS-CV", "OpenTV.1");
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void save(File f, String s) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.getBytes("UTF-8"));
        }
        tmp.renameTo(f);
    }

    private static String read(File f) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
    }
}
