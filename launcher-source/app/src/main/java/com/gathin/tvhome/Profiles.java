package com.gathin.tvhome;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Profiles for the status pill, shown like tvOS's people. Each Google account on the TV gets a profile of its
 * own (linked to it), and profiles can also be added without an account. The chosen one is the launcher's
 * active profile, its initial is the pill's avatar, and its account is the one the launcher hands to apps that
 * accept a chosen account (Google Play). Switching Google TV's own system-wide account needs system
 * permissions, so other apps keep their own account choice.
 */
final class Profiles {

    private static final int[] COLORS = {0xFF7FD36E, 0xFF5AB0F5, 0xFFFFB347, 0xFFF48FB1, 0xFFB39DDB, 0xFF4DD0C4};

    private Profiles() {
    }

    private static android.content.SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("profiles", Context.MODE_PRIVATE);
    }

    /** Profile names, in the order they were added. */
    static List<String> list(Context ctx) {
        List<String> out = new ArrayList<>();
        for (String n : prefs(ctx).getString("list", "").split("\n")) if (!n.trim().isEmpty()) out.add(n.trim());
        return out;
    }

    private static void save(Context ctx, List<String> all) {
        prefs(ctx).edit().putString("list", android.text.TextUtils.join("\n", all)).apply();
    }

    /** A profile without a Google account (or before one is linked). It becomes the active one. */
    static void add(Context ctx, String name) {
        List<String> all = list(ctx);
        if (!all.contains(name)) all.add(name);
        save(ctx, all);
        setActive(ctx, name);
    }

    static void remove(Context ctx, String name) {
        List<String> all = list(ctx);
        all.remove(name);
        save(ctx, all);
        prefs(ctx).edit().remove("account." + name).apply();
    }

    static void rename(Context ctx, String from, String to) {
        if (to.isEmpty() || from.equals(to)) return;
        List<String> all = list(ctx);
        int i = all.indexOf(from);
        if (i < 0 || all.contains(to)) return;
        all.set(i, to);
        save(ctx, all);
        String account = account(ctx, from);
        android.content.SharedPreferences.Editor e = prefs(ctx).edit().remove("account." + from);
        if (account != null) e.putString("account." + to, account);
        if (from.equals(prefs(ctx).getString("active", null))) e.putString("active", to);
        e.apply();
    }

    /** Links a Google account to a profile (and from no other profile). */
    static void link(Context ctx, String name, String email) {
        android.content.SharedPreferences p = prefs(ctx);
        android.content.SharedPreferences.Editor e = p.edit();
        for (String n : list(ctx)) if (email.equals(p.getString("account." + n, null))) e.remove("account." + n);
        e.putString("account." + name, email).apply();
    }

    /** The profile linked to a Google account, or null. */
    static String profileFor(Context ctx, String email) {
        for (String n : list(ctx)) if (email.equals(account(ctx, n))) return n;
        return null;
    }

    /** The Google account linked to a profile, or null for a profile without one. */
    static String account(Context ctx, String name) {
        return prefs(ctx).getString("account." + name, null);
    }

    static String active(Context ctx) {
        String saved = prefs(ctx).getString("active", null);
        List<String> all = list(ctx);
        if (saved != null && all.contains(saved)) return saved;
        return all.isEmpty() ? null : all.get(0);
    }

    static void setActive(Context ctx, String name) {
        prefs(ctx).edit().putString("active", name).apply();
    }

    /** The active profile's Google account, for apps that let the launcher pick one (Google Play). */
    static String activeAccount(Context ctx) {
        String a = active(ctx);
        return a == null ? null : account(ctx, a);
    }

    /** Next account to arrive (after "Sign in with Google" from the profiles panel) becomes the active profile. */
    static void expectNewAccount(Context ctx) {
        prefs(ctx).edit().putLong("expect_account_until", System.currentTimeMillis() + 15 * 60_000).apply();
    }

    /**
     * Matches the TV's Google accounts to profiles: an account already linked stays; otherwise it links to the
     * unlinked profile its name starts with ("Jane" and jane.doe@...), or gets a new profile of its own.
     * A profile whose account was removed from the TV keeps going without one. Returns true if anything changed.
     */
    static boolean syncAccounts(Context ctx) {
        Account[] accounts;
        try {
            accounts = AccountManager.get(ctx).getAccountsByType("com.google");
        } catch (RuntimeException e) {
            return false;
        }
        android.util.Log.i("TvProfiles", accounts.length + " Google accounts visible");
        android.content.SharedPreferences p = prefs(ctx);
        List<String> all = list(ctx);
        List<String> emails = new ArrayList<>();
        for (Account a : accounts) emails.add(a.name);
        boolean changed = false;
        android.content.SharedPreferences.Editor e = p.edit();
        for (String n : all) {
            String linked = p.getString("account." + n, null);
            if (linked != null && !emails.contains(linked)) {
                e.remove("account." + n);
                changed = true;
            }
        }
        boolean expecting = System.currentTimeMillis() < p.getLong("expect_account_until", 0);
        for (String email : emails) {
            boolean linked = false;
            for (String n : all) if (email.equals(p.getString("account." + n, null))) linked = true;
            if (linked) continue;
            String local = email.split("@")[0].toLowerCase(Locale.ROOT);
            String match = null;
            for (String n : all) {
                if (p.getString("account." + n, null) == null && local.startsWith(n.toLowerCase(Locale.ROOT))) match = n;
            }
            if (match == null) {
                String base = name(email);
                match = base;
                for (int n = 2; all.contains(match); n++) match = base + " " + n;
                all.add(match);
                if (expecting) {
                    e.putString("active", match);
                    e.remove("expect_account_until");
                }
            }
            e.putString("account." + match, email);
            changed = true;
        }
        e.putString("list", android.text.TextUtils.join("\n", all));
        e.apply();
        return changed;
    }

    /** "jane.doe42@gmail.com" -> "Jane". */
    static String name(String email) {
        String local = email.split("@")[0].split("[._+]")[0].replaceAll("\\d+$", "");
        if (local.isEmpty()) local = email.split("@")[0];
        return local.substring(0, 1).toUpperCase(Locale.ROOT) + local.substring(1);
    }

    static int color(String name) {
        return COLORS[Math.abs(name.hashCode()) % COLORS.length];
    }

    /** A round avatar: the profile's colour and initial, or a person glyph when there is no profile yet. */
    static View avatar(Context ctx, String profile, int sizePx) {
        FrameLayout f = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(profile == null ? 0xFF48484A : color(profile));
        f.setBackground(bg);
        if (profile == null) {
            ImageView iv = new ImageView(ctx);
            iv.setImageResource(R.drawable.ic_person);
            iv.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
            int s = (int) (sizePx * 0.6f);
            f.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        } else {
            TextView t = new TextView(ctx);
            t.setText(profile.substring(0, 1).toUpperCase(Locale.ROOT));
            t.setTextColor(0xFFFFFFFF);
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx * 0.46f);
            t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            t.setGravity(Gravity.CENTER);
            f.addView(t, new FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER));
        }
        return f;
    }

    /** The pill's avatar button: a white ring and a slight lift when focused, a grey ring while its panel is open. */
    static View avatarButton(Context ctx, boolean active, View.OnClickListener click) {
        float d = ctx.getResources().getDisplayMetrics().density;
        FrameLayout b = new FrameLayout(ctx);
        b.setBackgroundResource(R.drawable.pill_icon_bg);
        b.setFocusable(true);
        b.setClickable(true);
        b.setActivated(active);
        b.setOnClickListener(click);
        int inner = (int) (30 * d);
        b.addView(avatar(ctx, active(ctx), inner), new FrameLayout.LayoutParams(inner, inner, Gravity.CENTER));
        int size = (int) (38 * d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginStart((int) (3 * d));
        b.setLayoutParams(lp);
        return b;
    }
}
