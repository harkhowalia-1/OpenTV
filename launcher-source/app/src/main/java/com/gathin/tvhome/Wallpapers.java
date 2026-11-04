package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The Home screen's wallpaper: where it comes from and the picture Home draws ({@link #current}).
 *
 * <p>Kinds (the "wallpaper" preference): "" the colour glow; "device#n" a wallpaper built into the device;
 * "bing" Bing's picture of the day (fetched daily, older ones deleted); "aerial:ID" one of Apple TV's aerial
 * videos, streamed from Apple and played behind the apps; "upload:NAME" a picture sent from the iPhone or Mac, or
 * picked from the TV's photos; "dynamic:NAME" a macOS dynamic wallpaper sent from the Mac, whose picture follows
 * the time of day. Every still is saved once at screen size, so Home never decodes anything large.
 */
final class Wallpapers {

    static final String PREF = "wallpaper";
    private static final String TAG = "OtvWallpaper";

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    static String kind(Context c) {
        return prefs(c).getString(PREF, "");
    }

    /** The still Home shows (for a live aerial: its first picture, shown until the video starts). */
    static File current(Context c) {
        return new File(c.getFilesDir(), "wallpaper.jpg");
    }

    static File dir(Context c, String sub) {
        File d = new File(new File(c.getFilesDir(), "wallpapers"), sub);
        d.mkdirs();
        return d;
    }

    /** The aerial video to play behind Home, or null. */
    static String video(Context c) {
        String k = kind(c);
        return k.startsWith("aerial:") ? prefs(c).getString("aerialUrl", null) : null;
    }

    static void set(Context c, String kind) {
        prefs(c).edit().putString(PREF, kind).putLong("changed", System.currentTimeMillis()).apply();
    }

    static void glow(Context c) {
        current(c).delete();
        set(c, "");
    }

    // ---- pictures at screen size ------------------------------------------------------------------------

    /**
     * The TV's real panel resolution (Android often lays out its interface at 1080p even on a 4K set), so
     * wallpapers are saved in full 4K on 4K TVs and no bigger than needed on smaller ones.
     */
    static int[] screen(Context c) {
        int w = 0, h = 0;
        try {
            android.view.Display d = c.getSystemService(android.hardware.display.DisplayManager.class)
                    .getDisplay(android.view.Display.DEFAULT_DISPLAY);
            for (android.view.Display.Mode m : d.getSupportedModes()) {
                w = Math.max(w, Math.max(m.getPhysicalWidth(), m.getPhysicalHeight()));
                h = Math.max(h, Math.min(m.getPhysicalWidth(), m.getPhysicalHeight()));
            }
        } catch (Exception ignored) {
        }
        android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
        w = Math.max(w, Math.max(m.widthPixels, m.heightPixels));
        h = Math.max(h, Math.min(m.widthPixels, m.heightPixels));
        return new int[]{Math.min(w, 3840), Math.min(h, 2160)};
    }

    static boolean is4k(Context c) {
        return screen(c)[0] >= 3840;
    }

    /** Centre-crops a drawable to the screen and saves it as the current still. */
    static void saveDrawable(Context c, Drawable d, File out) throws Exception {
        int[] s = screen(c);
        Bitmap bmp = Bitmap.createBitmap(s[0], s[1], Bitmap.Config.RGB_565);
        Canvas cv = new Canvas(bmp);
        float k = Math.max(s[0] / (float) Math.max(1, d.getIntrinsicWidth()), s[1] / (float) Math.max(1, d.getIntrinsicHeight()));
        int dw = Math.round(d.getIntrinsicWidth() * k), dh = Math.round(d.getIntrinsicHeight() * k);
        d.setBounds((s[0] - dw) / 2, (s[1] - dh) / 2, (s[0] - dw) / 2 + dw, (s[1] - dh) / 2 + dh);
        d.draw(cv);
        write(bmp, out);
        bmp.recycle();
    }

    /** Decodes image bytes (any size) straight down to the screen, centre-cropped, and saves them. */
    static void saveBytes(Context c, byte[] data, File out) throws Exception {
        int[] s = screen(c);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0) throw new IllegalArgumentException("not a picture");
        int sample = 1;
        while (o.outWidth / (sample * 2) >= s[0] && o.outHeight / (sample * 2) >= s[1]) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        Bitmap bmp = Bitmap.createBitmap(s[0], s[1], Bitmap.Config.RGB_565);
        Canvas cv = new Canvas(bmp);
        float k = Math.max(s[0] / (float) src.getWidth(), s[1] / (float) src.getHeight());
        int dw = Math.round(src.getWidth() * k), dh = Math.round(src.getHeight() * k);
        cv.drawBitmap(src, null, new android.graphics.Rect((s[0] - dw) / 2, (s[1] - dh) / 2, (s[0] - dw) / 2 + dw, (s[1] - dh) / 2 + dh),
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        write(bmp, out);
        bmp.recycle();
    }

    private static void write(Bitmap bmp, File out) throws Exception {
        File tmp = new File(out.getPath() + ".tmp");
        try (FileOutputStream f = new FileOutputStream(tmp)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, f);
        }
        if (!tmp.renameTo(out)) throw new java.io.IOException("couldn't save " + out);
    }

    static void copy(File from, File to) throws Exception {
        try (InputStream in = new java.io.FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    static String safe(String name) {
        String s = name == null ? "" : name.replaceAll("[^A-Za-z0-9 ._-]", "").trim();
        return s.isEmpty() ? "Picture" : s.length() > 60 ? s.substring(0, 60) : s;
    }

    // ---- pictures you send (iPhone, Mac, the TV's photos) -------------------------------------------------

    /** A picture from the iPhone or Mac (or the TV's photos): kept in "Your Pictures" and shown now. */
    static void saveUpload(Context c, String name, byte[] data) throws Exception {
        String n = safe(name);
        File f = new File(dir(c, "uploads"), n + ".jpg");
        saveBytes(c, data, f);
        copy(f, current(c));
        set(c, "upload:" + n);
    }

    static List<File> uploads(Context c) {
        List<File> out = new ArrayList<>();
        File[] fs = dir(c, "uploads").listFiles((d, n) -> n.endsWith(".jpg"));
        if (fs != null) for (File f : fs) out.add(f);
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    // ---- dynamic wallpapers (time of day) ---------------------------------------------------------------

    /**
     * A macOS dynamic wallpaper from the Mac: "OTVD", u16 count, then for each picture u16 minute of the day it
     * starts at and u32 length + JPEG. Saved as numbered pictures and a list of start times.
     */
    static void saveDynamic(Context c, String name, byte[] raw) throws Exception {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(raw);
        if (b.getInt() != 0x4F545644) throw new IllegalArgumentException("not a dynamic wallpaper");
        int count = b.getShort() & 0xFFFF;
        String n = safe(name);
        File root = dir(c, "macos");
        File[] others = root.listFiles();
        if (others != null) for (File o : others) { MacOS.clear(o); o.delete(); }
        File d = dir(c, "macos/" + n);
        StringBuilder times = new StringBuilder();
        for (int i = 0; i < count; i++) {
            int minute = b.getShort() & 0xFFFF;
            byte[] jpeg = new byte[b.getInt()];
            b.get(jpeg);
            saveBytes(c, jpeg, new File(d, i + ".jpg"));
            times.append(i == 0 ? "" : ",").append(minute);
        }
        try (FileOutputStream f = new FileOutputStream(new File(d, "times.txt"))) {
            f.write(times.toString().getBytes("UTF-8"));
        }
        if (count > 1) new File(dir(c, "macos-thumbs"), n + ".dynamic").createNewFile();
        new File(dir(c, "macos-thumbs"), n + ".jpg").delete();   // a fresh preview from these pictures
        set(c, "macos:" + n);
        prefs(c).edit().remove("dynamicShown").apply();
        refreshDynamic(c);
    }

    /** The folder of pictures for a time-of-day wallpaper ("macos:NAME", or the older "dynamic:NAME"). */
    static File dynamicDir(Context c, String kind) {
        if (kind.startsWith("macos:")) return dir(c, "macos/" + kind.substring(6));
        if (kind.startsWith("dynamic:")) return dir(c, "dynamic/" + kind.substring(8));
        return null;
    }

    /** OtvOS 1.2.x kept the Mac's dynamic wallpapers under "Your Pictures": they're macOS wallpapers now. */
    static void migrate(Context c) {
        File[] old = dir(c, "dynamic").listFiles(File::isDirectory);
        if (old == null || old.length == 0) return;
        String k = kind(c);
        for (File d : old) {
            File to = new File(dir(c, "macos"), d.getName());
            if (k.equals("dynamic:" + d.getName()) && !to.exists() && d.renameTo(to)) {
                set(c, "macos:" + d.getName());
            } else {
                MacOS.clear(d);
                d.delete();
            }
        }
    }

    /** The early-afternoon picture of a time-of-day wallpaper (kind "macos:NAME"). */
    static File dynamicPreview(Context c, String kind) {
        File d = dynamicDir(c, kind);
        int i = frameFor(d, 13 * 60);   // early afternoon
        return new File(d, i + ".jpg");
    }

    /** The picture whose start time is the latest one not after this minute of the day (wrapping round). */
    private static int frameFor(File d, int minute) {
        try {
            String[] parts = new String(java.nio.file.Files.readAllBytes(new File(d, "times.txt").toPath()), "UTF-8").split(",");
            int best = -1, bestT = -1, latest = 0, latestT = -1;
            for (int i = 0; i < parts.length; i++) {
                int t = Integer.parseInt(parts[i].trim());
                if (t <= minute && t > bestT) { best = i; bestT = t; }
                if (t > latestT) { latest = i; latestT = t; }
            }
            return best >= 0 ? best : latest;
        } catch (Exception e) {
            return 0;
        }
    }

    /** For a dynamic wallpaper: puts the picture for this time of day in place. True if it changed. */
    static boolean refreshDynamic(Context c) {
        String k = kind(c);
        File d = dynamicDir(c, k);
        if (d == null) return false;
        Calendar now = Calendar.getInstance();
        int i = frameFor(d, now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE));
        if (prefs(c).getInt("dynamicShown", -1) == i && current(c).exists()) return false;
        try {
            copy(new File(d, i + ".jpg"), current(c));
            prefs(c).edit().putInt("dynamicShown", i).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- Bing's picture of the day ----------------------------------------------------------------------

    static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date());
    }

    /**
     * Today's Bing picture in 4K, through the bing-wallpaper service (github.com/TimothyYe/bing-wallpaper), or
     * Bing directly if that's unavailable. Keeps only today's: earlier ones are deleted.
     */
    static File fetchBing(Context c) throws Exception {
        String mkt = Locale.getDefault().toLanguageTag();
        File f = new File(dir(c, "bing"), "bing-" + today() + ".jpg");
        if (!f.exists()) {
            String url;
            try {
                JSONObject o = new JSONObject(get("https://bing.biturl.top/?resolution=UHD&format=json&index=0&mkt=" + mkt));
                url = o.getString("url");
            } catch (Exception e) {
                JSONObject o = new JSONObject(get("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=" + mkt));
                url = "https://www.bing.com" + o.getJSONArray("images").getJSONObject(0).getString("urlbase") + "_UHD.jpg";
            }
            saveBytes(c, getBytes(url), f);
        }
        File[] all = dir(c, "bing").listFiles();
        if (all != null) for (File o : all) if (!o.equals(f)) o.delete();
        return f;
    }

    // ---- keeping it fresh -------------------------------------------------------------------------------

    /**
     * Run every so often in the background: the new Bing picture each day, the right picture for the time of
     * day, and tidying (yesterday's Bing picture is deleted when it isn't the wallpaper any more).
     */
    static void refresh(Context c) {
        try {
            migrate(c);
            String k = kind(c);
            // macOS wallpapers come from Apple again whenever they're wanted: keep only the one in use.
            File[] mac = dir(c, "macos").listFiles();
            if (mac != null) for (File o : mac) {
                if (!k.equals("macos:" + o.getName())) { MacOS.clear(o); o.delete(); }
            }
            if (k.equals("bing")) {
                if (!today().equals(prefs(c).getString("bingDay", ""))) {
                    copy(fetchBing(c), current(c));
                    prefs(c).edit().putString("bingDay", today()).putLong("changed", System.currentTimeMillis()).apply();
                }
            } else {
                File[] all = dir(c, "bing").listFiles();
                if (all != null) for (File o : all) o.delete();   // not in use: nothing to keep
            }
            if (refreshDynamic(c)) prefs(c).edit().putLong("changed", System.currentTimeMillis()).apply();
        } catch (Exception e) {
            Log.w(TAG, "wallpaper refresh failed", e);
        }
    }

    // ---- web ----------------------------------------------------------------------------------------------

    static String get(String url) throws Exception {
        return new String(getBytes(url), "UTF-8");
    }

    static byte[] getBytes(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setInstanceFollowRedirects(true);
        h.setConnectTimeout(12000);
        h.setReadTimeout(30000);
        h.setRequestProperty("User-Agent", "OtvOS/" + Brand.OS_VERSION);
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
