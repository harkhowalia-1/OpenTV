package com.gathin.otvlive;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Channel logos from the web, fetched when a card comes on screen: kept in memory while browsing and on disk
 * (cache folder, which Android may clear) so they show instantly next time. Scaled down to card size.
 */
final class Images {

    private static Images instance;

    static synchronized Images get(Context ctx) {
        if (instance == null) instance = new Images(ctx.getApplicationContext());
        return instance;
    }

    private final File dir;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };

    private Images(Context ctx) {
        dir = new File(ctx.getCacheDir(), "logos");
        dir.mkdirs();
    }

    /** Shows `url` in `view` (or nothing), unless the view has been reused for another logo meanwhile. */
    void load(String url, ImageView view) {
        view.setTag(R.id.logo_url, url);
        if (url == null) {
            view.setImageDrawable(null);
            return;
        }
        Bitmap b = memory.get(url);
        if (b != null) {
            view.setImageBitmap(b);
            return;
        }
        view.setImageDrawable(null);
        pool.execute(() -> {
            Bitmap bmp = fetch(url);
            if (bmp != null) memory.put(url, bmp);
            main.post(() -> {
                if (url.equals(view.getTag(R.id.logo_url))) view.setImageBitmap(bmp);
            });
        });
    }

    private Bitmap fetch(String url) {
        File f = new File(dir, Integer.toHexString(url.hashCode()) + ".img");
        try {
            byte[] data;
            if (f.exists()) {
                data = java.nio.file.Files.readAllBytes(f.toPath());
            } else {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) IPTV/1.0");
                if (c.getResponseCode() != 200) return null;
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0 && out.size() < 4_000_000) out.write(buf, 0, n);
                    data = out.toByteArray();
                }
                try (FileOutputStream o = new FileOutputStream(f)) {
                    o.write(data);
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 320 && o.outHeight / (sample * 2) >= 120) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }
}
