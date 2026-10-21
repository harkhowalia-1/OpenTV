package com.gathin.tvhome;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.media.AudioManager;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * The status pill's drop-down panels, tvOS 26 style: Control Center, Inputs, Now Playing and Profiles. The pill is
 * rebuilt here in the same spot as on the home screen, with the open panel's icon in a grey circle; choosing
 * another pill icon swaps the panel in place. Opened by holding Home (HomeKeyService), the pill, or Menu.
 */
public class ControlCenterActivity extends Activity {

    static final String EXTRA_PANEL = "panel";
    /** Google TV Settings: pair a controller or other accessory. */
    private static final String TV_PAIR_ACCESSORY = "com.google.android.intent.action.CONNECT_INPUT";
    /** The AirPlay receiver (UxPlay for Android), installed alongside the launcher. */
    static final String AIRPLAY_PACKAGE = "io.github.jqssun.airplay";
    /** Google TV Settings: start the screensaver now (its "sleep" voice action). */
    private static final String TV_START_SCREENSAVER = "com.google.android.pano.action.SLEEP";
    /** Google TV Settings: screensaver (ambient mode) settings. */
    private static final String TV_SCREENSAVER_SETTINGS = "com.google.android.tv.settings.ambient";
    private static final int[] TIMER_STEPS_MIN = {15, 30, 60, 90};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private float density;
    private LinearLayout column;
    private FrameLayout holder;
    private String panelName;
    private boolean closing;
    /** The open Control Center, so the remote's Control Center button can close it again. */
    static volatile ControlCenterActivity current;

    // Control Center state
    private TextView timerValue;
    private TextView dndValue;
    private GradientDrawable dndBadge;

    // Volume state
    private View volumeFill;
    private View muteButton;

    // Now Playing state
    private MediaController media;
    private View progressFill;
    private TextView elapsed;
    private TextView remaining;
    private ImageView playPause;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (timerValue != null) updateTimer();
            if (progressFill != null) updateProgress();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;

        // Real blur of whatever is behind, where the TV supports it; otherwise only a light dim, as on tvOS.
        boolean blur = Build.VERSION.SDK_INT >= 31 && getWindowManager().isCrossWindowBlurEnabled();
        if (blur) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            getWindow().getAttributes().setBlurBehindRadius(dp(30));
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x33000000);
        root.setClipChildren(false);
        root.setOnClickListener(v -> close());

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.END);
        column.setClipChildren(false);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP);
        clp.setMargins(0, dp(26), dp(40), 0);   // same spot as the home screen's pill
        root.addView(column, clp);

        holder = new FrameLayout(this);
        holder.setClipChildren(false);
        setContentView(root);

        String start = getIntent().getStringExtra(EXTRA_PANEL);
        show(start != null ? start : StatusPill.CONTROL);
        current = this;
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    /** Opens Control Center, or closes it when it is already open (the remote's Control Center button). */
    static void toggle(android.content.Context ctx) {
        toggle(ctx, StatusPill.CONTROL);
    }

    /**
     * Opens <code>panel</code>; closes it when it is the one already open, or swaps to it from another panel
     * (the remote's Control Center and Inputs buttons).
     */
    static void toggle(android.content.Context ctx, String panel) {
        ControlCenterActivity cc = current;
        if (cc != null && !cc.isFinishing() && !cc.closing) {
            if (panel.equals(cc.panelName)) cc.close();
            else cc.show(panel);
            return;
        }
        ctx.startActivity(new Intent(ctx, ControlCenterActivity.class).putExtra(EXTRA_PANEL, panel)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String p = intent.getStringExtra(EXTRA_PANEL);
        show(p != null ? p : StatusPill.CONTROL);
    }

    /** Rebuilds the pill (so the right icon is marked) and drops the chosen panel out of it. */
    private void show(String which) {
        panelName = which;
        timerValue = null;
        progressFill = null;
        media = MediaListener.current(this);
        column.removeAllViews();
        boolean showMedia = media != null || StatusPill.MEDIA.equals(which);
        column.addView(StatusPill.build(this, p -> {
            if (p.equals(panelName)) close();
            else show(p);
        }, which, showMedia), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));

        holder.removeAllViews();
        View panel;
        switch (which) {
            case StatusPill.MEDIA: panel = buildMedia(); break;
            case StatusPill.PROFILE: panel = buildProfiles(); break;
            case StatusPill.INPUTS: panel = buildInputs(); break;
            default: panel = buildControl(); break;
        }
        holder.addView(panel);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(10);
        column.addView(holder, hlp);

        // Drop out of the pill: scale up from the top-right corner and fade in.
        panel.setPivotY(0);
        panel.post(() -> panel.setPivotX(panel.getWidth()));
        panel.setScaleX(0.92f);
        panel.setScaleY(0.92f);
        panel.setAlpha(0f);
        panel.setTranslationY(-dp(10));
        panel.animate().scaleX(1f).scaleY(1f).alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
        panel.post(() -> {
            View first = panel.findViewWithTag("first");
            if (first != null) first.requestFocus();
        });
    }

    private LinearLayout panelShell(int widthDp) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackground(new LiquidGlass(this, 26, LiquidGlass.SMOKE, false));
        p.setPadding(dp(10), dp(10), dp(10), dp(10));
        p.setClipChildren(false);
        p.setClipToPadding(false);
        p.setLayoutParams(new FrameLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT));
        return p;
    }

    // ---- Control Center --------------------------------------------------------------------------

    private View buildControl() {
        // Seven rows have to fit a 1080p screen (540 dp) below the status pill.
        int cell = dp(50), gap = dp(7);
        LinearLayout p = panelShell(4 * 50 + 3 * 7 + 20);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        grid.setClipChildren(false);
        p.addView(grid);

        View power = powerTile();
        power.setTag("first");
        add(grid, power, 0, 2, 0, 2, cell, gap);
        add(grid, wideTile(icon(R.drawable.ic_wifi, 22, ColorStateList.valueOf(0xFF3B9BFF)), getString(R.string.wifi),
                MainActivity.networkName(this), v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS))), 0, 1, 2, 2, cell, gap);

        dndBadge = new GradientDrawable();
        dndBadge.setShape(GradientDrawable.OVAL);
        FrameLayout moon = new FrameLayout(this);
        moon.setBackground(dndBadge);
        moon.addView(icon(R.drawable.ic_moon, 15, ColorStateList.valueOf(0xFFFFFFFF)),
                new FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER));
        moon.setLayoutParams(new LinearLayout.LayoutParams(dp(26), dp(26)));
        View dnd = wideTile(moon, getString(R.string.dnd), "", v -> toggleDnd());
        dndValue = dnd.findViewWithTag("sub");
        add(grid, dnd, 1, 1, 2, 2, cell, gap);

        add(grid, wideTile(icon(R.drawable.ic_screensaver, 22, glassText()), getString(R.string.screen_saver),
                getString(R.string.dream_name), v -> startScreenSaver()), 2, 1, 0, 2, cell, gap);
        View timer = wideTile(icon(R.drawable.ic_timer, 22, glassText()), getString(R.string.sleep_timer), "",
                v -> cycleTimer());
        timerValue = timer.findViewWithTag("sub");
        add(grid, timer, 2, 1, 2, 2, cell, gap);

        add(grid, volumeRow(), 3, 1, 0, 4, cell, gap);

        // Two rows of small round buttons, as in tvOS: the gear opens the tvOS-style Settings, the rest open
        // their screens (Google TV's own where there is no launcher one).
        int[] icons = {R.drawable.ic_remote, R.drawable.ic_airplay, R.drawable.ic_home, R.drawable.ic_settings,
                R.drawable.ic_gamepad, R.drawable.ic_accessibility, R.drawable.ic_display, R.drawable.ic_search};
        Runnable[] actions = {
                this::pairRemote,
                () -> openApp(AIRPLAY_PACKAGE),
                () -> open(new Intent(this, AppleHomeActivity.class)),
                () -> open(new Intent(this, TvSettingsActivity.class)),
                () -> open(new Intent(TV_PAIR_ACCESSORY)),
                () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                () -> open(new Intent(Settings.ACTION_SOUND_SETTINGS)),
                () -> open(new Intent(Intent.ACTION_ASSIST)),
        };
        for (int i = 0; i < icons.length; i++) {
            Runnable action = actions[i];
            FrameLayout t = new FrameLayout(this);
            tile(t, v -> action.run());
            t.addView(icon(icons[i], 22, glassText()), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
            add(grid, t, 4 + i / 4, 1, i % 4, 1, cell, gap);
        }
        add(grid, wideTile(icon(R.drawable.ic_close_apps, 22, glassText()), getString(R.string.close_apps),
                getString(R.string.close_apps_sub), v -> {
                    close();
                    HomeKeyService.closeBackgroundApps();
                }), 6, 1, 0, 4, cell, gap);
        updateDnd();
        updateTimer();
        updateVolume();
        return p;
    }

    private void add(GridLayout grid, View v, int row, int rowSpan, int col, int colSpan, int cell, int gap) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(row, rowSpan), GridLayout.spec(col, colSpan));
        lp.width = cell * colSpan + gap * (colSpan - 1);
        lp.height = cell * rowSpan + gap * (rowSpan - 1);
        lp.setMargins(col == 0 ? 0 : gap, row == 0 ? 0 : gap, 0, 0);
        grid.addView(v, lp);
    }

    private ColorStateList glassText() {
        return getColorStateList(R.drawable.glass_text);
    }

    private ImageView icon(int res, int sizeDp, ColorStateList tint) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setImageTintList(tint);
        iv.setDuplicateParentStateEnabled(true);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return iv;
    }

    private TextView text(String s, int sp, boolean bold, ColorStateList color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setDuplicateParentStateEnabled(true);
        t.setTypeface(android.graphics.Typeface.create(bold ? "sans-serif-medium" : "sans-serif",
                android.graphics.Typeface.NORMAL));
        return t;
    }

    /** A Control Center tile: near-black, white when focused, lifting slightly (tvOS). */
    private void tile(View v, View.OnClickListener click) {
        v.setBackgroundResource(R.drawable.cc_tile_bg);
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(click);
        v.setOnFocusChangeListener((view, f) -> view.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f)
                .setDuration(150).start());
    }

    private View powerTile() {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        tile(t, v -> sleep());
        t.addView(icon(R.drawable.ic_sleep, 46, glassText()));
        TextView l = text(getString(R.string.power_off), 12, true, glassText());
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(0, dp(10), 0, 0);
        t.addView(l, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    /** Wide tile: an icon, then a title over a value. The value view is tagged "sub". */
    private View wideTile(View iconView, String title, String value, View.OnClickListener click) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(10), 0, dp(8), 0);
        tile(t, click);
        iconView.setDuplicateParentStateEnabled(true);
        t.addView(iconView);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setDuplicateParentStateEnabled(true);
        words.setPadding(dp(8), 0, 0, 0);
        words.addView(text(title, 12, true, glassText()));
        TextView sub = text(value, 11, false, getColorStateList(R.drawable.glass_subtext));
        sub.setMaxLines(1);
        sub.setPadding(0, dp(2), 0, 0);
        sub.setTag("sub");
        words.addView(sub);
        t.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return t;
    }

    // ---- Inputs ------------------------------------------------------------------------------------

    /** The TV's inputs: HDMI ports with whether something is plugged in, then AV and the like, then Live TV. */
    private View buildInputs() {
        LinearLayout p = panelShell(280);
        p.setPadding(dp(8), dp(8), dp(8), dp(8));
        int lastKind = -1;
        boolean first = true;
        for (TvInputs.Input in : TvInputs.list(this)) {
            if (lastKind != -1 && in.kind != lastKind) {
                View line = new View(this);
                line.setBackgroundColor(0x1FFFFFFF);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
                llp.setMargins(dp(12), dp(6), dp(12), dp(6));
                p.addView(line, llp);
            }
            lastKind = in.kind;
            View row = inputRow(in);
            if (first) row.setTag("first");
            first = false;
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
            rlp.topMargin = p.getChildCount() == 0 ? 0 : dp(4);
            p.addView(row, rlp);
        }
        if (first) {
            TextView none = text(getString(R.string.no_inputs), 14, false, getColorStateList(R.drawable.glass_subtext));
            none.setPadding(dp(14), dp(14), dp(14), dp(14));
            p.addView(none);
        }
        return p;
    }

    private View inputRow(TvInputs.Input in) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), 0, dp(14), 0);
        tile(r, v -> {
            if (TvInputs.open(this, in)) finish();
            else Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        });
        r.addView(icon(in.icon(), 20, glassText()));
        TextView title = text(in.label, 15, true, glassText());
        title.setMaxLines(1);
        title.setPadding(dp(12), 0, dp(10), 0);
        r.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (in.state == TvInputs.CONNECTED) {
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0xFF30D158);
            dot.setBackground(g);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(7), dp(7));
            dlp.setMarginEnd(dp(7));
            r.addView(dot, dlp);
            r.addView(text(getString(R.string.connected), 11, false, getColorStateList(R.drawable.glass_subtext)));
        }
        return r;
    }

    // ---- volume ------------------------------------------------------------------------------------

    /**
     * Volume row: mute, quieter, a level bar, louder. Goes through Android's audio service, so with HDMI-CEC
     * volume on it drives the TV; otherwise it sets the Chromecast's own output level. Either way it does not
     * need the remote's infrared.
     */
    private View volumeRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6121214);
        bg.setCornerRadius(dp(16));
        row.setBackground(bg);
        row.setPadding(dp(4), 0, dp(4), 0);

        muteButton = volumeButton(R.drawable.ic_volume_up, v -> adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE));
        row.addView(muteButton);
        row.addView(volumeButton(R.drawable.ic_volume_down, v -> adjustVolume(AudioManager.ADJUST_LOWER)));

        FrameLayout track = new FrameLayout(this);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(0x40FFFFFF);
        trackBg.setCornerRadius(dp(3));
        track.setBackground(trackBg);
        volumeFill = new View(this);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setColor(0xFFFFFFFF);
        fillBg.setCornerRadius(dp(3));
        volumeFill.setBackground(fillBg);
        track.addView(volumeFill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, dp(6), 1f);
        tlp.setMargins(dp(4), 0, dp(4), 0);
        row.addView(track, tlp);

        row.addView(volumeButton(R.drawable.ic_volume_up, v -> adjustVolume(AudioManager.ADJUST_RAISE)));
        return row;
    }

    private View volumeButton(int icon, View.OnClickListener click) {
        FrameLayout b = new FrameLayout(this);
        b.setBackgroundResource(R.drawable.pill_icon_bg);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(click);
        b.addView(icon(icon, 22, glassText()), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return b;
    }

    private void adjustVolume(int direction) {
        AudioManager am = getSystemService(AudioManager.class);
        if (am == null) return;
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);   // the bar is the feedback
        handler.postDelayed(this::updateVolume, 120);
    }

    private void updateVolume() {
        AudioManager am = getSystemService(AudioManager.class);
        if (am == null || volumeFill == null) return;
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int now = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean muted = am.isStreamMute(AudioManager.STREAM_MUSIC);
        View track = (View) volumeFill.getParent();
        ViewGroup.LayoutParams lp = volumeFill.getLayoutParams();
        lp.width = muted ? 0 : (int) (track.getWidth() * (now / (float) max));
        volumeFill.setLayoutParams(lp);
        if (track.getWidth() == 0) track.post(this::updateVolume);   // first layout not done yet
        ((ImageView) ((FrameLayout) muteButton).getChildAt(0))
                .setImageResource(muted || now == 0 ? R.drawable.ic_volume_off : R.drawable.ic_volume_up);
    }

    private void sleep() {
        if (!SleepTimerReceiver.sleepNow(this)) {
            Toast.makeText(this, R.string.sleep_setup, Toast.LENGTH_LONG).show();
            return;
        }
        finish();
    }

    private void toggleDnd() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || !nm.isNotificationPolicyAccessGranted()) {
            Toast.makeText(this, R.string.dnd_setup, Toast.LENGTH_LONG).show();
            return;
        }
        boolean on = nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
        nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_ALL
                : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
        handler.postDelayed(this::updateDnd, 150);
    }

    private void updateDnd() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        boolean on = nm != null && nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
        dndBadge.setColor(on ? 0xFF5E5CE6 : 0xFF3A3A3C);   // purple moon when on, as on Apple devices
        dndValue.setText(on ? R.string.on : R.string.off);
    }

    /** Sleep Timer: OK steps through 15, 30, 60 and 90 minutes, then off. */
    private void cycleTimer() {
        SharedPreferences prefs = SleepTimerReceiver.prefs(this);
        long left = prefs.getLong("sleep_at", 0) - System.currentTimeMillis();
        int next = -1;
        for (int m : TIMER_STEPS_MIN) {
            if (left < m * 60_000L - 30_000L) {
                next = m;
                break;
            }
        }
        SleepTimerReceiver.schedule(this, next < 0 ? 0 : next * 60_000L);
        updateTimer();
    }

    private void updateTimer() {
        long left = SleepTimerReceiver.prefs(this).getLong("sleep_at", 0) - System.currentTimeMillis();
        timerValue.setText(left > 0 ? clock(left) : getString(R.string.off));
    }

    private static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    // ---- Now Playing -----------------------------------------------------------------------------

    private View buildMedia() {
        LinearLayout p = panelShell(270);

        // White card: artwork, title and artist.
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xF5FFFFFF);
        cardBg.setCornerRadius(dp(14));
        card.setBackground(cardBg);
        card.setPadding(dp(8), dp(8), dp(10), dp(8));
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackgroundColor(0xFFD1D1D6);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(7));
            }
        });
        art.setClipToOutline(true);
        card.addView(art, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(10), 0, 0, 0);
        TextView title = text("", 13, true, ColorStateList.valueOf(0xFF1C1C1E));
        title.setMaxLines(1);
        TextView artist = text("", 12, false, ColorStateList.valueOf(0x991C1C1E));
        artist.setMaxLines(1);
        artist.setPadding(0, dp(3), 0, 0);
        words.addView(title);
        words.addView(artist);
        card.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        p.addView(card);

        MediaMetadata md = media != null ? media.getMetadata() : null;
        if (md == null) {
            title.setText(R.string.not_playing);
            artist.setVisibility(View.GONE);
            art.setImageResource(R.drawable.ic_waveform);
            art.setImageTintList(ColorStateList.valueOf(0xFF8E8E93));
            art.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            art.setPadding(dp(10), dp(10), dp(10), dp(10));
            return p;
        }
        title.setText(md.getString(MediaMetadata.METADATA_KEY_TITLE));
        String who = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (who == null) who = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        artist.setText(who != null ? who : appLabel(media.getPackageName()));
        Bitmap bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (bmp != null) art.setImageBitmap(bmp);
        else {
            try {
                art.setImageDrawable(getPackageManager().getApplicationIcon(media.getPackageName()));
            } catch (Exception ignored) {
            }
        }

        // Progress bar and times.
        FrameLayout track = new FrameLayout(this);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(0x40FFFFFF);
        trackBg.setCornerRadius(dp(2));
        track.setBackground(trackBg);
        progressFill = new View(this);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setColor(0xFFFFFFFF);
        fillBg.setCornerRadius(dp(2));
        progressFill.setBackground(fillBg);
        track.addView(progressFill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        tlp.setMargins(dp(4), dp(14), dp(4), 0);
        p.addView(track, tlp);

        LinearLayout times = new LinearLayout(this);
        times.setPadding(dp(4), dp(5), dp(4), 0);
        ColorStateList grey = ColorStateList.valueOf(0x99FFFFFF);
        elapsed = text("", 11, false, grey);
        remaining = text("", 11, false, grey);
        remaining.setGravity(Gravity.END);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(remaining, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        p.addView(times);

        // Transport controls.
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(4), 0, dp(2));
        controls.addView(mediaButton(R.drawable.ic_rewind, v -> skip(false)));
        View pp = mediaButton(R.drawable.ic_pause, v -> togglePlay());
        playPause = (ImageView) ((FrameLayout) pp).getChildAt(0);
        pp.setTag("first");
        controls.addView(pp);
        controls.addView(mediaButton(R.drawable.ic_forward, v -> skip(true)));
        p.addView(controls);
        updateProgress();
        return p;
    }

    private View mediaButton(int icon, View.OnClickListener click) {
        FrameLayout b = new FrameLayout(this);
        b.setBackgroundResource(R.drawable.pill_icon_bg);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(click);
        b.addView(icon(icon, 26, glassText()), new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
        lp.setMargins(dp(12), 0, dp(12), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private String appLabel(String pkg) {
        try {
            return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return "";
        }
    }

    private long position(PlaybackState s) {
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        return pos;
    }

    private void updateProgress() {
        if (media == null) return;
        PlaybackState s = media.getPlaybackState();
        MediaMetadata md = media.getMetadata();
        if (s == null) return;
        long dur = md != null ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
        long pos = Math.max(0, position(s));
        if (dur > 0) pos = Math.min(pos, dur);
        elapsed.setText(clock(pos));
        remaining.setText(dur > 0 ? "-" + clock(dur - pos) : "");
        View track = (View) progressFill.getParent();
        ViewGroup.LayoutParams lp = progressFill.getLayoutParams();
        int w = dur > 0 ? (int) (track.getWidth() * (pos / (float) dur)) : 0;
        if (lp.width != w) {
            lp.width = w;
            progressFill.setLayoutParams(lp);
        }
        playPause.setImageResource(s.getState() == PlaybackState.STATE_PLAYING ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private void togglePlay() {
        if (media == null || media.getPlaybackState() == null) return;
        if (media.getPlaybackState().getState() == PlaybackState.STATE_PLAYING) media.getTransportControls().pause();
        else media.getTransportControls().play();
    }

    /** Previous / next track when the app supports it, otherwise a 10 second jump. */
    private void skip(boolean forward) {
        if (media == null || media.getPlaybackState() == null) return;
        PlaybackState s = media.getPlaybackState();
        long actions = s.getActions();
        MediaController.TransportControls t = media.getTransportControls();
        if (forward && (actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0) t.skipToNext();
        else if (!forward && (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0) t.skipToPrevious();
        else t.seekTo(Math.max(0, position(s) + (forward ? 10_000 : -10_000)));
    }

    // ---- Profiles --------------------------------------------------------------------------------

    private View buildProfiles() {
        LinearLayout p = panelShell(240);
        p.setPadding(dp(12), dp(8), dp(12), dp(10));
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setClipChildren(false);
        p.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String active = Profiles.active(this);
        boolean first = true;
        for (String name : Profiles.list(this)) {
            View item = profileItem(Profiles.avatar(this, name, dp(76)), name, name.equals(active), v -> {
                Profiles.setActive(this, name);
                show(StatusPill.PROFILE);
            });
            item.findViewWithTag("ring").setOnLongClickListener(v -> {
                String account = Profiles.account(this, name);
                new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(account != null ? name + " · " + account : name)
                        .setItems(new String[]{getString(R.string.rename_profile), getString(R.string.link_google_account),
                                getString(R.string.remove_profile)}, (d, w) -> {
                            if (w == 0) {
                                askName(name);
                            } else if (w == 1) {
                                pickAccount(name);
                            } else {
                                Profiles.remove(this, name);
                                show(StatusPill.PROFILE);
                            }
                        }).show();
                return true;
            });
            if (first) item.findViewWithTag("ring").setTag("first");
            first = false;
            grid.addView(item);
        }
        FrameLayout plus = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xFF1C1C1E);
        plus.setBackground(bg);
        plus.addView(icon(R.drawable.ic_plus, 34, ColorStateList.valueOf(0xFFFFFFFF)),
                new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER));
        View add = profileItem(plus, getString(R.string.add_profile), false, v -> addProfile());
        if (first) add.findViewWithTag("ring").setTag("first");
        grid.addView(add);
        return p;
    }

    private View profileItem(View avatar, String name, boolean checked, View.OnClickListener click) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(dp(6), dp(8), dp(6), dp(8));
        item.setClipChildren(false);

        FrameLayout ring = new FrameLayout(this);
        ring.setTag("ring");
        ring.setFocusable(true);
        ring.setClickable(true);
        ring.setOnClickListener(click);
        ring.setClipChildren(false);
        ring.addView(avatar, new FrameLayout.LayoutParams(dp(76), dp(76)));
        // Focus ring between the avatar and the badge, so the checkmark stays on top of it.
        View focusRing = new View(this);
        focusRing.setBackgroundResource(R.drawable.profile_ring);
        focusRing.setDuplicateParentStateEnabled(true);
        ring.addView(focusRing, new FrameLayout.LayoutParams(dp(76), dp(76)));
        if (checked) {
            FrameLayout badge = new FrameLayout(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0xFF30D158);
            g.setStroke(dp(2), 0xFFFFFFFF);
            badge.setBackground(g);
            badge.addView(icon(R.drawable.ic_check, 14, ColorStateList.valueOf(0xFFFFFFFF)),
                    new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER));
            ring.addView(badge, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.END | Gravity.TOP));
        }
        item.addView(ring, new LinearLayout.LayoutParams(dp(76), dp(76)));
        TextView label = text(name, 12, false, ColorStateList.valueOf(0xB3FFFFFF));
        label.setMaxLines(1);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setPadding(0, dp(8), 0, 0);
        item.addView(label, new LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT));
        return item;
    }

    /**
     * Add Profile: sign in with a Google account (its profile appears by itself once the sign-in finishes),
     * or just a name, for someone without an account.
     */
    private void addProfile() {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.add_profile)
                .setItems(new String[]{getString(R.string.profile_with_google), getString(R.string.profile_without_account)},
                        (d, w) -> {
                            if (w == 0) {
                                Profiles.expectNewAccount(this);
                                pickAccount(null);
                            } else {
                                askName(null);
                            }
                        })
                .show();
    }

    private static final int PICK_ACCOUNT = 7;
    private String linkingProfile;

    /**
     * Android's account chooser: the TV's Google accounts plus "Add account" (Google sign-in). Picking one there
     * is also what lets the launcher see that account at all, so this is how accounts and profiles get linked.
     */
    private void pickAccount(String profile) {
        linkingProfile = profile;
        Intent i = android.accounts.AccountManager.newChooseAccountIntent(null, null, new String[]{"com.google"},
                null, null, null, null);
        try {
            startActivityForResult(i, PICK_ACCOUNT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_ACCOUNT || resultCode != RESULT_OK || data == null) return;
        String email = data.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME);
        if (email == null) return;
        if (linkingProfile != null) {
            Profiles.link(this, linkingProfile, email);
        } else {
            Profiles.syncAccounts(this);
        }
        String profile = Profiles.profileFor(this, email);
        if (profile != null) Profiles.setActive(this, profile);
        show(StatusPill.PROFILE);
    }

    /** Type a name: a new profile (which becomes the active one), or a new name for {@code renaming}. */
    private void askName(String renaming) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.profile_name);
        if (renaming != null) {
            input.setText(renaming);
            input.setSelection(renaming.length());
        }
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(12), dp(24), 0);
        box.addView(input);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(renaming != null ? R.string.rename_profile : R.string.add_profile)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    if (renaming != null) Profiles.rename(this, renaming, name);
                    else Profiles.add(this, name);
                    show(StatusPill.PROFILE);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- common ----------------------------------------------------------------------------------

    /** Starts the screensaver straight away, as tvOS does; the screensaver settings if that isn't possible. */
    private void startScreenSaver() {
        Intent now = new Intent(TV_START_SCREENSAVER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(now);
            finish();
        } catch (ActivityNotFoundException | SecurityException e) {
            open(new Intent(TV_SCREENSAVER_SETTINGS));
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            finish();
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Remote: puts a pairing code (and the web address) on screen for the TV Remote app on a phone or Mac. */
    private void pairRemote() {
        RemoteServer server = RemoteServer.current;
        if (server == null) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        server.beginPairing();
        close();
    }

    private void openApp(String pkg) {
        Intent i = AppAdapter.launchIntentFor(this, pkg);
        if (i == null) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        open(i);
    }

    void close() {
        if (closing) return;
        closing = true;
        holder.animate().scaleX(0.92f).scaleY(0.92f).alpha(0f).translationY(-dp(10)).setDuration(160)
                .withEndAction(() -> {
                    finish();
                    overridePendingTransition(0, 0);
                }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
            close();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private int dp(int v) {
        return (int) (v * density);
    }
}
