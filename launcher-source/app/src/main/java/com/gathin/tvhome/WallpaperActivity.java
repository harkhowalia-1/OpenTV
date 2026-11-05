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
import android.widget.LinearLayout;

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
    private final ExecutorService previews = Executors.newFixedThreadPool(4);
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final ExecutorService macPreviews = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private String current;

    static final String CATEGORY = "category";
    static final String AERIALS = "aerials", MACOS = "macos", YOURS = "yours", PHOTOS_CAT = "photos", LIVE = "live";
    private static final int PER_ROW = 5;

    /**
     * One collection, as its own page (Settings › Wallpaper lists them, tvOS style): Apple TV aerials by theme,
     * macOS wallpapers, your pictures, or the photos on this TV, in rows of cards.
     */
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Wallpapers.migrate(this);
        current = Wallpapers.kind(this);
        String cat = getIntent().getStringExtra(CATEGORY);
        if (cat == null) cat = AERIALS;
        switch (cat) {
            case MACOS: buildMacOS(); break;
            case YOURS: buildYours(); break;
            case PHOTOS_CAT: buildPhotos(); break;
            case LIVE: buildLive(); break;
            default: buildAerials(); break;
        }
    }

    /** Puts focus on the first card once there is one. */
    private boolean focused;

    private void focusFirst(View v) {
        if (focused || v == null) return;
        focused = true;
        v.requestFocus();
    }

    private void buildAerials() {
        build(getString(R.string.wallpaper_aerials), getString(R.string.wallpaper_aerials_sub));
        work.execute(() -> {
            List<Aerials.Aerial> list = Aerials.list(this);
            main.post(() -> {
                // By theme, as tvOS groups them.
                for (String theme : Aerials.THEMES) {
                    LinearLayout row = null;
                    for (Aerials.Aerial a : list) {
                        if (!theme.equals(a.category)) continue;
                        if (row == null) row = section(theme);
                        String kind = "aerial:" + a.id;
                        View v = cardIn(row, null, 0xFF101826, a.name, current.equals(kind), () -> pick(() -> Wallpapers.apply(this, kind)));
                        if (current.equals(kind)) focusFirst(v);
                        previews.execute(() -> show(v, Aerials.thumbnail(this, a)));
                    }
                }
                if (!focused) {
                    View any = findViewWithCardTag();
                    focusFirst(any);
                }
            });
        });
        note(getString(R.string.wallpaper_aerials_note));
    }

    /** Live wallpapers: drawn on the TV, nothing to download. */
    private void buildLive() {
        build(getString(R.string.wallpaper_live), getString(R.string.wallpaper_live_sub));
        LinearLayout row = section(null);
        for (String id : Live.IDS) {
            String kind = "live:" + id;
            View v = cardIn(row, null, 0xFF0B1626, Live.name(this, id), current.equals(kind), () -> pick(() -> Wallpapers.apply(this, kind)));
            if (current.equals(kind)) focusFirst(v);
            previews.execute(() -> show(v, Live.thumbnail(this, id)));
        }
        if (!focused) focusFirst(findViewWithCardTag());
    }

    private void buildMacOS() {
        build(getString(R.string.wallpaper_macos), getString(R.string.wallpaper_macos_sub));
        if (!MacOS.supported()) {
            note(getString(R.string.wallpaper_failed));
            return;
        }
        work.execute(() -> {
            List<MacOS.Item> list = MacOS.list(this);
            main.post(() -> {
                LinearLayout row = null;
                for (int i = 0; i < list.size(); i++) {
                    if (i % PER_ROW == 0) row = section(null);
                    MacOS.Item it = list.get(i);
                    String kind = "macos:" + it.name;
                    String label = MacOS.isDynamic(this, it.name)
                            ? it.name + " · " + getString(R.string.wallpaper_dynamic) : it.name;
                    View v = cardIn(row, null, 0xFF1C1C21, label, current.equals(kind), () -> pick(() -> MacOS.choose(this, it)));
                    if (i == 0 || current.equals(kind)) {
                        if (current.equals(kind)) focused = false;
                        focusFirst(v);
                    }
                    macCards.put(v, i);
                    macLoads.add(() -> {
                        File f = MacOS.thumbnail(this, it);
                        show(v, f);
                        if (MacOS.isDynamic(this, it.name) && !label.endsWith(getString(R.string.wallpaper_dynamic))) {
                            main.post(() -> relabel(v, it.name + " · " + getString(R.string.wallpaper_dynamic)));
                        }
                    });
                }
                loadMacPreviews(list.size());   // every preview, top to bottom (each is kept once made)
            });
        });
        note(getString(R.string.wallpaper_macos_note));
    }

    private void buildYours() {
        build(getString(R.string.wallpaper_yours), getString(R.string.wallpaper_yours_sub));
        List<File> uploads = Wallpapers.uploads(this);
        LinearLayout row = null;
        for (int i = 0; i < uploads.size(); i++) {
            if (i % PER_ROW == 0) row = section(null);
            File f = uploads.get(i);
            String name = f.getName().replaceFirst("\\.jpg$", "");
            String kind = "upload:" + name;
            View v = cardIn(row, null, 0xFF1C1C21, name, current.equals(kind), () -> pick(() -> Wallpapers.apply(this, kind)));
            focusFirst(v);
            previews.execute(() -> show(v, f));
        }
        note(getString(R.string.wallpaper_send));
    }

    private void buildPhotos() {
        build(getString(R.string.wallpaper_photos), getString(R.string.wallpaper_photos_sub));
        if (canReadPhotos()) {
            addPhotos();
        } else {
            focusFirst(card(null, 0xFF1C1C21, getString(R.string.wallpaper_photos_allow), false, () -> requestPermissions(
                    new String[]{Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES
                            : Manifest.permission.READ_EXTERNAL_STORAGE}, PHOTOS)));
        }
    }

    private View findViewWithCardTag() {
        View root = getWindow().getDecorView();
        View pic = root.findViewWithTag("pic");
        return pic == null ? null : (View) pic.getParent().getParent();
    }

    private final java.util.Map<View, Integer> macCards = new java.util.HashMap<>();
    private final List<Runnable> macLoads = new ArrayList<>();
    private int macLoaded;

    /** Starts the previews from the start of the row up to a few cards past this one. */
    private void loadMacPreviews(int around) {
        int upTo = Math.min(macLoads.size(), around + 7);
        while (macLoaded < upTo) macPreviews.execute(macLoads.get(macLoaded++));
    }

    private void relabel(View card, String text) {
        if (card instanceof android.view.ViewGroup && ((android.view.ViewGroup) card).getChildCount() > 1
                && ((android.view.ViewGroup) card).getChildAt(1) instanceof android.widget.TextView) {
            ((android.widget.TextView) ((android.view.ViewGroup) card).getChildAt(1)).setText(text);
        }
    }

    @Override
    protected void onDestroy() {
        macPreviews.shutdownNow();
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
    private boolean busy;

    /**
     * Applies a choice off the main thread, with Apple's download ring on the card and how far along it is
     * underneath ("Downloading Big Sur… 42%"), then returns to Home.
     */
    private void pick(Job job) {
        if (busy) return;
        busy = true;
        View card = lastClicked;
        String name = card instanceof android.view.ViewGroup && ((android.view.ViewGroup) card).getChildAt(1) instanceof android.widget.TextView
                ? ((android.widget.TextView) ((android.view.ViewGroup) card).getChildAt(1)).getText().toString().replaceFirst(" · .*$", "")
                : "";
        note(getString(R.string.wallpaper_setting));
        progress(card, -1);
        long[] lastNote = {0};
        Wallpapers.Progress p = (f, downloading) -> {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastNote[0] < 100 && f < 0.999f) return;   // ten updates a second is plenty
            lastNote[0] = now;
            main.post(() -> {
                progress(card, f);
                if (downloading && f >= 0) note(getString(R.string.wallpaper_downloading, name, Math.round(f * 100)));
                else note(getString(R.string.wallpaper_setting));
            });
        };
        work.execute(() -> {
            Wallpapers.progress.set(p);
            try {
                job.run();
                main.post(() -> {
                    progress(card, 1f);
                    main.postDelayed(this::finish, 250);
                });
            } catch (Exception e) {
                android.util.Log.w("OtvWallpaper", "couldn't set the wallpaper", e);
                main.post(() -> {
                    busy = false;
                    clearProgress(card);
                    note(getString(R.string.wallpaper_failed));
                });
            } finally {
                Wallpapers.progress.remove();
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
                if (added > 0 && added % PER_ROW == 0) section(null);
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
                if (added == 0) focusFirst(v);
                added++;
            }
        } catch (Exception ignored) {
        }
        if (added == 0) card(null, 0xFF1C1C21, getString(R.string.wallpaper_photos_none), false, () -> { });
    }
}
