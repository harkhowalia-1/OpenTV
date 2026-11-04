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

    private LinearLayout cards, rows;
    private TextView note;

    int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    void build(String title, String subtitle) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0B0E);
        // Rows run to the screen's edges (cards slide off the edge as on tvOS); text keeps the 64dp margin.
        root.setPadding(0, dp(48), 0, dp(32));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(34);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(64), 0, dp(64), 0);
        root.addView(t);
        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextColor(0x99FFFFFF);
        s.setTextSize(15);
        s.setPadding(dp(64), dp(6), dp(64), dp(28));
        root.addView(s);
        android.widget.ScrollView vertical = new android.widget.ScrollView(this);
        vertical.setVerticalScrollBarEnabled(false);
        vertical.setClipToPadding(false);
        vertical.setClipChildren(false);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        vertical.addView(rows);
        root.addView(vertical, new LinearLayout.LayoutParams(-1, 0, 1));
        newRow(null);
        note = new TextView(this);
        note.setTextColor(0x99FFFFFF);
        note.setTextSize(15);
        note.setPadding(dp(64), dp(24), dp(64), 0);
        root.addView(note);
        setContentView(root);
    }

    /** Starts a new row of cards under a heading; later cards go into it (or into a row given to cardIn). */
    LinearLayout section(String heading) {
        // A row that never got a card (the first one, before a heading) would only leave a gap.
        if (cards != null && cards.getChildCount() == 0) rows.removeView((View) cards.getParent());
        newRow(heading);
        return cards;
    }

    /** A card in a particular row: for rows that fill in later, after other rows were added below them. */
    View cardIn(LinearLayout row, Drawable picture, int fill, String label, boolean current, Runnable pick) {
        LinearLayout keep = cards;
        cards = row;
        try {
            return card(picture, fill, label, current, pick);
        } finally {
            cards = keep;
        }
    }

    private void newRow(String heading) {
        if (heading != null) {
            TextView h = new TextView(this);
            h.setText(heading);
            h.setTextColor(0xE6FFFFFF);
            h.setTextSize(18);
            h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            h.setPadding(dp(64), dp(18), dp(64), 0);
            rows.addView(h);
        }
        HorizontalScrollView scroll = new HorizontalScrollView(this) {
            // Keep the focused card inside the margins (not just on screen), so it never sits at the edge.
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) {
                android.graphics.Rect in = new android.graphics.Rect(r);
                in.left -= getPaddingLeft();
                in.right += getPaddingRight();
                return super.computeScrollDeltaToGetChildRectOnScreen(in);
            }
        };
        scroll.setHorizontalScrollBarEnabled(false);
        // A focused card grows and lifts: leave it room, and don't clip it, so its rounded corners stay whole.
        scroll.setClipToPadding(false);
        scroll.setClipChildren(false);
        scroll.setPadding(dp(64), dp(16), dp(64), dp(16));
        cards = new LinearLayout(this);
        cards.setClipChildren(false);
        scroll.addView(cards);
        rows.addView(scroll);
    }

    /** The card chosen last (OK, or a click from the iPhone's trackpad). */
    View lastClicked;

    /** Shows Apple's download ring over a card: 0–1, or below 0 while the size isn't known. */
    void progress(View card, float fraction) {
        if (!(card instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup tile = (android.view.ViewGroup) ((android.view.ViewGroup) card).getChildAt(0);
        ProgressRing ring = tile.findViewWithTag("ring");
        if (ring == null) {
            ring = new ProgressRing(this);
            ring.setTag("ring");
            tile.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        }
        ring.set(fraction);
    }

    void clearProgress(View card) {
        if (!(card instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup tile = (android.view.ViewGroup) ((android.view.ViewGroup) card).getChildAt(0);
        View ring = tile.findViewWithTag("ring");
        if (ring != null) tile.removeView(ring);
    }

    /** The card's picture, to fill in once a preview has loaded. */
    static ImageView picture(View card) {
        return card.findViewWithTag("pic");
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
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setTag("pic");
        if (picture != null) iv.setImageDrawable(picture);
        tile.addView(iv, new FrameLayout.LayoutParams(-1, -1));
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
        box.setOnClickListener(v -> {
            lastClicked = v;
            pick.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, 0, dp(28), 0);
        cards.addView(box, lp);
        return box;
    }
}
