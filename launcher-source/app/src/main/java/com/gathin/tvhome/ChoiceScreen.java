package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A full-screen choice in OtvOS's style: a title, a line of explanation and a row of 16:9 cards that lift
 * under focus. Used for wallpapers and TV inputs.
 */
abstract class ChoiceScreen extends Activity {

    private LinearLayout cards;
    private TextView note;

    int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    void build(String title, String subtitle) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0B0E);
        root.setPadding(dp(64), dp(48), dp(64), dp(32));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(34);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(t);
        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextColor(0x99FFFFFF);
        s.setTextSize(15);
        s.setPadding(0, dp(6), 0, dp(28));
        root.addView(s);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(8), dp(12), dp(8), dp(12));
        cards = new LinearLayout(this);
        cards.setClipChildren(false);
        scroll.addView(cards);
        root.addView(scroll);
        note = new TextView(this);
        note.setTextColor(0x99FFFFFF);
        note.setTextSize(15);
        note.setPadding(0, dp(24), 0, 0);
        root.addView(note);
        setContentView(root);
    }

    void note(String text) {
        note.setText(text);
    }

    /** A card: a picture (or a coloured fill), its name, a tick when it's the current one. */
    View card(Drawable picture, int fill, String label, boolean current, Runnable pick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setFocusable(true);
        box.setClipChildren(false);
        FrameLayout tile = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(fill);
        tile.setBackground(bg);
        tile.setClipToOutline(true);
        if (picture != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(picture);
            tile.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        }
        if (current) {
            TextView tick = new TextView(this);
            tick.setText("✓");
            tick.setTextColor(0xFFFFFFFF);
            tick.setTextSize(16);
            tick.setGravity(Gravity.CENTER);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(0xFF0A84FF);
            tick.setBackground(dot);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP | Gravity.END);
            lp.setMargins(0, dp(8), dp(8), 0);
            tile.addView(tick, lp);
        }
        box.addView(tile, new LinearLayout.LayoutParams(dp(256), dp(144)));
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(0xB3FFFFFF);
        name.setTextSize(15);
        name.setPadding(dp(2), dp(10), 0, 0);
        box.addView(name);
        box.setOnFocusChangeListener((v, f) -> {
            tile.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).translationZ(f ? dp(14) : 0).setDuration(150).start();
            name.setTextColor(f ? 0xFFFFFFFF : 0xB3FFFFFF);
        });
        box.setOnClickListener(v -> pick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, 0, dp(28), 0);
        cards.addView(box, lp);
        return box;
    }
}
