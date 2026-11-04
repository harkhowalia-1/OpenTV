package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.View;

import java.util.Random;

/**
 * OtvOS's screensaver, as on tvOS. Three types (Settings › Screen Saver):
 * <ul>
 * <li>Aerials: Apple TV's aerial videos, streamed from Apple (4K where the TV can play it), from the themes you
 * chose, one after another through black. OK shows where the camera is, with Apple's own captions that follow the
 * video; left and right change the aerial; any other key wakes the TV.</li>
 * <li>Wallpapers: your wallpapers (pictures from the iPhone or Mac, Bing's picture, the macOS wallpaper in use),
 * slowly panning and zooming, cross-fading every few seconds.</li>
 * <li>Clock: a thin clock and date on black over a slow glow, drifting each minute so nothing burns in.</li>
 * </ul>
 * A clock can sit over Aerials and Wallpapers. The screensaver stays away while music plays, if you choose.
 */
public class ClockDream extends DreamService {

    private static final long MOVE_MS = 60_000L;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    /** The running screensaver, so the phone remote can wake the screen like a real remote does. */
    static volatile ClockDream active;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private View content;
    private GradientDrawable glow;
    private int colorIndex;
    private int glowColor;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveClock();
            shiftGlow();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    private String mode;
    private boolean sameAsWallpaper;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setFullscreen(true);
        mode = ScreenSaver.type(this);
        // Same as Wallpaper: what's on Home. Its aerial, its picture (slowly drifting), or the clock for the glow.
        sameAsWallpaper = mode.equals(ScreenSaver.SAME);
        if (sameAsWallpaper) {
            String k = Wallpapers.kind(this);
            mode = k.startsWith("aerial:") ? ScreenSaver.AERIALS
                    : k.isEmpty() || !Wallpapers.current(this).exists() ? ScreenSaver.CLOCK : ScreenSaver.WALLPAPERS;
        }
        if (!mode.equals(ScreenSaver.CLOCK)) {
            setInteractive(true);   // OK shows the place, left/right change the aerial; other keys wake (below)
            setScreenBright(true);
            buildPictures();
            return;
        }
        setInteractive(false);   // any key wakes the TV back to where it was
        setScreenBright(false);
        setContentView(R.layout.dream_clock);

        content = findViewById(R.id.dreamContent);
        View ambient = findViewById(R.id.dreamAmbient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        active = this;
        if (!ScreenSaver.duringMusic(this) && MediaListener.current(this) != null) {
            finish();   // music is playing: no screensaver
            return;
        }
        if (!mode.equals(ScreenSaver.CLOCK)) {
            startPictures();
            return;
        }
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.postDelayed(tick, MOVE_MS);
    }

    @Override
    public void onDreamingStopped() {
        active = null;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        recycleSlides();
        super.onDetachedFromWindow();
    }

    // ---- Aerials and Wallpapers ----------------------------------------------------------------------------

    private android.widget.FrameLayout stage;
    private android.view.SurfaceView surface;
    private boolean surfaceReady;
    private android.media.MediaPlayer player;
    private View black;
    private android.widget.TextView place;
    private final java.util.List<Aerials.Aerial> playlist = new java.util.ArrayList<>();
    private int index = -1, failures;
    private android.widget.ImageView[] slides;
    private final java.util.List<java.io.File> pictures = new java.util.ArrayList<>();
    private int slide = -1, front;

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildPictures() {
        stage = new android.widget.FrameLayout(this);
        stage.setBackgroundColor(0xFF000000);
        android.widget.FrameLayout.LayoutParams full = new android.widget.FrameLayout.LayoutParams(-1, -1);
        if (mode.equals(ScreenSaver.AERIALS)) {
            // A SurfaceView: the video goes straight to the TV's video layer, the cheapest way to play it.
            surface = new android.view.SurfaceView(this);
            surface.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
                @Override public void surfaceCreated(android.view.SurfaceHolder h) {
                    surfaceReady = true;
                    if (player != null) player.setDisplay(h);
                    else if (!playlist.isEmpty()) play(index < 0 ? 0 : index);
                }
                @Override public void surfaceChanged(android.view.SurfaceHolder h, int f, int w, int hh) { }
                @Override public void surfaceDestroyed(android.view.SurfaceHolder h) { surfaceReady = false; }
            });
            stage.addView(surface, full);
        } else {
            slides = new android.widget.ImageView[2];
            for (int i = 0; i < 2; i++) {
                slides[i] = new android.widget.ImageView(this);
                slides[i].setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                slides[i].setAlpha(0f);
                stage.addView(slides[i], new android.widget.FrameLayout.LayoutParams(-1, -1));
            }
        }
        black = new View(this);
        black.setBackgroundColor(0xFF000000);
        stage.addView(black, new android.widget.FrameLayout.LayoutParams(-1, -1));

        // Where the camera is: bottom left, as tvOS shows it.
        place = new android.widget.TextView(this);
        place.setTextColor(0xFFFFFFFF);
        place.setTextSize(26);
        place.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        place.setShadowLayer(dp(12), 0, dp(1), 0x99000000);
        place.setMaxWidth(getResources().getDisplayMetrics().widthPixels * 2 / 3);
        place.setAlpha(0f);
        android.widget.FrameLayout.LayoutParams pl = new android.widget.FrameLayout.LayoutParams(-2, -2,
                android.view.Gravity.BOTTOM | android.view.Gravity.START);
        pl.setMargins(dp(80), 0, dp(80), dp(64));
        stage.addView(place, pl);

        if (ScreenSaver.clock(this)) {
            android.widget.TextClock clock = new android.widget.TextClock(this);
            clock.setFormat12Hour("h:mm");
            clock.setFormat24Hour("HH:mm");
            clock.setTextColor(0xF2FFFFFF);
            clock.setTextSize(44);
            try {
                clock.setTypeface(getResources().getFont(R.font.outfit));
                clock.setFontVariationSettings("'wght' 300");
            } catch (Exception ignored) {
            }
            clock.setLetterSpacing(-0.02f);
            clock.setShadowLayer(dp(14), 0, dp(1), 0x80000000);
            android.widget.FrameLayout.LayoutParams cl = new android.widget.FrameLayout.LayoutParams(-2, -2,
                    android.view.Gravity.TOP | android.view.Gravity.END);
            cl.setMargins(0, dp(48), dp(80), 0);
            stage.addView(clock, cl);
        }
        setContentView(stage);
    }

    private void startPictures() {
        new Thread(() -> {
            if (mode.equals(ScreenSaver.AERIALS)) {
                String theme = ScreenSaver.theme(this);
                java.util.List<Aerials.Aerial> all = Aerials.list(this);
                java.util.List<Aerials.Aerial> pick = new java.util.ArrayList<>();
                // The aerials on the TV (streamed only when it's short on space).
                String home = sameAsWallpaper ? Wallpapers.kind(this).substring(7) : null;
                for (Aerials.Aerial a : all) {
                    boolean wanted = home != null ? a.id.equals(home)   // Home's aerial, over and over
                            : theme.equals(ScreenSaver.ALL) || theme.equals(a.category);
                    if (wanted && AerialCache.source(this, a, true) != null) pick.add(a);
                }
                AerialCache.update(this);
                java.util.Collections.shuffle(pick, random);
                handler.post(() -> {
                    playlist.addAll(pick);
                    if (playlist.isEmpty()) {
                        fallBackToClock();   // none downloaded yet: the clock, until they are
                    } else if (surfaceReady) {
                        play(0);
                    }
                });
            } else {
                java.util.List<java.io.File> found = sameAsWallpaper
                        ? new java.util.ArrayList<>(java.util.Collections.singletonList(Wallpapers.current(this)))
                        : wallpaperPictures();
                java.util.Collections.shuffle(found, random);
                handler.post(() -> {
                    pictures.addAll(found);
                    if (pictures.isEmpty()) finish();
                    else nextSlide();
                });
            }
        }, "otv-screensaver").start();
    }

    // ---- aerials

    private void play(int i) {
        if (playlist.isEmpty() || !surfaceReady) return;
        index = (i % playlist.size() + playlist.size()) % playlist.size();
        Aerials.Aerial a = playlist.get(index);
        handler.removeCallbacks(placeTick);
        place.animate().cancel();
        place.setAlpha(0f);
        black.animate().cancel();
        black.animate().alpha(1f).setDuration(player == null ? 0 : 900).withEndAction(() -> {
            releasePlayer();
            try {
                android.media.MediaPlayer mp = new android.media.MediaPlayer();
                player = mp;
                mp.setDisplay(surface.getHolder());
                String src = AerialCache.source(this, a, true);
                if (src == null) throw new java.io.IOException("not on the TV");
                mp.setDataSource(src);
                mp.setVolume(0f, 0f);
                mp.setOnPreparedListener(android.media.MediaPlayer::start);
                mp.setOnInfoListener((p, what, extra) -> {
                    if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        failures = 0;
                        black.animate().alpha(0f).setDuration(1600).start();
                        showPlace(7000);   // tvOS names the place as each aerial begins
                    }
                    return false;
                });
                mp.setOnCompletionListener(p -> play(index + 1));
                mp.setOnErrorListener((p, w, e) -> {
                    // Skip one that won't play; give up (to the clock) if none will, e.g. offline.
                    if (++failures > 4) fallBackToClock();
                    else handler.postDelayed(() -> play(index + 1), 1500);
                    return true;
                });
                mp.prepareAsync();
            } catch (Exception e) {
                if (++failures > 4) fallBackToClock();
                else handler.postDelayed(() -> play(index + 1), 1500);
            }
        }).start();
    }

    private final Runnable placeTick = new Runnable() {
        @Override
        public void run() {
            updatePlace();
            handler.postDelayed(this, 1000);
        }
    };
    private final Runnable hidePlace = () -> {
        handler.removeCallbacks(placeTick);
        place.animate().alpha(0f).setDuration(1000).start();
    };

    /** Shows where the camera is for a while; the caption follows the video as it moves. */
    private void showPlace(long forMs) {
        if (index < 0 || playlist.isEmpty()) return;
        updatePlace();
        place.animate().alpha(1f).setDuration(500).start();
        handler.removeCallbacks(placeTick);
        handler.postDelayed(placeTick, 1000);
        handler.removeCallbacks(hidePlace);
        handler.postDelayed(hidePlace, forMs);
    }

    private void updatePlace() {
        if (index < 0 || index >= playlist.size()) return;
        int at = 0;
        try {
            if (player != null) at = player.getCurrentPosition() / 1000;
        } catch (IllegalStateException ignored) {
        }
        String text = Aerials.caption(this, playlist.get(index), at);
        if (!text.contentEquals(place.getText())) place.setText(text);
    }

    private void releasePlayer() {
        if (player == null) return;
        try {
            player.release();
        } catch (Exception ignored) {
        }
        player = null;
    }

    private void fallBackToClock() {
        releasePlayer();
        handler.removeCallbacksAndMessages(null);
        mode = ScreenSaver.CLOCK;
        setInteractive(false);
        setContentView(R.layout.dream_clock);
        content = findViewById(R.id.dreamContent);
        View ambient = findViewById(R.id.dreamAmbient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> glow.setGradientRadius((r - l) * 0.55f));
        setGlow(PALETTE[colorIndex]);
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.postDelayed(tick, MOVE_MS);
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (mode == null || mode.equals(ScreenSaver.CLOCK)) return super.dispatchKeyEvent(e);
        int k = e.getKeyCode();
        boolean aerials = mode.equals(ScreenSaver.AERIALS);
        if (k == android.view.KeyEvent.KEYCODE_DPAD_CENTER || k == android.view.KeyEvent.KEYCODE_ENTER) {
            if (e.getAction() == android.view.KeyEvent.ACTION_UP) {
                if (aerials) showPlace(6000);
                else nextSlide();
            }
            return true;
        }
        if (k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT || k == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
            if (e.getAction() == android.view.KeyEvent.ACTION_UP) {
                int step = k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                if (aerials) play(index + step);
                else {
                    slide += step - 1;
                    nextSlide();
                }
            }
            return true;
        }
        if (k == android.view.KeyEvent.KEYCODE_VOLUME_UP || k == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
                || k == android.view.KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(e);   // volume works without waking
        }
        finish();   // any other key wakes the TV, as on tvOS
        return true;
    }

    // ---- wallpapers slideshow

    private java.util.List<java.io.File> wallpaperPictures() {
        java.util.List<java.io.File> out = new java.util.ArrayList<>(Wallpapers.uploads(this));
        java.io.File[] mac = Wallpapers.dir(this, "macos").listFiles();
        if (mac != null) for (java.io.File d : mac) {
            java.io.File[] frames = d.listFiles((dir, n) -> n.endsWith(".jpg"));
            if (frames != null) java.util.Collections.addAll(out, frames);
        }
        java.io.File[] bing = Wallpapers.dir(this, "bing").listFiles((dir, n) -> n.endsWith(".jpg"));
        if (bing != null) java.util.Collections.addAll(out, bing);
        if (out.isEmpty() && Wallpapers.current(this).exists()) out.add(Wallpapers.current(this));
        return out;
    }

    private final Runnable slideTick = this::nextSlide;

    /** The next picture fades in over the last one while slowly zooming and drifting (Ken Burns). */
    private void nextSlide() {
        if (pictures.isEmpty()) return;
        handler.removeCallbacks(slideTick);
        slide = ((slide + 1) % pictures.size() + pictures.size()) % pictures.size();
        java.io.File f = pictures.get(slide);
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
        android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
        handler.postDelayed(slideTick, 9000);
        if (b == null) return;
        int next = 1 - front;
        android.widget.ImageView in = slides[next], out = slides[front];
        recycle(in);
        in.setImageBitmap(b);
        float zoomFrom = 1.04f, zoomTo = 1.14f;
        boolean grow = random.nextBoolean();
        in.setScaleX(grow ? zoomFrom : zoomTo);
        in.setScaleY(grow ? zoomFrom : zoomTo);
        float dx = (random.nextFloat() - 0.5f) * dp(60), dy = (random.nextFloat() - 0.5f) * dp(34);
        in.setTranslationX(-dx);
        in.setTranslationY(-dy);
        in.bringToFront();
        black.bringToFront();
        place.bringToFront();
        for (int i = 0; i < stage.getChildCount(); i++) {
            if (stage.getChildAt(i) instanceof android.widget.TextClock) stage.getChildAt(i).bringToFront();
        }
        black.setAlpha(0f);
        in.setAlpha(0f);
        android.animation.ObjectAnimator fade = android.animation.ObjectAnimator.ofFloat(in, View.ALPHA, 0f, 1f);
        fade.setDuration(1800);
        android.animation.ObjectAnimator drift = android.animation.ObjectAnimator.ofPropertyValuesHolder(in,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, grow ? zoomTo : zoomFrom),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, grow ? zoomTo : zoomFrom),
                android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_X, dx),
                android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, dy));
        drift.setDuration(11000);
        drift.setInterpolator(new android.view.animation.LinearInterpolator());
        fade.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator anim) {
                out.setAlpha(0f);
            }
        });
        fade.start();
        drift.start();
        front = next;
    }

    private void recycle(android.widget.ImageView v) {
        android.graphics.drawable.Drawable d = v.getDrawable();
        v.setImageDrawable(null);
        if (d instanceof android.graphics.drawable.BitmapDrawable) ((android.graphics.drawable.BitmapDrawable) d).getBitmap().recycle();
    }

    private void recycleSlides() {
        if (slides != null) for (android.widget.ImageView v : slides) recycle(v);
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        }).start();
    }

    /** A random spot that keeps the whole clock on screen, with the glow centred behind it. */
    private void placeClock() {
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float x = maxX * (0.1f + 0.8f * random.nextFloat());
        float y = maxY * (0.1f + 0.8f * random.nextFloat());
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    private void shiftGlow() {
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), from, PALETTE[colorIndex]);
        anim.setDuration(8000);
        anim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        anim.start();
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}
