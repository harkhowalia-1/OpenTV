package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Apple TV's aerial videos, from Apple's own servers (the list Apple TV itself uses). Nothing is copied or
 * hosted elsewhere: the list is fetched from Apple and each video streams from Apple when it's the wallpaper.
 * 4K TVs that can decode HEVC get the 4K version; others the 1080p H.264 one.
 */
final class Aerials {

    // Apple's aerial server uses Apple's own certificate authority, which Android doesn't include, so it's reached
    // over plain HTTP (the videos are public and need no sign-in).
    private static final String LIST = "http://sylvan.apple.com/Aerials/resources-16.tar";
    private static final long WEEK = 7L * 24 * 3600 * 1000;

    static final class Aerial {
        String id, name, url1080, url4k, url1080hevc;
        /** tvOS's theme: Landscape, Cityscape, Earth or Underwater. */
        String category;
        /** Where the camera is, by second of the video (Apple's captions: "Over Midtown Manhattan…"). */
        final java.util.TreeMap<Integer, String> places = new java.util.TreeMap<>();
    }

    /** Apple's theme names on tvOS, from the list's category keys. */
    static String theme(String key) {
        switch (key) {
            case "Cities": return "Cityscape";
            case "Space": return "Earth";
            case "Landscapes": return "Landscape";
            default: return key;
        }
    }

    static final String[] THEMES = {"Landscape", "Cityscape", "Earth", "Underwater"};

    private static File stringsFile(Context c) {
        return new File(c.getFilesDir(), "aerials-strings.plist");
    }

    private static java.util.Map<?, ?> strings;

    /** The caption for this moment of the aerial ("Over the Dominican Republic…"), or its name. */
    static String caption(Context c, Aerial a, int seconds) {
        java.util.Map.Entry<Integer, String> e = a.places.floorEntry(seconds);
        if (e == null) return a.name;
        synchronized (Aerials.class) {
            if (strings == null) {
                try {
                    Object o = MacOS.bplist(java.nio.file.Files.readAllBytes(stringsFile(c).toPath()));
                    strings = o instanceof java.util.Map ? (java.util.Map<?, ?>) o : new HashMap<>();
                } catch (Exception ex) {
                    strings = new HashMap<>();
                }
            }
        }
        Object s = strings.get(e.getValue());
        return s != null ? s.toString().replace('\u00A0', ' ') : a.name;
    }

    private static File cache(Context c) {
        return new File(c.getFilesDir(), "aerials.json");
    }

    /** The aerials (from a copy at most a week old; otherwise fetched again). Call off the main thread. */
    static List<Aerial> list(Context c) {
        File f = cache(c);
        try {
            if (!f.exists() || !stringsFile(c).exists() || System.currentTimeMillis() - f.lastModified() > WEEK) {
                byte[] tar = Wallpapers.getBytes(LIST);
                byte[] json = untar(tar, "entries.json");
                if (json != null) {
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(json);
                    }
                }
                // The captions, in this TV's language (English if Apple has none for it).
                String lang = java.util.Locale.getDefault().getLanguage();
                byte[] strs = untar(tar, "/" + lang + ".lproj/Localizable.nocache.strings");
                if (strs == null) strs = untar(tar, "/en.lproj/Localizable.nocache.strings");
                if (strs != null) {
                    try (FileOutputStream out = new FileOutputStream(stringsFile(c))) {
                        out.write(strs);
                    }
                    synchronized (Aerials.class) {
                        strings = null;
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.w("OtvAerials", "couldn't fetch the aerial list", e);   // offline: use the copy we have
        }
        List<Aerial> out = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
            HashMap<String, String> cats = new HashMap<>();
            JSONArray cs = o.optJSONArray("categories");
            for (int i = 0; cs != null && i < cs.length(); i++) {
                JSONObject cat = cs.getJSONObject(i);
                String key = cat.optString("localizedNameKey").replace("AerialCategory", "");
                cats.put(cat.optString("id"), key);
            }
            JSONArray a = o.getJSONArray("assets");
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.getJSONObject(i);
                Aerial x = new Aerial();
                x.id = e.optString("id");
                x.name = e.optString("accessibilityLabel", "Aerial");
                x.url1080 = plain(e.optString("url-1080-H264", null));
                x.url4k = plain(e.optString("url-4K-SDR", null));
                x.url1080hevc = plain(e.optString("url-1080-SDR", null));
                JSONArray ec = e.optJSONArray("categories");
                x.category = theme(ec != null && ec.length() > 0 ? cats.getOrDefault(ec.getString(0), "") : "");
                JSONObject poi = e.optJSONObject("pointsOfInterest");
                if (poi != null) {
                    for (java.util.Iterator<String> k = poi.keys(); k.hasNext(); ) {
                        String at = k.next();
                        try {
                            x.places.put(Integer.parseInt(at), poi.getString(at));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                if (x.url1080 != null) out.add(x);
            }
        } catch (Exception e) {
            android.util.Log.w("OtvAerials", "couldn't read the aerial list", e);
        }
        return out;
    }

    private static String plain(String url) {
        return url == null ? null : url.replaceFirst("^https://sylvan\\.apple\\.com", "http://sylvan.apple.com");
    }

    /** The video for this TV: 4K HEVC on 4K TVs that can play it, otherwise 1080p. */
    static String videoFor(Context c, Aerial a) {
        if (Wallpapers.is4k(c) && a.url4k != null && canPlay4kHevc()) return a.url4k;
        return a.url1080;
    }

    static boolean canPlay4kHevc() {
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 3840, 2160);
        return new MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(f) != null;
    }

    static boolean canPlayHevc() {
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080);
        return new MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(f) != null;
    }

    static Aerial byId(Context c, String id) {
        for (Aerial a : list(c)) if (a.id.equals(id)) return a;
        return null;
    }

    /** A small preview picture (cached), taken from the video a few seconds in. Call off the main thread. */
    static File thumbnail(Context c, Aerial a) {
        File f = new File(Wallpapers.dir(c, "aerial-thumbs"), a.id + ".jpg");
        if (f.exists()) return f;
        try {
            Bitmap b = frame(a.url1080, 384, 216);
            if (b == null) return null;
            try (FileOutputStream out = new FileOutputStream(f)) {
                b.compress(Bitmap.CompressFormat.JPEG, 82, out);
            }
            b.recycle();
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    /** A full-size still of the aerial (shown while the video starts, and if it can't play). */
    static void saveStill(Context c, Aerial a, File out) throws Exception {
        int[] s = Wallpapers.screen(c);
        Bitmap b = frame(a.url1080, s[0], s[1]);
        if (b == null) throw new java.io.IOException("no picture");
        try (FileOutputStream f = new FileOutputStream(out)) {
            b.compress(Bitmap.CompressFormat.JPEG, 90, f);
        }
        b.recycle();
    }

    private static Bitmap frame(String url, int w, int h) throws Exception {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(url, new HashMap<>());
            Bitmap b = r.getFrameAtTime(4_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b == null) return null;
            Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
            if (s != b) b.recycle();
            return s;
        } finally {
            r.release();
        }
    }

    /** One file out of a tar archive. */
    private static byte[] untar(byte[] tar, String name) {
        int pos = 0;
        while (pos + 512 <= tar.length) {
            String n = new String(tar, pos, 100).trim().replace("\0", "");
            if (n.isEmpty()) break;
            String sizeOct = new String(tar, pos + 124, 12).trim().replace("\0", "");
            int size = sizeOct.isEmpty() ? 0 : Integer.parseInt(sizeOct.trim(), 8);
            if (n.endsWith(name)) {
                byte[] out = new byte[size];
                System.arraycopy(tar, pos + 512, out, 0, size);
                return out;
            }
            pos += 512 + ((size + 511) / 512) * 512;
        }
        return null;
    }
}
