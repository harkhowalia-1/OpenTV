package com.gathin.otvlive;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Every channel iptv-org lists with a working stream (iptv-org.github.io/api): channels, their streams,
 * logos, countries and categories.
 *
 * <p>The API is ~17 MB of JSON, too much to parse on every start on a 1.4 GB TV, so it is read once as a stream
 * (only channels that have a stream are kept), saved as a compact file, and refreshed in the background every
 * few days. Favourites and recently watched channels live in preferences.
 */
final class Catalog {

    static final class Stream {
        final String url, userAgent, referrer, quality;

        Stream(String url, String userAgent, String referrer, String quality) {
            this.url = url;
            this.userAgent = userAgent;
            this.referrer = referrer;
            this.quality = quality;
        }
    }

    static final class Channel {
        final String id, name, country, logo;
        final List<String> categories;
        final List<Stream> streams;

        Channel(String id, String name, String country, String logo, List<String> categories, List<Stream> streams) {
            this.id = id;
            this.name = name;
            this.country = country;
            this.logo = logo;
            this.categories = categories;
            this.streams = streams;
        }
    }

    static final class Country {
        final String code, name, flag;
        int count;

        Country(String code, String name, String flag) {
            this.code = code;
            this.name = name;
            this.flag = flag;
        }
    }

    interface Listener {
        void onCatalog(boolean ok);
    }

    private static final String API = "https://iptv-org.github.io/api/";
    private static final long REFRESH_MS = 3L * 24 * 3600 * 1000;
    private static final String TAG = "IPTV";

    private static Catalog instance;

    static synchronized Catalog get(Context ctx) {
        if (instance == null) instance = new Catalog(ctx.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private boolean loading;

    volatile List<Channel> channels = Collections.emptyList();
    volatile Map<String, Channel> byId = Collections.emptyMap();
    volatile Map<String, Country> countries = Collections.emptyMap();
    volatile Map<String, String> categories = Collections.emptyMap();

    private Catalog(Context ctx) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences("live", Context.MODE_PRIVATE);
    }

    private File cacheFile() {
        new File(ctx.getFilesDir(), "catalog.json").delete();   // the first version's format
        return new File(ctx.getFilesDir(), "catalog.bin");
    }

    boolean ready() {
        return !channels.isEmpty();
    }

    /** Loads the saved catalogue (fast), then fetches a fresh one if it's missing or old. */
    void load(Listener l) {
        if (l != null) listeners.add(l);
        if (loading) return;
        if (ready()) {
            notifyListeners(true);
            return;
        }
        loading = true;
        io.execute(() -> {
            File f = cacheFile();
            boolean ok = false;
            if (f.exists()) {
                try {
                    readCache(f);
                    ok = true;
                } catch (Exception e) {
                    Log.w(TAG, "cache unreadable", e);
                }
            }
            if (ok) post(true);
            if (!ok || System.currentTimeMillis() - f.lastModified() > REFRESH_MS) {
                try {
                    download();
                    readCache(f);
                    post(true);
                } catch (Exception e) {
                    Log.w(TAG, "couldn't fetch channels", e);
                    if (!ok) post(false);
                }
            }
            main.post(() -> loading = false);
        });
    }

    private void post(boolean ok) {
        main.post(() -> notifyListeners(ok));
    }

    private void notifyListeners(boolean ok) {
        for (Listener l : new ArrayList<>(listeners)) l.onCatalog(ok);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ---- lists for the screens -----------------------------------------------------------------------

    /** The TV's own country first ("CA" for a Canadian TV). */
    static String homeCountry() {
        String c = Locale.getDefault().getCountry();
        return c == null || c.isEmpty() ? "US" : c.toUpperCase(Locale.ROOT);
    }

    List<Channel> inCategory(String cat) {
        List<Channel> out = new ArrayList<>();
        for (Channel c : channels) if (c.categories.contains(cat)) out.add(c);
        sortHomeFirst(out);
        return out;
    }

    /** A country's channels, the likeliest-to-matter first (see {@link #score}). */
    List<Channel> inCountry(String code) {
        List<Channel> out = new ArrayList<>();
        for (Channel c : channels) if (code.equals(c.country)) out.add(c);
        Collections.sort(out, (a, b) -> Float.compare(score(b), score(a)));
        return out;
    }

    /** Channels from the TV's country first, then by {@link #score}. */
    private static void sortHomeFirst(List<Channel> list) {
        String home = homeCountry();
        Collections.sort(list, (a, b) -> {
            int ha = home.equals(a.country) ? 0 : 1, hb = home.equals(b.country) ? 0 : 1;
            if (ha != hb) return ha - hb;
            return Float.compare(score(b), score(a));
        });
    }

    /**
     * iptv-org has no ratings, so rows lead with channels that look like mainstream ones: a logo, HD, and a
     * general, news, entertainment or sports channel rather than a niche one.
     */
    private static float score(Channel c) {
        float s = c.logo != null ? 2 : 0;
        String q = c.streams.isEmpty() ? null : c.streams.get(0).quality;
        if ("1080p".equals(q)) s += 1;
        else if ("720p".equals(q)) s += 0.6f;
        for (String g : c.categories) {
            if (g.equals("general") || g.equals("news") || g.equals("entertainment") || g.equals("sports")) {
                s += 1.5f;
                break;
            }
        }
        if (c.streams.size() > 1) s += 0.3f;   // a fallback stream: more likely to play
        return s;
    }

    List<Channel> search(String q) {
        String s = q.trim().toLowerCase(Locale.ROOT);
        List<Channel> out = new ArrayList<>();
        if (s.isEmpty()) return out;
        for (Channel c : channels) {
            Country co = countries.get(c.country);
            if (c.name.toLowerCase(Locale.ROOT).contains(s)
                    || (co != null && co.name.toLowerCase(Locale.ROOT).startsWith(s))) {
                out.add(c);
                if (out.size() >= 300) break;
            }
        }
        return out;
    }

    List<Country> countryList() {
        List<Country> out = new ArrayList<>();
        for (Country c : countries.values()) if (c.count > 0) out.add(c);
        String home = homeCountry();
        Collections.sort(out, (a, b) -> a.code.equals(home) ? -1 : b.code.equals(home) ? 1 : a.name.compareTo(b.name));
        return out;
    }

    // ---- favourites and recents -----------------------------------------------------------------------

    List<Channel> favourites() {
        return resolve(prefs.getString("favourites", ""));
    }

    boolean isFavourite(Channel c) {
        return ("," + prefs.getString("favourites", "") + ",").contains("," + c.id + ",");
    }

    /** Adds or removes; returns whether it's a favourite now. */
    boolean toggleFavourite(Channel c) {
        List<String> ids = ids("favourites");
        boolean now = !ids.remove(c.id);
        if (now) ids.add(0, c.id);
        prefs.edit().putString("favourites", String.join(",", ids)).apply();
        return now;
    }

    List<Channel> recents() {
        return resolve(prefs.getString("recents", ""));
    }

    void watched(Channel c) {
        List<String> ids = ids("recents");
        ids.remove(c.id);
        ids.add(0, c.id);
        while (ids.size() > 20) ids.remove(ids.size() - 1);
        prefs.edit().putString("recents", String.join(",", ids)).apply();
    }

    private List<String> ids(String key) {
        List<String> out = new ArrayList<>();
        for (String s : prefs.getString(key, "").split(",")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    private List<Channel> resolve(String csv) {
        List<Channel> out = new ArrayList<>();
        for (String id : csv.split(",")) {
            Channel c = byId.get(id);
            if (c != null) out.add(c);
        }
        return out;
    }

    // ---- fetching ---------------------------------------------------------------------------------------

    private static InputStream open(String name) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(API + name).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "IPTV/1.0 (Android TV)");
        if (c.getResponseCode() != 200) throw new IOException(name + ": HTTP " + c.getResponseCode());
        return new BufferedInputStream(c.getInputStream(), 1 << 16);
    }

    private static String str(JsonReader r) throws IOException {
        if (r.peek() == JsonToken.NULL) {
            r.nextNull();
            return null;
        }
        if (r.peek() == JsonToken.BOOLEAN) return String.valueOf(r.nextBoolean());
        return r.nextString();
    }

    /** Reads the API as streams and writes the compact catalogue. */
    private void download() throws IOException {
        long t0 = System.currentTimeMillis();
        // 1. Streams, grouped by channel. Streams with no channel become channels of their own.
        Map<String, List<Stream>> streams = new HashMap<>();
        List<String[]> orphans = new ArrayList<>();   // title, url, ua, referrer, quality
        try (JsonReader r = new JsonReader(new InputStreamReader(open("streams.json"), StandardCharsets.UTF_8))) {
            r.beginArray();
            while (r.hasNext()) {
                String channel = null, url = null, ua = null, ref = null, q = null, title = null;
                r.beginObject();
                while (r.hasNext()) {
                    switch (r.nextName()) {
                        case "channel": channel = str(r); break;
                        case "url": url = str(r); break;
                        case "user_agent": ua = str(r); break;
                        case "referrer": ref = str(r); break;
                        case "quality": q = str(r); break;
                        case "title": title = str(r); break;
                        default: r.skipValue();
                    }
                }
                r.endObject();
                if (url == null) continue;
                if (channel != null) {
                    List<Stream> l = streams.get(channel);
                    if (l == null) streams.put(channel, l = new ArrayList<>(2));
                    l.add(new Stream(url, ua, ref, q));
                } else if (title != null) {
                    orphans.add(new String[]{title, url, ua, ref, q});
                }
            }
            r.endArray();
        }

        // 2. Channels that have a stream (not adult, not closed).
        Map<String, String[]> info = new HashMap<>();   // id -> name, country, categories csv
        try (JsonReader r = new JsonReader(new InputStreamReader(open("channels.json"), StandardCharsets.UTF_8))) {
            r.beginArray();
            while (r.hasNext()) {
                String id = null, name = null, country = null, closed = null;
                boolean nsfw = false;
                StringBuilder cats = new StringBuilder();
                r.beginObject();
                while (r.hasNext()) {
                    switch (r.nextName()) {
                        case "id": id = str(r); break;
                        case "name": name = str(r); break;
                        case "country": country = str(r); break;
                        case "closed": closed = str(r); break;
                        case "is_nsfw": nsfw = r.peek() == JsonToken.BOOLEAN && r.nextBoolean(); break;
                        case "categories":
                            if (r.peek() == JsonToken.NULL) { r.nextNull(); break; }
                            r.beginArray();
                            while (r.hasNext()) {
                                if (cats.length() > 0) cats.append(',');
                                cats.append(r.nextString());
                            }
                            r.endArray();
                            break;
                        default: r.skipValue();
                    }
                }
                r.endObject();
                if (id != null && name != null && !nsfw && closed == null && streams.containsKey(id)) {
                    info.put(id, new String[]{name, country, cats.toString()});
                }
            }
            r.endArray();
        }

        // 3. One logo per channel: the channel's own (not a feed's), in use.
        Map<String, String> logos = new HashMap<>();
        try (JsonReader r = new JsonReader(new InputStreamReader(open("logos.json"), StandardCharsets.UTF_8))) {
            r.beginArray();
            while (r.hasNext()) {
                String channel = null, feed = null, url = null;
                boolean inUse = true;
                r.beginObject();
                while (r.hasNext()) {
                    switch (r.nextName()) {
                        case "channel": channel = str(r); break;
                        case "feed": feed = str(r); break;
                        case "url": url = str(r); break;
                        case "in_use": inUse = r.peek() != JsonToken.BOOLEAN || r.nextBoolean(); break;
                        default: r.skipValue();
                    }
                }
                r.endObject();
                if (channel != null && url != null && inUse && feed == null && info.containsKey(channel)
                        && !logos.containsKey(channel)) {
                    logos.put(channel, url);
                }
            }
            r.endArray();
        }

        // 4. Countries and categories (small).
        Map<String, String[]> countryInfo = new LinkedHashMap<>();
        try (JsonReader r = new JsonReader(new InputStreamReader(open("countries.json"), StandardCharsets.UTF_8))) {
            r.beginArray();
            while (r.hasNext()) {
                String code = null, name = null, flag = null;
                r.beginObject();
                while (r.hasNext()) {
                    switch (r.nextName()) {
                        case "code": code = str(r); break;
                        case "name": name = str(r); break;
                        case "flag": flag = str(r); break;
                        default: r.skipValue();
                    }
                }
                r.endObject();
                if (code != null) countryInfo.put(code, new String[]{name, flag});
            }
            r.endArray();
        }
        Map<String, String> cats = new LinkedHashMap<>();
        try (JsonReader r = new JsonReader(new InputStreamReader(open("categories.json"), StandardCharsets.UTF_8))) {
            r.beginArray();
            while (r.hasNext()) {
                String id = null, name = null;
                r.beginObject();
                while (r.hasNext()) {
                    switch (r.nextName()) {
                        case "id": id = str(r); break;
                        case "name": name = str(r); break;
                        default: r.skipValue();
                    }
                }
                r.endObject();
                if (id != null) cats.put(id, name);
            }
            r.endArray();
        }

        // 5. The compact file (binary: reads in a second or two on the TV, where JSON took ~15 s).
        File tmp = new File(ctx.getFilesDir(), "catalog.tmp");
        try (java.io.DataOutputStream w = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(tmp), 1 << 16))) {
            w.writeInt(MAGIC);
            w.writeInt(countryInfo.size());
            for (Map.Entry<String, String[]> e : countryInfo.entrySet()) {
                put(w, e.getKey());
                put(w, e.getValue()[0]);
                put(w, e.getValue()[1]);
            }
            w.writeInt(cats.size());
            for (Map.Entry<String, String> e : cats.entrySet()) {
                put(w, e.getKey());
                put(w, e.getValue());
            }
            w.writeInt(info.size() + orphans.size());
            for (Map.Entry<String, String[]> e : info.entrySet()) {
                String[] i = e.getValue();
                put(w, e.getKey());
                put(w, i[0]);
                put(w, i[1]);
                put(w, i[2]);
                put(w, logos.get(e.getKey()));
                List<Stream> ss = streams.get(e.getKey());
                w.writeShort(ss.size());
                for (Stream st : ss) {
                    put(w, st.url);
                    put(w, st.userAgent);
                    put(w, st.referrer);
                    put(w, st.quality);
                }
            }
            int n = 0;
            for (String[] o : orphans) {
                put(w, "x" + (n++));
                put(w, o[0]);
                put(w, null);
                put(w, "");
                put(w, null);
                w.writeShort(1);
                put(w, o[1]);
                put(w, o[2]);
                put(w, o[3]);
                put(w, o[4]);
            }
        }
        if (!tmp.renameTo(cacheFile())) throw new IOException("couldn't save the catalogue");
        Log.i(TAG, "catalogue: " + info.size() + " channels + " + orphans.size() + " others in "
                + (System.currentTimeMillis() - t0) + " ms");
    }

    private static final int MAGIC = 0x4F4C5631;   // "OLV1"

    private static void put(java.io.DataOutputStream w, String v) throws IOException {
        w.writeBoolean(v != null);
        if (v != null) w.writeUTF(v.length() > 20000 ? v.substring(0, 20000) : v);
    }

    private static String take(java.io.DataInputStream r) throws IOException {
        return r.readBoolean() ? r.readUTF() : null;
    }

    private void readCache(File f) throws IOException {
        List<Channel> list = new ArrayList<>();
        Map<String, Channel> ids = new HashMap<>();
        Map<String, Country> cs = new HashMap<>();
        Map<String, String> cats = new LinkedHashMap<>();
        try (java.io.DataInputStream r = new java.io.DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16))) {
            if (r.readInt() != MAGIC) throw new IOException("old catalogue format");
            for (int i = r.readInt(); i > 0; i--) {
                String code = take(r), n = take(r), flag = take(r);
                cs.put(code, new Country(code, n, flag));
            }
            for (int i = r.readInt(); i > 0; i--) cats.put(take(r), take(r));
            for (int i = r.readInt(); i > 0; i--) {
                String id = take(r), n = take(r), country = take(r), catCsv = take(r), logo = take(r);
                int k = r.readShort();
                List<Stream> ss = new ArrayList<>(k);
                for (int j = 0; j < k; j++) ss.add(new Stream(take(r), take(r), take(r), take(r)));
                List<String> catList = new ArrayList<>(2);
                if (catCsv != null) for (String c : catCsv.split(",")) if (!c.isEmpty()) catList.add(c);
                Channel c = new Channel(id, n, country, logo, catList, ss);
                list.add(c);
                ids.put(id, c);
                Country co = country == null ? null : cs.get(country);
                if (co != null) co.count++;
            }
        }
        Collections.sort(list, (a, b) -> a.name.compareToIgnoreCase(b.name));
        countries = cs;
        categories = cats;
        byId = ids;
        channels = list;
    }

    /** Category ids in the order rows appear on Home. */
    static final String[] HOME_ROWS = {"news", "sports", "movies", "entertainment", "kids", "music", "documentary",
            "series", "comedy", "animation", "lifestyle", "cooking", "travel", "science", "business", "classic",
            "family", "outdoor", "auto", "culture", "education", "weather", "religious", "shop", "general"};

    static Set<String> homeRowSet() {
        return new HashSet<>(java.util.Arrays.asList(HOME_ROWS));
    }
}
