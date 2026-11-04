package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * tvOS-style Settings: the big Settings icon on the left, a sectioned list on the right. Each row opens the
 * matching Google TV Settings screen (those screens are Google's own and can't be restyled), and the last row
 * opens all of Google TV Settings.
 */
public class TvSettingsActivity extends Activity {

    private float density;
    private ViewGroup wallpaperRow, screenSaverRow;

    @Override
    protected void onResume() {
        super.onResume();
        // The wallpaper may have just been changed (here, or from the iPhone or Mac): show its current name.
        if (wallpaperRow != null && wallpaperRow.getChildAt(2) instanceof TextView) {
            ((TextView) wallpaperRow.getChildAt(2)).setText(wallpaperName());
        }
        if (screenSaverRow != null && screenSaverRow.getChildAt(2) instanceof TextView) {
            ((TextView) screenSaverRow.getChildAt(2)).setText(ScreenSaver.typeName(this));
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.25f, 0.5f);
        glow.setGradientRadius(900 * density);
        glow.setColors(new int[]{0xFF2A2A2E, 0xFF0B0B0C, 0xFF000000});
        root.setBackground(glow);

        // Left: the Settings icon, as tvOS shows the app's artwork beside its list.
        FrameLayout left = new FrameLayout(this);
        ImageView art = new ImageView(this);
        this.art = art;
        art.setImageResource(R.drawable.banner_settings);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 22 * density);
            }
        });
        art.setClipToOutline(true);
        art.setElevation(20 * density);
        // Artwork sized from the real screen: three quarters of the left pane, 16:9.
        int artW = (int) (getResources().getDisplayMetrics().widthPixels * 0.44f * 0.75f);
        left.addView(art, new FrameLayout.LayoutParams(artW, artW * 9 / 16, Gravity.CENTER));
        root.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.44f));

        // Right: title and the list.
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(40), dp(40), 0);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        // Room around the rows so a focused row, which grows slightly, is never cut by the list's edges.
        list.setPadding(dp(24), dp(8), dp(24), dp(56));
        list.setClipToPadding(false);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.56f));

        TextView title = new TextView(this);
        title.setText(screenTitle());
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(34);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(16), 0, 0, dp(18));
        list.addView(title);

        View first = fill(list);
        setContentView(root);
        first.requestFocus();
    }

    /** The left-hand artwork, for screens that show a preview there. */
    protected ImageView art;

    protected String screenTitle() {
        return getString(R.string.settings);
    }

    /** Adds this screen's rows; returns the one to focus first. */
    protected View fill(LinearLayout list) {
        section(list, "General");
        View first = row(list, R.drawable.ic_wifi, "Network", MainActivity.networkName(this),
                new Intent(Settings.ACTION_WIFI_SETTINGS));
        row(list, R.drawable.ic_person, "Users and Accounts", Profiles.active(this),
                new Intent(Settings.ACTION_SYNC_SETTINGS));
        wallpaperRow = (ViewGroup) row(list, R.drawable.ic_display, getString(R.string.wallpaper),
                wallpaperName(),
                new Intent(this, WallpaperSettingsActivity.class));
        screenSaverRow = (ViewGroup) row(list, R.drawable.ic_screensaver, getString(R.string.screen_saver),
                ScreenSaver.typeName(this), new Intent(this, ScreenSaverActivity.class));

        section(list, "Video and Audio");
        row(list, R.drawable.ic_display, "Display and Sound", displayMode(), new Intent(Settings.ACTION_SOUND_SETTINGS));
        row(list, R.drawable.ic_input, getString(R.string.inputs), null, new Intent(this, InputActivity.class));
        row(list, R.drawable.ic_accessibility, "Accessibility", null, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        row(list, R.drawable.ic_keyboard, "Subtitles", null, new Intent(Settings.ACTION_CAPTIONING_SETTINGS));

        section(list, "Remotes and Devices");
        row(list, R.drawable.ic_gamepad, "Pair Accessory", "Controllers, keyboards",
                new Intent("com.google.android.intent.action.CONNECT_INPUT"));
        row(list, R.drawable.ic_toggles, "Remote Buttons", "Power and volume",
                new Intent("com.google.android.tv.IR_WIZARD"));
        AppleHome home = AppleHome.current;
        row(list, R.drawable.ic_home, getString(R.string.apple_home),
                getString(home != null && home.isPaired() ? R.string.apple_home_sub_paired : R.string.apple_home_sub_unpaired),
                new Intent(this, AppleHomeActivity.class));

        section(list, "Apps");
        row(list, R.drawable.ic_apps, "Apps", null, new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        row(list, R.drawable.ic_lock, "Privacy", null, new Intent(Settings.ACTION_PRIVACY_SETTINGS));

        section(list, "System");
        row(list, R.drawable.ic_language, "Language", null, new Intent(Settings.ACTION_LOCALE_SETTINGS));
        row(list, R.drawable.ic_timer, "Date and Time", null, new Intent(Settings.ACTION_DATE_SETTINGS));
        row(list, R.drawable.ic_keyboard, "Keyboard", null, new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
        row(list, R.drawable.ic_storage, "Storage", null, new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
        row(list, R.drawable.ic_update, "Software Update",
                Updater.prefs(this).getBoolean("needsConfirm", false) ? "Update ready" : Brand.OS + " " + versionName(),
                new Intent(this, UpdateActivity.class));
        row(list, R.drawable.ic_update, "Device Software", null, new Intent("android.settings.SYSTEM_UPDATE_SETTINGS"));
        row(list, R.drawable.ic_info, "About", Brand.MODEL + " · " + Brand.OS + " " + Brand.OS_VERSION,
                new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));

        section(list, "");
        row(list, R.drawable.ic_settings, "All Google TV Settings", null,
                new Intent().setComponent(new ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings")));
        return first;
    }

    /** "Bing Daily Wallpaper", "Apple TV Aerial", "Catalina (Dynamic)"… for the Wallpaper row. */
    private String wallpaperName() {
        String k = Wallpapers.kind(this);
        if (k.isEmpty()) return getString(R.string.wallpaper_glow);
        if (k.equals("bing")) return getString(R.string.wallpaper_bing);
        if (k.startsWith("aerial:")) return "Apple TV Aerial";
        if (k.startsWith("upload:")) return k.substring(7);
        if (k.startsWith("macos:")) {
            String n = k.substring(6);
            return MacOS.isDynamic(this, n) ? n + " (" + getString(R.string.wallpaper_dynamic) + ")" : n;
        }
        if (k.startsWith("dynamic:")) return k.substring(8) + " (" + getString(R.string.wallpaper_dynamic) + ")";
        return getString(R.string.wallpaper_device);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** "1080p · 60 Hz" for the current HDMI output. */
    private String displayMode() {
        android.view.Display.Mode m = getDisplay().getMode();
        return m.getPhysicalHeight() + "p · " + Math.round(m.getRefreshRate()) + " Hz";
    }

    protected void section(LinearLayout list, String name) {
        TextView t = new TextView(this);
        t.setText(name.toUpperCase());
        t.setTextColor(0x80FFFFFF);
        t.setTextSize(13);
        t.setLetterSpacing(0.06f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(16), dp(18), 0, dp(8));
        list.addView(t);
    }

    /** A tvOS list row: icon, title, an optional value on the right and a chevron. White when focused. */
    protected View row(LinearLayout list, int icon, String title, String value, Intent target) {
        return row(list, icon, title, value, () -> open(target));
    }

    /** The row's value text (to change it in place), or null. */
    protected static TextView valueOf(View row) {
        ViewGroup g = (ViewGroup) row;
        return g.getChildCount() > 3 && g.getChildAt(2) instanceof TextView ? (TextView) g.getChildAt(2) : null;
    }

    protected View row(LinearLayout list, int icon, String title, String value, Runnable action) {
        ColorStateList text = getColorStateList(R.drawable.glass_text);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackgroundResource(R.drawable.settings_row_bg);
        r.setFocusable(true);
        r.setClickable(true);
        r.setPadding(dp(16), 0, dp(16), 0);
        r.setOnClickListener(v -> action.run());
        r.setOnFocusChangeListener((v, f) -> v.animate().scaleX(f ? 1.03f : 1f).scaleY(f ? 1.03f : 1f)
                .setDuration(150).start());

        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setImageTintList(text);
        iv.setDuplicateParentStateEnabled(true);
        r.addView(iv, new LinearLayout.LayoutParams(dp(22), dp(22)));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(text);
        t.setTextSize(18);
        t.setDuplicateParentStateEnabled(true);
        t.setPadding(dp(16), 0, 0, 0);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (value != null) {
            TextView val = new TextView(this);
            val.setText(value);
            val.setTextColor(getColorStateList(R.drawable.glass_subtext));
            val.setTextSize(16);
            val.setDuplicateParentStateEnabled(true);
            val.setPadding(0, 0, dp(10), 0);
            r.addView(val);
        }
        TextView chev = new TextView(this);
        chev.setText("›");
        chev.setTextColor(getColorStateList(R.drawable.glass_subtext));
        chev.setTextSize(24);
        chev.setDuplicateParentStateEnabled(true);
        r.addView(chev);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lp.bottomMargin = dp(4);
        list.addView(r, lp);
        return r;
    }

    protected void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    protected int dp(int v) {
        return (int) (v * density);
    }
}
