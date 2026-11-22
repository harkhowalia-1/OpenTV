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
import android.view.FocusFinder;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * IPTV, laid out like the Apple TV app with Netflix-style browsing: two tabs along the top. Home is rows of
 * cards (your Favourites first, then Recently Watched, channels like the last one, your country, then
 * categories) under a hero that describes the focused channel. Search is one page for finding anything: rows
 * of categories, countries and languages, and as you type, the matching ones and the matching channels.
 */
public class MainActivity extends Activity implements Cards.Host, Catalog.Listener {

    private static final String[] TABS = {"Home", "Search"};

    private Catalog catalog;
    private LinearLayout tabBar;
    private final List<TextView> tabs = new ArrayList<>();
    private FrameLayout content;
    private int tab = -1;
    /** Grid pages opened from a category, country or language ("Canada", "News"), on top of the tab's page. */
    private final ArrayList<View> stack = new ArrayList<>();
    private TextView heroName, heroMeta;
    private RecyclerView homeRows;
    private EditText searchField;
    /** Search is built once and kept (with what was typed), so moving along the tabs is instant. */
    private final View[] pages = new View[TABS.length];
    /** For each grid in {@link #stack}, the tile that opened it: Back lands there again. */
    private final ArrayList<View> stackFocus = new ArrayList<>();
    private List<Row> homeList = new ArrayList<>();
    private boolean returning, shown, backDown;
    /** Where the last channel was opened from, to come back to it. */
    private String playedRow;
    private int playedIndex;

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
            t.setOnClickListener(v -> {
                // OK on Search goes straight to typing: the keyboard opens at once.
                if (index == 1 && searchField != null) {
                    searchField.requestFocus();
                    showKeyboard(searchField);
                } else {
                    focusContent();
                }
            });
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
        // Favourites and Recently Watched may have changed while a channel was playing: refresh them and land
        // on the channel last watched, in the row (or grid) it was opened from.
        if (catalog.ready() && returning && stack.isEmpty()) {
            Catalog.Channel last = PlayerActivity.last;
            if (tab == 0) {
                refreshHomeRows();
                focusHome(playedRow, last, playedIndex);
            }
        }
        returning = false;
    }

    @Override
    public void onCatalog(boolean ok) {
        if (!ok && !catalog.ready()) {
            showMessage(false, "Couldn't get the channel list. Check the TV's internet connection.");
            return;
        }
        if (shown) {
            // A background refresh (every few days): keep the page and the focus where they are; the new channels
            // show up as pages are next opened.
            if (tab != 1) pages[1] = null;
            return;
        }
        shown = true;
        tab = -1;
        select(0);
        // Start on the first channel, so OK plays straight away.
        homeRows.post(() -> focusHome(null, null, 0));
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
        stackFocus.clear();
        for (int i = 0; i < tabs.size(); i++) styleTab(tabs.get(i), tabs.get(i).isFocused(), i == index);
        View page;
        switch (index) {
            case 0: page = home(); break;
            default: page = pages[1] != null ? pages[1] : (pages[1] = search()); break;
        }
        content.removeAllViews();
        page.setVisibility(View.VISIBLE);   // a kept page may have been hidden under a grid
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
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
            // Act once per press, on release, and only for a press that started here (not the release of the
            // Back that closed the player or the keyboard).
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) backDown = true;
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                if (backDown && !e.isCanceled()) back();
                backDown = false;
            }
            return true;
        }
        // Up into the tab bar lands on the tab that's showing, not on whichever tab happens to be above,
        // so the page never switches by accident.
        if (k == KeyEvent.KEYCODE_DPAD_UP && e.getAction() == KeyEvent.ACTION_DOWN && tab >= 0) {
            View f = getCurrentFocus();
            if (f != null && !tabs.contains(f)) {
                View next = FocusFinder.getInstance().findNextFocus((ViewGroup) getWindow().getDecorView(), f, View.FOCUS_UP);
                if (next != null && tabs.contains(next)) {
                    tabs.get(tab).requestFocus();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /**
     * One press of Back always does something you can see: close the grid that's open, or go from any tab
     * back to Home, or leave the app from Home (from anywhere on it, not only from the tab bar).
     */
    private void back() {
        if (!stack.isEmpty()) {
            content.removeView(stack.remove(stack.size() - 1));
            View under = content.getChildAt(content.getChildCount() - 1);
            under.setVisibility(View.VISIBLE);
            View from = stackFocus.remove(stackFocus.size() - 1);
            if (from != null && from.isAttachedToWindow() && from.requestFocus()) return;
            under.requestFocus(View.FOCUS_DOWN);
            return;
        }
        if (!catalog.ready() || tab <= 0) {
            finish();
            return;
        }
        select(0);
        homeRows.post(() -> focusHome(null, null, 0));
    }

    private void showKeyboard(View v) {
        v.post(() -> {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null && v.hasFocus()) imm.showSoftInput(v, 0);
        });
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    // ---- focus ---------------------------------------------------------------------------------------------

    private static int indexOf(List<Catalog.Channel> list, Catalog.Channel c, int fallback) {
        if (c != null) {
            for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(c.id)) return i;
        }
        return fallback;
    }

    /** Focuses channel {@code c} (or else the card at {@code fallback}) in the Home row titled {@code title}. */
    private void focusHome(String title, Catalog.Channel c, int fallback) {
        if (homeRows == null || homeList.isEmpty()) return;
        int r = 0;
        for (int i = 0; i < homeList.size(); i++) {
            if (homeList.get(i).title.equals(title)) {
                r = i;
                break;
            }
        }
        // "Because you watched…" is renamed after each channel: find it by its start.
        if (title != null && r == 0 && !homeList.get(0).title.equals(title)) {
            String start = title.startsWith("Because you watched") ? "Because you watched"
                    : title.startsWith("More from") ? "More from" : null;
            for (int i = 0; start != null && i < homeList.size(); i++) {
                if (homeList.get(i).title.startsWith(start)) {
                    r = i;
                    break;
                }
            }
        }
        List<Catalog.Channel> items = homeList.get(r).items;
        int pos = Math.max(0, Math.min(items.size() - 1, indexOf(items, c, fallback)));
        int row = r;
        RecyclerView.ViewHolder h = homeRows.findViewHolderForAdapterPosition(row);
        if (h == null) homeRows.scrollToPosition(row);
        homeRows.post(() -> {
            RecyclerView.ViewHolder rh = homeRows.findViewHolderForAdapterPosition(row);
            if (rh == null) {
                homeRows.requestFocus(View.FOCUS_DOWN);
                return;
            }
            focusGrid((RecyclerView) ((ViewGroup) rh.itemView).getChildAt(1), pos);
        });
    }

    /** Focuses the card at {@code pos} in a row or grid, scrolling to it first. */
    private void focusGrid(RecyclerView list, int pos) {
        RecyclerView.Adapter<?> a = list.getAdapter();
        if (a == null || a.getItemCount() == 0) {
            list.requestFocus(View.FOCUS_DOWN);
            return;
        }
        int p = Math.max(0, Math.min(a.getItemCount() - 1, pos));
        list.scrollToPosition(p);
        list.post(() -> {
            RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(p);
            if (h == null || !h.itemView.requestFocus()) list.requestFocus(View.FOCUS_DOWN);
        });
    }

    /** The focused card on Home, as {row, card}, or null. */
    private int[] homeFocus() {
        View f = getCurrentFocus();
        if (f == null || homeRows == null) return null;
        View card = f;
        while (card.getParent() instanceof View && !(card.getParent() instanceof RecyclerView)) card = (View) card.getParent();
        if (!(card.getParent() instanceof RecyclerView) || card.getParent() == homeRows) return null;
        RecyclerView inner = (RecyclerView) card.getParent();
        View row = inner;
        while (row.getParent() instanceof View && row.getParent() != homeRows) row = (View) row.getParent();
        if (row.getParent() != homeRows) return null;
        int r = homeRows.getChildAdapterPosition(row), c = inner.getChildAdapterPosition(card);
        return r < 0 || c < 0 || r >= homeList.size() ? null : new int[]{r, c};
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
        if (!favs.isEmpty()) rows.add(new Row("Favourites", favs));
        if (!recents.isEmpty()) rows.add(new Row("Recently Watched", recents));
        String home = Catalog.homeCountry();
        // Because you watched…: more of the last channel's kind, and more from its country when that's abroad.
        if (!recents.isEmpty()) {
            Catalog.Channel last = recents.get(0);
            if (!last.categories.isEmpty()) {
                List<Catalog.Channel> like = new ArrayList<>();
                for (Catalog.Channel c : catalog.inCategory(last.categories.get(0))) {
                    if (!c.id.equals(last.id) && (last.country == null || last.country.equals(c.country))) like.add(c);
                }
                if (like.size() < 6) {
                    for (Catalog.Channel c : catalog.inCategory(last.categories.get(0))) {
                        if (!c.id.equals(last.id) && !like.contains(c)) like.add(c);
                        if (like.size() >= 40) break;
                    }
                }
                if (like.size() >= 4) rows.add(new Row("Because you watched " + last.name, cap(like)));
            }
            Catalog.Country abroad = last.country == null || last.country.equals(home) ? null : catalog.countries.get(last.country);
            if (abroad != null) {
                List<Catalog.Channel> more = new ArrayList<>(catalog.inCountry(abroad.code));
                more.remove(last);
                if (more.size() >= 4) rows.add(new Row("More from " + abroad.name, cap(more)));
            }
        }
        Catalog.Country co = catalog.countries.get(home);
        List<Catalog.Channel> local = catalog.inCountry(home);
        if (!local.isEmpty()) rows.add(new Row("Top in " + (co != null ? co.name : home), cap(local)));
        String lang = Catalog.homeLanguage();
        if (!"eng".equals(lang) && catalog.languages.containsKey(lang)) {
            List<Catalog.Channel> inLang = catalog.inLanguage(lang);
            if (inLang.size() >= 4) rows.add(new Row("In " + catalog.languages.get(lang), cap(inLang)));
        }
        for (String cat : Catalog.HOME_ROWS) {
            List<Catalog.Channel> l = catalog.inCategory(cat);
            if (l.size() >= 4) rows.add(new Row(catalog.categories.getOrDefault(cat, cat), cap(l)));
        }
        homeList = rows;
        // Updated in place, so the list keeps its scroll position.
        if (homeRows.getAdapter() instanceof RowAdapter) {
            ((RowAdapter) homeRows.getAdapter()).rows = rows;
            homeRows.getAdapter().notifyDataSetChanged();
        } else {
            homeRows.setAdapter(new RowAdapter(rows));
        }
    }

    private static List<Catalog.Channel> cap(List<Catalog.Channel> l) {
        return l.size() > 40 ? new ArrayList<>(l.subList(0, 40)) : l;
    }

    private final class RowAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        List<Row> rows;
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
        stackFocus.add(getCurrentFocus());
        if (content.getChildCount() > 0) content.getChildAt(content.getChildCount() - 1).setVisibility(View.GONE);
        stack.add(page);
        content.addView(page);
        page.post(() -> page.requestFocus(View.FOCUS_DOWN));
    }

    /** Big tiles for categories, countries and languages: {big, label, sub, key, kind}. */
    private final class Tiles extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final List<String[]> items;
        final int widthDp;

        Tiles(List<String[]> items, int widthDp) {
            this.items = items;
            this.widthDp = widthDp;
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
            t.setPadding(dp(8), 0, dp(8), 0);
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF2A2A31, 0xFF1C1C21});
            bg.setCornerRadius(dp(14));
            t.setBackground(bg);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(widthDp), dp(92));
            lp.setMargins(dp(9), dp(9), dp(9), dp(9));
            t.setLayoutParams(lp);
            for (int i = 0; i < 3; i++) {
                TextView v = new TextView(MainActivity.this);
                v.setGravity(Gravity.CENTER);
                v.setSingleLine(true);
                v.setEllipsize(android.text.TextUtils.TruncateAt.END);
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
            t.setOnClickListener(v -> open(it));
        }
    }

    /** Opens the channels of a category, country or language tile. */
    private void open(String[] it) {
        switch (it[4]) {
            case "country": push(it[0] + "  " + it[1], catalog.inCountry(it[3])); break;
            case "category": push(it[1], catalog.inCategory(it[3])); break;
            default: push(it[1], catalog.inLanguage(it[3])); break;
        }
    }

    private static final Map<String, String> CATEGORY_ICONS = new java.util.HashMap<>();

    static {
        String[] pairs = {"animation", "🎨", "auto", "🚗", "business", "💼", "classic", "🎞", "comedy", "😂",
                "cooking", "🍳", "culture", "🎭", "documentary", "🌍", "education", "🎓", "entertainment", "✨",
                "family", "👪", "general", "📺", "kids", "🧸", "legislative", "🏛", "lifestyle", "🌿", "movies", "🎬",
                "music", "🎵", "news", "📰", "outdoor", "🏕", "relax", "🧘", "religious", "🕊", "science", "🔬",
                "series", "🍿", "shop", "🛍", "sports", "⚽", "travel", "✈", "weather", "⛅", "interactive", "🎮",
                "public", "📡"};
        for (int i = 0; i < pairs.length; i += 2) CATEGORY_ICONS.put(pairs[i], pairs[i + 1]);
    }

    private List<String[]> categoryTiles() {
        List<String[]> items = new ArrayList<>();
        for (String id : Catalog.HOME_ROWS) addCategory(items, id);   // the popular kinds first
        for (String id : catalog.categories.keySet()) if (!Catalog.homeRowSet().contains(id)) addCategory(items, id);
        return items;
    }

    private void addCategory(List<String[]> items, String id) {
        String name = catalog.categories.get(id);
        if (name == null) return;
        int n = catalog.inCategory(id).size();
        if (n > 0) items.add(new String[]{CATEGORY_ICONS.getOrDefault(id, "📺"), name, n + " channels", id, "category"});
    }

    private List<String[]> countryTiles() {
        List<String[]> items = new ArrayList<>();
        for (Catalog.Country c : catalog.countryList()) {
            items.add(new String[]{c.flag, c.name, c.count + " channels", c.code, "country"});
        }
        return items;
    }

    private List<String[]> languageTiles() {
        List<String[]> items = new ArrayList<>();
        for (String[] l : catalog.languageList()) items.add(new String[]{"", l[1], l[2] + " channels", l[0], "language"});
        return items;
    }

    /** A titled row on the Search page: a row of tiles, or (tiles null) a heading for the channels under it. */
    private static final class Section {
        final String title;
        final List<String[]> tiles;

        Section(String title, List<String[]> tiles) {
            this.title = title;
            this.tiles = tiles;
        }
    }

    private final class Sections extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final List<Section> items = new ArrayList<>();

        @Override
        public int getItemCount() {
            return items.size();
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
            title.setPadding(dp(16), dp(14), 0, 0);
            box.addView(title);
            RecyclerView list = new RecyclerView(MainActivity.this);
            list.setLayoutManager(new LinearLayoutManager(MainActivity.this, LinearLayoutManager.HORIZONTAL, false));
            list.setClipToPadding(false);
            list.setClipChildren(false);
            list.setPadding(dp(6), 0, dp(6), 0);
            list.setItemAnimator(null);
            box.addView(list, new LinearLayout.LayoutParams(-1, -2));
            return new RecyclerView.ViewHolder(box) {};
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder h, int position) {
            LinearLayout box = (LinearLayout) h.itemView;
            Section s = items.get(position);
            ((TextView) box.getChildAt(0)).setText(s.title);
            RecyclerView list = (RecyclerView) box.getChildAt(1);
            list.setVisibility(s.tiles == null ? View.GONE : View.VISIBLE);
            list.setAdapter(s.tiles == null ? null : new Tiles(s.tiles, 200));
        }
    }

    private View search() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        EditText field = new EditText(this);
        searchField = field;
        field.setHint("Search channels, countries, categories and languages");
        field.setHintTextColor(0x66FFFFFF);
        field.setTextColor(Color.WHITE);
        field.setTextSize(20);
        field.setSingleLine(true);
        // The TV's own keyboard, docked under the field (not a full-screen text box).
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0xFF22222A);
        bg.setStroke(dp(2), Color.TRANSPARENT);
        field.setBackground(bg);
        field.setPadding(dp(18), dp(10), dp(18), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(56), dp(10), dp(56), dp(2));
        page.addView(field, lp);

        // One list: rows of tiles across the whole width, then channel cards four to a row.
        Sections sections = new Sections();
        List<Catalog.Channel> results = new ArrayList<>();
        Cards cards = new Cards(results, this, 170);
        RecyclerView list = new RecyclerView(this);
        GridLayoutManager glm = new GridLayoutManager(this, 4);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return position < sections.getItemCount() ? 4 : 1;
            }
        });
        list.setLayoutManager(glm);
        list.setClipToPadding(false);
        list.setPadding(dp(40), 0, dp(40), dp(40));
        list.setItemAnimator(null);
        list.setAdapter(new ConcatAdapter(sections, cards));
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        Runnable update = () -> {
            String q = field.getText().toString().trim();
            sections.items.clear();
            results.clear();
            if (q.isEmpty()) {
                sections.items.add(new Section("Categories", categoryTiles()));
                sections.items.add(new Section("Countries", countryTiles()));
                List<String[]> langs = languageTiles();
                if (!langs.isEmpty()) sections.items.add(new Section("Languages", langs));
            } else {
                String l = q.toLowerCase(java.util.Locale.ROOT);
                List<String[]> m = new ArrayList<>();
                for (List<String[]> group : java.util.Arrays.asList(categoryTiles(), countryTiles(), languageTiles())) {
                    for (String[] it : group) if (it[1] != null && it[1].toLowerCase(java.util.Locale.ROOT).contains(l)) m.add(it);
                }
                if (!m.isEmpty()) sections.items.add(new Section("Categories, countries and languages", m));
                results.addAll(catalog.search(q));
                sections.items.add(new Section(results.isEmpty() ? "No channels named \u201c" + q + "\u201d"
                        : results.size() >= 300 ? "Channels (the first 300)" : results.size() == 1 ? "1 channel"
                        : results.size() + " channels", null));
            }
            sections.notifyDataSetChanged();
            cards.notifyDataSetChanged();
        };
        update.run();

        // The keyboard opens on the first OK on the field (or on the Search tab). Just moving past the field
        // to browse the rows under it doesn't open it.
        field.setOnFocusChangeListener((v, f) -> bg.setStroke(dp(2), f ? Color.WHITE : Color.TRANSPARENT));
        field.setOnClickListener(v -> showKeyboard(field));
        // Search on the keyboard: put it away and go to the first channel (or the first tile).
        field.setOnEditorActionListener((v, action, ev) -> {
            hideKeyboard(field);
            focusGrid(list, results.isEmpty() ? 0 : sections.getItemCount());
            return true;
        });
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                // Waits for a pause in typing, so the keyboard never lags.
                list.removeCallbacks(update);
                list.postDelayed(update, 150);
            }
        });
        return page;
    }

    // ---- Cards.Host ----------------------------------------------------------------------------------------

    @Override
    public void play(List<Catalog.Channel> list, int index) {
        if (index < 0) return;
        PlayerActivity.list = new ArrayList<>(list);
        int[] at = tab == 0 && stack.isEmpty() ? homeFocus() : null;
        playedRow = at != null ? homeList.get(at[0]).title : null;
        playedIndex = at != null ? at[1] : index;
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
        // The Favourites row changes at once, and focus stays on the same card.
        if (tab == 0 && stack.isEmpty()) {
            int[] at = homeFocus();
            String row = at != null ? homeList.get(at[0]).title : null;
            refreshHomeRows();
            focusHome(row, c, at != null ? at[1] : 0);
        }
    }
}
