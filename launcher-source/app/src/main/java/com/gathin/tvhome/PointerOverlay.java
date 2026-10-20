package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * The Mac's cursor on the TV, Universal Control style: it comes in through the edge facing the Mac, hovering
 * moves the TV's focus to whatever is under it (so TV apps highlight it as if the remote had moved there),
 * clicking selects it, two-finger scrolling (and click-dragging) moves through rows and across shelves the
 * way the remote does, and a two-finger click is the Home button . Leaving through the same edge hands the cursor back to the
 * Mac. (A TV has no touch screen, so Android can't take injected touches here; scrolling uses the TV's own
 * navigation, which every TV app supports.)
 *
 * <p>Smoothness: the cursor has a window and a display thread of its own, so it keeps moving however busy the
 * app below is (including this launcher animating the tile it just focused). Movements are added up and
 * applied once per screen refresh by moving a small cursor view (no redrawing), and finding what's under the
 * cursor runs on another thread. All public methods may be called from any thread.
 */
final class PointerOverlay {

    interface Exit {
        /** The pointer left through the entry edge at this point along it (0..1). */
        void left(float along);
    }

    /** Scroll phases from the Mac: a wheel click, or a trackpad gesture beginning, moving (with its glide) and ending. */
    static final int SCROLL_WHEEL = 0, SCROLL_BEGAN = 1, SCROLL_CHANGED = 2, SCROLL_ENDED = 4;

    private static final float GAIN = 1.4f;          // Mac points to TV pixels
    private static final long HOVER_EVERY_MS = 60;

    private final AccessibilityService service;
    private final WindowManager wm;
    private int width, height;   // set in the constructor; not final because the frame callback reads them
    private final Handler hoverThread;
    /** The cursor window's own UI thread. */
    private final Handler ui;
    private FrameLayout layer;
    private ImageView cursor;
    private float hotX, hotY;                         // the arrow's tip inside the cursor image
    private volatile float x, y;
    private float pendingX, pendingY;
    private boolean frameScheduled;
    private String edge = "bottom";
    private Exit exit;
    private long lastHover;
    private AccessibilityNodeInfo hovered;
    private boolean down;
    private float downX, downY;
    private long downAt;

    // Scrolling: movement adds up, and every STEP of it is one move of the TV's selection.
    private static final float STEP = 55;
    private float scrollAccX, scrollAccY;

    /** A two-finger click (the Mac's right button): one press of the Home button. */
    private final Runnable homePress;

    PointerOverlay(AccessibilityService service, Runnable homePress) {
        this.service = service;
        this.homePress = homePress;
        wm = service.getSystemService(WindowManager.class);
        DisplayMetrics dm = service.getResources().getDisplayMetrics();
        width = dm.widthPixels;
        height = dm.heightPixels;
        HandlerThread t = new HandlerThread("tv-pointer-hover");
        t.start();
        hoverThread = new Handler(t.getLooper());
        HandlerThread u = new HandlerThread("tv-cursor", android.os.Process.THREAD_PRIORITY_DISPLAY);
        u.start();
        ui = new Handler(u.getLooper());
    }

    boolean active() {
        return layer != null;
    }

    void enter(float along, String fromEdge, Exit onExit) {
        ui.post(() -> doEnter(along, fromEdge, onExit));
    }

    void hide() {
        ui.post(this::doHide);
    }

    void move(int dx, int dy) {
        ui.post(() -> doMove(dx, dy));
    }

    void button(int button, boolean isDown) {
        ui.post(() -> doButton(button, isDown));
    }

    void scroll(int dx, int dy, int phase) {
        ui.post(() -> doScroll(dx, dy, phase));
    }

    private void doEnter(float along, String fromEdge, Exit onExit) {
        edge = fromEdge;
        exit = onExit;
        switch (edge) {
            case "left": x = 1; y = along * height; break;
            case "right": x = width - 2; y = along * height; break;
            case "top": x = along * width; y = 1; break;
            default: x = along * width; y = height - 2; break;   // the TV is above the Mac
        }
        if (layer == null) {
            layer = new FrameLayout(service);
            cursor = new ImageView(service);
            cursor.setImageBitmap(cursorBitmap(service));
            layer.addView(cursor, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            wm.addView(layer, lp);
        }
        place();
    }

    private void doHide() {
        if (layer != null) {
            try {
                wm.removeView(layer);
            } catch (RuntimeException ignored) {
            }
        }
        layer = null;
        cursor = null;
        hovered = null;
        down = false;
        pendingX = pendingY = 0;
        scrollAccX = scrollAccY = 0;
    }

    // ---- movement -----------------------------------------------------------------------------------

    /** Adds up movement and applies it on the next screen refresh. */
    private void doMove(int dx, int dy) {
        if (layer == null) return;
        pendingX += dx * GAIN;
        pendingY += dy * GAIN;
        if (!frameScheduled) {
            frameScheduled = true;
            Choreographer.getInstance().postFrameCallback(frame);
        }
    }

    private final Choreographer.FrameCallback frame = t -> {
        frameScheduled = false;
        if (layer == null) return;
        x += pendingX;
        y += pendingY;
        pendingX = pendingY = 0;
        // Pushing back out through the edge we came in by returns the cursor to the Mac.
        float along = -1;
        if (edge.equals("bottom") && y > height) along = x / width;
        else if (edge.equals("top") && y < 0) along = x / width;
        else if (edge.equals("left") && x < 0) along = y / height;
        else if (edge.equals("right") && x > width) along = y / height;
        if (along >= 0 && !down) {
            doHide();
            if (exit != null) exit.left(along);
            return;
        }
        x = clampX(x);
        y = clampY(y);
        place();
        long now = SystemClock.uptimeMillis();
        if (!down && now - lastHover > HOVER_EVERY_MS) {
            lastHover = now;
            hoverLater(0);
        }
        // And once more where the cursor comes to rest, so focus always ends up under it.
        hoverLater(HOVER_EVERY_MS);
    };

    private void place() {
        if (cursor == null) return;
        cursor.setTranslationX(x - hotX);
        cursor.setTranslationY(y - hotY);
    }

    // ---- buttons ------------------------------------------------------------------------------------

    private void doButton(int button, boolean isDown) {
        if (layer == null) return;
        if (button == 1) {          // right button (two-finger click or tap): Home
            if (isDown) homePress.run();
            return;
        }
        if (isDown) {
            down = true;
            downX = x;
            downY = y;
            downAt = SystemClock.uptimeMillis();
            return;
        }
        if (!down) return;
        down = false;
        long held = SystemClock.uptimeMillis() - downAt;
        float moved = (float) Math.hypot(x - downX, y - downY);
        final float px = x, py = y;
        if (moved > 12) {
            // A click-drag moves through the content like a finger would: drag up to go down.
            steps(downX - px, downY - py);
        } else if (held > 550) {
            // Long click: the TV's own long-press (options, move tile).
            hoverThread.post(() -> {
                AccessibilityNodeInfo n = nodeAt(px, py);
                if (n != null) n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
            });
        } else {
            hoverThread.post(() -> click(px, py));
        }
    }

    // ---- scrolling ----------------------------------------------------------------------------------

    /**
     * Two-finger scrolling (and the mouse wheel): fingers moving up show what's below, so the selection moves
     * down one row for every STEP of movement; sideways moves along a shelf. The trackpad's glide after a
     * flick keeps it going, so a quick flick skims several rows and a slow push moves one.
     */
    private void doScroll(int dx, int dy, int phase) {
        if (layer == null) return;
        if (phase == SCROLL_BEGAN) scrollAccX = scrollAccY = 0;
        float k = phase == SCROLL_WHEEL ? 4 : 1;   // a wheel click is one decent step
        steps(-dx * k, -dy * k);
        if (phase == SCROLL_ENDED) scrollAccX = scrollAccY = 0;
    }

    /** Moves the selection: positive y is down, positive x is right, one move per STEP. */
    private void steps(float x, float y) {
        scrollAccX += x;
        scrollAccY += y;
        while (Math.abs(scrollAccY) >= STEP) {
            boolean downward = scrollAccY > 0;
            service.performGlobalAction(downward ? AccessibilityService.GLOBAL_ACTION_DPAD_DOWN
                    : AccessibilityService.GLOBAL_ACTION_DPAD_UP);
            scrollAccY -= downward ? STEP : -STEP;
        }
        while (Math.abs(scrollAccX) >= STEP) {
            boolean right = scrollAccX > 0;
            service.performGlobalAction(right ? AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT
                    : AccessibilityService.GLOBAL_ACTION_DPAD_LEFT);
            scrollAccX -= right ? STEP : -STEP;
        }
    }

    // ---- hover and click, off the UI thread --------------------------------------------------------

    private final Runnable hoverTask = this::hover;

    private void hoverLater(long delay) {
        hoverThread.removeCallbacks(hoverTask);
        if (delay == 0) hoverThread.post(hoverTask);
        else hoverThread.postDelayed(hoverTask, delay);
    }

    /** Moves the TV's focus to the focusable item under the cursor, so the app highlights it. */
    private void hover() {
        if (down) return;
        AccessibilityNodeInfo n = nodeAt(x, y);
        if (n == null || n.equals(hovered)) return;
        hovered = n;
        if (!n.isFocused()) n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
    }

    /**
     * A click: selects the item under the cursor and presses OK on it, exactly like the remote, which every TV
     * app (and this launcher, which opens apps only on a real OK press) responds to.
     */
    private void click(float px, float py) {
        AccessibilityNodeInfo n = nodeAt(px, py);
        if (n != null && n.isFocusable()) {
            boolean moved = !n.isFocused() && n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            // Give the app a moment to move its selection before OK.
            hoverThread.postDelayed(() -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER),
                    moved ? 80 : 0);
            return;
        }
        if (n != null && n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
        // Nothing selectable under the cursor: OK on whatever is selected.
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER);
    }

    /** The smallest focusable (or clickable) item under the point in the active window. */
    private AccessibilityNodeInfo nodeAt(float px, float py) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return null;
        return deepest(root, (int) px, (int) py, new Rect());
    }

    private AccessibilityNodeInfo deepest(AccessibilityNodeInfo node, int px, int py, Rect r) {
        node.getBoundsInScreen(r);
        if (!r.contains(px, py) || !node.isVisibleToUser()) return null;
        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c == null) continue;
            AccessibilityNodeInfo hit = deepest(c, px, py, r);
            if (hit != null) return hit;
        }
        return node.isFocusable() || node.isClickable() ? node : null;
    }

    private float clampX(float v) {
        return Math.max(0, Math.min(width - 1, v));
    }

    private float clampY(float v) {
        return Math.max(0, Math.min(height - 1, v));
    }

    /** A macOS-style arrow: white with a dark outline and a soft shadow, drawn once. */
    private Bitmap cursorBitmap(Context c) {
        float s = c.getResources().getDisplayMetrics().density * 1.1f;
        float pad = 6 * s;
        hotX = pad;
        hotY = pad;
        Path arrow = new Path();
        arrow.moveTo(0, 0);
        arrow.lineTo(0, 17 * s);
        arrow.lineTo(4.2f * s, 13.2f * s);
        arrow.lineTo(7 * s, 19.5f * s);
        arrow.lineTo(9.8f * s, 18.3f * s);
        arrow.lineTo(7.1f * s, 12.2f * s);
        arrow.lineTo(12.6f * s, 12.2f * s);
        arrow.close();
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(0xFFFFFFFF);
        fill.setShadowLayer(3 * s, 0, 1.5f * s, 0x73000000);
        Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        outline.setColor(0xFF000000);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(1.2f * s);
        outline.setStrokeJoin(Paint.Join.ROUND);
        Bitmap b = Bitmap.createBitmap((int) (13 * s + 2 * pad), (int) (20 * s + 2 * pad), Bitmap.Config.ARGB_8888);
        Canvas bc = new Canvas(b);
        bc.translate(pad, pad);
        bc.drawPath(arrow, fill);
        bc.drawPath(arrow, outline);
        return b;
    }
}
