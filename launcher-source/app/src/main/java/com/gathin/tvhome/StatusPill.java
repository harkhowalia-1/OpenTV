package com.gathin.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;

/**
 * The tvOS top-right status pill in smoked glass: the time, then plain icons for Now Playing (only while
 * something is playing), Inputs (only on a TV with HDMI or other inputs), Control Center and the profile
 * avatar. A focused icon sits in a white circle; the icon whose panel is open sits in a grey one. The home
 * screen and the panels build the same pill in the same spot, so a panel appears to drop out of it.
 */
final class StatusPill {

    static final String MEDIA = "media";
    static final String INPUTS = "inputs";
    static final String CONTROL = "control";
    static final String PROFILE = "profile";

    interface Listener {
        void onPill(String panel);
    }

    private StatusPill() {
    }

    static LinearLayout build(Context ctx, Listener listener, String active, boolean showMedia) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout pill = new LinearLayout(ctx);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(new LiquidGlass(ctx, -1, LiquidGlass.SMOKE, false));
        int p = (int) (5 * d);
        pill.setPadding((int) (18 * d), p, p, p);

        TextClock time = new TextClock(ctx);
        time.setFormat12Hour("h:mma");
        time.setFormat24Hour("HH:mm");
        time.setTextSize(17);
        time.setTextColor(0xF2FFFFFF);
        time.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMarginEnd((int) (8 * d));
        pill.addView(time, tlp);

        if (showMedia) {
            pill.addView(iconButton(ctx, R.id.pillMedia, R.drawable.ic_waveform, MEDIA.equals(active),
                    v -> listener.onPill(MEDIA)));
        }
        if (TvInputs.available(ctx)) {
            pill.addView(iconButton(ctx, R.id.pillInputs, R.drawable.ic_input, INPUTS.equals(active),
                    v -> listener.onPill(INPUTS)));
        }
        pill.addView(iconButton(ctx, R.id.pillControl, R.drawable.ic_toggles, CONTROL.equals(active),
                v -> listener.onPill(CONTROL)));
        View avatar = Profiles.avatarButton(ctx, PROFILE.equals(active), v -> listener.onPill(PROFILE));
        avatar.setId(R.id.pillProfile);
        pill.addView(avatar);

        // Keep focus inside the pill at its ends and at the top.
        int first = pill.getChildAt(1).getId();
        View.OnKeyListener keys = (v, code, ev) -> ev.getAction() == KeyEvent.ACTION_DOWN
                && (code == KeyEvent.KEYCODE_DPAD_UP
                || (code == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == first)
                || (code == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.pillProfile));
        for (int i = 1; i < pill.getChildCount(); i++) pill.getChildAt(i).setOnKeyListener(keys);
        return pill;
    }

    private static View iconButton(Context ctx, int id, int icon, boolean active, View.OnClickListener click) {
        float d = ctx.getResources().getDisplayMetrics().density;
        FrameLayout b = new FrameLayout(ctx);
        b.setId(id);
        b.setBackgroundResource(R.drawable.pill_icon_bg);
        b.setFocusable(true);
        b.setClickable(true);
        b.setActivated(active);
        b.setOnClickListener(click);
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(icon);
        iv.setDuplicateParentStateEnabled(true);
        ColorStateList tint = ctx.getColorStateList(R.drawable.glass_text);
        iv.setImageTintList(tint);
        int s = (int) (20 * d);
        b.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        int size = (int) (38 * d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginStart((int) (3 * d));
        b.setLayoutParams(lp);
        return b;
    }
}
