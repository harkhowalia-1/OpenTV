package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * What's behind the glass, frosted: a small, blurred copy of the screen behind the dock, the status pill and
 * Control Center, which {@link LiquidGlass} draws through itself (saturated, under its smoked tint), the way iOS
 * and tvOS materials blur what's behind them. Android TV has no system backdrop blur, so OtvOS makes its own:
 * Home keeps this up to date from its wallpaper (each frame of a live aerial), and from its whole screen while a
 * panel such as Control Center is open over it.
 *
 * <p>The blur follows Apple's own frosted bars (saturate 180%, blur 20pt): a ~20pt Gaussian, approximated by
 * three box blurs on a picture an eighth of the screen's size, which keeps it cheap enough to run on every frame.
 */
final class Backdrop {

    static final int SCALE = 8;   // the frosted copy is 1/8 of the screen each way
    /** Home's own glass (dock, status pill): the wallpaper behind it. */
    static final int HOME = 0;
    /** Panels over Home (Control Center): all of Home behind them, apps included. */
    static final int PANEL = 1;

    private static final Bitmap[] frost = new Bitmap[2];
    private static int screenW, screenH;
    private static final List<WeakReference<View>> hosts = new ArrayList<>();
    private static int[] px, tmp;

    private Backdrop() {
    }

    /** The current frosted picture of the screen, or null (then glass looks as it did: smoked, no blur). */
    static Bitmap frost(int layer) {
        return frost[layer];
    }

    static int screenWidth() {
        return screenW;
    }

    static int screenHeight() {
        return screenH;
    }

    /** A glass surface that draws the backdrop: redrawn whenever the backdrop changes. */
    static void watch(View host) {
        for (WeakReference<View> r : hosts) if (r.get() == host) return;
        hosts.add(new WeakReference<>(host));
    }

    /**
     * New content behind the glass: small (screen / {@link #SCALE}), blurred here in place. Keeps the bitmap.
     * Call on the main thread.
     */
    static void update(int layer, Bitmap small, int screenWidth, int screenHeight, float density) {
        blur(small, Math.max(1, Math.round(20f * density / SCALE * 0.9f)));
        Bitmap old = frost[layer];
        frost[layer] = small;
        screenW = screenWidth;
        screenH = screenHeight;
        if (old != null && old != small) old.recycle();
        invalidate();
    }

    /** Nothing frosted behind the glass any more (Home went away). */
    static void clear(int layer) {
        if (frost[layer] == null) return;
        Bitmap old = frost[layer];
        frost[layer] = null;
        invalidate();
        old.recycle();
    }

    private static void invalidate() {
        for (int i = hosts.size() - 1; i >= 0; i--) {
            View v = hosts.get(i).get();
            if (v == null) hosts.remove(i);
            else v.invalidate();
        }
    }

    /** Three box blurs of this radius, each way: close to a Gaussian (sigma ≈ radius). */
    private static void blur(Bitmap b, int r) {
        int w = b.getWidth(), h = b.getHeight(), n = w * h;
        if (px == null || px.length < n) {
            px = new int[n];
            tmp = new int[n];
        }
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            box(px, tmp, w, h, r, true);
            box(tmp, px, w, h, r, false);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    /** One box blur along rows (horizontal) or columns, edges clamped. */
    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h, step = horizontal ? 1 : w;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = horizontal ? line * w : line;
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[base + Math.min(len - 1, Math.max(0, i)) * step];
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[base + i * step] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = in[base + Math.min(len - 1, i + r + 1) * step];
                int sub = in[base + Math.max(0, i - r) * step];
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }
}
