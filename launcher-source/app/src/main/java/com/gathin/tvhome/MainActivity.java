package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.util.Log;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.os.Build;
import android.graphics.Bitmap;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private ImageView heroArt;
    private FrameLayout statusSlot;
    private DockDecoration dockDecoration;
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private boolean heroShown;
    private TextView greeting;
    private TextView btnWifi;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        COLUMNS = Math.max(1, getResources().getInteger(R.integer.columns));
        liveTvPkg = getString(R.string.live_tv_package).trim();

        root = findViewById(R.id.root);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        heroArt = findViewById(R.id.heroArt);
        // Top Shelf as in the original launcher: the app's name over a soft glow in its colour, no artwork.
        heroArt.setVisibility(View.GONE);
        findViewById(R.id.heroScrim).setVisibility(View.GONE);
        // tvOS focus is the tile's own lift and sheen (AppAdapter); the floating glass slab is retired.
        glass.setVisibility(View.GONE);
        statusSlot = findViewById(R.id.statusSlot);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        // The glow lights up the frosted dock and status pill as it fades in and out (tvOS vibrancy).
        ambient.animate().setUpdateListener(a -> syncGlassTint());
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        greeting = findViewById(R.id.greeting);
        btnWifi = findViewById(R.id.btnWifi);

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        dockDecoration = new DockDecoration(this, COLUMNS, density);
        grid.addItemDecoration(dockDecoration);
        dockDecoration.glass.frosted(Backdrop.HOME, grid);
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS);
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int dockRow = AppAdapter.rowHeight(this, COLUMNS) + (int) ((6 + 12) * density);   // plus DockDecoration offsets
            int want = Math.max(0, h - dockRow - (int) (30 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.58f));
        }

        View btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        btnSettings.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        // Hold OK on Settings (or press Menu) for Control Center; holding Home opens it too (HomeKeyService).
        btnSettings.setOnLongClickListener(v -> {
            openControlCenter();
            return true;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        // Streaming sticks (e.g. Chromecast) have no HDMI/AV inputs or tuner: drop the Inputs pill there.
        final int firstPill = hasInputs() ? R.id.btnInputs : R.id.btnWifi;
        if (firstPill != R.id.btnInputs) btnInputs.setVisibility(View.GONE);
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == firstPill) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);

        showBootIntroOnce();
    }

    /**
     * Apple TV style start-up: on the first Home after each boot, a white "otv" wordmark glows in on
     * black, holds, then the whole card fades away to reveal the home screen (about 2.5 s).
     */
    private void showBootIntroOnce() {
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        android.content.SharedPreferences prefs = getSharedPreferences("intro", MODE_PRIVATE);
        if (boot < 0 || prefs.getInt("boot", -2) == boot) return;
        prefs.edit().putInt("boot", boot).apply();

        FrameLayout card = new FrameLayout(this);
        card.setBackgroundColor(Color.BLACK);
        card.setClickable(true);
        TextView mark = new TextView(this);
        mark.setText("otv");
        mark.setTextColor(Color.WHITE);
        mark.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 96);
        mark.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));   // Outfit ships only in thin
        mark.setLetterSpacing(-0.04f);
        mark.setShadowLayer(48f * density, 0f, 0f, 0x66FFFFFF);
        int pad = (int) (72 * density);   // room for the glow, which is clipped to the view
        mark.setPadding(pad, pad, pad, pad);
        mark.setAlpha(0f);
        mark.setScaleX(0.92f);
        mark.setScaleY(0.92f);
        card.addView(mark, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        android.view.animation.DecelerateInterpolator ease = new android.view.animation.DecelerateInterpolator(1.6f);
        mark.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(250).setDuration(900).setInterpolator(ease)
                .withEndAction(() -> card.animate().alpha(0f).setStartDelay(800).setDuration(600)
                        .withEndAction(() -> root.removeView(card)));
    }

    private String shownWallpaper;
    private boolean glowOn = true;
    private android.view.TextureView aerialView;
    private android.media.MediaPlayer aerial;
    private String aerialUrl;
    private final Runnable wallpaperTick = new Runnable() {
        @Override
        public void run() {
            // Dynamic wallpapers follow the time of day; Bing changes each morning.
            new Thread(() -> {
                Wallpapers.refresh(MainActivity.this);
                runOnUiThread(MainActivity.this::applyWallpaper);
            }, "otv-wallpaper").start();
            handler.postDelayed(this, 5 * 60 * 1000);
        }
    };

    /** Draws the chosen wallpaper (only when it changed): a still, or a live aerial over its still. */
    private void applyWallpaper() {
        String kind = Wallpapers.kind(this);
        java.io.File f = Wallpapers.current(this);
        String key = kind.isEmpty() || !f.exists() ? "" : kind + f.lastModified();
        android.widget.ImageView wall = findViewById(R.id.wallpaper);
        // The colour glow behind the apps belongs to the Glow wallpaper only: over a picture or video it's off.
        glowOn = key.isEmpty();
        ambient.setVisibility(glowOn ? View.VISIBLE : View.INVISIBLE);
        syncGlassTint();
        if (!key.equals(shownWallpaper)) {
            shownWallpaper = key;
            if (key.isEmpty()) {
                wall.setImageDrawable(null);
                wall.setVisibility(View.GONE);
            } else {
                // Decoded at the interface's size (the 4K file stays for 4K uses): a 4K texture drawn at 1080p
                // costs the GPU four times the memory and bandwidth for no visible detail.
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
                int want = Math.max(1, getResources().getDisplayMetrics().widthPixels);
                o.inSampleSize = 1;
                while (o.outWidth / (o.inSampleSize * 2) >= want) o.inSampleSize *= 2;
                o.inJustDecodeBounds = false;
                o.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
                android.graphics.drawable.Drawable old = wall.getDrawable();
                wall.setImageBitmap(android.graphics.BitmapFactory.decodeFile(f.getPath(), o));
                if (old instanceof android.graphics.drawable.BitmapDrawable) ((android.graphics.drawable.BitmapDrawable) old).getBitmap().recycle();
                wall.setColorFilter(0x59000000, android.graphics.PorterDuff.Mode.SRC_ATOP);
                wall.setVisibility(View.VISIBLE);
            }
        }
        playAerial(Wallpapers.video(this));
    }

    /** A live aerial: plays muted and looping behind the apps, only while Home is showing. */
    private void playAerial(String url) {
        View scrim = findViewById(R.id.root).findViewWithTag("aerialScrim");
        if (url == null) {
            stopAerial();
            if (aerialView != null) aerialView.setVisibility(View.GONE);
            if (scrim != null) scrim.setVisibility(View.GONE);
            return;
        }
        if (scrim != null) scrim.setVisibility(View.VISIBLE);
        if (aerialView == null) {
            aerialView = new android.view.TextureView(this);
            aerialView.setAlpha(0f);
            android.view.ViewGroup root = findViewById(R.id.root);
            root.addView(aerialView, 1, new android.widget.FrameLayout.LayoutParams(-1, -1));
            View dim = new View(this);
            dim.setBackgroundColor(0x59000000);
            dim.setTag("aerialScrim");
            root.addView(dim, 2, new android.widget.FrameLayout.LayoutParams(-1, -1));
        }
        aerialView.setVisibility(View.VISIBLE);
        if (aerial != null && url.equals(aerialUrl)) {
            if (!aerial.isPlaying()) aerial.start();
            return;
        }
        stopAerial();
        aerialUrl = url;
        Runnable start = () -> {
            try {
                android.media.MediaPlayer mp = new android.media.MediaPlayer();
                aerial = mp;
                mp.setSurface(new android.view.Surface(aerialView.getSurfaceTexture()));
                mp.setDataSource(url);
                mp.setLooping(true);
                mp.setVolume(0f, 0f);
                mp.setOnPreparedListener(p -> {
                    p.start();
                    aerialView.animate().alpha(1f).setDuration(800).start();
                });
                mp.setOnErrorListener((p, w, e) -> true);   // the still stays up
                mp.prepareAsync();
            } catch (Exception ignored) {
            }
        };
        if (aerialView.isAvailable()) {
            start.run();
        } else {
            aerialView.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture t, int w, int h) { start.run(); }
                @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture t, int w, int h) { }
                @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture t) { stopAerial(); return true; }
                @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture t) { }
            });
        }
    }

    private void stopAerial() {
        if (aerial != null) {
            try {
                aerial.release();
            } catch (Exception ignored) {
            }
            aerial = null;
            aerialUrl = null;
        }
        if (aerialView != null) aerialView.setAlpha(0f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Backdrop.clear(Backdrop.PANEL);
        resumedAt = SystemClock.uptimeMillis();
        Profiles.syncAccounts(this);
        applyWallpaper();
        handler.removeCallbacks(wallpaperTick);
        handler.postDelayed(wallpaperTick, 1000);
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        heroArt.setAlpha(0f);
        ambient.setAlpha(0f);
        backdropDeep = false;
        if (backdropAnim != null) backdropAnim.cancel();
        applyBackdrop(0f);
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        adapter.reload();
        grid.scheduleLayoutAnimation();
        updateGreeting();
        updateWifiLabel();
        refreshPill();
        handler.postDelayed(minuteTick, MINUTE_MS);

        // Return to the app we left from, or the same slot if that app is gone. Always refocus: after
        // reload() the view that holds focus may no longer show the app it had.
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        adapter.endEditing();   // leaving Home ends edit mode, as pressing Home does on iPhone
        statusSlot.setVisibility(View.INVISIBLE);   // a panel on top draws its own pill in the same spot
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(wallpaperTick);
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.removeCallbacks(frostTick);
        handler.post(frostTick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(frostTick);
        Backdrop.clear(Backdrop.PANEL);   // panels over an app don't see Home
        // An app covers Home (Control Center and other see-through panels only pause it, so the aerial keeps
        // playing behind them): give its memory back to the app. The video player, the wallpaper picture and the
        // frosted copies are let go, and come back in a moment when Home returns.
        stopAerial();
        aerialUrl = null;
        android.widget.ImageView wall = findViewById(R.id.wallpaper);
        android.graphics.drawable.Drawable d = wall.getDrawable();
        wall.setImageDrawable(null);
        if (d instanceof android.graphics.drawable.BitmapDrawable) ((android.graphics.drawable.BitmapDrawable) d).getBitmap().recycle();
        shownWallpaper = null;
        frostedStill = null;
        Backdrop.clear(Backdrop.HOME);
        if (spareHome != null) {
            spareHome.recycle();
            spareHome = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.unregisterNetworkCallback(networkCallback);
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(10f * density * AppAdapter.FOCUS_SCALE + pad);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
            showHero(position, r.top - dy);
            setBackdrop(false);
        } else {
            hideHero();
            setBackdrop(true);
            // Scroll just enough so the whole glass (not only the tile) is visible.
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, false);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 18 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        AppAdapter.Entry entry = adapter.entryAt(position);
        android.graphics.drawable.Drawable art = entry == null ? null : entry.banner != null ? entry.banner : entry.icon;
        if (!heroShown || changed) {
            heroArt.animate().alpha(0f).setDuration(heroShown ? 140 : 0).withEndAction(() -> {
                if (!heroShown) return;   // moved into the grid meanwhile
                heroArt.setImageDrawable(art);
                heroArt.animate().alpha(1f).setDuration(450).start();
            }).start();
        }

        if (!heroShown) {
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                // Fast moves (a trackpad flick) can leave the dock before this runs: then stay hidden, or
                // the old title would sit behind the grid as it scrolls up.
                if (!heroShown) return;
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    if (!heroShown) return;
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            heroName.setY(y);
        }
    }

    // ---- backdrop: the wallpaper blurs and dims when you move down into the apps, as on Apple TV --------

    // ---- frosted glass: keeps the blurred copies of what's behind the glass up to date (see Backdrop) ------

    private boolean resumed;
    private String frostedStill;
    private final Runnable frostTick = new Runnable() {
        @Override
        public void run() {
            boolean live = aerial != null && aerialView != null && aerialView.getAlpha() > 0.5f;
            updateHomeFrost(live);
            if (!resumed) updatePanelFrost();
            handler.postDelayed(this, live || !resumed ? 40 : 400);
        }
    };

    private Bitmap spareHome, sparePanel;
    private boolean copying;

    /** A small screen-sized picture: the spare one from last time when it fits, so frames don't allocate. */
    private Bitmap smallScreen(Bitmap spare) {
        int w = Math.max(1, root.getWidth() / Backdrop.SCALE), h = Math.max(1, root.getHeight() / Backdrop.SCALE);
        if (spare != null && !spare.isRecycled() && spare.getWidth() == w && spare.getHeight() == h) return spare;
        if (spare != null) spare.recycle();
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    }

    /** Home's own glass sees the wallpaper: the still, or each frame of a live aerial. */
    private void updateHomeFrost(boolean live) {
        if (root.getWidth() == 0) return;
        ImageView wall = findViewById(R.id.wallpaper);
        if (live) {
            Bitmap small = smallScreen(spareHome);
            spareHome = null;
            aerialView.getBitmap(small);
            android.graphics.Canvas cv = new android.graphics.Canvas(small);
            cv.drawColor(0x59000000);   // the aerial's dimming
            spareHome = Backdrop.update(Backdrop.HOME, small, root.getWidth(), root.getHeight(), density);
            frostedStill = null;
        } else if (shownWallpaper != null && !shownWallpaper.isEmpty() && wall.getDrawable() != null) {
            String key = shownWallpaper;
            if (key.equals(frostedStill)) return;
            frostedStill = key;
            Bitmap small = smallScreen(spareHome);
            spareHome = null;
            android.graphics.Canvas cv = new android.graphics.Canvas(small);
            cv.drawColor(0xFF000000);
            cv.scale(small.getWidth() / (float) wall.getWidth(), small.getHeight() / (float) wall.getHeight());
            wall.draw(cv);   // software drawing: the still and its dimming, without the on-screen blur
            cv.setMatrix(null);
            spareHome = Backdrop.update(Backdrop.HOME, small, root.getWidth(), root.getHeight(), density);
        } else if (frostedStill == null || !frostedStill.isEmpty()) {
            frostedStill = "";
            Backdrop.clear(Backdrop.HOME);   // the colour glow: glass keeps its glow tint instead
        }
    }

    /** Panels over Home (Control Center) see all of Home, apps and all, copied from this window. */
    private void updatePanelFrost() {
        if (root.getWidth() == 0 || Build.VERSION.SDK_INT < 26 || copying) return;
        Bitmap small = smallScreen(sparePanel);
        sparePanel = null;
        copying = true;
        try {
            android.view.PixelCopy.request(getWindow(), small, result -> {
                copying = false;
                if (result == android.view.PixelCopy.SUCCESS && !resumed) {
                    sparePanel = Backdrop.update(Backdrop.PANEL, small, root.getWidth(), root.getHeight(), density);
                } else {
                    sparePanel = small;
                }
            }, handler);
        } catch (IllegalArgumentException e) {
            copying = false;
            sparePanel = small;
        }
    }

    private boolean backdropDeep;
    private float backdropAmount;
    private android.animation.ValueAnimator backdropAnim;

    private void setBackdrop(boolean deep) {
        if (deep == backdropDeep) return;
        backdropDeep = deep;
        if (backdropAnim != null) backdropAnim.cancel();
        backdropAnim = android.animation.ValueAnimator.ofFloat(backdropAmount, deep ? 1f : 0f);
        backdropAnim.setDuration(deep ? 500 : 380);
        backdropAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
        backdropAnim.addUpdateListener(a -> applyBackdrop((float) a.getAnimatedValue()));
        backdropAnim.start();
    }

    private void applyBackdrop(float t) {
        backdropAmount = t;
        if (deepBlur == null) {
            ViewGroup root = findViewById(R.id.root);
            deepBlur = new View(this) {
                private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
                private final android.graphics.Rect dst = new android.graphics.Rect();

                @Override
                protected void onDraw(android.graphics.Canvas c) {
                    Bitmap b = Backdrop.deep();
                    if (b == null || b.isRecycled()) return;
                    dst.set(0, 0, getWidth(), getHeight());
                    c.drawBitmap(b, null, dst, paint);
                    c.drawColor(0x4D000000);   // and dimmer, so the apps stand out
                }
            };
            deepBlur.setAlpha(0f);
            deepBlur.setVisibility(View.GONE);
            Backdrop.watch(deepBlur);
            root.addView(deepBlur, root.indexOfChild(ambient), new FrameLayout.LayoutParams(-1, -1));
        }
        // The blurred, dimmed wallpaper fades in over the sharp one. Only one is drawn at rest: the blur covers
        // the whole screen, so the sharp picture under it is hidden then (each full-screen layer costs the GPU).
        boolean show = t > 0.01f && Backdrop.deep() != null;
        deepBlur.setVisibility(show ? View.VISIBLE : View.GONE);
        deepBlur.setAlpha(t);
        ImageView wall = findViewById(R.id.wallpaper);
        boolean covered = show && t > 0.999f;
        if (wall.getDrawable() != null) wall.setVisibility(covered ? View.INVISIBLE : View.VISIBLE);
    }

    private View deepBlur;

    private void syncGlassTint() {
        float s = glowOn ? ambient.getAlpha() : 0f;
        dockDecoration.glass.setTint(ambientColor, s);
        grid.invalidate();
        if (statusSlot.getChildCount() > 0 && statusSlot.getChildAt(0).getBackground() instanceof LiquidGlass) {
            ((LiquidGlass) statusSlot.getChildAt(0).getBackground()).setTint(ambientColor, s * 0.5f);
        }
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c});
        syncGlassTint();
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        // Cancel half-finished title swaps first: their fade-in would otherwise bring the title back.
        heroName.animate().cancel();
        heroArt.animate().cancel();
        ambient.animate().cancel();
        heroName.animate().alpha(0f).setDuration(160).start();
        heroArt.animate().alpha(0f).setDuration(300).start();
        ambient.animate().alpha(0f).setDuration(300).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- tiles: open, options, move, folders -----------------------------------------------------

    @Override
    public void onTileClicked(int position) {
        AppAdapter.Entry e = adapter.entryAt(position);
        if (e == null) return;
        if (e.isFolder()) openFolder(e);
        else if (realPress(e.key)) AppAdapter.launch(this, e);
    }

    /** Holding OK on a tile: every tile jiggles, as on iPhone, and can be moved. */
    @Override
    public void onTileLongPressed(int position) {
        adapter.startEditing();
        Toast.makeText(this, R.string.edit_hint, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onEditDone() {
        adapter.endEditing();
        grid.postDelayed(() -> focusTile(lastFocused), 60);
    }

    @Override
    public void onTileOptions(int position) {
        AppAdapter.Entry e = adapter.entryAt(position);
        if (e == null) return;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (e.isFolder()) {
            labels.add(getString(R.string.rename));
            actions.add(() -> askFolderName(e.folderName(), name -> {
                adapter.renameFolder(e.folderName(), name);
                refocus(AppAdapter.FOLDER + name, position);
            }));
            labels.add(getString(R.string.remove_folder));
            actions.add(() -> {
                adapter.deleteFolder(e.folderName());
                refocus(e.children.get(0).key, position);
            });
        } else {
            labels.add(getString(R.string.add_to_folder));
            actions.add(() -> chooseFolder(e));
            labels.add(getString(R.string.app_info));
            actions.add(() -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + e.key))));
        }
        showMenu(e.label, labels, actions);
    }

    private void showMenu(String title, List<String> labels, List<Runnable> actions) {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void chooseFolder(AppAdapter.Entry app) {
        List<String> labels = new ArrayList<>(adapter.folderNames());
        List<Runnable> actions = new ArrayList<>();
        for (String name : labels) actions.add(() -> {
            adapter.addToFolder(name, app.key);
            refocus(AppAdapter.FOLDER + name, lastFocused);
        });
        labels.add(getString(R.string.new_folder));
        actions.add(() -> askFolderName(getString(R.string.folder_default, adapter.folderNames().size() + 1),
                name -> {
                    adapter.addToFolder(name, app.key);
                    refocus(AppAdapter.FOLDER + name, lastFocused);
                }));
        showMenu(getString(R.string.add_to_folder), labels, actions);
    }

    private interface NameCallback {
        void onName(String name);
    }

    private void askFolderName(String initial, NameCallback done) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(initial);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        int pad = (int) (24 * density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.folder_name)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = input.getText().toString().replace("\n", " ").trim();
                    if (!name.isEmpty()) done.onName(name);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** After the grid is rebuilt, put focus on this tile (or the same slot when it is gone). */
    private void refocus(String key, int fallback) {
        lastFocusedKey = key;
        grid.postDelayed(() -> {
            int pos = adapter.positionOf(key);
            focusTile(pos >= 0 ? pos : fallback);
        }, 80);
    }

    @Override
    public void onMoveKey(int position, int keyCode) {
        int target;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: target = position - 1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: target = position + 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: target = position - COLUMNS; break;
            default: target = position + COLUMNS; break;
        }
        if (target < 0 || target >= adapter.getItemCount()) return;
        int now = adapter.moveTile(position, target);
        lastFocused = now;
        lastFocusedKey = adapter.keyAt(now);
        // The moved view keeps focus; once the grid has laid it out in its new slot, bring the glass along.
        grid.postDelayed(() -> focusTile(now), 60);
    }

    @Override
    public void onMoveDone() {
        adapter.endMove();
        grid.postDelayed(() -> focusTile(lastFocused), 60);
    }

    /** A folder opens as a dimmed overlay with its apps in a row of tiles. */
    private void openFolder(AppAdapter.Entry folder) {
        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout dim = new FrameLayout(this);
        dim.setBackgroundColor(0xD9000000);
        dim.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        TextView title = new TextView(this);
        title.setText(folder.label);
        title.setTextColor(Color.WHITE);
        title.setTextSize(36);
        title.setTypeface(getResources().getFont(R.font.outfit));
        title.setPadding(0, 0, 0, (int) (28 * density));
        box.addView(title);

        android.widget.GridLayout gridBox = new android.widget.GridLayout(this);
        gridBox.setColumnCount(COLUMNS);
        gridBox.setClipChildren(false);
        for (AppAdapter.Entry app : folder.children) {
            View tile = getLayoutInflater().inflate(R.layout.item_app, gridBox, false);
            View card = tile.findViewById(R.id.card);
            TextView name = tile.findViewById(R.id.name);
            AppAdapter.bindTile(tile.findViewById(R.id.banner), tile.findViewById(R.id.icon), tile.findViewById(R.id.label), app);
            name.setText(app.label);
            card.setClipToOutline(true);
            card.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 12 * density);
                }
            });
            card.setOnFocusChangeListener((v, has) -> {
                float s = has ? AppAdapter.FOCUS_SCALE : 1f;
                v.animate().scaleX(s).scaleY(s).setDuration(200).start();
                name.animate().alpha(has ? 1f : 0f).setDuration(200).start();
            });
            card.setOnClickListener(v -> {
                dialog.dismiss();
                AppAdapter.launch(this, app);
            });
            card.setOnLongClickListener(v -> {
                List<String> labels = new ArrayList<>();
                List<Runnable> actions = new ArrayList<>();
                labels.add(getString(R.string.remove_from_folder));
                actions.add(() -> {
                    dialog.dismiss();
                    adapter.removeFromFolder(folder.folderName(), app.key);
                    refocus(app.key, lastFocused);
                });
                labels.add(getString(R.string.app_info));
                actions.add(() -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + app.key))));
                showMenu(app.label, labels, actions);
                return true;
            });
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = (int) (175 * density);
            tile.setLayoutParams(lp);
            gridBox.addView(tile);
        }
        box.addView(gridBox);
        dialog.setContentView(dim, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // let the home screen show through the dim
        dialog.show();
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        gridBox.post(() -> gridBox.getChildAt(0).findViewById(R.id.card).requestFocus());
    }

    // ---- control center --------------------------------------------------------------------------

    private void openControlCenter() {
        openPanel(StatusPill.CONTROL);
    }

    private void openPanel(String panel) {
        Intent i = new Intent(this, ControlCenterActivity.class).putExtra(ControlCenterActivity.EXTRA_PANEL, panel);
        startActivity(i);
        overridePendingTransition(0, 0);
    }

    /** The status pill shows Now Playing only while something is playing, and the active profile's avatar. */
    private void refreshPill() {
        statusSlot.setVisibility(View.VISIBLE);
        statusSlot.removeAllViews();
        LinearLayout pill = StatusPill.build(this, this::openPanel, null, MediaListener.current(this) != null);
        ((LiquidGlass) pill.getBackground()).frosted(Backdrop.HOME, null);
        statusSlot.addView(pill);
        StatusPill.rememberHome(pill);
        syncGlassTint();
    }

    /**
     * Opening an app needs a real OK press seen by this screen. A click with no OK press behind it (an
     * accessibility click from another app), or an OK that arrives right as the screen comes back (the press
     * that woke the TV or ended the screensaver, or a stray HDMI-CEC key from the TV), is ignored. Each
     * launch and each ignored one is logged under "TvHome" with the key's source, to trace stray launches.
     */
    private long selectDownAt;
    private String selectDevice = "";
    private long resumedAt;

    private static boolean isSelect(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isSelect(event.getKeyCode()) && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            android.view.InputDevice dev = event.getDevice();
            selectDevice = dev != null ? dev.getName() : "unknown";
            long now = SystemClock.uptimeMillis();
            if (now - resumedAt < 700) {
                Log.w("TvHome", "ignored OK right after the screen came back, from " + selectDevice);
                selectDownAt = 0;
                return true;
            }
            selectDownAt = now;
        }
        return super.dispatchKeyEvent(event);
    }

    /** True when the click follows an OK press from a remote in the last couple of seconds. */
    private boolean realPress(String what) {
        boolean ok = SystemClock.uptimeMillis() - selectDownAt < 2500;
        Log.i("TvHome", (ok ? "open " : "ignored click (no OK press) for ") + what + " via " + selectDevice);
        return ok;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            openControlCenter();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        btnWifi.setText(networkName(this));
    }

    /** The TV's name as set in Google TV settings ("Living room TV"). */
    static String tvName(Context ctx) {
        String n = Settings.Global.getString(ctx.getContentResolver(), Settings.Global.DEVICE_NAME);
        return n != null && !n.isEmpty() ? n : "TV";
    }

    /** The Wi-Fi network name when it can be read, otherwise "Ethernet", "Wi-Fi" or "Offline". */
    static String networkName(Context ctx) {
        String text = ctx.getString(R.string.wifi);
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            if (caps == null) {
                text = ctx.getString(R.string.offline);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                text = ctx.getString(R.string.ethernet);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        return text;
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean hasInputs() {
        if (!liveTvPkg.isEmpty()) return true;
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim == null) return false;
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                // Only physical inputs count; apps such as VLC register non-passthrough "tuner" inputs.
                if (info.isPassthroughInput()) return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (tunerId == null || info.getId().compareTo(tunerId) < 0)) {
                    tunerId = info.getId();
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
            }
        }
        Intent liveTv = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        // Drop in: fade and unfold from the top edge.
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), Gravity.START);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }
}
