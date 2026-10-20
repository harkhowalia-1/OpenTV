package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

/**
 * Full-screen view of the Mac's screen ({@link ScreenReceiver}). The remote's buttons are sent to the Mac;
 * Home closes it and ends the stream.
 */
public class ScreenActivity extends Activity {

    private static volatile ScreenActivity current;

    private android.widget.ImageView cursor;
    private int hotX, hotY;
    // The newest cursor position from the network thread, applied once per screen refresh.
    private volatile int cx, cy, videoW = 1, videoH = 1;
    private volatile boolean cursorVisible;
    private final java.util.concurrent.atomic.AtomicBoolean cursorPending = new java.util.concurrent.atomic.AtomicBoolean();
    private final Runnable applyCursor = this::applyCursor;

    /** Where the Mac's cursor is, in the video's pixels. */
    static void cursorMoved(int x, int y, boolean visible, int videoW, int videoH) {
        ScreenActivity a = current;
        if (a == null || a.cursor == null) return;
        a.cx = x;
        a.cy = y;
        a.cursorVisible = visible;
        if (videoW > 0) a.videoW = videoW;
        if (videoH > 0) a.videoH = videoH;
        if (a.cursorPending.compareAndSet(false, true)) a.cursor.postOnAnimation(a.applyCursor);
    }

    /** The cursor's current shape, at the video's scale, with its hotspot. */
    private static volatile Object[] lastLook;   // the shape may arrive before this screen opens

    static void cursorLook(android.graphics.Bitmap bmp, int hx, int hy) {
        lastLook = new Object[] {bmp, hx, hy};
        ScreenActivity a = current;
        if (a == null || a.cursor == null) return;
        a.cursor.post(() -> {
            float k = a.cursor.getRootView().getWidth() / (float) Math.max(1, a.videoW);
            if (k <= 0) k = 1;
            a.hotX = Math.round(hx * k);
            a.hotY = Math.round(hy * k);
            a.cursor.setLayoutParams(new android.widget.FrameLayout.LayoutParams(Math.round(bmp.getWidth() * k),
                    Math.round(bmp.getHeight() * k)));
            a.cursor.setImageBitmap(bmp);
            a.applyCursor();
        });
    }

    private void applyCursor() {
        cursorPending.set(false);
        View root = cursor.getRootView();
        float kx = root.getWidth() / (float) Math.max(1, videoW), ky = root.getHeight() / (float) Math.max(1, videoH);
        cursor.setTranslationX(cx * kx - hotX);
        cursor.setTranslationY(cy * ky - hotY);
        cursor.setVisibility(cursorVisible && cursor.getDrawable() != null ? View.VISIBLE : View.INVISIBLE);
    }

    /** The Mac stopped streaming: close and go back to the Home screen (not whatever app was behind). */
    static void finishCurrent() {
        ScreenActivity a = current;
        if (a == null) return;
        a.runOnUiThread(() -> {
            if (a.isFinishing()) return;
            a.startActivity(new android.content.Intent(a, MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            a.finish();
            a.overridePendingTransition(0, android.R.anim.fade_out);
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        SurfaceView v = new SurfaceView(this);
        v.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder h) {
                ScreenReceiver r = ScreenReceiver.current;
                if (r != null) r.attach(h.getSurface());
            }

            @Override
            public void surfaceChanged(SurfaceHolder h, int format, int w, int hgt) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder h) {
                ScreenReceiver r = ScreenReceiver.current;
                if (r != null) r.detach();
            }
        });
        v.setBackgroundColor(0);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.addView(v);
        cursor = new android.widget.ImageView(this);
        cursor.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        cursor.setVisibility(View.INVISIBLE);
        cursor.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        frame.addView(cursor, new android.widget.FrameLayout.LayoutParams(1, 1));
        setContentView(frame);
        Object[] look = lastLook;
        if (look != null) cursorLook((android.graphics.Bitmap) look[0], (int) look[1], (int) look[2]);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Left by Home or another app: stop streaming rather than keep the Mac sending to nothing.
        if (!isChangingConfigurations()) {
            ScreenReceiver r = ScreenReceiver.current;
            if (r != null) r.endFromTv();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        String name = keyName(e.getKeyCode());
        if (name == null) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            ScreenReceiver r = ScreenReceiver.current;
            if (r != null) r.sendKey(name);
        }
        return true;
    }

    private static String keyName(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "right";
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: return "select";
            case KeyEvent.KEYCODE_BACK: return "back";
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return "playpause";
            case KeyEvent.KEYCODE_MEDIA_NEXT: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: return "next";
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: case KeyEvent.KEYCODE_MEDIA_REWIND: return "previous";
            case KeyEvent.KEYCODE_MENU: return "menu";
            default: return null;
        }
    }
}
