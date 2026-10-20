package com.gathin.otvlive;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Full-screen live channel. Up/Down (or Channel +/−) change channel within the row or grid it was opened from,
 * OK shows the channel's info, holding OK or Menu adds it to Favourites. When a stream fails the channel's
 * other streams are tried before giving up.
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {

    /** The list the channel was picked from (set by MainActivity just before opening). */
    static List<Catalog.Channel> list = new ArrayList<>();

    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Chromecast) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/124.0 Safari/537.36";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private int index, streamIndex;
    private LinearLayout info;
    private TextView name, meta, number, status, heart;
    private ImageView logo;
    private ProgressBar spinner;
    private final Runnable hideInfo = () -> info.animate().alpha(0).setDuration(300).start();
    private long okDownAt;

    private int dp(float v) {
        return Cards.dp(this, v);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        index = getIntent().getIntExtra("index", 0);
        if (list.isEmpty()) {
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        SurfaceView surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        spinner = new ProgressBar(this);
        root.addView(spinner, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        status = new TextView(this);
        status.setTextColor(0xCCFFFFFF);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        sp.topMargin = dp(70);
        root.addView(status, sp);

        // The info bar: logo, name, number, country and categories, over a dark gradient at the bottom.
        info = new LinearLayout(this);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(56), dp(40), dp(56), dp(34));
        info.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE6000000, 0x00000000}));
        FrameLayout tile = new FrameLayout(this);
        GradientDrawable tbg = new GradientDrawable();
        tbg.setCornerRadius(dp(10));
        tbg.setColor(0xFF24242B);
        tile.setBackground(tbg);
        logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setPadding(dp(10), dp(10), dp(10), dp(10));
        tile.addView(logo, new FrameLayout.LayoutParams(-1, -1));
        info.addView(tile, new LinearLayout.LayoutParams(dp(128), dp(72)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(18), 0, 0, 0);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        number = new TextView(this);
        number.setTextColor(0x99FFFFFF);
        number.setTextSize(15);
        number.setPadding(0, 0, dp(10), 0);
        top.addView(number);
        name = new TextView(this);
        name.setTextColor(Color.WHITE);
        name.setTextSize(26);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setSingleLine(true);
        top.addView(name);
        TextView live = new TextView(this);
        live.setText("LIVE");
        live.setTextColor(Color.WHITE);
        live.setTextSize(11);
        live.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable lbg = new GradientDrawable();
        lbg.setCornerRadius(dp(4));
        lbg.setColor(0xFFE5243B);
        live.setBackground(lbg);
        live.setPadding(dp(6), dp(1), dp(6), dp(1));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.leftMargin = dp(12);
        top.addView(live, llp);
        heart = new TextView(this);
        heart.setText("  ♥");
        heart.setTextColor(0xFFFF375F);
        heart.setTextSize(18);
        top.addView(heart);
        text.addView(top);
        meta = new TextView(this);
        meta.setTextColor(0xB3FFFFFF);
        meta.setTextSize(14);
        meta.setPadding(0, dp(4), 0, 0);
        text.addView(meta);
        TextView hint = new TextView(this);
        hint.setText("▲▼ change channel   ·   hold OK for Favourites");
        hint.setTextColor(0x66FFFFFF);
        hint.setTextSize(12);
        hint.setPadding(0, dp(6), 0, 0);
        text.addView(hint);
        info.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(info, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);

        player = new ExoPlayer.Builder(this)
                // Live TV: start quickly, keep a modest buffer.
                .setLoadControl(new DefaultLoadControl.Builder().setBufferDurationsMs(4000, 30000, 1000, 2000).build())
                .build();
        player.setVideoSurfaceView(surface);
        player.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                spinner.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (state == Player.STATE_READY) status.setText("");
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                nextStream();
            }
        });
        tune(index);
    }

    private void tune(int i) {
        index = (i + list.size()) % list.size();
        streamIndex = 0;
        Catalog.Channel c = list.get(index);
        Catalog.get(this).watched(c);
        name.setText(c.name);
        meta.setText(MainActivity.describe(this, c));
        number.setText(String.valueOf(index + 1));
        heart.setVisibility(Catalog.get(this).isFavourite(c) ? View.VISIBLE : View.GONE);
        Images.get(this).load(c.logo, logo);
        showInfo();
        play();
    }

    private void play() {
        Catalog.Channel c = list.get(index);
        if (streamIndex >= c.streams.size()) {
            spinner.setVisibility(View.GONE);
            status.setText(c.name + " isn't available right now.\nPress ▲ or ▼ for another channel.");
            return;
        }
        Catalog.Stream s = c.streams.get(streamIndex);
        status.setText("");
        Map<String, String> headers = new HashMap<>();
        if (s.referrer != null && !s.referrer.isEmpty()) headers.put("Referer", s.referrer);
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(s.userAgent != null && !s.userAgent.isEmpty() ? s.userAgent : UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(15000)
                .setDefaultRequestProperties(headers);
        MediaItem.Builder item = new MediaItem.Builder().setUri(s.url);
        if (s.url.contains(".m3u8") || s.url.contains("m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        else if (s.url.contains(".mpd")) item.setMimeType(MimeTypes.APPLICATION_MPD);
        player.setMediaSource(new DefaultMediaSourceFactory(http).createMediaSource(item.build()));
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void nextStream() {
        streamIndex++;
        play();
    }

    private void showInfo() {
        handler.removeCallbacks(hideInfo);
        info.animate().alpha(1).setDuration(200).start();
        handler.postDelayed(hideInfo, 5000);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (down) tune(index - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (down) tune(index + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (down && e.getRepeatCount() == 0) okDownAt = e.getEventTime();
                if (down && e.getRepeatCount() > 0 && okDownAt > 0 && e.getEventTime() - okDownAt > 600) {
                    okDownAt = 0;
                    toggleFavourite();
                }
                if (!down && okDownAt > 0) {
                    okDownAt = 0;
                    showInfo();
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (!down) toggleFavourite();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_INFO:
                if (down) showInfo();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void toggleFavourite() {
        Catalog.Channel c = list.get(index);
        boolean now = Catalog.get(this).toggleFavourite(c);
        heart.setVisibility(now ? View.VISIBLE : View.GONE);
        status.setText("");
        android.widget.Toast.makeText(this, now ? "Added to Favourites" : "Removed from Favourites",
                android.widget.Toast.LENGTH_SHORT).show();
        showInfo();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) player.pause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (player != null) player.play();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) player.release();
        super.onDestroy();
    }
}
