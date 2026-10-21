package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * OtvOS Update, in the style of tvOS's Software Update: looks for a newer OtvOS on GitHub, shows what's new,
 * and downloads and installs it (and IPTV, when it's installed) without a computer. OtvOS restarts by itself
 * once it has been replaced.
 */
public class UpdateActivity extends Activity {

    private enum Step { CHECKING, UP_TO_DATE, AVAILABLE, NEEDS_PERMISSION, WORKING, FAILED, DONE }

    /** The screen that's open, for UpdateReceiver. */
    private static UpdateActivity current;

    private float density;
    private TextView status;
    private TextView detail;
    private TextView notes;
    private TextView button;
    private Step step = Step.CHECKING;
    private Updater.Release release;
    private List<Updater.Apk> queue;
    private int index;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        current = this;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.25f, 0.5f);
        glow.setGradientRadius(900 * density);
        glow.setColors(new int[]{0xFF2A2A2E, 0xFF0B0B0C, 0xFF000000});
        root.setBackground(glow);

        // Left: the OtvOS icon, as tvOS shows the app's artwork beside its screen.
        FrameLayout left = new FrameLayout(this);
        ImageView art = new ImageView(this);
        art.setImageResource(R.mipmap.ic_launcher);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 36 * density);
            }
        });
        art.setClipToOutline(true);
        art.setElevation(20 * density);
        int artSize = (int) (getResources().getDisplayMetrics().widthPixels * 0.44f * 0.42f);
        left.addView(art, new FrameLayout.LayoutParams(artSize, artSize, Gravity.CENTER));
        root.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.44f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setPadding(dp(16), dp(40), dp(64), dp(40));
        right.setClipChildren(false);
        right.setClipToPadding(false);
        root.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.56f));

        TextView title = text("OtvOS Update", 34, 0xFFFFFFFF, true);
        right.addView(title);

        TextView installed = text("Installed: " + Brand.OS + " " + Updater.installedVersion(this), 15, 0x80FFFFFF, false);
        installed.setPadding(0, dp(6), 0, dp(26));
        right.addView(installed);

        status = text("", 22, 0xFFFFFFFF, true);
        right.addView(status);
        detail = text("", 15, 0xB3FFFFFF, false);
        detail.setPadding(0, dp(8), 0, 0);
        right.addView(detail);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        notes = text("", 15, 0x99FFFFFF, false);
        notes.setLineSpacing(0, 1.2f);
        scroll.addView(notes);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = dp(16);
        slp.bottomMargin = dp(20);
        right.addView(scroll, slp);

        button = new TextView(this);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(18);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(getColorStateList(R.drawable.glass_text));
        button.setBackgroundResource(R.drawable.settings_row_bg);
        button.setFocusable(true);
        button.setClickable(true);
        button.setOnClickListener(v -> act());
        button.setOnFocusChangeListener((v, f) -> v.animate().scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f)
                .setDuration(150).start());
        right.addView(button, new LinearLayout.LayoutParams(dp(320), dp(56)));

        setContentView(root);
        check();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from "Install unknown apps": carry on if it was allowed.
        if (step == Step.NEEDS_PERMISSION && Updater.canInstall(this)) startInstall();
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    // ---- steps -------------------------------------------------------------------------------------

    private void check() {
        show(Step.CHECKING, "Checking for updates…", "", "", null);
        Updater.check(this, (r, error) -> {
            if (isFinishing()) return;
            if (r == null) {
                show(Step.FAILED, "Couldn't check for updates", friendly(error), "", "Try Again");
                return;
            }
            release = r;
            List<Updater.Apk> updates = r.updates(this);
            if (updates.isEmpty()) {
                show(Step.UP_TO_DATE, "OtvOS is up to date", "The newest release is " + r.version + ".", "", "Check Again");
                return;
            }
            StringBuilder what = new StringBuilder(Brand.OS + " " + r.version + " is available.");
            for (Updater.Apk a : updates) {
                if (Updater.IPTV_PACKAGE.equals(a.packageName)) what.append(" IPTV will be updated too.");
            }
            long bytes = 0;
            for (Updater.Apk a : updates) bytes += a.size;
            if (bytes > 0) what.append(String.format(java.util.Locale.US, " %.0f MB.", bytes / 1_000_000.0));
            show(Step.AVAILABLE, "Update available", what.toString(), clean(r.notes), "Download and Install");
        });
    }

    private void act() {
        switch (step) {
            case AVAILABLE: startInstall(); break;
            case NEEDS_PERMISSION: askPermission(); break;
            case UP_TO_DATE:
            case FAILED: check(); break;
            case DONE: finish(); break;
            default: break;
        }
    }

    private void startInstall() {
        if (release == null) return;
        if (!Updater.canInstall(this)) {
            show(Step.NEEDS_PERMISSION, "Allow OtvOS to install updates",
                    "Turn on \"Install unknown apps\" for OtvOS on the next screen, then come back here.",
                    "If the TV has no such setting, run this once from a computer:\n"
                            + "adb shell appops set " + getPackageName() + " REQUEST_INSTALL_PACKAGES allow\n"
                            + "OpenTV Installer for Mac does it for you.",
                    "Allow");
            return;
        }
        queue = release.updates(this);
        index = 0;
        next();
    }

    private void askPermission() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException | SecurityException e) {
            try {
                startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            } catch (ActivityNotFoundException | SecurityException e2) {
                detail.setText("This TV has no setting for it. Use the adb command below once.");
            }
        }
    }

    /** Installs the next app in the queue: IPTV first, then OtvOS, which restarts once replaced. */
    private void next() {
        if (queue == null || index >= queue.size()) {
            show(Step.DONE, "Up to date", Brand.OS + " " + release.version + " is installed.", "", "Done");
            return;
        }
        Updater.Apk apk = queue.get(index);
        String name = Updater.IPTV_PACKAGE.equals(apk.packageName) ? "IPTV" : Brand.OS;
        show(Step.WORKING, "Downloading " + name + "…", "", clean(release.notes), null);
        Updater.install(this, apk, (a, installing, pct) -> {
            if (installing) {
                status.setText("Installing " + name + "…");
                detail.setText(a.packageName.equals(getPackageName())
                        ? "OtvOS restarts by itself when it's done." : "");
            } else {
                detail.setText(pct >= 0 ? pct + "%" : "");
            }
        }, (v, error) -> {
            if (error != null) show(Step.FAILED, "The update didn't install", friendly(error), "", "Try Again");
        });
    }

    /** From UpdateReceiver: Android finished (or refused) an install. */
    static void installed(String pkg, int result, String message) {
        UpdateActivity a = current;
        if (a == null || a.isFinishing()) return;
        a.runOnUiThread(() -> a.onInstalled(pkg, result, message));
    }

    private void onInstalled(String pkg, int result, String message) {
        if (step != Step.WORKING) return;
        if (result == PackageInstaller.STATUS_SUCCESS) {
            index++;
            next();
            return;
        }
        String why;
        switch (result) {
            case PackageInstaller.STATUS_FAILURE_ABORTED: why = "The install was cancelled."; break;
            case PackageInstaller.STATUS_FAILURE_STORAGE: why = "There isn't enough free space on the TV."; break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                why = "This copy was installed from a different build. Install the new release with OpenTV Installer once; "
                        + "after that, updates work from here.";
                break;
            default: why = message != null && !message.isEmpty() ? message : "Android refused the update.";
        }
        show(Step.FAILED, "The update didn't install", why, "", "Try Again");
    }

    // ---- view helpers ------------------------------------------------------------------------------

    private void show(Step s, String headline, String sub, String body, String action) {
        step = s;
        status.setText(headline);
        detail.setText(sub);
        notes.setText(body);
        if (action == null) {
            button.setVisibility(View.INVISIBLE);
        } else {
            button.setText(action);
            button.setVisibility(View.VISIBLE);
            button.requestFocus();
        }
    }

    private static String friendly(String error) {
        if (error == null || error.isEmpty()) return "Something went wrong.";
        String e = error.toLowerCase(java.util.Locale.ROOT);
        if (e.contains("unable to resolve host") || e.contains("timeout") || e.contains("timed out") || e.contains("failed to connect")) {
            return "The TV couldn't reach GitHub. Check its internet connection.";
        }
        return error;
    }

    /** Release notes are Markdown; keep them readable as plain text. */
    private static String clean(String md) {
        if (md == null) return "";
        StringBuilder out = new StringBuilder();
        for (String line : md.replace("\r", "").split("\n")) {
            String l = line.replaceAll("^#+\\s*", "").replaceAll("^[*-]\\s+", "• ")
                    .replace("**", "").replace("`", "")
                    .replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1");
            out.append(l).append('\n');
        }
        return out.toString().trim();
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    private int dp(int v) {
        return (int) (v * density);
    }
}
