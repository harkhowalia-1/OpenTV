package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.util.Base64;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * macOS's wallpapers, straight from Apple: the list is Apple's public desktop-picture catalog (the one every Mac
 * uses), and a wallpaper is fetched from Apple's update servers only when it's previewed or chosen. Nothing is
 * copied into OpenTV or hosted anywhere else.
 *
 * <p>Most of Apple's downloads keep the picture uncompressed inside the zip, so the TV reads just the parts it needs
 * (a preview reads one picture, not the whole 100 MB file). Dynamic wallpapers become a set of pictures with the
 * time of day each belongs to, from the wallpaper's own metadata, so Home follows the day as a Mac does.
 */
final class MacOS {

    private static final String TAG = "OtvMacOS";
    private static final String CATALOG =
            "https://mesu.apple.com/assets/macos/com_apple_MobileAsset_DesktopPicture/com_apple_MobileAsset_DesktopPicture.xml";
    private static final long WEEK = 7L * 24 * 3600 * 1000;

    static final class Item {
        String name, url;
        long size;
    }

    // ---- the catalog ------------------------------------------------------------------------------------

    /** Apple's list (a copy at most a week old). Call off the main thread. */
    static List<Item> list(Context c) {
        File f = new File(c.getFilesDir(), "macos.xml");
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > WEEK) {
            for (String u : new String[]{CATALOG, CATALOG.replace("https://", "http://")}) {
                try {
                    byte[] b = Wallpapers.getBytes(u);
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(b);
                    }
                    break;
                } catch (Exception e) {
                    Log.w(TAG, "couldn't fetch the catalog from " + u, e);
                }
            }
        }
        List<Item> out = new ArrayList<>();
        try (InputStream in = new java.io.FileInputStream(f)) {
            Object root = plistXml(in);
            Object assets = root instanceof Map ? ((Map<?, ?>) root).get("Assets") : null;
            TreeMap<String, Item> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            if (assets instanceof List) {
                for (Object o : (List<?>) assets) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> a = (Map<?, ?>) o;
                    Object name = a.get("DesktopPictureID"), base = a.get("__BaseURL"), rel = a.get("__RelativePath");
                    if (name == null || base == null || rel == null) continue;
                    Item it = new Item();
                    it.name = Wallpapers.safe(name.toString());
                    it.url = base.toString() + rel;
                    Object s = a.get("_DownloadSize");
                    it.size = s instanceof Long ? (Long) s : 0;
                    byName.put(it.name, it);
                }
            }
            out.addAll(byName.values());
        } catch (Exception e) {
            Log.w(TAG, "couldn't read the catalog", e);
        }
        return out;
    }

    /** A small XML property-list reader (dict, array, string, integer, real, true/false). */
    private static Object plistXml(InputStream in) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(in, null);
        while (p.next() != XmlPullParser.START_TAG || p.getName().equals("plist")) {
            if (p.getEventType() == XmlPullParser.END_DOCUMENT) return null;
        }
        return plistValue(p);
    }

    private static Object plistValue(XmlPullParser p) throws Exception {
        String tag = p.getName();
        switch (tag) {
            case "dict": {
                Map<String, Object> m = new HashMap<>();
                String key = null;
                while (p.next() != XmlPullParser.END_TAG) {
                    if (p.getEventType() != XmlPullParser.START_TAG) continue;
                    if (p.getName().equals("key")) key = p.nextText();
                    else m.put(key, plistValue(p));
                }
                return m;
            }
            case "array": {
                List<Object> l = new ArrayList<>();
                while (p.next() != XmlPullParser.END_TAG) {
                    if (p.getEventType() == XmlPullParser.START_TAG) l.add(plistValue(p));
                }
                return l;
            }
            case "true":
            case "false":
                p.next();
                return tag.equals("true");
            case "integer":
                try {
                    return Long.parseLong(p.nextText().trim());
                } catch (NumberFormatException e) {
                    return 0L;
                }
            case "real":
                return Double.parseDouble(p.nextText().trim());
            default:
                return p.nextText();
        }
    }

    // ---- reading Apple's zip without downloading all of it ------------------------------------------------

    /** The picture inside one of Apple's zips: where it starts and how long it is, and whether it's compressed. */
    private static final class Entry {
        long offset, size;
        boolean stored;
    }

    private static Entry entry(String url) throws Exception {
        long total = length(url);
        int tailLen = (int) Math.min(total, 65536);
        byte[] tail = range(url, total - tailLen, tailLen);
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(tail).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i + 46 < tail.length; i++) {
            if (b.getInt(i) != 0x02014b50) continue;
            int method = b.getShort(i + 10) & 0xFFFF;
            long csize = b.getInt(i + 20) & 0xFFFFFFFFL;
            int nl = b.getShort(i + 28) & 0xFFFF, el = b.getShort(i + 30) & 0xFFFF;
            long local = b.getInt(i + 42) & 0xFFFFFFFFL;
            String name = new String(tail, i + 46, nl, "UTF-8");
            if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".heic")) continue;
            if (csize == 0xFFFFFFFFL || local == 0xFFFFFFFFL) {   // zip64: sizes live in the extra field
                java.nio.ByteBuffer x = java.nio.ByteBuffer.wrap(tail, i + 46 + nl, el).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                while (x.remaining() >= 4) {
                    int id = x.getShort() & 0xFFFF, len = x.getShort() & 0xFFFF;
                    if (id == 1) {
                        if ((b.getInt(i + 24) & 0xFFFFFFFFL) == 0xFFFFFFFFL) x.getLong();   // uncompressed size
                        if (csize == 0xFFFFFFFFL) csize = x.getLong();
                        if (local == 0xFFFFFFFFL) local = x.getLong();
                        break;
                    }
                    x.position(x.position() + len);
                }
            }
            byte[] lh = range(url, local, 30);
            java.nio.ByteBuffer l = java.nio.ByteBuffer.wrap(lh).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            Entry e = new Entry();
            e.offset = local + 30 + (l.getShort(26) & 0xFFFF) + (l.getShort(28) & 0xFFFF);
            e.size = csize;
            e.stored = method == 0;
            return e;
        }
        throw new java.io.IOException("no picture in " + url);
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(12000);
        h.setReadTimeout(30000);
        h.setRequestProperty("User-Agent", "OtvOS/" + Brand.OS_VERSION);
        return h;
    }

    private static long length(String url) throws Exception {
        HttpURLConnection h = open(url);
        h.setRequestMethod("HEAD");
        long n = h.getContentLengthLong();
        h.disconnect();
        if (n <= 0) throw new java.io.IOException("no length for " + url);
        return n;
    }

    private static byte[] range(String url, long from, int len) throws Exception {
        HttpURLConnection h = open(url);
        h.setRequestProperty("Range", "bytes=" + from + "-" + (from + len - 1));
        try (InputStream in = h.getInputStream()) {
            if (h.getResponseCode() != 206) throw new java.io.IOException("no partial reads from " + url);
            byte[] out = new byte[len];
            int got = 0, n;
            while (got < len && (n = in.read(out, got, len - got)) > 0) got += n;
            if (got < len) throw new java.io.IOException("short read");
            return out;
        }
    }

    /** The picture inside the zip as a random-access source, read from Apple in 1 MB pieces as needed. */
    private static final class Remote extends MediaDataSource {
        private static final int BLOCK = 1 << 20;
        private final String url;
        private final long base, size;
        private final Wallpapers.Progress progress;
        private long fetched;
        private final LinkedHashMap<Long, byte[]> blocks = new LinkedHashMap<Long, byte[]>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, byte[]> e) {
                return size() > 6;
            }
        };

        Remote(String url, Entry e, Wallpapers.Progress progress) {
            this.progress = progress;
            this.url = url;
            this.base = e.offset;
            this.size = e.size;
        }

        @Override
        public synchronized int readAt(long position, byte[] buffer, int offset, int len) throws java.io.IOException {
            if (position >= size) return -1;
            len = (int) Math.min(len, size - position);
            int done = 0;
            while (done < len) {
                long p = position + done, k = p / BLOCK;
                byte[] blk = blocks.get(k);
                if (blk == null) {
                    long start = k * BLOCK;
                    int n = (int) Math.min(BLOCK, size - start);
                    try {
                        blk = range(url, base + start, n);
                    } catch (java.io.IOException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new java.io.IOException(e);
                    }
                    blocks.put(k, blk);
                    fetched += n;
                    // Choosing reads (nearly) the whole picture file, so bytes fetched is the progress.
                    if (progress != null) progress.on(Math.min(0.97f, fetched / (float) size), true);
                }
                int in = (int) (p - k * BLOCK), n = Math.min(len - done, blk.length - in);
                System.arraycopy(blk, in, buffer, offset + done, n);
                done += n;
            }
            return done;
        }

        @Override
        public long getSize() {
            return size;
        }

        @Override
        public void close() {
            blocks.clear();
        }
    }

    /** A retriever on the wallpaper: read in place when possible, otherwise downloaded and unpacked first. */
    private static MediaMetadataRetriever retriever(Context c, Item it, File[] temp, Wallpapers.Progress progress) throws Exception {
        Entry e = entry(it.url);
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        if (e.stored) {
            Remote src = new Remote(it.url, e, progress);
            LAST_SOURCE.set(src);
            r.setDataSource(src);
            return r;
        }
        File f = new File(c.getCacheDir(), "macos-" + it.name.hashCode() + ".heic");
        HttpURLConnection h = open(it.url);
        long total = h.getContentLengthLong();
        long[] got = {0};
        InputStream raw = new java.io.FilterInputStream(h.getInputStream()) {
            @Override
            public int read(byte[] b, int off, int len) throws java.io.IOException {
                int n = super.read(b, off, len);
                if (n > 0 && progress != null && total > 0) progress.on(0.85f * (got[0] += n) / total, true);
                return n;
            }
        };
        try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(raw)) {
            java.util.zip.ZipEntry ze;
            while ((ze = z.getNextEntry()) != null) {
                if (!ze.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".heic")) continue;
                try (FileOutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = z.read(buf)) > 0) out.write(buf, 0, n);
                }
                break;
            }
        }
        temp[0] = f;
        LAST_SOURCE.set(f);
        r.setDataSource(f.getPath());
        return r;
    }

    // ---- previews and choosing one -------------------------------------------------------------------------

    static boolean supported() {
        return Build.VERSION.SDK_INT >= 28;   // HEIF pictures
    }

    static File dir(Context c, String name) {
        return Wallpapers.dir(c, "macos/" + name);
    }

    /** Is this one known to change with the time of day? (Known once it's been previewed or chosen.) */
    static boolean isDynamic(Context c, String name) {
        return new File(Wallpapers.dir(c, "macos-thumbs"), name + ".dynamic").exists();
    }

    /** A small preview (cached). Reads only the main picture from Apple. Call off the main thread. */
    static File thumbnail(Context c, Item it) {
        File f = new File(Wallpapers.dir(c, "macos-thumbs"), it.name + ".jpg");
        if (f.exists()) return f;
        File chosen = new File(dir(c, it.name), "0.jpg");
        File[] temp = new File[1];
        MediaMetadataRetriever r = null;
        try {
            if (chosen.exists() && new File(dir(c, it.name), "times.txt").exists()) {
                Bitmap b = android.graphics.BitmapFactory.decodeFile(Wallpapers.dynamicPreview(c, "macos:" + it.name).getPath());
                save(fit(b, 480, 270, true), f, 82);
                return f;
            }
            r = retriever(c, it, temp, null);
            int count = count(r);
            if (count > 1) new File(Wallpapers.dir(c, "macos-thumbs"), it.name + ".dynamic").createNewFile();
            Bitmap b = image(r, count > 1 ? middayIndex(r, count) : 0);
            if (b == null) return null;
            save(fit(b, 480, 270, true), f, 82);
            return f;
        } catch (Exception e) {
            Log.w(TAG, "no preview for " + it.name, e);
            return null;
        } finally {
            close(r);
            if (temp[0] != null) temp[0].delete();
        }
    }

    /**
     * Makes this the wallpaper: each picture at the TV's resolution (4K on 4K TVs), with its time of day. Only
     * the chosen macOS wallpaper is kept; the one before it is deleted. Call off the main thread.
     */
    static void choose(Context c, Item it) throws Exception {
        File[] temp = new File[1];
        MediaMetadataRetriever r = null;
        try {
            Wallpapers.Progress p = Wallpapers.progress.get();
            r = retriever(c, it, temp, p);
            int count = count(r);
            int[] s = Wallpapers.screen(c);
            File staging = Wallpapers.dir(c, "macos-new");
            clear(staging);
            int[] minutes = count > 1 ? times(r, count) : new int[]{0};
            StringBuilder times = new StringBuilder();
            int saved = 0;
            for (int i = 0; i < minutes.length && i < count; i++) {
                if (minutes[i] < 0) continue;   // a picture the wallpaper never shows
                Bitmap b = image(r, i);
                if (b == null) continue;
                save(fit(b, s[0], s[1], true), new File(staging, saved + ".jpg"), 90);
                times.append(saved == 0 ? "" : ",").append(minutes[i]);
                saved++;
            }
            if (saved == 0) throw new java.io.IOException("no pictures in " + it.name);
            try (FileOutputStream f = new FileOutputStream(new File(staging, "times.txt"))) {
                f.write(times.toString().getBytes("UTF-8"));
            }
            if (saved > 1) new File(Wallpapers.dir(c, "macos-thumbs"), it.name + ".dynamic").createNewFile();
            File root = Wallpapers.dir(c, "macos");
            File[] old = root.listFiles();
            if (old != null) for (File o : old) if (!o.equals(staging)) { clear(o); o.delete(); }
            File target = new File(root, it.name);
            clear(target);
            target.delete();
            if (!staging.renameTo(target)) throw new java.io.IOException("couldn't keep " + it.name);
            Wallpapers.set(c, "macos:" + it.name);
            Wallpapers.prefs(c).edit().remove("dynamicShown").apply();
            Wallpapers.refreshDynamic(c);
        } finally {
            close(r);
            if (temp[0] != null) temp[0].delete();
        }
    }

    static void clear(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) {
            if (f.isDirectory()) clear(f);
            f.delete();
        }
    }

    private static void close(MediaMetadataRetriever r) {
        if (r == null) return;
        try {
            r.release();
        } catch (Exception ignored) {
        }
    }

    private static int count(MediaMetadataRetriever r) {
        try {
            return Math.max(1, Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_IMAGE_COUNT)));
        } catch (Exception e) {
            return 1;
        }
    }

    private static Bitmap image(MediaMetadataRetriever r, int index) {
        MediaMetadataRetriever.BitmapParams p = new MediaMetadataRetriever.BitmapParams();
        p.setPreferredConfig(Bitmap.Config.RGB_565);
        return index == 0 ? r.getImageAtIndex(0, p) : r.getImageAtIndex(index, p);
    }

    /** Centre-crops to w×h (or fits inside when crop is false), recycling the source. */
    private static Bitmap fit(Bitmap src, int w, int h, boolean crop) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        float k = crop ? Math.max(w / (float) src.getWidth(), h / (float) src.getHeight())
                : Math.min(w / (float) src.getWidth(), h / (float) src.getHeight());
        int dw = Math.round(src.getWidth() * k), dh = Math.round(src.getHeight() * k);
        new Canvas(out).drawBitmap(src, null, new Rect((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        return out;
    }

    private static void save(Bitmap b, File out, int quality) throws Exception {
        File tmp = new File(out.getPath() + ".tmp");
        try (FileOutputStream f = new FileOutputStream(tmp)) {
            b.compress(Bitmap.CompressFormat.JPEG, quality, f);
        }
        b.recycle();
        if (!tmp.renameTo(out)) throw new java.io.IOException("couldn't save " + out);
    }

    // ---- time of day --------------------------------------------------------------------------------------

    private static final int XMP_OFFSET = 41, XMP_LENGTH = 42;   // MediaMetadataRetriever keys (Android 11+)
    private static final Pattern DESKTOP = Pattern.compile("apple_desktop:(h24|solar|apr)\\s*=\\s*\"([A-Za-z0-9+/=\\s]+)\"|<apple_desktop:(h24|solar|apr)>([A-Za-z0-9+/=\\s]+)<");

    /** The picture for early afternoon (for the preview). */
    private static int middayIndex(MediaMetadataRetriever r, int count) {
        int[] t = times(r, count);
        int best = 0, bestD = Integer.MAX_VALUE;
        for (int i = 0; i < t.length; i++) {
            if (t[i] < 0) continue;
            int d = Math.abs(t[i] - 13 * 60);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    /**
     * The minute of the day each picture starts at (-1: not shown), from the wallpaper's metadata: "h24" gives
     * times; "solar" gives the sun's compass direction (east morning, south noon, west evening); "apr" is a light
     * and a dark picture. Without metadata the pictures share the day evenly.
     */
    private static int[] times(MediaMetadataRetriever r, int count) {
        int[] out = new int[count];
        java.util.Arrays.fill(out, -1);
        try {
            byte[] xmp = xmp(r);
            Matcher m = xmp == null ? null : DESKTOP.matcher(new String(xmp, "UTF-8"));
            if (m != null && m.find()) {
                String kind = m.group(1) != null ? m.group(1) : m.group(3);
                String b64 = (m.group(2) != null ? m.group(2) : m.group(4)).replaceAll("\\s", "");
                Object plist = bplist(Base64.decode(b64, Base64.DEFAULT));
                if (plist instanceof Map) {
                    Map<?, ?> p = (Map<?, ?>) plist;
                    if (kind.equals("h24") && p.get("ti") instanceof List) {
                        for (Object o : (List<?>) p.get("ti")) {
                            Map<?, ?> t = (Map<?, ?>) o;
                            int i = num(t.get("i")).intValue();
                            if (i >= 0 && i < count && out[i] < 0) out[i] = (int) Math.round(num(t.get("t")).doubleValue() * 1440) % 1440;
                        }
                    } else if (kind.equals("solar") && p.get("si") instanceof List) {
                        for (Object o : (List<?>) p.get("si")) {
                            Map<?, ?> t = (Map<?, ?>) o;
                            int i = num(t.get("i")).intValue();
                            double z = num(t.get("z")).doubleValue() % 360;
                            if (i >= 0 && i < count && out[i] < 0) out[i] = (int) Math.round(z / 360 * 1440) % 1440;
                        }
                    } else if (kind.equals("apr")) {
                        int l = num(p.get("l")).intValue(), d = num(p.get("d")).intValue();
                        if (l >= 0 && l < count) out[l] = 7 * 60;
                        if (d >= 0 && d < count) out[d] = 19 * 60;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "couldn't read the time of day", e);
        }
        boolean any = false;
        for (int t : out) any |= t >= 0;
        if (!any) for (int i = 0; i < count; i++) out[i] = i * 1440 / count;
        return out;
    }

    private static Number num(Object o) {
        return o instanceof Number ? (Number) o : 0;
    }

    private static byte[] xmp(MediaMetadataRetriever r) {
        if (Build.VERSION.SDK_INT < 30) return null;
        try {
            int off = Integer.parseInt(r.extractMetadata(XMP_OFFSET)), len = Integer.parseInt(r.extractMetadata(XMP_LENGTH));
            if (len <= 0 || len > 1 << 20) return null;
            // The retriever doesn't hand out its data source, so read the XMP through our own copy of it.
            return LAST_SOURCE.get() == null ? null : read(LAST_SOURCE.get(), off, len);
        } catch (Exception e) {
            return null;
        }
    }

    private static final ThreadLocal<Object> LAST_SOURCE = new ThreadLocal<>();

    private static byte[] read(Object src, long off, int len) throws Exception {
        byte[] out = new byte[len];
        if (src instanceof MediaDataSource) {
            int got = 0;
            while (got < len) {
                int n = ((MediaDataSource) src).readAt(off + got, out, got, len - got);
                if (n <= 0) break;
                got += n;
            }
        } else {
            try (java.io.RandomAccessFile f = new java.io.RandomAccessFile((File) src, "r")) {
                f.seek(off);
                f.readFully(out);
            }
        }
        return out;
    }

    /** A binary property list (dict, array, int, real, bool, string). */
    static Object bplist(byte[] b) {
        java.nio.ByteBuffer t = java.nio.ByteBuffer.wrap(b);
        int n = b.length;
        int offSize = b[n - 26] & 0xFF, refSize = b[n - 25] & 0xFF;
        long count = t.getLong(n - 24), top = t.getLong(n - 16), table = t.getLong(n - 8);
        long[] offs = new long[(int) count];
        for (int i = 0; i < count; i++) offs[i] = be(b, (int) table + i * offSize, offSize);
        return bpObject(b, offs, (int) top, refSize, 0);
    }

    private static long be(byte[] b, int at, int len) {
        long v = 0;
        for (int i = 0; i < len; i++) v = (v << 8) | (b[at + i] & 0xFF);
        return v;
    }

    private static Object bpObject(byte[] b, long[] offs, int ref, int refSize, int depth) {
        if (depth > 16) return null;
        int at = (int) offs[ref];
        int marker = b[at] & 0xFF, type = marker >> 4, info = marker & 0xF;
        if (type == 0) return info == 9;
        if (type == 1) return be(b, at + 1, 1 << info);
        if (type == 2) {
            return info == 2 ? (double) Float.intBitsToFloat((int) be(b, at + 1, 4)) : Double.longBitsToDouble(be(b, at + 1, 8));
        }
        int len = info, start = at + 1;
        if (info == 0xF) {
            int intLen = 1 << (b[at + 1] & 0xF);
            len = (int) be(b, at + 2, intLen);
            start = at + 2 + intLen;
        }
        if (type == 5) return new String(b, start, len, java.nio.charset.StandardCharsets.US_ASCII);
        if (type == 6) return new String(b, start, len * 2, java.nio.charset.StandardCharsets.UTF_16BE);
        if (type == 0xA) {
            List<Object> l = new ArrayList<>();
            for (int i = 0; i < len; i++) l.add(bpObject(b, offs, (int) be(b, start + i * refSize, refSize), refSize, depth + 1));
            return l;
        }
        if (type == 0xD) {
            Map<String, Object> m = new HashMap<>();
            for (int i = 0; i < len; i++) {
                Object k = bpObject(b, offs, (int) be(b, start + i * refSize, refSize), refSize, depth + 1);
                Object v = bpObject(b, offs, (int) be(b, start + (len + i) * refSize, refSize), refSize, depth + 1);
                m.put(String.valueOf(k), v);
            }
            return m;
        }
        return null;
    }
}
