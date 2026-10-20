package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App tiles and folders. The first {@link MainActivity#COLUMNS} positions are the dock. The order is the
 * user's own (long-press → Move) and is saved; until it has been changed, the dock follows
 * res/values/config.xml (dock_apps) and the rest is alphabetical. New apps are added at the end.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad Up/Down on a tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);

        void onTileClicked(int position);

        void onTileLongPressed(int position);

        /** An arrow key while a tile is being moved. */
        void onMoveKey(int position, int keyCode);

        /** OK or Back while a tile is being moved. */
        void onMoveDone();
    }

    static final class Entry {
        /** Package name for an app, "folder:<name>" for a folder. */
        final String key;
        final String label;
        final ComponentName component;   // null for a folder
        final Drawable banner;
        final Drawable icon;
        final List<Entry> children;      // non-null for a folder
        int ambient;

        Entry(String key, String label, ComponentName component, Drawable banner, Drawable icon, List<Entry> children) {
            this.key = key;
            this.label = label;
            this.component = component;
            this.banner = banner;
            this.icon = icon;
            this.children = children;
        }

        boolean isFolder() {
            return children != null;
        }

        String folderName() {
            return key.substring(FOLDER.length());
        }
    }

    static final String FOLDER = "folder:";

    /** tvOS-style tiles that replace an app's own banner (res/drawable-nodpi). */
    private static final Map<String, Integer> CUSTOM_BANNERS = new HashMap<>();
    /** Names shown instead of the app's own label. */
    private static final Map<String, String> CUSTOM_LABELS = new HashMap<>();

    static {
        CUSTOM_BANNERS.put("com.android.tv.settings", R.drawable.banner_settings);
        CUSTOM_BANNERS.put("com.android.vending", R.drawable.banner_appstore);
        CUSTOM_BANNERS.put("com.spotify.tv.android", R.drawable.banner_spotify);
        CUSTOM_LABELS.put("com.android.vending", "App Store");
    }
    static final float FOCUS_SCALE = 1.10f;
    /** Tilt in degrees when focus arrives from a neighbouring tile. */
    private static final float TILT = 9f;

    private final Context ctx;
    private final TileListener listener;
    private final int columns;
    /** Initial dock order (res/values/config.xml, dock_apps). */
    private final List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final SharedPreferences store;
    private final List<Entry> items = new ArrayList<>();
    /** Key of the tile being moved, or null. */
    private String movingKey;
    /** Previously focused position, for the direction of the parallax tilt. */
    private int lastFocusPos = -1;

    AppAdapter(Context ctx, TileListener listener, int columns) {
        this.ctx = ctx;
        this.listener = listener;
        this.columns = columns;
        this.favourites = Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps));
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        this.store = ctx.getSharedPreferences("layout", Context.MODE_PRIVATE);
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    // ---- loading ---------------------------------------------------------------------------------

    void reload() {
        PackageManager pm = ctx.getPackageManager();
        Map<String, Entry> apps = new LinkedHashMap<>();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg) || apps.containsKey(pkg)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Integer custom = CUSTOM_BANNERS.get(pkg);
            if (custom != null) banner = ctx.getDrawable(custom);
            String label = CUSTOM_LABELS.containsKey(pkg) ? CUSTOM_LABELS.get(pkg) : String.valueOf(ri.loadLabel(pm));
            apps.put(pkg, new Entry(pkg, label, new ComponentName(pkg, ri.activityInfo.name), banner, ri.loadIcon(pm), null));
        }
        // A dock app with no TV launch entry (a phone app) still gets its slot through its phone launcher activity.
        for (String pkg : favourites) {
            if (apps.containsKey(pkg) || hidden.contains(pkg)) continue;
            Intent phone = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
            List<ResolveInfo> hits = pm.queryIntentActivities(phone, 0);
            if (hits.isEmpty() || hits.get(0).activityInfo == null) continue;
            ResolveInfo ri = hits.get(0);
            apps.put(pkg, new Entry(pkg, String.valueOf(ri.loadLabel(pm)), new ComponentName(pkg, ri.activityInfo.name),
                    ri.activityInfo.applicationInfo.loadBanner(pm), ri.loadIcon(pm), null));
        }

        // Folders take their apps out of the grid. A folder whose apps are all gone disappears.
        Map<String, Entry> folders = new HashMap<>();
        for (String name : folderNames()) {
            List<Entry> kids = new ArrayList<>();
            for (String pkg : split(store.getString(FOLDER + name, ""))) {
                Entry e = apps.remove(pkg);
                if (e != null) kids.add(e);
            }
            if (!kids.isEmpty()) {
                folders.put(FOLDER + name, new Entry(FOLDER + name, name, null, folderBanner(kids), null, kids));
            }
        }

        List<Entry> fresh = new ArrayList<>();
        List<String> order = split(store.getString("order", ""));
        if (order.isEmpty()) {
            for (String pkg : favourites) {
                Entry e = apps.remove(pkg);
                if (e != null) fresh.add(e);
            }
        } else {
            for (String key : order) {
                Entry e = key.startsWith(FOLDER) ? folders.remove(key) : apps.remove(key);
                if (e != null) fresh.add(e);
            }
        }
        List<Entry> rest = new ArrayList<>(folders.values());
        rest.addAll(apps.values());
        Collections.sort(rest, (a, b) -> a.label.compareToIgnoreCase(b.label));
        fresh.addAll(rest);

        movingKey = null;
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String part : s.split("\n")) if (!part.isEmpty()) out.add(part);
        return out;
    }

    // ---- layout edits ----------------------------------------------------------------------------

    List<String> folderNames() {
        return split(store.getString("folders", ""));
    }

    private void saveOrder() {
        List<String> keys = new ArrayList<>();
        for (Entry e : items) keys.add(e.key);
        store.edit().putString("order", TextUtils.join("\n", keys)).apply();
    }

    /** Puts an app into a folder, creating the folder (in the app's place) when it is new. */
    void addToFolder(String name, String pkg) {
        List<String> names = folderNames();
        List<String> pkgs = split(store.getString(FOLDER + name, ""));
        if (!pkgs.contains(pkg)) pkgs.add(pkg);
        // A new folder takes the slot of the app it was made from.
        if (!names.contains(name)) {
            names.add(name);
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).key.equals(pkg)) {
                    Entry e = items.get(i);
                    items.set(i, new Entry(FOLDER + name, name, null, null, null, Collections.singletonList(e)));
                    break;
                }
            }
        }
        saveOrder();
        store.edit().putString("folders", TextUtils.join("\n", names))
                .putString(FOLDER + name, TextUtils.join("\n", pkgs)).apply();
        reload();
    }

    /** Takes an app out of a folder; it lands right after the folder (or in its place, if it was the last). */
    void removeFromFolder(String name, String pkg) {
        List<String> pkgs = split(store.getString(FOLDER + name, ""));
        pkgs.remove(pkg);
        List<String> order = split(store.getString("order", ""));
        int at = order.indexOf(FOLDER + name);
        if (at >= 0) order.add(at + 1, pkg);
        store.edit().putString(FOLDER + name, TextUtils.join("\n", pkgs))
                .putString("order", TextUtils.join("\n", order)).apply();
        if (pkgs.isEmpty()) deleteFolder(name);
        else reload();
    }

    void renameFolder(String from, String to) {
        if (to.isEmpty() || folderNames().contains(to)) return;
        List<String> names = folderNames();
        names.set(names.indexOf(from), to);
        List<String> order = split(store.getString("order", ""));
        int at = order.indexOf(FOLDER + from);
        if (at >= 0) order.set(at, FOLDER + to);
        store.edit().putString("folders", TextUtils.join("\n", names))
                .putString(FOLDER + to, store.getString(FOLDER + from, ""))
                .remove(FOLDER + from)
                .putString("order", TextUtils.join("\n", order)).apply();
        reload();
    }

    /** Removes the folder; its apps go back to the grid where the folder was. */
    void deleteFolder(String name) {
        List<String> names = folderNames();
        names.remove(name);
        List<String> order = split(store.getString("order", ""));
        int at = order.indexOf(FOLDER + name);
        if (at >= 0) {
            order.remove(at);
            order.addAll(at, split(store.getString(FOLDER + name, "")));
        }
        store.edit().putString("folders", TextUtils.join("\n", names)).remove(FOLDER + name)
                .putString("order", TextUtils.join("\n", order)).apply();
        reload();
    }

    // ---- moving ----------------------------------------------------------------------------------

    void startMove(int position) {
        movingKey = items.get(position).key;
        notifyItemChanged(position);
    }

    boolean isMoving() {
        return movingKey != null;
    }

    /** Moves the tile one step; returns its new position. */
    int moveTile(int from, int to) {
        to = Math.max(0, Math.min(items.size() - 1, to));
        if (to == from) return from;
        items.add(to, items.remove(from));
        notifyItemMoved(from, to);
        return to;
    }

    void endMove() {
        if (movingKey == null) return;
        movingKey = null;
        saveOrder();
        notifyDataSetChanged();
    }

    // ---- binding ---------------------------------------------------------------------------------

    /**
     * Tile width and height in pixels: about four fifths of a grid column (the rest is the gap), in the 5:3
     * shape tvOS uses. Worked out from the real screen width, so nothing assumes a 1080p panel.
     */
    static int[] tileSize(Context ctx, int columns) {
        int screen = ctx.getResources().getDisplayMetrics().widthPixels;
        int column = (screen - dp(ctx, 80)) / Math.max(1, columns);   // the grid's 40dp side padding
        int w = (int) (column * 0.82f);
        return new int[]{w, w * 3 / 5};
    }

    /** Height of one grid row: tile, its name underneath, and the row padding. */
    static int rowHeight(Context ctx, int columns) {
        return tileSize(ctx, columns)[1] + dp(ctx, 8 + 7 + 22 + 8);
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        // Size the tile from the grid's real width, so it fits any resolution and density.
        int[] size = tileSize(parent.getContext(), columns);
        View card = v.findViewById(R.id.card);
        card.getLayoutParams().width = size[0];
        card.getLayoutParams().height = size[1];
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nlp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nlp.width = size[0];
        nlp.topMargin = size[1] + dp(parent.getContext(), 7);
        return new Holder(v, this);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        bindTile(h.banner, h.icon, h.label, e);
        h.name.setText(e.label);
        h.name.setVisibility(position < columns ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        h.sheen.setAlpha(focused ? 1f : 0f);
        h.setWiggle(e.key.equals(movingKey));
    }

    /** Fills a tile: the banner when there is one, otherwise the icon over the app's name. */
    static void bindTile(ImageView banner, ImageView icon, TextView label, Entry e) {
        if (e.banner != null) {
            banner.setImageDrawable(e.banner);
            banner.setVisibility(View.VISIBLE);
            icon.setVisibility(View.GONE);
            label.setVisibility(View.GONE);
        } else {
            banner.setImageDrawable(null);
            banner.setVisibility(View.GONE);
            icon.setImageDrawable(e.icon);
            icon.setVisibility(View.VISIBLE);
            label.setText(e.label);
            label.setVisibility(View.VISIBLE);
        }
    }

    /** The TV launch intent for an app (its leanback activity, else its phone one), or null if not installed. */
    static Intent launchIntentFor(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    static void launch(Context ctx, Entry e) {
        // Settings and Google Play open as their tvOS-style front ends; those link on to Google's apps.
        Class<?> front = e.key.equals("com.android.tv.settings") ? TvSettingsActivity.class
                : e.key.equals("com.android.vending") ? StoreActivity.class : null;
        if (front != null) {
            ctx.startActivity(new Intent(ctx, front).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).key.hashCode();
    }

    Entry entryAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    /** Stable key for the tile at a position, or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).key : null;
    }

    /** Position of the tile with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key.equals(key)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    /** A folder tile: up to four of its apps in a 2×2 grid on dark glass. */
    private Drawable folderBanner(List<Entry> kids) {
        int w = 320, h = 180, pad = 14, gap = 8;
        int cw = (w - 2 * pad - gap) / 2, ch = (h - 2 * pad - gap) / 2;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF3A3A3E);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xFF2A2A2D);
        Path clip = new Path();
        for (int i = 0; i < Math.min(4, kids.size()); i++) {
            int x = pad + (i % 2) * (cw + gap), y = pad + (i / 2) * (ch + gap);
            RectF cell = new RectF(x, y, x + cw, y + ch);
            c.save();
            clip.reset();
            clip.addRoundRect(cell, 10, 10, Path.Direction.CW);
            c.clipPath(clip);
            c.drawRect(cell, bg);
            Entry k = kids.get(i);
            Drawable d = k.banner != null ? k.banner : k.icon;
            if (d != null) {
                d = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
                if (k.banner != null) {
                    d.setBounds(x, y, x + cw, y + ch);
                } else {
                    int s = ch - 16;
                    d.setBounds(x + (cw - s) / 2, y + 8, x + (cw + s) / 2, y + 8 + s);
                }
                d.draw(c);
            }
            c.restore();
        }
        return new BitmapDrawable(ctx.getResources(), bmp);
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        final View sheen;
        private ObjectAnimator wiggle;

        Holder(View v, AppAdapter adapter) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            sheen = v.findViewById(R.id.sheen);
            TileListener listener = adapter.listener;

            final float density = v.getResources().getDisplayMetrics().density;
            final float radius = density * 10f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);
            card.setCameraDistance(9000 * density);

            card.setOnClickListener(view -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) listener.onTileClicked(pos);
            });
            card.setOnLongClickListener(view -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && !adapter.isMoving()) listener.onTileLongPressed(pos);
                return true;
            });

            card.setOnKeyListener((view, keyCode, event) -> {
                int pos = getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return false;
                if (adapter.isMoving()) {
                    // Move mode: arrows carry the tile, OK or Back drops it. Swallow everything else.
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
                    switch (keyCode) {
                        case KeyEvent.KEYCODE_DPAD_LEFT:
                        case KeyEvent.KEYCODE_DPAD_RIGHT:
                        case KeyEvent.KEYCODE_DPAD_UP:
                        case KeyEvent.KEYCODE_DPAD_DOWN:
                            listener.onMoveKey(pos, keyCode);
                            return true;
                        case KeyEvent.KEYCODE_DPAD_CENTER:
                        case KeyEvent.KEYCODE_ENTER:
                        case KeyEvent.KEYCODE_BACK:
                            listener.onMoveDone();
                            return true;
                        default:
                            return true;
                    }
                }
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                return listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                // tvOS: the focused tile lifts and catches a sheen; no outline. (No elevation: its shadow smeared the dock.)
                sheen.animate().alpha(focused ? 1f : 0f).setDuration(200).start();
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 260 : 180)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 4f : -density * 6f)
                        .setDuration(220).start();
                int pos = getBindingAdapterPosition();
                if (!focused || pos == RecyclerView.NO_POSITION) return;
                tiltFrom(adapter.lastFocusPos, pos, adapter.columns);
                adapter.lastFocusPos = pos;
                listener.onTileFocused(pos, view);
            });
        }

        /** Parallax: the tile leans in the direction focus arrived from, then settles flat. */
        private void tiltFrom(int prev, int pos, int columns) {
            if (prev < 0 || prev == pos || wiggle != null) return;
            int dx = Integer.signum(pos % columns - prev % columns);
            int dy = Integer.signum(pos / columns - prev / columns);
            if (dx == 0 && dy == 0) return;
            card.setRotationY(dx * TILT);
            card.setRotationX(-dy * TILT);
            card.animate().rotationX(0f).rotationY(0f).setDuration(650)
                    .setInterpolator(new OvershootInterpolator(2.2f)).start();
        }

        /** The tile being moved jiggles until it is dropped. */
        void setWiggle(boolean on) {
            if (on && wiggle == null) {
                card.setRotationX(0f);
                card.setRotationY(0f);
                wiggle = ObjectAnimator.ofFloat(card, View.ROTATION, -1.8f, 1.8f);
                wiggle.setDuration(130);
                wiggle.setRepeatMode(ValueAnimator.REVERSE);
                wiggle.setRepeatCount(ValueAnimator.INFINITE);
                wiggle.start();
            } else if (!on && wiggle != null) {
                wiggle.cancel();
                wiggle = null;
                card.setRotation(0f);
            }
        }
    }
}
