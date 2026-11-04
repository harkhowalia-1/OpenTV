package com.gathin.otvbrowser;

import android.content.Context;
import android.net.Uri;
import android.util.Patterns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** What the browser remembers: Favorites, history (for Frequently Visited and suggestions) and open tabs. */
final class Browser {

    static final class Site {
        String url, title;
        int visits;
        long last;

        Site(String url, String title) {
            this.url = url;
            this.title = title;
        }
    }

    private static final String[][] DEFAULT_FAVORITES = {
            {"https://www.google.com/", "Google"},
            {"https://www.youtube.com/", "YouTube"},
            {"https://www.wikipedia.org/", "Wikipedia"},
            {"https://news.google.com/", "News"},
            {"https://www.reddit.com/", "Reddit"},
            {"https://weather.com/", "Weather"},
            {"https://www.twitch.tv/", "Twitch"},
            {"https://github.com/", "GitHub"},
    };

    /** Well-known sites for the start page's Popular Sites (the ones already in Favorites are left out). */
    static final String[][] POPULAR = {
            {"https://www.google.com/", "Google"},
            {"https://www.youtube.com/", "YouTube"},
            {"https://www.twitch.tv/", "Twitch"},
            {"https://www.netflix.com/", "Netflix"},
            {"https://www.primevideo.com/", "Prime Video"},
            {"https://www.disneyplus.com/", "Disney+"},
            {"https://tv.apple.com/", "Apple TV"},
            {"https://open.spotify.com/", "Spotify"},
            {"https://music.youtube.com/", "YouTube Music"},
            {"https://soundcloud.com/", "SoundCloud"},
            {"https://www.crunchyroll.com/", "Crunchyroll"},
            {"https://pluto.tv/", "Pluto TV"},
            {"https://tubitv.com/", "Tubi"},
            {"https://vimeo.com/", "Vimeo"},
            {"https://kick.com/", "Kick"},
            {"https://www.instagram.com/", "Instagram"},
            {"https://www.tiktok.com/", "TikTok"},
            {"https://x.com/", "X"},
            {"https://www.facebook.com/", "Facebook"},
            {"https://www.pinterest.com/", "Pinterest"},
            {"https://www.reddit.com/", "Reddit"},
            {"https://www.wikipedia.org/", "Wikipedia"},
            {"https://www.imdb.com/", "IMDb"},
            {"https://www.espn.com/", "ESPN"},
            {"https://www.bbc.com/", "BBC"},
            {"https://www.cnn.com/", "CNN"},
            {"https://news.google.com/", "Google News"},
            {"https://weather.com/", "Weather"},
            {"https://www.google.com/maps", "Maps"},
            {"https://www.amazon.com/", "Amazon"},
            {"https://www.ebay.com/", "eBay"},
            {"https://github.com/", "GitHub"},
            {"https://chatgpt.com/", "ChatGPT"},
            {"https://claude.ai/", "Claude"},
            {"https://www.speedtest.net/", "Speedtest"},
    };

    /** Popular Sites not already in Favorites. */
    List<Site> popular() {
        List<Site> out = new ArrayList<>();
        for (String[] p : POPULAR) if (!isFavorite(p[0])) out.add(new Site(p[0], p[1]));
        return out;
    }

    private final File dir;
    final List<Site> favorites = new ArrayList<>();
    final Map<String, Site> history = new LinkedHashMap<>();

    Browser(Context c) {
        dir = c.getFilesDir();
        load();
    }

    // ---- addresses ------------------------------------------------------------------------------------------

    /** What you typed, as an address: a website if it looks like one, otherwise a Google search. */
    static String addressFor(String typed) {
        String t = typed.trim();
        if (t.isEmpty()) return null;
        if (t.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) return t;
        if (!t.contains(" ") && (Patterns.WEB_URL.matcher(t).matches() || t.startsWith("localhost"))) return "https://" + t;
        return "https://www.google.com/search?q=" + Uri.encode(t);
    }

    /** "apple.com" from "https://www.apple.com/tv/": what Safari shows in its address field. */
    static String domain(String url) {
        if (url == null) return "";
        String h = Uri.parse(url).getHost();
        if (h == null) return url;
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    /** A site's own home-screen icon (the one Safari uses), from its address. */
    static String touchIcon(String url) {
        Uri u = Uri.parse(url);
        return u.getScheme() + "://" + u.getHost() + "/apple-touch-icon.png";
    }

    // ---- favorites and history ---------------------------------------------------------------------------

    boolean isFavorite(String url) {
        for (Site s : favorites) if (sameSite(s.url, url)) return true;
        return false;
    }

    void toggleFavorite(String url, String title) {
        for (int i = 0; i < favorites.size(); i++) {
            if (sameSite(favorites.get(i).url, url)) {
                favorites.remove(i);
                save();
                return;
            }
        }
        favorites.add(new Site(url, title == null || title.isEmpty() ? domain(url) : title));
        save();
    }

    void removeFavorite(Site s) {
        favorites.remove(s);
        save();
    }

    private static boolean sameSite(String a, String b) {
        return a != null && b != null && a.replaceAll("/+$", "").equalsIgnoreCase(b.replaceAll("/+$", ""));
    }

    void visited(String url, String title) {
        if (url == null || !url.startsWith("http")) return;
        Site s = history.remove(url);
        if (s == null) s = new Site(url, title);
        if (title != null && !title.isEmpty()) s.title = title;
        s.visits++;
        s.last = System.currentTimeMillis();
        history.put(url, s);   // newest last
        while (history.size() > 400) history.remove(history.keySet().iterator().next());
        save();
    }

    /** One entry per website, the most visited first (Safari's Frequently Visited). */
    List<Site> frequent(int max) {
        Map<String, Site> bySite = new LinkedHashMap<>();
        for (Site s : history.values()) {
            String d = domain(s.url);
            Site best = bySite.get(d);
            if (best == null) {
                best = new Site(Uri.parse(s.url).getScheme() + "://" + Uri.parse(s.url).getHost() + "/", d);
                bySite.put(d, best);
            }
            best.visits += s.visits;
            best.last = Math.max(best.last, s.last);
        }
        List<Site> out = new ArrayList<>(bySite.values());
        for (Site f : favorites) out.removeIf(s -> domain(s.url).equals(domain(f.url)));
        Collections.sort(out, (a, b) -> b.visits != a.visits ? b.visits - a.visits : Long.compare(b.last, a.last));
        return out.subList(0, Math.min(max, out.size()));
    }

    /** Favorites and history matching what's being typed. */
    List<Site> suggestions(String typed, int max) {
        String t = typed.trim().toLowerCase(Locale.ROOT);
        List<Site> out = new ArrayList<>();
        if (t.isEmpty()) return out;
        for (Site s : favorites) if (matches(s, t)) out.add(s);
        List<Site> h = new ArrayList<>(history.values());
        Collections.sort(h, (a, b) -> b.visits - a.visits);
        for (Site s : h) {
            if (out.size() >= max) break;
            if (matches(s, t) && !out.contains(s)) out.add(s);
        }
        return out.subList(0, Math.min(max, out.size()));
    }

    private static boolean matches(Site s, String t) {
        return s.url.toLowerCase(Locale.ROOT).contains(t) || (s.title != null && s.title.toLowerCase(Locale.ROOT).contains(t));
    }

    // ---- open tabs ------------------------------------------------------------------------------------------

    List<String> savedTabs() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(read("tabs.json"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    void saveTabs(List<String> urls, int current) {
        JSONArray a = new JSONArray();
        for (String u : urls) a.put(u == null ? "" : u);
        write("tabs.json", a.toString());
        write("tab.txt", String.valueOf(current));
    }

    int savedCurrent() {
        try {
            return Integer.parseInt(read("tab.txt").trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ---- storage ------------------------------------------------------------------------------------------

    private void load() {
        try {
            JSONArray a = new JSONArray(read("favorites.json"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                favorites.add(new Site(o.getString("url"), o.optString("title")));
            }
        } catch (Exception e) {
            for (String[] f : DEFAULT_FAVORITES) favorites.add(new Site(f[0], f[1]));
        }
        try {
            JSONArray a = new JSONArray(read("history.json"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Site s = new Site(o.getString("url"), o.optString("title"));
                s.visits = o.optInt("visits", 1);
                s.last = o.optLong("last");
                history.put(s.url, s);
            }
        } catch (Exception ignored) {
        }
    }

    private void save() {
        try {
            JSONArray f = new JSONArray();
            for (Site s : favorites) f.put(new JSONObject().put("url", s.url).put("title", s.title));
            write("favorites.json", f.toString());
            JSONArray h = new JSONArray();
            for (Site s : history.values()) {
                h.put(new JSONObject().put("url", s.url).put("title", s.title).put("visits", s.visits).put("last", s.last));
            }
            write("history.json", h.toString());
        } catch (Exception ignored) {
        }
    }

    void clearHistory() {
        history.clear();
        save();
    }

    private String read(String name) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(new File(dir, name).toPath()), "UTF-8");
    }

    private void write(String name, String s) {
        File f = new File(dir, name), tmp = new File(dir, name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.getBytes("UTF-8"));
        } catch (Exception e) {
            return;
        }
        tmp.renameTo(f);
    }
}
