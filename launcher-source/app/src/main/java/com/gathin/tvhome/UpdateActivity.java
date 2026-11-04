package com.gathin.tvhome;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings › Software Update: checks OpenTV's newest release on GitHub and, when it's newer than what's
 * installed, downloads OtvOS (and IPTV, if it's on the TV) and installs them. Android asks once to allow OtvOS
 * to install apps, then shows its own confirmation for each one. OtvOS restarts by itself after updating.
 */
public class UpdateActivity extends Activity {


    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView title, detail;
    private ProgressBar spinner;
    private TextView button;
    private String latest;
    private final List<String[]> pending = new ArrayList<>();   // {url, package, name}

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF0B0B0E);
        root.setPadding(dp(80), dp(40), dp(80), dp(40));

        TextView os = new TextView(this);
        os.setText(Brand.OS);
        os.setTextColor(0x99FFFFFF);
        os.setTextSize(16);
        os.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(os);
        title = new TextView(this);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(34);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(0, dp(6), 0, dp(10));
        root.addView(title);
        detail = new TextView(this);
        detail.setTextColor(0xB3FFFFFF);
        detail.setTextSize(16);
        detail.setGravity(Gravity.CENTER);
        detail.setMaxLines(8);
        root.addView(detail, new LinearLayout.LayoutParams(dp(640), LinearLayout.LayoutParams.WRAP_CONTENT));
        spinner = new ProgressBar(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(40), dp(40));
        sp.topMargin = dp(24);
        root.addView(spinner, sp);
        button = new TextView(this);
        button.setTextSize(18);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(32), dp(12), dp(32), dp(12));
        button.setFocusable(true);
        button.setVisibility(android.view.View.GONE);
        button.setOnFocusChangeListener((v, f) -> styleButton(f));
        styleButton(false);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(28);
        root.addView(button, bp);
        setContentView(root);

        title.setText("Checking for Updates…");
        detail.setText("Installed: " + Brand.OS + " " + installedVersion("com.gathin.tvhome"));
        new Thread(this::check, "otv-update-check").start();
    }

    private void styleButton(boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(26));
        bg.setColor(focused ? 0xFFFFFFFF : 0x33FFFFFF);
        button.setBackground(bg);
        button.setTextColor(focused ? 0xFF000000 : 0xFFFFFFFF);
        button.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(150).start();
    }

    private String installedVersion(String pkg) {
        return Updater.installedVersion(this, pkg);
    }

    // ---- checking ---------------------------------------------------------------------------------------

    private void check() {
        try {
            Updater.Release r = Updater.check(this);
            latest = r.version;
            pending.addAll(r.apps);
            main.post(() -> showResult(r.notes));
        } catch (Exception e) {
            main.post(() -> {
                spinner.setVisibility(android.view.View.GONE);
                title.setText("Couldn't Check for Updates");
                detail.setText("Check the TV's internet connection and try again.");
                showButton("Try Again", () -> recreate());
            });
        }
    }

    private void showResult(String notes) {
        spinner.setVisibility(android.view.View.GONE);
        if (pending.isEmpty()) {
            title.setText(Brand.OS + " is up to date");
            detail.setText(Brand.OS + " " + installedVersion("com.gathin.tvhome") + " is the newest version.");
            showButton("Done", this::finish);
            return;
        }
        title.setText(Brand.OS + " " + latest + " is available");
        detail.setText(notes == null || notes.isEmpty() ? "Improvements and fixes." : notes);
        showButton("Download and Install", this::start);
    }

    private void showButton(String text, Runnable action) {
        button.setText(text);
        button.setVisibility(android.view.View.VISIBLE);
        button.setOnClickListener(v -> action.run());
        button.requestFocus();
    }

    // ---- installing -------------------------------------------------------------------------------------

    private void start() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            // Once: let OtvOS install apps.
            title.setText("Allow OtvOS to Install Updates");
            detail.setText("Turn on \"Allow from this source\" for OtvOS, then come back and choose Download and Install.");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        button.setVisibility(android.view.View.GONE);
        spinner.setVisibility(android.view.View.VISIBLE);
        next();
    }

    /** Installs the next waiting app; OtvOS itself goes last, since installing it restarts OtvOS. */
    private void next() {
        if (pending.isEmpty()) {
            spinner.setVisibility(android.view.View.GONE);
            title.setText("Up to Date");
            detail.setText("Everything is updated.");
            showButton("Done", this::finish);
            return;
        }
        String[] app = pending.remove(0);
        title.setText("Downloading " + app[2] + "…");
        detail.setText("");
        new Thread(() -> {
            try {
                File f = new File(getCacheDir(), "update.apk");
                Updater.download(app[0], f, pct -> main.post(() -> detail.setText(pct + "%")));
                main.post(() -> title.setText("Installing " + app[2] + "…"));
                install(f, app[1]);
            } catch (Exception e) {
                main.post(() -> fail(app[2]));
            }
        }, "otv-update-download").start();
    }

    private void install(File apk, String pkg) throws Exception {
        Intent result = new Intent(this, UpdateActivity.class).setAction("otv.update.RESULT")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent cb = PendingIntent.getActivity(this, pkg.hashCode(), result,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        Updater.install(this, apk, pkg, cb.getIntentSender());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!"otv.update.RESULT".equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) startActivity(confirm);
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            Updater.prefs(this).edit().putBoolean("needsConfirm", false).apply();
            next();
        } else {
            fail(intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME));
        }
    }

    private void fail(String what) {
        spinner.setVisibility(android.view.View.GONE);
        title.setText("Update Didn't Finish");
        detail.setText((what == null ? "The update" : what) + " couldn't be installed. Try again in a moment.");
        showButton("Try Again", this::recreate);
    }
}
