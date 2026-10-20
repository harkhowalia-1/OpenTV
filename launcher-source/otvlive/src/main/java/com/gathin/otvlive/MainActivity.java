package com.gathin.otvlive;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * IPTV, laid out like the Apple TV app: tabs along the top (Home, Favourites, Countries, Categories,
 * Search) that switch as focus moves over them, a hero that describes the focused channel, and rows of cards.
 */
public class MainActivity extends Activity implements Cards.Host, Catalog.Listener {

    private static final String[] TABS = {"Home", "Favourites", "Countries", "Categories", "Search"};

    private Catalog catalog;
    private LinearLayout tabBar;
    private final List<TextView> tabs = new ArrayList<>();
    private FrameLayout content;
    private int tab = -1;
    /** Grid pages opened from Countries or Categories ("Canada", "News"), on top of the tab's own page. */
    private final ArrayList<View> stack = new ArrayList<>();
    private TextView heroName, heroMeta;
    private RecyclerView homeRows;
    private boolean returning;

    private int dp(float v) {
        return Cards.dp(this, v);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        catalog = Catalog.get(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF15151C, 0xFF0A0A0E, 0xFF07070A}));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        tabBar = new LinearLayout(this);
        tabBar.setGravity(Gravity.CENTER);
        tabBar.setPadding(0, dp(22), 0, dp(6));
        for (int i = 0; i < TABS.length; i++) {
            int index = i;
            TextView t = new TextView(this);
            t.setText(TABS[i]);
            t.setTextSize(15);
            t.setTextColor(0x99FFFFFF);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setPadding(dp(18), dp(7), dp(18), dp(7));
            t.setFocusable(true);
            t.setOnFocusChangeListener((v, f) -> {
                styleTab(t, f, index == tab);
                if (f && index != tab) select(index);
            });
            t.setOnClickListener(v -> focusContent());
            tabs.add(t);
            tabBar.addView(t);
        }
        column.addView(tabBar, new LinearLayout.LayoutParams(-1, -2));

        content = new FrameLayout(this);
        column.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        showMessage(true, "Getting channels…\nThe first time takes a minute or two.");
        catalog.load(this);
    }

    @Override
    protected void onDestroy() {
        catalog.removeListener(this);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Favourites and Recently Watched may have changed while a channel was playing.
        if (catalog.ready() && tab == 0 && stack.isEmpty() && returning) {
            // Land on what was just watched, at the front of Recently Watched.
            refreshHomeRows();
            homeRows.post(() -> homeRows.requestFocus(View.FOCUS_DOWN));
        }
        returning = false;
    }

    @Override
    public void onCatalog(boolean ok) {
        if (!ok && !catalog.ready()) {
            showMessage(false, "Couldn't get the channel list. Check the TV's internet connection.");
            return;
        }
        int t = tab < 0 ? 0 : tab;
        tab = -1;
        select(t);
        if (getCurrentFocus() == null) tabs.get(t).requestFocus();
    }

    private void styleTab(TextView t, boolean focused, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(focused ? Color.WHITE : selected ? 0x33FFFFFF : Color.TRANSPARENT);
        t.setBackground(bg);
        t.setTextColor(focused ? Color.BLACK : selected ? Color.WHITE : 0x99FFFFFF);
    }

    private void select(int index) {
        if (!catalog.ready()) return;
        tab = index;
        stack.clear();
        for (int i = 0; i < tabs.size(); i++) styleTab(tabs.get(i), tabs.get(i).isFocused(), i == index);
        View page;
        switch (index) {
            case 0: page = home(); break;
            case 1: page = favourites(); break;
            case 2: page = countries(); break;
            case 3: page = categories(); break;
            default: page = search(); break;
        }
        content.removeAllViews();
        content.addView(page);
    }

    private void showMessage(boolean spinner, String text) {
        content.removeAllViews();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        if (spinner) box.addView(new ProgressBar(this));
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0x99FFFFFF);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(14), 0, 0);
        box.addView(t);
        content.addView(box, new FrameLayout.LayoutParams(-1, -1));
    }

    private void focusContent() {
        View v = content.getChildCount() > 0 ? content.getChildAt(content.getChildCount() - 1) : null;
        if (v != null) v.requestFocus(View.FOCUS_DOWN);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) {
            if (!stack.isEmpty()) {
                content.removeView(stack.remove(stack.size() - 1));
                View under = content.getChildAt(content.getChildCount() - 1);
                under.setVisibility(View.VISIBLE);
                under.requestFocus();
                return true;
            }
            View f = getCurrentFocus();
            if (f != null && !tabs.contains(f)) {
                tabs.get(Math.max(0, tab)).requestFocus();
                return true;
            }
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_DOWN) {
            View f = getCurrentFocus();
            if (!stack.isEmpty() || (f != null && !tabs.contains(f))) return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---- Home --------------------------------------------------------------------------------------------

    private static final class Row {
        final String title;
        final List<Catalog.Channel> items;

        Row(String title, List<Catalog.Channel> items) {
            this.title = title;
            this.items = items;
        }
    }

    private View home() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(56), dp(10), dp(56), dp(4));
        heroName = new TextView(this);
        heroName.setTextColor(Color.WHITE);
        heroName.setTextSize(34);
        heroName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heroName.setSingleLine(true);
        heroMeta = new TextView(this);
        heroMeta.setTextColor(0xB3FFFFFF);
        heroMeta.setTextSize(15);
        heroMeta.setPadding(0, dp(4), 0, 0);
        TextView hint = new TextView(this);
        hint.setText("OK to watch  ·  hold OK to add to Favourites  ·  " + catalog.channels.size() + " channels");
        hint.setTextColor(0x66FFFFFF);
        hint.setTextSize(12);
        hint.setPadding(0, dp(6), 0, 0);
        hero.addView(heroName);
        hero.addView(heroMeta);
        hero.addView(hint);
        heroName.setText("Live TV from around the world");
        heroMeta.setText("Free channels from every country");
        page.addView(hero, new LinearLayout.LayoutParams(-1, dp(118)));

        homeRows = new RecyclerView(this);
        homeRows.setLayoutManager(new LinearLayoutManager(this));
        homeRows.setClipToPadding(false);
        homeRows.setPadding(0, 0, 0, dp(40));
        homeRows.setItemAnimator(null);
        page.addView(homeRows, new LinearLayout.LayoutParams(-1, 0, 1));
        refreshHomeRows();
        return page;
    }

    private void refreshHomeRows() {
        if (homeRows == null) return;
        List<Row> rows = new ArrayList<>();
        List<Catalog.Channel> recents = catalog.recents(), favs = catalog.favourites();
        if (!recents.isEmpty()) rows.add(new Row("Recently Watched", recents));
        if (!favs.isEmpty()) rows.add(new Row("Favourites", favs));
        String home = Catalog.homeCountry();
        Catalog.Country co = catalog.countries.get(home);
        List<Catalog.Channel> local = catalog.inCountry(home);
        if (!local.isEmpty()) rows.add(new Row("Top in " + (co != null ? co.name : home), cap(local)));
        for (String cat : Catalog.HOME_ROWS) {
            List<Catalog.Channel> l = catalog.inCategory(cat);
            if (l.size() >= 4) rows.add(new Row(catalog.categories.getOrDefault(cat, cat), cap(l)));
        }
        homeRows.setAdapter(new RowAdapter(rows));
    }

    private static List<Catalog.Channel> cap(List<Catalog.Channel> l) {
        return l.size() > 40 ? new ArrayList<>(l.subList(0, 40)) : l;
    }

    private final class RowAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final List<Row> rows;
        final RecyclerView.RecycledViewPool pool = new RecyclerView.RecycledViewPool();

        RowAdapter(List<Row> rows) {
            this.rows = rows;
            pool.setMaxRecycledViews(0, 40);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout box = new LinearLayout(MainActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setClipChildren(false);
            box.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            TextView title = new TextView(MainActivity.this);
            title.setTextColor(0xE6FFFFFF);
            title.setTextSize(18);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setPadding(dp(56), dp(14), 0, 0);
            box.addView(title);
            RecyclerView list = new RecyclerView(MainActivity.this);
            list.setLayoutManager(new LinearLayoutManager(MainActivity.this, LinearLayoutManager.HORIZONTAL, false));
            list.setRecycledViewPool(pool);
            list.setClipToPadding(false);
            list.setClipChildren(false);
            list.setPadding(dp(46), dp(4), dp(46), dp(8));
            list.setItemAnimator(null);
            box.addView(list, new LinearLayout.LayoutParams(-1, -2));
            return new RecyclerView.ViewHolder(box) {};
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder h, int position) {
            LinearLayout box = (LinearLayout) h.itemView;
            Row r = rows.get(position);
            ((TextView) box.getChildAt(0)).setText(r.title);
            ((RecyclerView) box.getChildAt(1)).setAdapter(new Cards(r.items, MainActivity.this, 190));
        }
    }

    // ---- grids ---------------------------------------------------------------------------------------------

    private RecyclerView grid(RecyclerView.Adapter<?> adapter, int columns) {
        RecyclerView g = new RecyclerView(this);
        g.setLayoutManager(new GridLayoutManager(this, columns));
        g.setClipToPadding(false);
        g.setPadding(dp(40), dp(8), dp(40), dp(40));
        g.setItemAnimator(null);
        g.setAdapter(adapter);
        return g;
    }

    private View titled(String title, String subtitle, View body) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(28);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(56), dp(10), dp(56), 0);
        page.addView(t);
        if (subtitle != null) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextColor(0x99FFFFFF);
            s.setTextSize(14);
            s.setPadding(dp(56), dp(2), dp(56), dp(4));
            page.addView(s);
        }
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        return page;
    }

    /** A grid of channels on top of the current tab (Back returns). */
    private void push(String title, List<Catalog.Channel> list) {
        View page = titled(title, list.size() + " channels", grid(new Cards(list, this, 170), 4));
        page.setBackgroundColor(0xFF0A0A0E);   // nothing of the page underneath shows through
        if (content.getChildCount() > 0) content.getChildAt(content.getChildCount() - 1).setVisibility(View.GONE);
        stack.add(page);
        content.addView(page);
        page.post(() -> page.requestFocus(View.FOCUS_DOWN));
    }

    private View favourites() {
        List<Catalog.Channel> favs = catalog.favourites();
        if (favs.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("No favourites yet.\nHold OK on any channel to add it here.");
            t.setTextColor(0x99FFFFFF);
            t.setTextSize(18);
            t.setGravity(Gravity.CENTER);
            return t;
        }
        return titled("Favourites", null, grid(new Cards(favs, this, 170), 4));
    }

    /** Simple big tiles for countries and categories. */
    private final class Tiles extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final List<String[]> items;   // big, label, sub, key
        final java.util.function.Consumer<String[]> open;

        Tiles(List<String[]> items, java.util.function.Consumer<String[]> open) {
            this.items = items;
            this.open = open;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout t = new LinearLayout(MainActivity.this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setGravity(Gravity.CENTER);
            t.setFocusable(true);
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF2A2A31, 0xFF1C1C21});
            bg.setCornerRadius(dp(14));
            t.setBackground(bg);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, dp(92));
            lp.setMargins(dp(9), dp(9), dp(9), dp(9));
            t.setLayoutParams(lp);
            for (int i = 0; i < 3; i++) {
                TextView v = new TextView(MainActivity.this);
                v.setGravity(Gravity.CENTER);
                v.setSingleLine(true);
                v.setTextColor(i == 2 ? 0x80FFFFFF : Color.WHITE);
                v.setTextSize(i == 0 ? 26 : i == 1 ? 15 : 11);
                if (i == 1) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                t.addView(v);
            }
            t.setOnFocusChangeListener((v, f) -> {
                v.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).translationZ(f ? dp(12) : 0).setDuration(150).start();
                GradientDrawable g = (GradientDrawable) v.getBackground();
                g.setColors(f ? new int[]{0xFFF2F2F5, 0xFFD9D9DE} : new int[]{0xFF2A2A31, 0xFF1C1C21});
                for (int i = 0; i < 3; i++) {
                    ((TextView) ((LinearLayout) v).getChildAt(i)).setTextColor(f ? (i == 2 ? 0x99000000 : Color.BLACK)
                            : (i == 2 ? 0x80FFFFFF : Color.WHITE));
                }
            });
            return new RecyclerView.ViewHolder(t) {};
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder h, int position) {
            String[] it = items.get(position);
            LinearLayout t = (LinearLayout) h.itemView;
            ((TextView) t.getChildAt(0)).setText(it[0]);
            ((TextView) t.getChildAt(0)).setVisibility(it[0] == null || it[0].isEmpty() ? View.GONE : View.VISIBLE);
            ((TextView) t.getChildAt(1)).setText(it[1]);
            ((TextView) t.getChildAt(2)).setText(it[2]);
            t.setOnClickListener(v -> open.accept(it));
        }
    }

    private View countries() {
        List<String[]> items = new ArrayList<>();
        for (Catalog.Country c : catalog.countryList()) {
            items.add(new String[]{c.flag, c.name, c.count + " channels", c.code});
        }
        return titled("Countries", null, grid(new Tiles(items, it -> push(it[0] + "  " + it[1], catalog.inCountry(it[3]))), 5));
    }

    private View categories() {
        List<String[]> items = new ArrayList<>();
        for (Map.Entry<String, String> e : catalog.categories.entrySet()) {
            int n = catalog.inCategory(e.getKey()).size();
            if (n > 0) items.add(new String[]{"", e.getValue(), n + " channels", e.getKey()});
        }
        return titled("Categories", null, grid(new Tiles(items, it -> push(it[1], catalog.inCategory(it[3]))), 4));
    }

    private View search() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        EditText field = new EditText(this);
        field.setHint("Channels and countries");
        field.setHintTextColor(0x66FFFFFF);
        field.setTextColor(Color.WHITE);
        field.setTextSize(20);
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0xFF22222A);
        field.setBackground(bg);
        field.setPadding(dp(18), dp(10), dp(18), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(56), dp(10), dp(56), dp(6));
        page.addView(field, lp);
        List<Catalog.Channel> results = new ArrayList<>();
        Cards adapter = new Cards(results, this, 170);
        RecyclerView g = grid(adapter, 4);
        page.addView(g, new LinearLayout.LayoutParams(-1, 0, 1));
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                results.clear();
                results.addAll(catalog.search(s.toString()));
                adapter.notifyDataSetChanged();
            }
        });
        return page;
    }

    // ---- Cards.Host ----------------------------------------------------------------------------------------

    @Override
    public void play(List<Catalog.Channel> list, int index) {
        if (index < 0) return;
        PlayerActivity.list = new ArrayList<>(list);
        returning = true;
        startActivity(new Intent(this, PlayerActivity.class).putExtra("index", index));
    }

    @Override
    public void focused(Catalog.Channel c) {
        if (heroName == null || tab != 0 || !stack.isEmpty()) return;
        heroName.setText(c.name);
        heroMeta.setText(describe(this, c));
    }

    static String describe(Context ctx, Catalog.Channel c) {
        Catalog cat = Catalog.get(ctx);
        StringBuilder s = new StringBuilder();
        Catalog.Country co = c.country == null ? null : cat.countries.get(c.country);
        if (co != null) s.append(co.flag).append("  ").append(co.name);
        for (String g : c.categories) {
            if (s.length() > 0) s.append("  ·  ");
            s.append(cat.categories.getOrDefault(g, g));
        }
        String q = c.streams.isEmpty() ? null : c.streams.get(0).quality;
        if (q != null && !q.isEmpty()) s.append(s.length() > 0 ? "  ·  " : "").append(q);
        return s.toString();
    }

    @Override
    public void favouriteChanged(Catalog.Channel c, boolean now) {
        Toast.makeText(this, now ? "Added " + c.name + " to Favourites" : "Removed " + c.name + " from Favourites",
                Toast.LENGTH_SHORT).show();
    }
}
