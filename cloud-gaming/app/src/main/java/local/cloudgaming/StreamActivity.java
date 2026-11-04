package local.cloudgaming;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Xbox's player, full screen: the game streams in Xbox's own web player, presented as a desktop browser so it
 * serves its controller UI.
 *
 * <p>Input, no pointer needed:
 * <ul>
 * <li>A controller goes straight to the game (the Gamepad API); B never turns into Back.</li>
 * <li>The TV remote is an Xbox controller too: the D-pad is the D-pad, OK is A, Back is B, Play/Pause is Menu,
 * rewind and fast-forward are the bumpers. Hold Back to leave the game.</li>
 * <li>A pointer appears only when it's wanted: the iPhone remote's or a Mac's trackpad, or Microsoft's sign-in
 * pages (made for a mouse), where the D-pad moves it and OK clicks.</li>
 * </ul>
 */
public class StreamActivity extends Activity {

    static final String EXTRA_URL = "url", EXTRA_GAME = "game";

    private static final int CURSOR_STEP = 14;       // px per key event, before acceleration
    private static final int CURSOR_MAX_STEP = 60;
    private static final float SCROLL_AMOUNT = 3f;  // mouse-wheel notches per edge push

    private FrameLayout root;
    private WebView web;
    private CursorView cursor;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        cursor = new CursorView(this);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(cursor, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cursor.visible = false;   // only when it's wanted (see above)
        setContentView(root);
        Account.restoreCookies(this);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setUserAgentString(desktopUserAgent(s.getUserAgentString()));

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);   // Microsoft sign-in hops across domains

        RemotePad.install(web);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                RemotePad.injectIfNeeded(v);
                onHost(url);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                onHost(url);
                Account.saveCookies();
                if (isXbox(url)) Account.probe(StreamActivity.this, v);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // Only DRM-protected media; never camera or microphone.
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(r)) {
                        request.grant(new String[]{r});
                        return;
                    }
                }
                request.deny();
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                // The stream asks for element fullscreen; show it above the page.
                if (customView != null) callback.onCustomViewHidden();
                customView = view;
                customViewCallback = callback;
                root.addView(view, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }

            @Override
            public void onHideCustomView() {
                exitCustomView();
            }
        });

        web.setFocusable(true);
        web.requestFocus();
        String start = getIntent().getStringExtra(EXTRA_URL);
        String game = getIntent().getStringExtra(EXTRA_GAME);
        if (game != null) Xbox.played(this, game);
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(start != null ? start : getString(R.string.start_url));
    }

    /** Xbox's own pages take the remote as a controller; any other page (Microsoft sign-in) gets the pointer. */
    private boolean padMode = true;

    private static boolean isXbox(String url) {
        if (url == null) return true;
        String host = android.net.Uri.parse(url).getHost();
        return host == null || host.endsWith("xbox.com");
    }

    private void onHost(String url) {
        padMode = isXbox(url);
        if (padMode) cursor.hide();
        else cursor.show();
    }

    /** The site's own browser check only lets desktop browsers into the full controller UI. */
    private static String desktopUserAgent(String webViewUa) {
        String chrome = "155.0.0.0";
        Matcher m = Pattern.compile("Chrome/([0-9.]+)").matcher(webViewUa);
        if (m.find()) chrome = m.group(1);
        String major = chrome.split("\\.")[0];
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + chrome + " Safari/537.36 Edg/" + major + ".0.0.0";
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        c.hide(WindowInsets.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private static boolean fromController(int src) {
        return (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean controller = fromController(event.getSource()) && code != KeyEvent.KEYCODE_BACK
                && (KeyEvent.isGamepadButton(code) || isDpad(code));
        if (controller) {
            cursor.hide();
            web.dispatchKeyEvent(event);
            return true;   // swallow even if unhandled, so B / Select never fall back to Back
        }
        // Hold Back to leave; a short press is B (or back, on sign-in pages).
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                backHeld = false;
                handler.postDelayed(holdBack, 700);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(holdBack);
                if (!backHeld) {
                    if (padMode && customView == null) RemotePad.tap(web, RemotePad.B);
                    else onBack();
                }
            }
            return true;
        }
        // The remote as a controller on Xbox's pages.
        int button = padMode ? RemotePad.buttonFor(code) : -1;
        if (button >= 0) {
            cursor.hide();   // the trackpad's pointer steps aside when the remote is used
            if (event.getRepeatCount() == 0) {
                RemotePad.set(web, button, event.getAction() == KeyEvent.ACTION_DOWN);
            }
            return true;
        }
        // The on-screen keyboard sees keys before the activity, so typing is unaffected.
        if (isDpad(code) || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) onRemoteKey(code, event.getRepeatCount());
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (fromController(event.getSource())) {
            cursor.hide();
            web.dispatchGenericMotionEvent(event);
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private static boolean isDpad(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean backHeld;
    private final Runnable holdBack = () -> {
        backHeld = true;
        confirmLeave();
    };

    /** "Leave this game?", as a console asks, so a long press can't end a session by accident. */
    private void confirmLeave() {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.leave_title)
                .setMessage(R.string.leave_message)
                .setPositiveButton(R.string.leave, (d, w) -> finish())
                .setNegativeButton(R.string.keep_playing, null)
                .show();
    }

    private void onRemoteKey(int code, int repeat) {
        if (!cursor.visible) {
            cursor.show();
            return;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            tap(cursor.x, cursor.y);
            return;
        }
        int step = Math.min(CURSOR_MAX_STEP, CURSOR_STEP + repeat * 4);
        int w = root.getWidth(), h = root.getHeight();
        float x = cursor.x, y = cursor.y;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: x -= step; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: x += step; break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (y <= 0) scroll(SCROLL_AMOUNT);
                y -= step;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (y >= h - 1) scroll(-SCROLL_AMOUNT);
                y += step;
                break;
        }
        cursor.moveTo(Math.max(0, Math.min(w - 1, x)), Math.max(0, Math.min(h - 1, y)));
    }

    /**
     * OpenTV's pointer (a Mac's trackpad or the iPhone remote's trackpad), forwarded by OtvOS while this app is in
     * front: moves this cursor smoothly, clicks where it is, and scrolls under it.
     */
    private final android.content.BroadcastReceiver pointer = new android.content.BroadcastReceiver() {
        private float accX, accY;

        @Override
        public void onReceive(Context c, android.content.Intent i) {
            int a = i.getIntExtra("a", 0), b = i.getIntExtra("b", 0);
            switch (String.valueOf(i.getStringExtra("what"))) {
                case "show":
                    cursor.show();
                    break;
                case "move": {
                    if (!cursor.visible) cursor.show();   // the trackpad brings the pointer back
                    int w = root.getWidth(), h = root.getHeight();
                    float x = cursor.x < 0 ? w / 2f : cursor.x, y = cursor.y < 0 ? h / 2f : cursor.y;
                    cursor.moveTo(Math.max(0, Math.min(w - 1, x + a * 1.4f)), Math.max(0, Math.min(h - 1, y + b * 1.4f)));
                    break;
                }
                case "button":
                    if (a == 1) {
                        if (!cursor.visible) cursor.show();
                        tap(cursor.x, cursor.y);
                    }
                    break;
                case "scroll":
                    accY += b;
                    if (Math.abs(accY) >= 30) {
                        scroll(accY > 0 ? 1 : -1);
                        accY = 0;
                    }
                    break;
                default:
                    break;
            }
        }
    };

    private void tap(float x, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        View target = customView != null ? customView : web;
        target.dispatchTouchEvent(down);
        target.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    /** A mouse-wheel event at the cursor, so whichever panel is under it scrolls. */
    private void scroll(float notches) {
        MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties()};
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
        coords[0].x = cursor.x;
        coords[0].y = cursor.y;
        coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, notches);
        long t = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(t, t, MotionEvent.ACTION_SCROLL, 1, props, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        web.dispatchGenericMotionEvent(e);
        e.recycle();
    }

    private void onBack() {
        if (customView != null) {
            exitCustomView();
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            finish();
        }
    }

    private void exitCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
        hideSystemBars();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(pointer);
        } catch (IllegalArgumentException ignored) {
        }
        web.onPause();
        Account.saveCookies();   // keep the sign-in across restarts
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        android.content.IntentFilter f = new android.content.IntentFilter("local.cloudgaming.POINTER");
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(pointer, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(pointer, f);
    }

    @Override
    protected void onDestroy() {
        root.removeAllViews();
        web.destroy();
        super.onDestroy();
    }

    /** A soft white pointer with a dark ring, drawn above the page. Hidden while a controller is in use. */
    private static final class CursorView extends View {
        float x = -1, y = -1;
        boolean visible = true;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

        CursorView(Context ctx) {
            super(ctx);
            fill.setColor(0xEEFFFFFF);
            ring.setColor(0x99000000);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(4f);
            setFocusable(false);
        }

        void moveTo(float nx, float ny) {
            x = nx;
            y = ny;
            invalidate();
        }

        void show() {
            visible = true;
            invalidate();
        }

        void hide() {
            if (!visible) return;
            visible = false;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (x < 0) {   // first layout: start in the middle
                x = getWidth() / 2f;
                y = getHeight() / 2f;
            }
            if (!visible) return;
            c.drawCircle(x, y, 14f, fill);
            c.drawCircle(x, y, 14f, ring);
        }
    }
}
