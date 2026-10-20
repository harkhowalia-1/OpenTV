package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.Locale;

/**
 * Plays a video or audio link sent from the iPhone or Mac (MP4, HLS and the like). OK or Play/Pause pauses,
 * left and right skip 10 seconds, Back closes it.
 */
public class PlayerActivity extends Activity {

    static final String EXTRA_START = "start";

    private VideoView video;
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideStatus = () -> status.animate().alpha(0f).setDuration(300).start();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(20);
        status.setShadowLayer(8, 0, 2, 0xAA000000);
        float d = getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        slp.setMargins((int) (48 * d), 0, 0, (int) (36 * d));
        root.addView(status, slp);
        setContentView(root);

        Uri uri = getIntent().getData();
        double start = getIntent().getDoubleExtra(EXTRA_START, 0);
        video.setVideoURI(uri);
        video.setOnPreparedListener(mp -> {
            if (start > 0) video.seekTo((int) (start * 1000));
            video.start();
            show(uri != null ? uri.getLastPathSegment() : "");
        });
        video.setOnCompletionListener(mp -> finish());
        video.setOnErrorListener((mp, what, extra) -> {
            show("Can't play this link");
            handler.postDelayed(this::finish, 2500);
            return true;
        });
    }

    private void show(String text) {
        status.setText(text);
        status.setAlpha(1f);
        handler.removeCallbacks(hideStatus);
        handler.postDelayed(hideStatus, 2500);
    }

    private String clock() {
        int s = video.getCurrentPosition() / 1000, total = Math.max(0, video.getDuration() / 1000);
        return String.format(Locale.ROOT, "%d:%02d / %d:%02d", s / 60, s % 60, total / 60, total % 60);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (video.isPlaying()) video.pause(); else video.start();
                show((video.isPlaying() ? "▶  " : "❚❚  ") + clock());
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                video.seekTo(video.getCurrentPosition() + 10_000);
                show("+10s  " + clock());
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                video.seekTo(Math.max(0, video.getCurrentPosition() - 10_000));
                show("−10s  " + clock());
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }
}
