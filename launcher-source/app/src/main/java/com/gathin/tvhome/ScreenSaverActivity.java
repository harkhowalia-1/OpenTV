package com.gathin.tvhome;

import android.content.Intent;
import android.graphics.BitmapFactory;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/**
 * Settings › Screen Saver, as on tvOS: the type, Aerial themes, Start After, a clock over it, whether it shows
 * during music, and a preview. Selecting a row with a value moves to the next choice, as tvOS's pickers do.
 */
public class ScreenSaverActivity extends TvSettingsActivity {

    private final View[] types = new View[3];
    private View themeRow;

    @Override
    protected String screenTitle() {
        return getString(R.string.screen_saver);
    }

    @Override
    protected View fill(LinearLayout list) {
        section(list, getString(R.string.ss_type));
        String[] ids = {ScreenSaver.AERIALS, ScreenSaver.WALLPAPERS, ScreenSaver.CLOCK};
        int[] names = {R.string.ss_aerials, R.string.ss_wallpapers, R.string.ss_clock};
        int[] icons = {R.drawable.ic_screensaver, R.drawable.ic_display, R.drawable.ic_timer};
        for (int i = 0; i < 3; i++) {
            String id = ids[i];
            types[i] = row(list, icons[i], getString(names[i]), "", () -> {
                ScreenSaver.prefs(this).edit().putString("type", id).apply();
                refresh();
            });
        }

        section(list, getString(R.string.ss_options));
        themeRow = row(list, R.drawable.ic_screensaver, getString(R.string.ss_themes), "", () -> {
            String[] all = new String[Aerials.THEMES.length + 1];
            all[0] = ScreenSaver.ALL;
            System.arraycopy(Aerials.THEMES, 0, all, 1, Aerials.THEMES.length);
            String now = ScreenSaver.theme(this);
            int i = 0;
            while (i < all.length && !all[i].equals(now)) i++;
            ScreenSaver.prefs(this).edit().putString("theme", all[(i + 1) % all.length]).apply();
            refresh();
        });
        View clock = row(list, R.drawable.ic_timer, getString(R.string.ss_show_clock), "", () -> {
            ScreenSaver.prefs(this).edit().putBoolean("clock", !ScreenSaver.clock(this)).apply();
            refresh();
        });
        clock.setTag("clock");
        View music = row(list, R.drawable.ic_screensaver, getString(R.string.ss_music), "", () -> {
            ScreenSaver.prefs(this).edit().putBoolean("music", !ScreenSaver.duringMusic(this)).apply();
            refresh();
        });
        music.setTag("music");
        View after = row(list, R.drawable.ic_timer, getString(R.string.ss_start_after), "", () -> {
            if (!ScreenSaver.canSetStartAfter(this)) {
                open(new Intent(TV_SCREENSAVER_SETTINGS));
                return;
            }
            int now = ScreenSaver.startAfter(this), i = 0;
            while (i < ScreenSaver.START_AFTER.length && ScreenSaver.START_AFTER[i] != now) i++;
            ScreenSaver.setStartAfter(this, ScreenSaver.START_AFTER[(i + 1) % ScreenSaver.START_AFTER.length]);
            refresh();
        });
        after.setTag("after");
        row(list, R.drawable.ic_screensaver, getString(R.string.ss_preview), null, this::preview);
        if (!ScreenSaver.isOurs(this)) {
            section(list, "");
            row(list, R.drawable.ic_settings, getString(R.string.ss_make_default), null,
                    () -> open(new Intent(TV_SCREENSAVER_SETTINGS)));
        }
        list.post(this::refresh);
        return types[0];
    }

    static final String TV_SCREENSAVER_SETTINGS = "android.settings.DREAM_SETTINGS";

    @Override
    protected void onResume() {
        super.onResume();
        if (types[0] != null) refresh();
    }

    private void refresh() {
        String type = ScreenSaver.type(this);
        String[] ids = {ScreenSaver.AERIALS, ScreenSaver.WALLPAPERS, ScreenSaver.CLOCK};
        for (int i = 0; i < 3; i++) set(types[i], ids[i].equals(type) ? "✓" : "");
        set(themeRow, ScreenSaver.theme(this).equals(ScreenSaver.ALL) ? getString(R.string.ss_all) : ScreenSaver.theme(this));
        themeRow.setVisibility(type.equals(ScreenSaver.AERIALS) ? View.VISIBLE : View.GONE);
        View root = types[0].getRootView();
        View clock = root.findViewWithTag("clock");
        set(clock, getString(ScreenSaver.clock(this) ? R.string.ss_on : R.string.ss_off));
        clock.setVisibility(type.equals(ScreenSaver.CLOCK) ? View.GONE : View.VISIBLE);
        set(root.findViewWithTag("music"), getString(ScreenSaver.duringMusic(this) ? R.string.ss_on : R.string.ss_off));
        int m = ScreenSaver.startAfter(this);
        set(root.findViewWithTag("after"), m == 0 ? getString(R.string.ss_never) : m < 0 ? "" : getResources().getQuantityString(R.plurals.ss_minutes, m, m));
        showArt(type);
    }

    private void set(View row, String value) {
        TextView v = row == null ? null : valueOf(row);
        if (v != null) v.setText(value);
    }

    /** A picture of the chosen type beside the list. */
    private void showArt(String type) {
        if (art == null) return;
        File f = null;
        if (type.equals(ScreenSaver.AERIALS)) {
            File[] thumbs = Wallpapers.dir(this, "aerial-thumbs").listFiles();
            if (thumbs != null && thumbs.length > 0) f = thumbs[(int) (System.currentTimeMillis() / 60000 % thumbs.length)];
        } else if (type.equals(ScreenSaver.WALLPAPERS) && Wallpapers.current(this).exists()) {
            f = Wallpapers.current(this);
        }
        if (f != null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = f.equals(Wallpapers.current(this)) ? 4 : 1;
            art.setImageBitmap(BitmapFactory.decodeFile(f.getPath(), o));
        } else {
            art.setImageResource(R.drawable.banner_settings);
        }
    }

    /** Starts the screensaver now, as tvOS's Preview does. */
    private void preview() {
        Intent dream = new Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(dream);
        } catch (Exception e) {
            open(new Intent(TV_SCREENSAVER_SETTINGS));
        }
    }
}
