package com.gathin.tvhome;

import android.Manifest;
import android.app.WallpaperManager;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ImageView;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings › Wallpaper: the colour glow, the device's own wallpapers, Bing's picture of the day, Apple TV's aerials
 * (playing behind the apps), pictures sent from the iPhone or Mac (including macOS dynamic wallpapers that follow
 * the time of day), and the photos stored on this TV.
 */
public class WallpaperActivity extends ChoiceScreen {

    /** Kept for older code paths: the preference and still live in {@link Wallpapers}. */
    static final String PREF = Wallpapers.PREF;

    static File file(android.content.Context c) {
        return Wallpapers.current(c);
    }

    private static final int PHOTOS = 7;
    private final ExecutorService previews = Executors.newFixedThreadPool(2);
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private String current;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        build(getString(R.string.wallpaper), getString(R.string.wallpaper_sub));
        current = Wallpapers.kind(this);

        // Default
        GradientDrawable glow = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF7A0A14, 0xFF14060A, 0xFF000000});
        View first = card(glow, 0xFF000000, getString(R.string.wallpaper_glow), current.isEmpty(),
                () -> pick(() -> Wallpapers.glow(this)));
        List<Drawable> device = deviceWallpapers();
        for (int i = 0; i < device.size(); i++) {
            Drawable d = device.get(i);
            String kind = "device#" + i;
            card(d, 0xFF1C1C21, getString(R.string.wallpaper_device) + (device.size() > 1 ? " " + (i + 1) : ""),
                    current.equals(kind), () -> pick(() -> {
                        Wallpapers.saveDrawable(this, d, Wallpapers.current(this));
                        Wallpapers.set(this, kind);
                    }));
        }

        // Bing
        View bing = card(null, 0xFF0F3A63, getString(R.string.wallpaper_bing), current.equals("bing"), () -> pick(() -> {
            Wallpapers.copy(Wallpapers.fetchBing(this), Wallpapers.current(this));
            Wallpapers.set(this, "bing");
            Wallpapers.prefs(this).edit().putString("bingDay", Wallpapers.today()).apply();
        }));
        previews.execute(() -> {
            try {
                show(bing, Wallpapers.fetchBing(this));
            } catch (Exception ignored) {
            }
        });

        // Your pictures: sent from the iPhone or Mac, and macOS dynamic wallpapers.
        List<File> uploads = Wallpapers.uploads(this);
        List<String> dynamics = Wallpapers.dynamics(this);
        if (!uploads.isEmpty() || !dynamics.isEmpty()) {
            section(getString(R.string.wallpaper_yours));
            for (String name : dynamics) {
                String kind = "dynamic:" + name;
                View v = card(null, 0xFF1C1C21, name + " · " + getString(R.string.wallpaper_dynamic), current.equals(kind),
                        () -> pick(() -> {
                            Wallpapers.set(this, kind);
                            Wallpapers.prefs(this).edit().remove("dynamicShown").apply();
                            Wallpapers.refreshDynamic(this);
                        }));
                previews.execute(() -> show(v, Wallpapers.dynamicPreview(this, name)));
            }
            for (File f : uploads) {
                String name = f.getName().replaceFirst("\\.jpg$", "");
                String kind = "upload:" + name;
                View v = card(null, 0xFF1C1C21, name, current.equals(kind), () -> pick(() -> {
                    Wallpapers.copy(f, Wallpapers.current(this));
                    Wallpapers.set(this, kind);
                }));
                previews.execute(() -> show(v, f));
            }
        }

        // Photos stored on this TV.
        section(getString(R.string.wallpaper_photos));
        if (canReadPhotos()) {
            addPhotos();
        } else {
            card(null, 0xFF1C1C21, getString(R.string.wallpaper_photos_allow), false, () -> requestPermissions(
                    new String[]{Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES
                            : Manifest.permission.READ_EXTERNAL_STORAGE}, PHOTOS));
        }

        // Apple TV aerials, from Apple.
        section(getString(R.string.wallpaper_aerials));
        work.execute(() -> {
            List<Aerials.Aerial> list = Aerials.list(this);
            main.post(() -> {
                for (Aerials.Aerial a : list) {
                    String kind = "aerial:" + a.id;
                    View v = card(null, 0xFF101826, a.name, current.equals(kind), () -> pick(() -> {
                        Aerials.saveStill(this, a, Wallpapers.current(this));
                        Wallpapers.prefs(this).edit().putString("aerialUrl", Aerials.videoFor(this, a)).apply();
                        Wallpapers.set(this, kind);
                    }));
                    previews.execute(() -> show(v, Aerials.thumbnail(this, a)));
                }
            });
        });

        note(getString(R.string.wallpaper_send));
        first.requestFocus();
    }

    @Override
    protected void onDestroy() {
        previews.shutdownNow();
        super.onDestroy();
    }

    /** Puts a preview picture into a card when it's ready. */
    private void show(View card, File f) {
        if (f == null || !f.exists()) return;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 4;
        android.graphics.Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) return;
        main.post(() -> {
            ImageView iv = picture(card);
            if (iv != null) iv.setImageBitmap(b);
        });
    }

    private interface Job {
        void run() throws Exception;
    }

    /** Applies a choice off the main thread, then returns to Home. */
    private void pick(Job job) {
        note(getString(R.string.wallpaper_setting));
        work.execute(() -> {
            try {
                job.run();
                main.post(this::finish);
            } catch (Exception e) {
                main.post(() -> note(getString(R.string.wallpaper_failed)));
            }
        });
    }

    // ---- the device's own wallpapers ------------------------------------------------------------------

    private List<Drawable> deviceWallpapers() {
        List<Drawable> out = new ArrayList<>();
        WallpaperManager wm = WallpaperManager.getInstance(this);
        for (int which : new int[]{WallpaperManager.FLAG_SYSTEM, WallpaperManager.FLAG_LOCK}) {
            try {
                Drawable d = wm.getBuiltInDrawable(which);
                if (d != null && d.getIntrinsicWidth() > 64 && !contains(out, d)) out.add(d);
            } catch (RuntimeException ignored) {
            }
        }
        try {
            Drawable d = wm.getDrawable();
            if (d != null && d.getIntrinsicWidth() > 64 && !contains(out, d)) out.add(d);
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    private static boolean contains(List<Drawable> list, Drawable d) {
        if (!(d instanceof BitmapDrawable)) return false;
        for (Drawable e : list) {
            if (e instanceof BitmapDrawable && ((BitmapDrawable) e).getBitmap().sameAs(((BitmapDrawable) d).getBitmap())) return true;
        }
        return false;
    }

    // ---- photos on this TV ------------------------------------------------------------------------------

    private boolean canReadPhotos() {
        String p = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == PHOTOS && canReadPhotos()) recreate();
    }

    /** The 40 newest pictures in the TV's storage (Downloads, Pictures, USB drives). */
    private void addPhotos() {
        String[] cols = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME};
        int added = 0;
        try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, null, null,
                MediaStore.Images.Media.DATE_MODIFIED + " DESC")) {
            while (c != null && c.moveToNext() && added < 40) {
                Uri uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, String.valueOf(c.getLong(0)));
                String name = c.getString(1) == null ? "Photo" : c.getString(1).replaceFirst("\\.[^.]+$", "");
                View v = card(null, 0xFF1C1C21, name, false, () -> pick(() -> {
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        Wallpapers.saveUpload(this, name, out.toByteArray());
                    }
                }));
                previews.execute(() -> {
                    try {
                        android.graphics.Bitmap t;
                        if (Build.VERSION.SDK_INT >= 29) {
                            t = getContentResolver().loadThumbnail(uri, new android.util.Size(384, 216), null);
                        } else {
                            BitmapFactory.Options o = new BitmapFactory.Options();
                            o.inSampleSize = 8;
                            try (InputStream in = getContentResolver().openInputStream(uri)) {
                                t = BitmapFactory.decodeStream(in, null, o);
                            }
                        }
                        main.post(() -> {
                            ImageView iv = picture(v);
                            if (iv != null) iv.setImageBitmap(t);
                        });
                    } catch (Exception ignored) {
                    }
                });
                added++;
            }
        } catch (Exception ignored) {
        }
        if (added == 0) card(null, 0xFF1C1C21, getString(R.string.wallpaper_photos_none), false, () -> { });
    }
}
