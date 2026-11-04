package com.gathin.otvbrowser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Browser, in Safari's style and made for the TV.
 *
 * <ul>
 * <li>Start page: Favorites (with each site's own icon, as Safari shows them) and Frequently Visited.</li>
 * <li>A compact glass bar: back, forward, the address (the site's name, with a lock when it's secure), reload,
 * add to Favorites, and tabs. It tucks away while you browse and comes back when you move to the top.</li>
 * <li>On a page the remote moves a pointer: OK clicks, the edges scroll, Play/Pause brings the bar back,
 * rewind and fast-forward page up and down. The iPhone remote's trackpad and keyboard work too.</li>
 * <li>Tabs, shown as a grid of pages; only the last few stay loaded, to keep the TV fast.</li>
 * </ul>
 */
public class BrowserActivity extends Activity {

    private static final int LIVE_TABS = 3;
    private static final String HOME_SCHEME = "about:start";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService icons = Executors.newFixedThreadPool(3);
    private Browser data;
    private float d;

    private FrameLayout root, pages;
    private ScrollView startPage;
    private LinearLayout bar;
    private TextView address;
    private ImageView lock, star;
    private TextView tabCount;
    private View progress;
    private ImageView backBtn, forwardBtn;
    private Cursor cursor;
    private View overlay;   // the address screen or the tab grid, when one is open
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    private static final class Tab {
        WebView web;
        String url, title;
        Bitmap thumb;
        long used;
    }

    private final List<Tab> tabs = new ArrayList<>();
    private int cur;
    private boolean webMode;

    private int dp(float v) {
        return Math.round(v * d);
    }

    // ---- set-up -------------------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        data = new Browser(this);
        CookieManager.getInstance().setAcceptCookie(true);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF1C1C1E);
        // On a page the pointer has the remote: the page area holds focus, so no button looks chosen.
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        pages = new FrameLayout(this);
        root.addView(pages, full());
        startPage = new ScrollView(this);
        startPage.setFillViewport(true);
        startPage.setClipToPadding(false);
        root.addView(startPage, full());
        cursor = new Cursor(this);
        root.addView(cursor, full());
        root.addView(buildBar(), barParams());
        setContentView(root);

        List<String> saved = data.savedTabs();
        for (String u : saved) {
            Tab t = new Tab();
            t.url = u.isEmpty() ? null : u;
            tabs.add(t);
        }
        if (tabs.isEmpty()) tabs.add(new Tab());
        cur = Math.max(0, Math.min(tabs.size() - 1, data.savedCurrent()));
        if (!handle(getIntent())) showTab(cur);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handle(i);
    }

    /** A link from the iPhone or Mac ("Send to TV"), another app, or a search: opens in a new tab. */
    private boolean handle(Intent i) {
        String url = null;
        if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) url = i.getData().toString();
        if (Intent.ACTION_WEB_SEARCH.equals(i.getAction())) url = Browser.addressFor(String.valueOf(i.getStringExtra("query")));
        if (url == null) return false;
        Tab current = tabs.isEmpty() ? null : tabs.get(cur);
        if (current != null && current.url == null) {
            showTab(cur);
            load(url);
        } else {
            newTab(url);
        }
        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        List<String> urls = new ArrayList<>();
        for (Tab t : tabs) urls.add(t.web != null && t.web.getUrl() != null ? t.web.getUrl() : t.url);
        data.saveTabs(urls, cur);
        for (Tab t : tabs) if (t.web != null) t.web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!tabs.isEmpty() && tabs.get(cur).web != null) tabs.get(cur).web.onResume();
    }

    @Override
    protected void onDestroy() {
        icons.shutdownNow();
        for (Tab t : tabs) if (t.web != null) t.web.destroy();
        super.onDestroy();
    }

    // ---- the bar ----------------------------------------------------------------------------------------------

    private View buildBar() {
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0xE6262629);
        glass.setCornerRadius(dp(30));
        glass.setStroke(Math.max(1, dp(0.75f)), 0x26FFFFFF);
        bar.setBackground(glass);
        bar.setElevation(dp(10));

        backBtn = barButton(R.drawable.ic_back, "Back", v -> goBack());
        forwardBtn = barButton(R.drawable.ic_forward, "Forward", v -> {
            WebView w = web();
            if (w != null && w.canGoForward()) w.goForward();
        });
        bar.addView(backBtn);
        bar.addView(forwardBtn);

        // The address: the site's name with a lock when secure; OK opens the address screen.
        FrameLayout field = new FrameLayout(this);
        LinearLayout inner = new LinearLayout(this);
        inner.setGravity(Gravity.CENTER);
        lock = new ImageView(this);
        lock.setImageResource(R.drawable.ic_lock);
        inner.addView(lock, new LinearLayout.LayoutParams(dp(15), dp(15)));
        address = new TextView(this);
        address.setTextSize(17);
        address.setSingleLine(true);
        address.setEllipsize(TextUtils.TruncateAt.END);
        address.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        address.setPadding(dp(8), 0, 0, 0);
        inner.addView(address);
        field.addView(inner, new FrameLayout.LayoutParams(-1, -1));
        progress = new View(this);
        progress.setBackgroundColor(0xFF0A84FF);
        progress.setPivotX(0);
        progress.setScaleX(0);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM);
        plp.setMargins(dp(14), 0, dp(14), dp(2));
        field.addView(progress, plp);
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setCornerRadius(dp(22));
        fieldBg.setColor(0x1FFFFFFF);
        field.setBackground(fieldBg);
        field.setFocusable(true);
        field.setOnClickListener(v -> openAddress());
        field.setOnFocusChangeListener((v, f) -> {
            fieldBg.setColor(f ? 0xFFFFFFFF : 0x1FFFFFFF);
            int c = f ? 0xFF1C1C1E : 0xFFFFFFFF;
            address.setTextColor(c);
            lock.setImageTintList(ColorStateList.valueOf(f ? 0xFF1C1C1E : 0xB3FFFFFF));
            v.animate().scaleX(f ? 1.03f : 1f).scaleY(f ? 1.03f : 1f).setDuration(150).start();
        });
        address.setTextColor(0xFFFFFFFF);
        lock.setImageTintList(ColorStateList.valueOf(0xB3FFFFFF));
        field.setOnKeyListener(barKeys);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(dp(420), dp(44));
        flp.setMargins(dp(10), 0, dp(10), 0);
        bar.addView(field, flp);

        bar.addView(barButton(R.drawable.ic_reload, "Reload", v -> {
            WebView w = web();
            if (w != null) w.reload();
        }));
        star = barButton(R.drawable.ic_star, "Add to Favorites", v -> toggleFavorite());
        bar.addView(star);
        // Tabs: the count of open tabs, in a rounded square like Safari's.
        FrameLayout tabsBtn = new FrameLayout(this);
        tabCount = new TextView(this);
        tabCount.setTextSize(13);
        tabCount.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tabCount.setGravity(Gravity.CENTER);
        GradientDrawable box = new GradientDrawable();
        box.setCornerRadius(dp(6));
        box.setStroke(dp(2), 0xFFFFFFFF);
        tabCount.setBackground(box);
        tabCount.setTextColor(0xFFFFFFFF);
        tabsBtn.addView(tabCount, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        styleButton(tabsBtn, v -> showTabs());
        tabsBtn.setOnFocusChangeListener((v, f) -> {
            ((GradientDrawable) v.getBackground()).setColor(f ? 0xFFFFFFFF : 0x00000000);
            tabCount.setTextColor(f ? 0xFF1C1C1E : 0xFFFFFFFF);
            box.setStroke(dp(2), f ? 0xFF1C1C1E : 0xFFFFFFFF);
            v.animate().scaleX(f ? 1.1f : 1f).scaleY(f ? 1.1f : 1f).setDuration(150).start();
        });
        tabsBtn.setContentDescription("Tabs");
        bar.addView(tabsBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        return bar;
    }

    private FrameLayout.LayoutParams barParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(20);
        return lp;
    }

    /** Down from the bar goes into the page. */
    private final View.OnKeyListener barKeys = (v, code, e) -> {
        if (code == KeyEvent.KEYCODE_DPAD_DOWN && e.getAction() == KeyEvent.ACTION_DOWN && currentHasPage()) {
            enterWebMode();
            return true;
        }
        return false;
    };

    private ImageView barButton(int icon, String label, View.OnClickListener click) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        b.setContentDescription(label);
        styleButton(b, click);
        b.setOnFocusChangeListener((v, f) -> {
            ((GradientDrawable) v.getBackground()).setColor(f ? 0xFFFFFFFF : 0x00000000);
            b.setImageTintList(ColorStateList.valueOf(f ? 0xFF1C1C1E : (v.isEnabled() ? 0xFFFFFFFF : 0x59FFFFFF)));
            v.animate().scaleX(f ? 1.1f : 1f).scaleY(f ? 1.1f : 1f).setDuration(150).start();
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return b;
    }

    private void styleButton(View b, View.OnClickListener click) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x00000000);
        b.setBackground(bg);
        b.setFocusable(true);
        b.setOnClickListener(click);
        b.setOnKeyListener(barKeys);
    }

    private void updateBar() {
        Tab t = tabs.get(cur);
        WebView w = t.web;
        String url = w != null && w.getUrl() != null ? w.getUrl() : t.url;
        boolean page = url != null;
        address.setText(page ? Browser.domain(url) : getString(R.string.search_hint));
        address.setAlpha(page ? 1f : 0.6f);
        lock.setVisibility(page && url.startsWith("https") ? View.VISIBLE : View.GONE);
        star.setImageResource(page && data.isFavorite(url) ? R.drawable.ic_star_filled : R.drawable.ic_star);
        boolean canBack = w != null && (w.canGoBack() || page);
        backBtn.setAlpha(canBack ? 1f : 0.35f);
        forwardBtn.setAlpha(w != null && w.canGoForward() ? 1f : 0.35f);
        tabCount.setText(String.valueOf(tabs.size()));
    }

    private boolean barShown = true;

    private void showBar(boolean show) {
        if (show == barShown) return;
        barShown = show;
        bar.animate().translationY(show ? 0 : -(bar.getHeight() + dp(30))).alpha(show ? 1f : 0f).setDuration(220).start();
    }

    // ---- tabs -------------------------------------------------------------------------------------------------

    private WebView web() {
        return tabs.isEmpty() ? null : tabs.get(cur).web;
    }

    private boolean currentHasPage() {
        Tab t = tabs.get(cur);
        return t.web != null && t.url != null;
    }

    private void newTab(String url) {
        Tab t = new Tab();
        t.url = url;
        tabs.add(t);
        showTab(tabs.size() - 1);
    }

    /** Shows a tab: its page (loaded again if it was put to sleep), or the start page for a new tab. */
    private void showTab(int i) {
        if (!tabs.isEmpty() && cur < tabs.size() && tabs.get(cur).web != null && cur != i) snapshot(tabs.get(cur));
        cur = i;
        Tab t = tabs.get(i);
        t.used = SystemClock.uptimeMillis();
        pages.removeAllViews();
        if (t.url == null) {
            showStart();
        } else {
            if (t.web == null) {
                t.web = makeWebView(t);
                t.web.loadUrl(t.url);
            }
            pages.addView(t.web, full());
            t.web.onResume();
            startPage.setVisibility(View.GONE);
            enterWebMode();
        }
        trimTabs();
        updateBar();
    }

    /** Only the most recently used tabs keep their page loaded; the rest reload when you go back to them. */
    private void trimTabs() {
        List<Tab> live = new ArrayList<>();
        for (Tab t : tabs) if (t.web != null) live.add(t);
        live.sort((a, b) -> Long.compare(b.used, a.used));
        for (int i = LIVE_TABS; i < live.size(); i++) {
            Tab t = live.get(i);
            if (t.web.getUrl() != null) t.url = t.web.getUrl();
            t.web.destroy();
            t.web = null;
        }
    }

    private void closeTab(int i) {
        Tab t = tabs.remove(i);
        if (t.web != null) {
            pages.removeView(t.web);
            t.web.destroy();
        }
        if (tabs.isEmpty()) tabs.add(new Tab());
        cur = Math.min(cur >= i && cur > 0 ? cur - 1 : cur, tabs.size() - 1);
    }

    private void snapshot(Tab t) {
        if (t.web == null || t.web.getWidth() == 0) return;
        try {
            Bitmap b = Bitmap.createBitmap(t.web.getWidth() / 4, t.web.getHeight() / 4, Bitmap.Config.RGB_565);
            Canvas c = new Canvas(b);
            c.scale(0.25f, 0.25f);
            c.translate(-t.web.getScrollX(), -t.web.getScrollY());
            t.web.draw(c);
            if (t.thumb != null) t.thumb.recycle();
            t.thumb = b;
        } catch (Throwable ignored) {
        }
    }

    // ---- pages ------------------------------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private WebView makeWebView(Tab t) {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(desktopAgent(s.getUserAgentString()));   // full websites, as Safari asks for on iPad
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.setBackgroundColor(0xFFFFFFFF);
        w.setFocusable(true);
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String scheme = u.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme) || "about".equals(scheme)) return false;
                try {   // app links (market:, intent:, mailto:) open in their app where there is one
                    startActivity(Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap icon) {
                t.url = url;
                if (tabs.indexOf(t) == cur) updateBar();
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                t.url = url;
                data.visited(url, v.getTitle());
                if (tabs.indexOf(t) == cur) updateBar();
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                if (tabs.indexOf(t) != cur) return;
                progress.animate().scaleX(p / 100f).setDuration(150).start();
                progress.animate().alpha(p >= 100 ? 0f : 1f).setStartDelay(p >= 100 ? 300 : 0).setDuration(200).start();
            }

            @Override
            public void onReceivedTitle(WebView v, String title) {
                t.title = title;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) cb.onCustomViewHidden();
                customView = view;
                customCallback = cb;
                root.addView(view, full());
                bar.setVisibility(View.GONE);
                cursor.hide();
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }

            @Override
            public void onPermissionRequest(PermissionRequest r) {
                // Protected (DRM) video only; never the camera or microphone.
                for (String res : r.getResources()) {
                    if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(res)) {
                        r.grant(new String[]{res});
                        return;
                    }
                }
                r.deny();
            }
        });
        return w;
    }

    private static String desktopAgent(String ua) {
        String chrome = "130.0.0.0";
        Matcher m = Pattern.compile("Chrome/([0-9.]+)").matcher(ua);
        if (m.find()) chrome = m.group(1);
        return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + chrome + " Safari/537.36";
    }

    private void load(String url) {
        Tab t = tabs.get(cur);
        t.url = url;
        if (t.web == null) {
            t.web = makeWebView(t);
            pages.removeAllViews();
            pages.addView(t.web, full());
        }
        startPage.setVisibility(View.GONE);
        t.web.loadUrl(url);
        enterWebMode();
        updateBar();
    }

    private void goBack() {
        WebView w = web();
        if (w != null && w.canGoBack()) {
            w.goBack();
        } else if (currentHasPage()) {
            Tab t = tabs.get(cur);   // back past the first page: the start page, as a new tab shows
            t.web.destroy();
            t.web = null;
            t.url = null;
            pages.removeAllViews();
            showStart();
            updateBar();
        }
    }

    private void exitFullscreen() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        bar.setVisibility(View.VISIBLE);
    }

    private void toggleFavorite() {
        Tab t = tabs.get(cur);
        String url = web() != null && web().getUrl() != null ? web().getUrl() : t.url;
        if (url == null) return;
        boolean was = data.isFavorite(url);
        data.toggleFavorite(url, t.title);
        Toast.makeText(this, was ? R.string.removed_favorite : R.string.added_favorite, Toast.LENGTH_SHORT).show();
        updateBar();
    }

    // ---- the pointer, on pages ------------------------------------------------------------------------------

    private void enterWebMode() {
        webMode = true;
        root.requestFocus();
        cursor.show();
        main.removeCallbacks(tuckBar);
        main.postDelayed(tuckBar, 1800);
    }

    private void leaveWebMode() {
        webMode = false;
        cursor.hide();
        showBar(true);
        View field = (View) address.getParent().getParent();
        field.requestFocus();
    }

    /** The bar tucks away while you're on the page (as Safari's does as you scroll). */
    private final Runnable tuckBar = () -> {
        if (webMode && cursor.y > bar.getBottom() + dp(40)) showBar(false);
    };

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BACK) {
            if (!down) onBackKey();
            return true;
        }
        if (overlay != null || !webMode || customView != null && code != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            return super.dispatchKeyEvent(e);
        }
        // A text box on the page has the keyboard: keys type.
        WebView w = web();
        if (w == null) return super.dispatchKeyEvent(e);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) moveCursor(code, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!down) tap(cursor.x, cursor.y);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MENU:
                if (down) leaveWebMode();
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) w.pageDown(false);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) w.pageUp(false);
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void onBackKey() {
        if (overlay != null) {
            closeOverlay();
        } else if (customView != null) {
            exitFullscreen();
        } else if (webMode || currentHasPage()) {
            goBack();
            if (!currentHasPage()) leaveWebMode();
        } else {
            finish();
        }
    }

    private void moveCursor(int code, int repeat) {
        WebView w = web();
        int step = Math.min(dp(34), dp(9) + repeat * dp(3));
        float x = cursor.x, y = cursor.y;
        int width = root.getWidth(), height = root.getHeight();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (y <= dp(10)) {
                    showBar(true);   // at the top edge the bar is always there to reach
                    if (w.canScrollVertically(-1)) scroll(0, 4);
                    else leaveWebMode();   // the top of the page: up to the bar
                    return;
                }
                y -= step;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (y >= height - dp(10)) {
                    scroll(0, -4);
                    return;
                }
                y += step;
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (x <= dp(10)) {
                    scroll(4, 0);
                    return;
                }
                x -= step;
                break;
            default:
                if (x >= width - dp(10)) {
                    scroll(-4, 0);
                    return;
                }
                x += step;
                break;
        }
        cursor.moveTo(Math.max(0, Math.min(width - 1, x)), Math.max(0, Math.min(height - 1, y)));
        // Near the top, the bar comes back; further down it tucks away.
        if (cursor.y < bar.getBottom() + dp(24)) {
            showBar(true);
        } else if (barShown) {
            main.removeCallbacks(tuckBar);
            main.postDelayed(tuckBar, 1200);
        }
    }

    private void tap(float x, float y) {
        WebView w = web();
        if (w == null) return;
        // A click on the bar (when it's showing) works the bar, not the page under it.
        if (barShown && y < bar.getBottom() && x > bar.getLeft() && x < bar.getRight()) {
            leaveWebMode();
            return;
        }
        long t = SystemClock.uptimeMillis();
        MotionEvent dn = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0);
        dn.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        View target = customView != null ? customView : w;
        target.dispatchTouchEvent(dn);
        target.dispatchTouchEvent(up);
        dn.recycle();
        up.recycle();
        cursor.pulse();
    }

    /** A mouse wheel at the pointer, so the part of the page under it scrolls (smoothly, as a trackpad would). */
    private void scroll(float h, float v) {
        MotionEvent.PointerProperties[] p = {new MotionEvent.PointerProperties()};
        p[0].id = 0;
        p[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] c = {new MotionEvent.PointerCoords()};
        c[0].x = cursor.x;
        c[0].y = cursor.y;
        c[0].setAxisValue(MotionEvent.AXIS_VSCROLL, v);
        c[0].setAxisValue(MotionEvent.AXIS_HSCROLL, -h);
        long t = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(t, t, MotionEvent.ACTION_SCROLL, 1, p, c, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        WebView w = web();
        if (w != null) w.dispatchGenericMotionEvent(e);
        e.recycle();
    }

    // ---- start page ------------------------------------------------------------------------------------------

    private void showStart() {
        webMode = false;
        cursor.hide();
        showBar(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(80), dp(110), dp(80), dp(60));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF2B2B33, 0xFF1C1C1E, 0xFF151517});
        col.setBackground(bg);
        View first = section(col, getString(R.string.favorites), data.favorites, true);
        List<Browser.Site> freq = data.frequent(12);
        if (!freq.isEmpty()) section(col, getString(R.string.frequent), freq, false);
        TextView hint = new TextView(this);
        hint.setText(R.string.favorite_hint);
        hint.setTextColor(0x59FFFFFF);
        hint.setTextSize(13);
        hint.setPadding(0, dp(28), 0, 0);
        col.addView(hint);
        startPage.removeAllViews();
        startPage.addView(col, new ScrollView.LayoutParams(-1, -2));
        startPage.setVisibility(View.VISIBLE);
        startPage.scrollTo(0, 0);
        View field = (View) address.getParent().getParent();
        field.requestFocus();
        if (first != null) first.setNextFocusUpId(View.NO_ID);
    }

    /** A row of site tiles (Favorites or Frequently Visited), wrapping like Safari's grid. */
    private View section(LinearLayout col, String title, List<Browser.Site> sites, boolean favorites) {
        TextView h = new TextView(this);
        h.setText(title);
        h.setTextColor(0xFFFFFFFF);
        h.setTextSize(22);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        h.setPadding(0, dp(14), 0, dp(14));
        col.addView(h);
        View first = null;
        LinearLayout row = null;
        int perRow = 6;
        for (int i = 0; i < sites.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                col.addView(row);
            }
            Browser.Site s = sites.get(i);
            View tile = siteTile(s, favorites);
            row.addView(tile);
            if (first == null) first = tile;
        }
        return first;
    }

    private View siteTile(Browser.Site s, boolean favorite) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setFocusable(true);
        box.setClipChildren(false);
        FrameLayout card = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(0xFF3A3A3C);
        card.setBackground(bg);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(18));
            }
        });
        card.setClipToOutline(true);
        // The site's initial until its icon arrives.
        String dom = Browser.domain(s.url);
        TextView letter = new TextView(this);
        letter.setText(dom.isEmpty() ? "?" : dom.substring(0, 1).toUpperCase());
        letter.setTextColor(0xFFFFFFFF);
        letter.setTextSize(30);
        letter.setGravity(Gravity.CENTER);
        letter.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        GradientDrawable mono = new GradientDrawable();
        mono.setColor(monoColor(dom));
        letter.setBackground(mono);
        card.addView(letter, new FrameLayout.LayoutParams(-1, -1));
        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(icon, new FrameLayout.LayoutParams(-1, -1));
        loadIcon(s.url, icon);
        box.addView(card, new LinearLayout.LayoutParams(dp(92), dp(92)));
        TextView name = new TextView(this);
        name.setText(s.title == null || s.title.isEmpty() ? dom : s.title);
        name.setTextColor(0xB3FFFFFF);
        name.setTextSize(13);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(8), 0, 0);
        box.addView(name, new LinearLayout.LayoutParams(dp(104), -2));
        box.setOnFocusChangeListener((v, f) -> {
            card.animate().scaleX(f ? 1.12f : 1f).scaleY(f ? 1.12f : 1f).translationZ(f ? dp(14) : 0).setDuration(170).start();
            name.setTextColor(f ? 0xFFFFFFFF : 0xB3FFFFFF);
        });
        box.setOnClickListener(v -> load(s.url));
        box.setOnKeyListener((v, code, e) -> {
            if (favorite && code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && e.getAction() == KeyEvent.ACTION_DOWN) {
                data.removeFavorite(s);
                showStart();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(104), -2);
        lp.setMargins(0, 0, dp(22), dp(22));
        box.setLayoutParams(lp);
        return box;
    }

    private static int monoColor(String domain) {
        int[] c = {0xFF0A84FF, 0xFF5E5CE6, 0xFFBF5AF2, 0xFFFF375F, 0xFFFF9F0A, 0xFF30D158, 0xFF64D2FF};
        return c[Math.abs(domain.hashCode()) % c.length];
    }

    /** A site's own touch icon (as Safari's Favorites show), downloaded once and kept. */
    private void loadIcon(String url, ImageView into) {
        String dom = Browser.domain(url);
        File f = new File(getCacheDir(), "icon-" + Integer.toHexString(dom.hashCode()) + ".png");
        File none = new File(getCacheDir(), "icon-" + Integer.toHexString(dom.hashCode()) + ".none");
        if (none.exists()) return;
        icons.execute(() -> {
            try {
                // The site's own touch icon first (what Safari shows); its large site icon otherwise.
                if (!f.exists() && !fetch(Browser.touchIcon(url), f)
                        && !fetch("https://www.google.com/s2/favicons?sz=128&domain=" + Uri.encode(dom), f)) {
                    none.createNewFile();
                    return;
                }
                Bitmap b = BitmapFactory.decodeFile(f.getPath());
                if (b == null || b.getWidth() < 48) {
                    none.createNewFile();
                    return;
                }
                main.post(() -> into.setImageBitmap(b));
            } catch (Exception ignored) {
            }
        });
    }

    private static boolean fetch(String url, File to) {
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(6000);
            h.setReadTimeout(8000);
            h.setInstanceFollowRedirects(true);
            h.setRequestProperty("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15");
            if (h.getResponseCode() != 200 || !String.valueOf(h.getContentType()).startsWith("image")) return false;
            try (InputStream in = h.getInputStream(); FileOutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            Bitmap b = BitmapFactory.decodeFile(to.getPath());
            boolean ok = b != null && b.getWidth() >= 48;
            if (!ok) to.delete();
            return ok;
        } catch (Exception e) {
            to.delete();
            return false;
        }
    }

    // ---- the address screen ---------------------------------------------------------------------------------

    /** Search or enter a website: a big field at the top, with Favorites and history matches below. */
    private void openAddress() {
        FrameLayout dim = new FrameLayout(this);
        dim.setBackgroundColor(0xE6000000);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(640), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        clp.topMargin = dp(40);
        dim.addView(col, clp);

        LinearLayout field = new LinearLayout(this);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(dp(18), 0, dp(10), 0);
        GradientDrawable fb = new GradientDrawable();
        fb.setColor(0xFF3A3A3C);
        fb.setCornerRadius(dp(26));
        field.setBackground(fb);
        ImageView mag = new ImageView(this);
        mag.setImageResource(R.drawable.ic_search);
        mag.setImageTintList(ColorStateList.valueOf(0x99FFFFFF));
        field.addView(mag, new LinearLayout.LayoutParams(dp(20), dp(20)));
        EditText input = new EditText(this);
        input.setHint(R.string.search_hint);
        input.setHintTextColor(0x80FFFFFF);
        input.setTextColor(0xFFFFFFFF);
        input.setTextSize(20);
        input.setSingleLine(true);
        input.setBackground(null);
        input.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setPadding(dp(12), dp(14), dp(12), dp(14));
        WebView w = web();
        if (w != null && w.getUrl() != null) {
            input.setText(w.getUrl());
            input.selectAll();
        }
        field.addView(input, new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(field, new LinearLayout.LayoutParams(-1, dp(56)));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(12), 0, 0);
        col.addView(list);

        Runnable suggest = () -> {
            list.removeAllViews();
            for (Browser.Site s : data.suggestions(input.getText().toString(), 6)) list.addView(suggestion(s));
        };
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { suggest.run(); }
        });
        input.setOnEditorActionListener((v, action, e) -> {
            String url = Browser.addressFor(input.getText().toString());
            if (url == null) return true;
            closeOverlay();
            load(url);
            return true;
        });
        showOverlay(dim);
        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    private View suggestion(Browser.Site s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(18), dp(10), dp(18), dp(10));
        row.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0x00000000);
        row.setBackground(bg);
        TextView t = new TextView(this);
        t.setText(s.title == null || s.title.isEmpty() ? Browser.domain(s.url) : s.title);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(17);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(t);
        TextView u = new TextView(this);
        u.setText(Browser.domain(s.url));
        u.setTextColor(0x99FFFFFF);
        u.setTextSize(13);
        row.addView(u);
        row.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? 0xFFFFFFFF : 0x00000000);
            t.setTextColor(f ? 0xFF1C1C1E : 0xFFFFFFFF);
            u.setTextColor(f ? 0x991C1C1E : 0x99FFFFFF);
        });
        row.setOnClickListener(v -> {
            closeOverlay();
            load(s.url);
        });
        return row;
    }

    // ---- the tab grid ------------------------------------------------------------------------------------------

    private void showTabs() {
        if (tabs.get(cur).web != null) snapshot(tabs.get(cur));
        FrameLayout dim = new FrameLayout(this);
        dim.setBackgroundColor(0xF2161618);
        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(80), dp(50), dp(80), dp(50));
        sv.addView(col);
        dim.addView(sv, full());
        TextView title = new TextView(this);
        title.setText(getString(R.string.tabs_title, tabs.size()));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(26);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        col.addView(title);
        TextView hint = new TextView(this);
        hint.setText(R.string.tab_hint);
        hint.setTextColor(0x80FFFFFF);
        hint.setTextSize(14);
        hint.setPadding(0, dp(6), 0, dp(24));
        col.addView(hint);
        LinearLayout row = null;
        View focus = null;
        for (int i = 0; i <= tabs.size(); i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                col.addView(row);
            }
            View card = i < tabs.size() ? tabCard(i) : newTabCard();
            row.addView(card);
            if (i == cur) focus = card;
        }
        showOverlay(dim);
        if (focus != null) focus.requestFocus();
    }

    private View tabCard(int i) {
        Tab t = tabs.get(i);
        LinearLayout box = cardBox();
        FrameLayout pic = (FrameLayout) box.getChildAt(0);
        if (t.thumb != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(t.thumb);
            pic.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            TextView l = new TextView(this);
            l.setText(t.url == null ? getString(R.string.start_page) : Browser.domain(t.url));
            l.setTextColor(0x99FFFFFF);
            l.setGravity(Gravity.CENTER);
            pic.addView(l, new FrameLayout.LayoutParams(-1, -1));
        }
        TextView name = (TextView) box.getChildAt(1);
        name.setText(t.url == null ? getString(R.string.start_page) : t.title != null && !t.title.isEmpty() ? t.title : Browser.domain(t.url));
        if (i == cur) ((GradientDrawable) pic.getBackground()).setStroke(dp(3), 0xFF0A84FF);
        box.setOnClickListener(v -> {
            closeOverlay();
            showTab(i);
        });
        box.setOnKeyListener((v, code, e) -> {
            if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && e.getAction() == KeyEvent.ACTION_DOWN) {
                closeTab(i);
                closeOverlay();
                showTab(cur);
                showTabs();
                return true;
            }
            return false;
        });
        return box;
    }

    private View newTabCard() {
        LinearLayout box = cardBox();
        FrameLayout pic = (FrameLayout) box.getChildAt(0);
        ImageView plus = new ImageView(this);
        plus.setImageResource(R.drawable.ic_plus);
        plus.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        pic.addView(plus, new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER));
        ((TextView) box.getChildAt(1)).setText(R.string.new_tab);
        box.setOnClickListener(v -> {
            closeOverlay();
            newTab(null);
        });
        return box;
    }

    private LinearLayout cardBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setFocusable(true);
        box.setClipChildren(false);
        FrameLayout pic = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0xFF2C2C2E);
        pic.setBackground(bg);
        pic.setClipToOutline(true);
        box.addView(pic, new LinearLayout.LayoutParams(dp(220), dp(124)));
        TextView name = new TextView(this);
        name.setTextColor(0xB3FFFFFF);
        name.setTextSize(14);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(dp(2), dp(8), 0, 0);
        box.addView(name, new LinearLayout.LayoutParams(dp(220), -2));
        box.setOnFocusChangeListener((v, f) -> {
            pic.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).translationZ(f ? dp(14) : 0).setDuration(170).start();
            name.setTextColor(f ? 0xFFFFFFFF : 0xB3FFFFFF);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, 0, dp(28), dp(28));
        box.setLayoutParams(lp);
        return box;
    }

    // ---- overlays ----------------------------------------------------------------------------------------------

    private void showOverlay(View v) {
        closeOverlay();
        overlay = v;
        webMode = false;
        cursor.hide();
        v.setAlpha(0f);
        v.setElevation(dp(30));   // above the glass bar
        root.addView(v, full());
        v.animate().alpha(1f).setDuration(180).start();
    }

    private void closeOverlay() {
        if (overlay == null) return;
        View v = overlay;
        overlay = null;
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        root.removeView(v);
        if (currentHasPage()) enterWebMode();
        else showStart();
    }

    private static FrameLayout.LayoutParams full() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /** The pointer on pages: a soft white dot with a dark edge (visible on any page), briefly pressed on click. */
    private static final class Cursor extends View {
        float x = -1, y = -1;
        private boolean visible;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float press = 1f;

        Cursor(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            fill.setColor(0xF2FFFFFF);
            ring.setColor(0x80000000);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(1.5f * d);
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
            visible = false;
            invalidate();
        }

        void pulse() {
            press = 0.7f;
            invalidate();
            postDelayed(() -> {
                press = 1f;
                invalidate();
            }, 120);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (x < 0) {
                x = getWidth() / 2f;
                y = getHeight() / 2f;
            }
            if (!visible) return;
            float r = 11 * getResources().getDisplayMetrics().density * press;
            c.drawCircle(x, y, r, fill);
            c.drawCircle(x, y, r, ring);
        }
    }
}
