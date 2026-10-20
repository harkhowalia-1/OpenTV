package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * App Store-style storefront for the TV. Discover shows a featured app and rows of popular Android TV apps,
 * Installed lists what's on the TV, and Search filters both. An app's page opens it, or hands off to its
 * Google Play page only to install or update (Google has no public store catalogue, so Discover is a
 * hand-picked list and apps that aren't installed get colour tiles instead of artwork).
 */
public class StoreActivity extends Activity {

    /** A store entry: package, name, category, one-line pitch, tile colours. */
    private static final class App {
        final String pkg, name, category, pitch;
        final int c1, c2;

        App(String pkg, String name, String category, String pitch, int c1, int c2) {
            this.pkg = pkg;
            this.name = name;
            this.category = category;
            this.pitch = pitch;
            this.c1 = c1;
            this.c2 = c2;
        }
    }

    private static final App[] CATALOG = {
            new App("com.apple.atve.androidtv.appletv", "Apple TV", "Entertainment", "Apple TV+ originals, MLS and your purchases.", 0xFF2C2C2E, 0xFF000000),
            new App("com.disney.disneyplus", "Disney+", "Entertainment", "Disney, Pixar, Marvel, Star Wars and National Geographic.", 0xFF0B3A82, 0xFF041A3D),
            new App("com.netflix.ninja", "Netflix", "Entertainment", "Series, films and games.", 0xFFE50914, 0xFF5A0306),
            new App("com.google.android.youtube.tv", "YouTube", "Entertainment", "Videos, music and live streams.", 0xFFFF2D2D, 0xFF7A0000),
            new App("com.amazon.amazonvideo.livingroom", "Prime Video", "Entertainment", "Prime originals, films and live sport.", 0xFF1A98FF, 0xFF00315E),
            new App("ca.bellmedia.cravetv", "Crave", "Entertainment", "HBO, Showtime and Canadian TV.", 0xFF1E5BFF, 0xFF0A1C55),
            new App("com.wbd.stream", "Max", "Entertainment", "HBO originals, films and more.", 0xFF002BE7, 0xFF000F57),
            new App("com.cbs.ott", "Paramount+", "Entertainment", "Paramount films, CBS and live sport.", 0xFF0064FF, 0xFF001A4D),
            new App("com.crunchyroll.crunchyroid", "Crunchyroll", "Entertainment", "The biggest anime library.", 0xFFF47521, 0xFF6E2A00),
            new App("com.plexapp.android", "Plex", "Entertainment", "Your own media, plus free films and TV.", 0xFFE5A00D, 0xFF3D2A00),
            new App("com.tubitv", "Tubi", "Entertainment", "Free films and TV, no subscription.", 0xFFFF5A1F, 0xFF4A1400),
            new App("tv.pluto.android", "Pluto TV", "Entertainment", "Free live channels and on-demand.", 0xFFFFE600, 0xFF5C5200),
            new App("com.spotify.tv.android", "Spotify", "Music", "Music and podcasts.", 0xFF1ED760, 0xFF0A4A22),
            new App("com.soundcloud.android", "SoundCloud", "Music", "Discover new music and artists.", 0xFFFF5500, 0xFF5C1F00),
            new App("com.nvidia.geforcenow", "GeForce NOW", "Games", "Stream PC games you own.", 0xFF76B900, 0xFF203300),
            new App("com.valvesoftware.steamlink", "Steam Link", "Games", "Play your Steam games from your PC.", 0xFF1B2838, 0xFF0B1118),
            new App("com.limelight", "Moonlight", "Games", "Stream games from a gaming PC.", 0xFF4D4DFF, 0xFF141455),
            new App("org.videolan.vlc", "VLC", "Utilities", "Plays almost any video or audio file.", 0xFFFF8800, 0xFF5C3100),
            new App("org.xbmc.kodi", "Kodi", "Utilities", "A home theatre for your own media.", 0xFF17B2E7, 0xFF063C4F),
            new App("com.esaba.downloader", "Downloader", "Utilities", "Download files and browse the web.", 0xFFF2A900, 0xFF4F3700),
    };

    private float density;
    private PackageManager pm;
    private FrameLayout content;
    private LinearLayout tabs;
    private String tab = "discover";
    private App detail;
    private boolean started;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        pm = getPackageManager();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);

        tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER);
        tabs.setPadding(0, dp(24), 0, dp(10));
        root.addView(tabs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        buildTabs();
        show("discover");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!started) {
            started = true;
            return;
        }
        // Coming back from Google Play after an install: refresh Get/Open.
        if (detail != null) showDetail(detail);
        else if (content.getChildCount() > 0) show(tab);
    }

    // ---- tabs ------------------------------------------------------------------------------------

    private void buildTabs() {
        LinearLayout pill = new LinearLayout(this);
        pill.setBackground(new LiquidGlass(this, -1, LiquidGlass.SMOKE, false));
        pill.setPadding(dp(6), dp(6), dp(6), dp(6));
        String[][] items = {{"discover", "Discover"}, {"installed", "Installed"}, {"search", "Search"}};
        for (String[] it : items) {
            TextView t = new TextView(this);
            t.setText(it[1]);
            t.setTag(it[0]);
            t.setTextSize(17);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setTextColor(getColorStateList(R.drawable.glass_text));
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(24), 0, dp(24), 0);
            t.setBackgroundResource(R.drawable.store_tab_bg);
            t.setFocusable(true);
            t.setOnClickListener(v -> show((String) v.getTag()));
            pill.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        }
        tabs.addView(pill);
    }

    private void markTab() {
        LinearLayout pill = (LinearLayout) tabs.getChildAt(0);
        for (int i = 0; i < pill.getChildCount(); i++) {
            View t = pill.getChildAt(i);
            t.setActivated(tab.equals(t.getTag()));
        }
    }

    private void show(String which) {
        tab = which;
        detail = null;
        markTab();
        content.removeAllViews();
        switch (which) {
            case "installed": content.addView(installed()); break;
            case "search": content.addView(search()); break;
            default: content.addView(discover()); break;
        }
    }

    // ---- Discover --------------------------------------------------------------------------------

    private View discover() {
        ScrollView scroll = vscroll();
        LinearLayout col = column();
        scroll.addView(col);

        // Featured: the first catalogue app that isn't installed yet, or the first one.
        App featured = CATALOG[0];
        for (App a : CATALOG) {
            if (!installed(a.pkg)) {
                featured = a;
                break;
            }
        }
        col.addView(hero(featured));

        String[] cats = {"Entertainment", "Music", "Games", "Utilities"};
        String[] titles = {"Top Streaming Apps", "Music", "Games and Game Streaming", "Utilities"};
        for (int i = 0; i < cats.length; i++) {
            List<App> row = new ArrayList<>();
            for (App a : CATALOG) if (a.category.equals(cats[i])) row.add(a);
            col.addView(rowTitle(titles[i]));
            col.addView(shelf(row));
        }
        return scroll;
    }

    /** The big featured card: colour wash, name, pitch and a Get/Open button. */
    private View hero(App a) {
        FrameLayout card = new FrameLayout(this);
        card.setBackground(gradient(a.c1, a.c2, 28));
        card.setClipToOutline(true);
        card.setOutlineProvider(rounded(28));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(40), dp(34), dp(40), dp(30));
        TextView eyebrow = label("FEATURED", 13, 0xB3FFFFFF, true);
        eyebrow.setLetterSpacing(0.08f);
        text.addView(eyebrow);
        text.addView(label(a.name, 44, 0xFFFFFFFF, true));
        TextView pitch = label(a.pitch, 18, 0xD9FFFFFF, false);
        pitch.setPadding(0, dp(6), 0, dp(18));
        text.addView(pitch);
        View button = pillButton(installed(a.pkg) ? "Open" : "Get", true, v -> primaryAction(a));
        text.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        card.addView(text);
        Drawable art = banner(a.pkg);
        if (art != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(art);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setClipToOutline(true);
            iv.setOutlineProvider(rounded(16));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(300), dp(169), Gravity.END | Gravity.CENTER_VERTICAL);
            lp.setMarginEnd(dp(40));
            card.addView(iv, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250));
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);
        button.post(button::requestFocus);
        return card;
    }

    private View shelf(List<App> apps) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipChildren(false);
        h.setClipToPadding(false);
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        // Inner padding keeps a focused (enlarged) tile inside the row; the negative start margin keeps
        // the first tile lined up with the titles above.
        row.setPadding(dp(18), dp(16), 0, dp(12));
        for (App a : apps) row.addView(tile(a, tileWidth()));
        h.addView(row);
        // Rows run to the right edge of the screen, as in the App Store.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(-dp(18));
        lp.setMarginEnd(-dp(72));
        h.setLayoutParams(lp);
        return h;
    }

    // ---- Installed ------------------------------------------------------------------------------

    private View installed() {
        ScrollView scroll = vscroll();
        LinearLayout col = column();
        scroll.addView(col);
        col.addView(rowTitle("On This TV"));
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(5);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(0, dp(12), 0, 0);   // room for the focused tile's lift
        List<App> apps = installedApps();
        for (App a : apps) grid.addView(tile(a, gridTileWidth()));
        col.addView(grid);
        if (grid.getChildCount() > 0) grid.getChildAt(0).post(() -> grid.getChildAt(0).findViewWithTag("card").requestFocus());
        return scroll;
    }

    /** Every launchable TV app, as store entries (catalogue details when we have them). */
    private List<App> installedApps() {
        List<App> out = new ArrayList<>();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            App known = find(pkg);
            out.add(known != null ? known : new App(pkg, String.valueOf(ri.loadLabel(pm)),
                    (ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ? "System" : "App",
                    "", 0xFF3A3A3C, 0xFF1C1C1E));
        }
        Collections.sort(out, (x, y) -> x.name.compareToIgnoreCase(y.name));
        return out;
    }

    // ---- Search ---------------------------------------------------------------------------------

    private View search() {
        LinearLayout col = column();
        EditText field = new EditText(this);
        field.setHint("Apps and Games");
        field.setHintTextColor(0x66FFFFFF);
        field.setTextColor(0xFFFFFFFF);
        field.setTextSize(20);
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1C1C1E);
        bg.setCornerRadius(dp(14));
        field.setBackground(bg);
        field.setPadding(dp(20), 0, dp(20), 0);
        col.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setClipChildren(false);
        col.addView(results);

        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                fillResults(results, s.toString().trim());
            }
        };
        field.addTextChangedListener(watcher);
        field.setOnEditorActionListener((v, id, ev) -> {
            String q = field.getText().toString().trim();
            if (!q.isEmpty()) openPlay(Uri.parse("market://search?q=" + Uri.encode(q) + "&c=apps"));
            return true;
        });
        fillResults(results, "");
        field.post(field::requestFocus);
        ScrollView scroll = vscroll();
        scroll.addView(col);
        return scroll;
    }

    private void fillResults(LinearLayout results, String q) {
        results.removeAllViews();
        String needle = q.toLowerCase(Locale.ROOT);
        List<App> hits = new ArrayList<>();
        for (App a : CATALOG) if (needle.isEmpty() || a.name.toLowerCase(Locale.ROOT).contains(needle)) hits.add(a);
        for (App a : installedApps()) {
            if (find(a.pkg) == null && !needle.isEmpty() && a.name.toLowerCase(Locale.ROOT).contains(needle)) hits.add(a);
        }
        results.addView(rowTitle(needle.isEmpty() ? "Popular" : "Results"));
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(5);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(0, dp(12), 0, 0);   // room for the focused tile's lift
        for (App a : hits) grid.addView(tile(a, gridTileWidth()));
        results.addView(grid);
        if (!needle.isEmpty()) {
            View more = pillButton("Search Google Play for “" + q + "”", false,
                    v -> openPlay(Uri.parse("market://search?q=" + Uri.encode(q) + "&c=apps")));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
            lp.topMargin = dp(18);
            results.addView(more, lp);
        }
    }

    // ---- app page ---------------------------------------------------------------------------------

    private void showDetail(App a) {
        detail = a;
        content.removeAllViews();
        boolean have = installed(a.pkg);
        LinearLayout page = new LinearLayout(this);
        page.setGravity(Gravity.CENTER_VERTICAL);
        page.setPadding(dp(72), 0, dp(72), dp(40));

        View art = artwork(a, dp(420), dp(236), 26);
        page.addView(art);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(48), 0, 0, 0);
        info.addView(label(a.name, 44, 0xFFFFFFFF, true));
        TextView cat = label(a.category + (have ? " · Installed" : ""), 17, 0x99FFFFFF, false);
        cat.setPadding(0, dp(4), 0, dp(14));
        info.addView(cat);
        if (!a.pitch.isEmpty()) {
            TextView pitch = label(a.pitch, 19, 0xD9FFFFFF, false);
            pitch.setPadding(0, 0, 0, dp(24));
            info.addView(pitch);
        }
        LinearLayout buttons = new LinearLayout(this);
        View primary = pillButton(have ? "Open" : "Get", true, v -> primaryAction(a));
        buttons.addView(primary, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        if (have) {
            addSecondary(buttons, "Update", v -> openPlay(Uri.parse("market://details?id=" + a.pkg)));
            addSecondary(buttons, "App Info", v -> launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.pkg))));
            if (!isSystem(a.pkg)) {
                addSecondary(buttons, "Uninstall", v -> launch(new Intent(Intent.ACTION_DELETE,
                        Uri.parse("package:" + a.pkg))));
            }
        } else {
            addSecondary(buttons, "View in Google Play", v -> openPlay(Uri.parse("market://details?id=" + a.pkg)));
        }
        info.addView(buttons);
        page.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        primary.post(primary::requestFocus);
    }

    private void addSecondary(LinearLayout buttons, String text, View.OnClickListener click) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        lp.setMarginStart(dp(12));
        buttons.addView(pillButton(text, false, click), lp);
    }

    private void primaryAction(App a) {
        if (installed(a.pkg)) {
            Intent open = pm.getLeanbackLaunchIntentForPackage(a.pkg);
            if (open == null) open = pm.getLaunchIntentForPackage(a.pkg);
            if (open != null) launch(open);
        } else {
            openPlay(Uri.parse("market://details?id=" + a.pkg));
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && detail != null) {
            show(tab);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- pieces -----------------------------------------------------------------------------------

    /** An app tile: artwork (or a colour tile), with its name and category underneath, as in the App Store. */
    private View tile(App a, int width) {
        int height = width * 9 / 16;
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setClipChildren(false);
        t.setClipToPadding(false);   // otherwise the focused (enlarged) artwork is cut flat at its edges
        t.setPadding(0, 0, dp(24), dp(18));
        View art = artwork(a, width, height, 14);
        art.setTag("card");
        art.setFocusable(true);
        art.setClickable(true);
        art.setOnClickListener(v -> showDetail(a));
        art.setOnFocusChangeListener((v, f) -> v.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f)
                .translationZ(f ? dp(10) : 0).setDuration(180).start());
        t.addView(art);
        TextView name = label(a.name, 15, 0xF2FFFFFF, true);
        name.setPadding(0, dp(10), 0, 0);
        name.setSingleLine(true);
        t.addView(name, new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView cat = label(a.category, 13, 0x80FFFFFF, false);
        t.addView(cat);
        return t;
    }

    /** The app's own banner when installed, otherwise a colour tile with its name. */
    private View artwork(App a, int w, int h, int radius) {
        FrameLayout f = new FrameLayout(this);
        f.setClipToOutline(true);
        f.setOutlineProvider(rounded(radius));
        Drawable art = banner(a.pkg);
        if (art != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(art);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            f.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            f.setBackground(gradient(a.c1, a.c2, radius));
            TextView n = label(a.name, Math.max(16, w / dp(10)), 0xFFFFFFFF, true);
            n.setGravity(Gravity.CENTER);
            f.addView(n, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        View sheen = new View(this);
        sheen.setBackgroundResource(R.drawable.tile_sheen);
        sheen.setDuplicateParentStateEnabled(true);
        f.addView(sheen, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        f.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        return f;
    }

    private Drawable banner(String pkg) {
        if (!installed(pkg)) return null;
        try {
            if (pkg.equals("com.android.vending")) return getDrawable(R.drawable.banner_appstore);
            if (pkg.equals("com.spotify.tv.android")) return getDrawable(R.drawable.banner_spotify);
            if (pkg.equals("com.android.tv.settings")) return getDrawable(R.drawable.banner_settings);
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            Drawable d = i != null ? pm.getActivityBanner(i) : null;
            if (d == null) d = pm.getApplicationBanner(pkg);
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    private View pillButton(String text, boolean primary, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(17);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(28), 0, dp(28), 0);
        b.setTextColor(getColorStateList(primary ? R.drawable.store_primary_text : R.drawable.glass_text));
        b.setBackgroundResource(primary ? R.drawable.store_primary_bg : R.drawable.store_secondary_bg);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(click);
        b.setOnFocusChangeListener((v, f) -> v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(150).start());
        return b;
    }

    private TextView rowTitle(String text) {
        TextView t = label(text, 22, 0xFFFFFFFF, true);
        t.setPadding(0, dp(22), 0, dp(4));
        return t;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    private ScrollView vscroll() {
        ScrollView s = new ScrollView(this);
        s.setVerticalScrollBarEnabled(false);
        s.setClipChildren(false);
        s.setClipToPadding(false);
        return s;
    }

    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);   // let shelves run past the right padding to the screen edge
        col.setPadding(dp(72), dp(14), dp(72), dp(48));
        return col;
    }

    private GradientDrawable gradient(int c1, int c2, int radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        g.setCornerRadius(dp(radius));
        return g;
    }

    private ViewOutlineProvider rounded(int radius) {
        return new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(radius));
            }
        };
    }

    private App find(String pkg) {
        for (App a : CATALOG) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    private boolean installed(String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean isSystem(String pkg) {
        try {
            return (pm.getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    /** Hands off to Google Play, only for installing, updating or searching the full store. */
    private void openPlay(Uri uri) {
        Intent play = new Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending");
        // Open Google Play as the active profile's Google account.
        String account = Profiles.activeAccount(this);
        if (account != null) play.putExtra("authAccount", account);
        launch(play);
    }

    private void launch(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Shelf tiles: about a fifth of the screen width, from the real display. */
    private int tileWidth() {
        return (int) (getResources().getDisplayMetrics().widthPixels * 0.21f);
    }

    /** Grid tiles: five to a row inside the page margins, with room between them. */
    private int gridTileWidth() {
        int usable = getResources().getDisplayMetrics().widthPixels - dp(72) * 2;
        return usable / 5 - dp(24);
    }

    private int dp(int v) {
        return (int) (v * density);
    }
}
