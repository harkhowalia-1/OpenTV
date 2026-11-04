package com.gathin.tvhome;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * Settings › Wallpaper, laid out like tvOS's Settings pages: the wallpaper large on the left, a list on the right.
 * Colour Glow and Bing Daily apply right away; each collection (Apple TV Aerials, macOS Wallpapers, Your Pictures,
 * Photos on This TV) opens its own page. Moving over a collection previews it on the left.
 */
public class WallpaperSettingsActivity extends TvSettingsActivity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private View glow, bing, aerials, macos, yours, photos;
    private boolean busy;

    @Override
    protected String screenTitle() {
        return getString(R.string.wallpaper);
    }

    @Override
    protected View fill(LinearLayout list) {
        section(list, getString(R.string.wallpaper_now));
        glow = row(list, R.drawable.ic_display, getString(R.string.wallpaper_glow), "", () -> apply(""));
        bing = row(list, R.drawable.ic_language, getString(R.string.wallpaper_bing), "", () -> apply("bing"));

        section(list, getString(R.string.wallpaper_collections));
        aerials = row(list, R.drawable.ic_screensaver, getString(R.string.wallpaper_aerials), "",
                () -> open(WallpaperActivity.AERIALS));
        if (MacOS.supported()) {
            macos = row(list, R.drawable.ic_display, getString(R.string.wallpaper_macos), "", () -> open(WallpaperActivity.MACOS));
        }
        yours = row(list, R.drawable.ic_person, getString(R.string.wallpaper_yours), "", () -> open(WallpaperActivity.YOURS));
        photos = row(list, R.drawable.ic_storage, getString(R.string.wallpaper_photos), "", () -> open(WallpaperActivity.PHOTOS_CAT));

        TextView hint = new TextView(this);
        hint.setText(R.string.wallpaper_send);
        hint.setTextColor(0x80FFFFFF);
        hint.setTextSize(14);
        hint.setPadding(dp(16), dp(18), dp(16), 0);
        list.addView(hint);

        // Moving over a row shows what it is on the left, as tvOS previews a choice.
        previewOnFocus(glow, () -> showGlow());
        previewOnFocus(bing, () -> showFile(new File(Wallpapers.dir(this, "bing"), "bing-" + Wallpapers.today() + ".jpg")));
        previewOnFocus(aerials, () -> showFirst(Wallpapers.dir(this, "aerial-thumbs")));
        if (macos != null) previewOnFocus(macos, () -> showFirst(Wallpapers.dir(this, "macos-thumbs")));
        previewOnFocus(yours, () -> {
            List<File> u = Wallpapers.uploads(this);
            if (!u.isEmpty()) showFile(u.get(0));
            else showCurrent();
        });
        previewOnFocus(photos, this::showCurrent);
        list.post(this::refresh);
        return currentRow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (glow != null) refresh();
    }

    private View currentRow() {
        String k = Wallpapers.kind(this);
        if (k.isEmpty()) return glow;
        if (k.equals("bing")) return bing;
        if (k.startsWith("aerial:")) return aerials;
        if (k.startsWith("macos:") || k.startsWith("dynamic:")) return macos != null ? macos : glow;
        return yours;
    }

    /** Ticks and counts, and the wallpaper itself on the left. */
    private void refresh() {
        String k = Wallpapers.kind(this);
        set(glow, k.isEmpty() ? "✓" : "");
        set(bing, k.equals("bing") ? "✓" : getString(R.string.wallpaper_bing_sub));
        set(yours, count(Wallpapers.uploads(this).size(), k.startsWith("upload:")));
        set(photos, "");
        showCurrent();
        new Thread(() -> {
            int a = Aerials.list(this).size();
            int m = MacOS.supported() ? MacOS.list(this).size() : 0;
            main.post(() -> {
                set(aerials, count(a, k.startsWith("aerial:")));
                if (macos != null) set(macos, count(m, k.startsWith("macos:") || k.startsWith("dynamic:")));
            });
        }, "otv-wallpaper-counts").start();
    }

    private String count(int n, boolean inUse) {
        return (inUse ? "✓  " : "") + (n > 0 ? String.valueOf(n) : "");
    }

    private void set(View row, String value) {
        TextView v = row == null ? null : valueOf(row);
        if (v != null) v.setText(value);
    }

    private void open(String category) {
        startActivity(new Intent(this, WallpaperActivity.class).putExtra(WallpaperActivity.CATEGORY, category));
    }

    /** Colour Glow or Bing: applied right here, with progress in the row while Bing's picture downloads. */
    private void apply(String kind) {
        if (busy) return;
        busy = true;
        View row = kind.isEmpty() ? glow : bing;
        set(row, getString(R.string.wallpaper_setting));
        new Thread(() -> {
            Wallpapers.progress.set((f, dl) -> main.post(() -> {
                if (dl && f >= 0) set(row, Math.round(f * 100) + "%");
            }));
            try {
                Wallpapers.apply(this, kind);
            } catch (Exception e) {
                main.post(() -> set(row, getString(R.string.wallpaper_failed)));
            } finally {
                Wallpapers.progress.remove();
                main.post(() -> {
                    busy = false;
                    refresh();
                });
            }
        }, "otv-set-wallpaper").start();
    }

    // ---- the picture on the left ----------------------------------------------------------------------

    private void previewOnFocus(View row, Runnable show) {
        if (row == null) return;
        View.OnFocusChangeListener scale = row.getOnFocusChangeListener();
        row.setOnFocusChangeListener((v, f) -> {
            if (scale != null) scale.onFocusChange(v, f);
            if (f) show.run();
        });
    }

    private void showCurrent() {
        if (Wallpapers.kind(this).isEmpty() || !Wallpapers.current(this).exists()) showGlow();
        else showFile(Wallpapers.current(this));
    }

    private void showGlow() {
        if (art == null) return;
        art.setImageDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF7A0A14, 0xFF14060A, 0xFF000000}));
    }

    private void showFirst(File dir) {
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".jpg"));
        if (fs != null && fs.length > 0) showFile(fs[(int) (System.currentTimeMillis() / 4000 % fs.length)]);
        else showCurrent();
    }

    private void showFile(File f) {
        if (art == null || f == null || !f.exists()) {
            if (f != null && !f.exists()) showCurrent();
            return;
        }
        new Thread(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= 720) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            if (b != null) main.post(() -> art.setImageBitmap(b));
        }).start();
    }
}
