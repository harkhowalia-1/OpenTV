package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.Collections;

/**
 * "Add to Apple Home": the HomeKit setup code as a QR code and as digits, laid out like the tvOS Settings
 * screens. Once an iPhone adds the TV it switches to a short "Added" note with a way to remove it.
 */
public class AppleHomeActivity extends Activity {

    private float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Boolean shownPaired;
    private LinearLayout right;
    private FrameLayout left;

    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            AppleHome home = AppleHome.current;
            boolean paired = home != null && home.isPaired();
            if (shownPaired == null || shownPaired != paired) build(home);
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.25f, 0.5f);
        glow.setGradientRadius(900 * density);
        glow.setColors(new int[]{0xFF2A2A2E, 0xFF0B0B0C, 0xFF000000});
        root.setBackground(glow);
        left = new FrameLayout(this);
        root.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.44f));
        right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setPadding(0, dp(48), dp(72), dp(48));
        right.setClipChildren(false);
        root.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.56f));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(watch);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(watch);
        super.onPause();
    }

    private void build(AppleHome home) {
        left.removeAllViews();
        right.removeAllViews();
        shownPaired = home != null && home.isPaired();
        int screenH = getResources().getDisplayMetrics().heightPixels;

        if (home == null) {
            right.addView(text(getString(R.string.apple_home), 34, true, 0xFFFFFFFF));
            right.addView(text(getString(R.string.apple_home_unavailable), 18, false, 0x99FFFFFF));
            return;
        }

        if (shownPaired) {
            ImageView icon = new ImageView(this);
            icon.setImageResource(R.drawable.ic_check);
            icon.setColorFilter(0xFF30D158);
            int s = screenH / 4;
            left.addView(icon, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
            right.addView(text(getString(R.string.apple_home_paired_title), 34, true, 0xFFFFFFFF));
            right.addView(text(getString(R.string.apple_home_paired_body), 18, false, 0x99FFFFFF));
            TextView reset = button(getString(R.string.apple_home_reset));
            reset.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle(R.string.apple_home_reset)
                    .setMessage("The TV will be removed from every Home it was added to, and get a new setup code.")
                    .setPositiveButton(R.string.apple_home_reset, (d, w) -> {
                        home.reset();
                        build(home);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
            right.addView(reset);
            reset.requestFocus();
            return;
        }

        // QR code on a white card, sized from the real screen.
        int side = (int) (screenH * 0.42f);
        ImageView qr = new ImageView(this);
        qr.setImageBitmap(qr(home.setupUri(), side));
        GradientDrawable card = new GradientDrawable();
        card.setColor(Color.WHITE);
        card.setCornerRadius(22 * density);
        qr.setBackground(card);
        int pad = side / 14;
        qr.setPadding(pad, pad, pad, pad);
        qr.setElevation(20 * density);
        left.addView(qr, new FrameLayout.LayoutParams(side, side, Gravity.CENTER));

        right.addView(text(getString(R.string.apple_home_title), 34, true, 0xFFFFFFFF));
        right.addView(text(getString(R.string.apple_home_steps), 18, false, 0x99FFFFFF));
        right.addView(text(getString(R.string.apple_home_manual), 16, false, 0x80FFFFFF));
        TextView code = text(home.pin(), 52, false, 0xFFFFFFFF);
        code.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        code.setLetterSpacing(0.08f);
        right.addView(code);
    }

    private TextView text(String s, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setPadding(0, 0, 0, dp(bold ? 18 : 14));
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private TextView button(String s) {
        TextView b = new TextView(this);
        b.setText(s);
        b.setTextSize(18);
        b.setTextColor(getColorStateList(R.drawable.glass_text));
        b.setBackgroundResource(R.drawable.settings_row_bg);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(20), 0, dp(20), 0);
        b.setFocusable(true);
        b.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56));
        lp.topMargin = dp(16);
        b.setLayoutParams(lp);
        b.setOnFocusChangeListener((v, f) -> v.animate().scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f).setDuration(150).start());
        return b;
    }

    private static Bitmap qr(String content, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size,
                    Collections.singletonMap(EncodeHintType.MARGIN, 0));
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }

    private int dp(int v) {
        return (int) (v * density);
    }
}
