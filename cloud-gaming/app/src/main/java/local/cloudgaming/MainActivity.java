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
 * Full-screen cloud gaming on a TV: one WebView on the cloud gaming site, presented as a desktop
 * browser so the site serves its controller-friendly UI.
 *
 * Input: controller buttons and sticks go straight to the page (the Gamepad API), and are always
 * consumed so B never turns into Back. The TV remote drives an on-screen cursor instead: D-pad
 * moves it, OK taps, pushing past the top or bottom edge scrolls, and Back goes back / exits.
 */
public class MainActivity extends Activity {

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
        setContentView(root);

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

        web.setWebViewClient(new WebViewClient());
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
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(getString(R.string.start_url));
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
        // The on-screen keyboard sees keys before the activity, so typing is unaffected.
        if (isDpad(code) || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) onRemoteKey(code, event.getRepeatCount());
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) onBack();
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
        web.onPause();
        CookieManager.getInstance().flush();   // keep the sign-in across restarts
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
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
