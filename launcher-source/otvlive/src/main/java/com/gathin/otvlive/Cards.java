package com.gathin.otvlive;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Channel cards, as in the Apple TV app: a dark 16:9 tile with the channel's logo, its name underneath, lifting
 * and brightening when focused. OK plays; holding OK (or Menu) adds or removes a favourite.
 */
final class Cards extends RecyclerView.Adapter<Cards.Holder> {

    interface Host {
        void play(List<Catalog.Channel> list, int index);

        void focused(Catalog.Channel c);

        void favouriteChanged(Catalog.Channel c, boolean now);
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    final List<Catalog.Channel> items;
    private final Host host;
    private final int widthDp;

    Cards(List<Catalog.Channel> items, Host host, int widthDp) {
        this.items = items;
        this.host = host;
        this.widthDp = widthDp;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final FrameLayout tile;
        final ImageView logo;
        final TextView initials, name, heart;

        Holder(View v, FrameLayout tile, ImageView logo, TextView initials, TextView name, TextView heart) {
            super(v);
            this.tile = tile;
            this.logo = logo;
            this.initials = initials;
            this.name = name;
            this.heart = heart;
        }
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        Context c = parent.getContext();
        int w = dp(c, widthDp), h = w * 9 / 16;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        box.setFocusable(true);
        box.setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 6));
        box.setLayoutParams(new RecyclerView.LayoutParams(w + dp(c, 20), ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout tile = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF2A2A31, 0xFF1A1A1F});
        bg.setCornerRadius(dp(c, 12));
        tile.setBackground(bg);
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(c, 12));
            }
        });
        tile.setClipToOutline(true);
        TextView initials = new TextView(c);
        initials.setTextColor(0x99FFFFFF);
        initials.setTextSize(26);
        initials.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        initials.setGravity(Gravity.CENTER);
        tile.addView(initials, new FrameLayout.LayoutParams(-1, -1));
        ImageView logo = new ImageView(c);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp(c, 18);
        logo.setPadding(pad, pad, pad, pad);
        tile.addView(logo, new FrameLayout.LayoutParams(-1, -1));
        TextView heart = new TextView(c);
        heart.setText("♥");
        heart.setTextColor(0xFFFF375F);
        heart.setTextSize(14);
        heart.setPadding(0, dp(c, 6), dp(c, 9), 0);
        tile.addView(heart, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
        box.addView(tile, new LinearLayout.LayoutParams(w, h));

        TextView name = new TextView(c);
        name.setTextColor(0x99FFFFFF);
        name.setTextSize(13);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(dp(c, 2), dp(c, 8), dp(c, 2), 0);
        box.addView(name, new LinearLayout.LayoutParams(w, -2));
        return new Holder(box, tile, logo, initials, name, heart);
    }

    @Override
    public void onBindViewHolder(Holder h, int position) {
        Catalog.Channel ch = items.get(position);
        Context c = h.itemView.getContext();
        // A reused card may still be lifted from when it last had focus.
        boolean f = h.itemView.isFocused();
        h.tile.animate().cancel();
        h.name.animate().cancel();
        h.tile.setScaleX(f ? 1.1f : 1f);
        h.tile.setScaleY(f ? 1.1f : 1f);
        h.tile.setTranslationZ(f ? dp(c, 14) : 0);
        h.name.setTranslationY(f ? dp(c, 8) : 0);
        h.name.setTextColor(f ? Color.WHITE : 0x99FFFFFF);
        h.name.setText(ch.name);
        h.initials.setText(initials(ch.name));
        h.initials.setVisibility(ch.logo == null ? View.VISIBLE : View.INVISIBLE);
        Images.get(c).load(ch.logo, h.logo);
        h.heart.setVisibility(Catalog.get(c).isFavourite(ch) ? View.VISIBLE : View.GONE);
        h.itemView.setOnClickListener(v -> host.play(items, h.getBindingAdapterPosition()));
        h.itemView.setOnLongClickListener(v -> {
            toggle(h);
            return true;
        });
        h.itemView.setOnKeyListener((v, keyCode, e) -> {
            if (keyCode == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) {
                toggle(h);
                return true;
            }
            return false;
        });
        h.itemView.setOnFocusChangeListener((v, focused) -> {
            float s = focused ? 1.1f : 1f;
            h.tile.animate().scaleX(s).scaleY(s).translationZ(focused ? dp(c, 14) : 0).setDuration(160).start();
            h.name.setTextColor(focused ? Color.WHITE : 0x99FFFFFF);
            h.name.animate().translationY(focused ? dp(c, 8) : 0).setDuration(160).start();
            if (focused) host.focused(ch);
        });
    }

    private void toggle(Holder h) {
        int i = h.getBindingAdapterPosition();
        if (i < 0) return;
        Catalog.Channel ch = items.get(i);
        boolean now = Catalog.get(h.itemView.getContext()).toggleFavourite(ch);
        h.heart.setVisibility(now ? View.VISIBLE : View.GONE);
        host.favouriteChanged(ch, now);
    }

    static String initials(String name) {
        StringBuilder s = new StringBuilder();
        for (String w : name.split("\\s+")) {
            if (!w.isEmpty() && Character.isLetterOrDigit(w.charAt(0))) s.append(Character.toUpperCase(w.charAt(0)));
            if (s.length() == 3) break;
        }
        return s.toString();
    }
}
