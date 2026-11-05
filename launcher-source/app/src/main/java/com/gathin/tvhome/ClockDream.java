package com.gathin.tvhome;

import android.service.dreams.DreamService;
import android.view.KeyEvent;

/** OtvOS's screensaver, as the TV's own screensaver (pick it in Settings › Screen Saver). The screensaver itself is {@link Saver}. */
public class ClockDream extends DreamService implements Saver.Host {

    /** The running screensaver (or its preview), so the phone remote can wake the screen like a real remote does. */
    static volatile Saver.Host active;

    private Saver saver;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setFullscreen(true);
        saver = new Saver(this, this);
        saver.attached();
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        active = this;
        saver.started();
    }

    @Override
    public void onDreamingStopped() {
        if (active == this) active = null;
        saver.stopped();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (saver != null) saver.detached();
        super.onDetachedFromWindow();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        return saver != null ? saver.key(e) : super.dispatchKeyEvent(e);
    }

    @Override
    public boolean superDispatchKeyEvent(KeyEvent e) {
        return super.dispatchKeyEvent(e);
    }
}
