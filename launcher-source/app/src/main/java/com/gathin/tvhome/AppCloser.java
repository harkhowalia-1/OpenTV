package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * "Close Background Apps" in Control Center. Android 14 lets no app stop another, so this does what a person
 * would: opens each app's info page in Settings and presses Force stop (skipping apps that aren't running,
 * where the button is greyed out), behind a full-screen card so the Settings pages never show. ~0.5 s per app.
 *
 * <p>Which apps: everything installed from outside the system image, plus any app opened since the TV started,
 * except the ones that have to keep running (this launcher, the AirPlay receiver, the keyboard).
 */
final class AppCloser {

    private static final String SETTINGS = "com.android.tv.settings";
    private static final long STEP_MS = 60, TIMEOUT_MS = 2500, CONFIRM_MS = 900;

    /** Apps brought to the front since the TV started (from window changes). */
    static final Set<String> opened = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
    private static volatile boolean running;

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> queue = new ArrayList<>();
    private View card;
    private TextView status;
    private int total, closed;

    private AppCloser(AccessibilityService service) {
        this.service = service;
    }

    static void closeAll(AccessibilityService service) {
        if (running || service == null) return;
        new AppCloser(service).start();
    }

    private void start() {
        running = true;
        queue.addAll(candidates());
        total = queue.size();
        showCard();
        handler.postDelayed(this::next, 150);
    }

    private List<String> candidates() {
        PackageManager pm = service.getPackageManager();
        Set<String> keep = new java.util.HashSet<>();
        keep.add(service.getPackageName());
        keep.add(ControlCenterActivity.AIRPLAY_PACKAGE);
        keep.add(SETTINGS);
        String ime = Settings.Secure.getString(service.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null) keep.add(ime.split("/")[0]);
        Set<String> out = new LinkedHashSet<>();
        synchronized (opened) {
            // Only real apps (ones with a home-screen entry), never system services that showed a dialog.
            for (String p : opened) {
                if (pm.getLeanbackLaunchIntentForPackage(p) != null || pm.getLaunchIntentForPackage(p) != null) out.add(p);
            }
        }
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0) out.add(ai.packageName);
        }
        // Built-in apps people open (YouTube, Play Store…): everything with a home-screen entry. Ones that
        // aren't running cost a quick look at a greyed-out button.
        for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            out.add(r.activityInfo.packageName);
        }
        out.removeAll(keep);
        out.removeIf(p -> p.startsWith("com.android.") || p.equals("android"));
        return new ArrayList<>(out);
    }

    // ---- one app at a time ------------------------------------------------------------------------

    private void next() {
        if (queue.isEmpty()) {
            finish();
            return;
        }
        String pkg = queue.remove(0);
        String name = label(pkg);
        setStatus(name, total - queue.size());
        try {
            service.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (Exception e) {
            handler.post(this::next);
            return;
        }
        long until = System.currentTimeMillis() + TIMEOUT_MS;
        // The page must be this app's (its name showing): the previous app's page lingers while this one opens.
        waitFor("Force stop", name, until, node -> {
            if (node == null || !node.isEnabled()) {      // not running (or no page): nothing to do
                next();
                return;
            }
            clickable(node).performAction(AccessibilityNodeInfo.ACTION_CLICK);
            waitFor("OK", null, System.currentTimeMillis() + CONFIRM_MS, ok -> {
                if (ok != null) {
                    clickable(ok).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    closed++;
                }
                handler.postDelayed(this::next, 150);
            });
        });
    }

    private interface Found {
        void on(AccessibilityNodeInfo node);
    }

    /** Polls the Settings window for a control with this exact text (on a page also showing `page`), until `until`. */
    private void waitFor(String text, String page, long until, Found found) {
        AccessibilityNodeInfo n = find(text, page);
        if (n != null || System.currentTimeMillis() > until) {
            found.on(n);
            return;
        }
        handler.postDelayed(() -> waitFor(text, page, until, found), STEP_MS);
    }

    private AccessibilityNodeInfo find(String text, String page) {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        AccessibilityNodeInfo active = service.getRootInActiveWindow();
        if (active != null) roots.add(active);
        for (AccessibilityWindowInfo w : service.getWindows()) {
            AccessibilityNodeInfo r = w.getRoot();
            if (r != null) roots.add(r);
        }
        for (AccessibilityNodeInfo root : roots) {
            if (root.getPackageName() == null || !SETTINGS.contentEquals(root.getPackageName())) continue;
            if (page != null && !shows(root, page)) continue;
            for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(text)) {
                if (n.getText() != null && text.equalsIgnoreCase(n.getText().toString().trim())) return n;
            }
        }
        return null;
    }

    private static boolean shows(AccessibilityNodeInfo root, String text) {
        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(text)) {
            if (n.getText() != null && text.equalsIgnoreCase(n.getText().toString().trim())) return true;
        }
        return false;
    }

    private static AccessibilityNodeInfo clickable(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        while (c != null && !c.isClickable()) c = c.getParent();
        return c != null ? c : n;
    }

    private String label(String pkg) {
        try {
            PackageManager pm = service.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private void finish() {
        service.startActivity(new Intent(service, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        setStatus(closed == 0 ? "Nothing was running" : closed == 1 ? "Closed 1 app" : "Closed " + closed + " apps");
        synchronized (opened) {
            opened.clear();
        }
        handler.postDelayed(() -> {
            hideCard();
            running = false;
        }, 900);
    }

    // ---- the card over the screen while it works -------------------------------------------------------

    private void showCard() {
        FrameLayout root = new FrameLayout(service);
        root.setBackgroundColor(0xF2000000);
        LinearLayout box = new LinearLayout(service);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = new TextView(service);
        title.setText("Closing Background Apps");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(30);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        status = new TextView(service);
        status.setTextColor(0x99FFFFFF);
        status.setTextSize(18);
        status.setPadding(0, 24, 0, 0);
        box.addView(title);
        box.addView(status);
        root.addView(box, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        card = root;
        service.getSystemService(WindowManager.class).addView(root, lp);
    }

    /** An app's name with progress ("Netflix · 3 of 9"), or the final result. */
    private void setStatus(String s) {
        if (status != null) status.setText(s);
    }

    private void setStatus(String app, int n) {
        setStatus(app + "  ·  " + n + " of " + total);
    }

    private void hideCard() {
        if (card == null) return;
        try {
            service.getSystemService(WindowManager.class).removeView(card);
        } catch (Exception ignored) {
        }
        card = null;
    }
}
