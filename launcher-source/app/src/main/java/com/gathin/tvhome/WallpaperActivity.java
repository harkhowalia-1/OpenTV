package com.gathin.tvhome;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings › Wallpaper: the home screen's colour glow (follows the focused app), or one of the wallpapers that
 * came with the device. The chosen picture is saved at screen size so Home draws it without delay.
 */
public class WallpaperActivity extends ChoiceScreen {

    static final String PREF = "wallpaper";   // "" = colour glow, "file" = the saved picture

    static File file(Context c) {
        return new File(c.getFilesDir(), "wallpaper.jpg");
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        build(getString(R.string.wallpaper), getString(R.string.wallpaper_sub));
        String current = getSharedPreferences("home", MODE_PRIVATE).getString(PREF, "");
        GradientDrawable glow = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF7A0A14, 0xFF14060A, 0xFF000000});
        View first = card(glow, 0xFF000000, getString(R.string.wallpaper_glow), current.isEmpty(), () -> choose(null, -1));
        List<Drawable> device = deviceWallpapers();
        for (int i = 0; i < device.size(); i++) {
            Drawable d = device.get(i);
            int index = i;
            card(d, 0xFF1C1C21, device.size() == 1 ? getString(R.string.wallpaper_device)
                    : getString(R.string.wallpaper_device) + " " + (i + 1), current.equals("file#" + i), () -> choose(d, index));
        }
        if (device.isEmpty()) note(getString(R.string.wallpaper_none));
        first.requestFocus();
    }

    /** The wallpapers built into this device's software (system and lock screen, without duplicates). */
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
            Drawable d = wm.getDrawable();   // the device's current wallpaper, where Android allows reading it
            if (d != null && d.getIntrinsicWidth() > 64 && !contains(out, d)) out.add(d);
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    private static boolean contains(List<Drawable> list, Drawable d) {
        if (!(d instanceof BitmapDrawable)) return false;
        Bitmap b = ((BitmapDrawable) d).getBitmap();
        for (Drawable e : list) {
            if (e instanceof BitmapDrawable && ((BitmapDrawable) e).getBitmap().sameAs(b)) return true;
        }
        return false;
    }

    private void choose(Drawable d, int index) {
        android.content.SharedPreferences p = getSharedPreferences("home", MODE_PRIVATE);
        if (d == null) {
            p.edit().putString(PREF, "").apply();
            file(this).delete();
            finish();
            return;
        }
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(bmp);
        // Centre-crop to the screen.
        float k = Math.max(w / (float) Math.max(1, d.getIntrinsicWidth()), h / (float) Math.max(1, d.getIntrinsicHeight()));
        int dw = Math.round(d.getIntrinsicWidth() * k), dh = Math.round(d.getIntrinsicHeight() * k);
        d.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh);
        d.draw(c);
        try (FileOutputStream out = new FileOutputStream(file(this))) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
        } catch (Exception e) {
            return;
        }
        p.edit().putString(PREF, "file#" + index).apply();
        finish();
    }
}
