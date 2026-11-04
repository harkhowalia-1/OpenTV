package local.cloudgaming;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Game art: downloaded once at the size it's shown (Microsoft's image service resizes it), kept on disk, and the
 * pictures on screen kept in a small memory cache. Cards that scroll away stop waiting for theirs.
 */
final class Images {

    private static final ExecutorService pool = Executors.newFixedThreadPool(3);
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(
            (int) Math.min(Runtime.getRuntime().maxMemory() / 6, 48L << 20)) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    interface Done {
        void on(Bitmap b);
    }

    static void into(ImageView v, String url) {
        v.setTag(R.id.image_url, url);
        if (url == null) {
            v.setImageDrawable(null);
            return;
        }
        Bitmap hit = memory.get(url);
        if (hit != null) {
            v.setImageBitmap(hit);
            return;
        }
        v.setImageDrawable(null);
        load(v.getContext(), url, b -> {
            if (url.equals(v.getTag(R.id.image_url))) v.setImageBitmap(b);
        });
    }

    static void load(Context c, String url, Done done) {
        Bitmap hit = memory.get(url);
        if (hit != null) {
            done.on(hit);
            return;
        }
        Context app = c.getApplicationContext();
        pool.execute(() -> {
            try {
                File f = new File(app.getCacheDir(), "img-" + Integer.toHexString(url.hashCode()) + ".jpg");
                if (!f.exists()) {
                    byte[] data = Xbox.getBytes(url);
                    File tmp = new File(f.getPath() + ".tmp");
                    try (FileOutputStream out = new FileOutputStream(tmp)) {
                        out.write(data);
                    }
                    tmp.renameTo(f);
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
                if (b == null) return;
                memory.put(url, b);
                main.post(() -> done.on(b));
            } catch (Exception ignored) {
            }
        });
    }
}
