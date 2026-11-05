package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Preview: the screensaver, now, as a page of its own (Google TV doesn't let apps start the real one). It looks and
 * behaves the same; leaving it, or any key other than the ones the screensaver uses, goes back.
 */
public class ScreenSaverPreview extends Activity implements Saver.Host {

    private Saver saver;
    private boolean interactive, started;

    static void start(Context c) {
        c.startActivity(new Intent(c, ScreenSaverPreview.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        saver = new Saver(this, this);
        saver.attached();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ClockDream.active = this;
        if (!started) {
            started = true;
            saver.started();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (ClockDream.active == this) ClockDream.active = null;
        finish();   // a preview ends once you leave it
    }

    @Override
    protected void onDestroy() {
        saver.stopped();
        saver.detached();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean volume = k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE;
        if (!interactive && !volume) {
            // The clock: any key ends it, as it would wake the TV.
            if (e.getAction() == KeyEvent.ACTION_UP) finish();
            return true;
        }
        return saver.key(e);
    }

    @Override
    public boolean superDispatchKeyEvent(KeyEvent e) {
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void setInteractive(boolean interactive) {
        this.interactive = interactive;
    }

    @Override
    public void setScreenBright(boolean bright) {
    }
}
