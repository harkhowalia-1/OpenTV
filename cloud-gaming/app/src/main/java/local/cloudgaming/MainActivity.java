package local.cloudgaming;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cloud Gaming's home, as an Apple TV app would look: the focused game's art fills the screen behind its title
 * and description, with rows of games below (Jump Back In, then Xbox Cloud Gaming's own rows, live from
 * Microsoft). Everything works with the remote's arrows and OK; the pointer is never needed.
 */
public class MainActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private float density;

    private ImageView heroA, heroB;
    private View dim;
    private LinearLayout info;
    private TextView brand;
    private View chip;
    private TextView title, meta, description, profileName;
    private ImageView profilePic;
    private RecyclerView rows;
    private final List<Row> data = new ArrayList<>();
    private RowsAdapter rowsAdapter;
    private String shownHero;

    static final class Row {
        String title;
        final List<Xbox.Game> games = new ArrayList<>();
        List<XboxLive.Friend> friends;   // the Friends row; null for a row of games

        String signature() {
            StringBuilder b = new StringBuilder();
            for (Xbox.Game g : games) b.append(g.id).append(',');
            if (friends != null) for (XboxLive.Friend f : friends) b.append(f.xuid).append(f.online).append(f.presence).append(f.pic).append(',');
            return b.toString();
        }
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        density = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        heroA = hero();
        heroB = hero();
        root.addView(heroA, full());
        root.addView(heroB, full());

        // Apple's legibility gradients: dark at the left (behind the text) and the bottom (behind the rows).
        View side = new View(this);
        side.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xE6000000, 0x99000000, 0x00000000}));
        root.addView(side, full());
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xFF000000, 0xCC000000, 0x00000000}));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, (int) (getResources().getDisplayMetrics().heightPixels * 0.72f), Gravity.BOTTOM);
        root.addView(bottom, blp);

        // Dims the art once you move past the first row, as tvOS does.
        dim = new View(this);
        dim.setBackgroundColor(0xB3000000);
        dim.setAlpha(0f);
        root.addView(dim, full());

        // The focused game: title, publisher, a line or two about it.
        info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        title = text(40, 0xFFFFFFFF, "sans-serif-medium");
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        meta = text(15, 0xB3FFFFFF, "sans-serif-medium");
        meta.setPadding(0, dp(8), 0, 0);
        description = text(16, 0xCCFFFFFF, "sans-serif");
        description.setMaxLines(2);
        description.setEllipsize(TextUtils.TruncateAt.END);
        description.setLineSpacing(0, 1.15f);
        description.setPadding(0, dp(10), 0, 0);
        info.addView(title);
        info.addView(meta);
        info.addView(description);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams((int) (getResources().getDisplayMetrics().widthPixels * 0.5f), -2);
        ilp.setMargins(dp(64), dp(84), 0, 0);
        root.addView(info, ilp);


        // The rows, from just under the middle of the screen.
        rows = new RecyclerView(this);
        rows.setLayoutManager(new LinearLayoutManager(this));
        rows.setClipToPadding(false);
        rows.setClipChildren(false);
        rows.setPadding(0, (int) (getResources().getDisplayMetrics().heightPixels * 0.50f), 0, dp(40));
        rows.setItemAnimator(null);
        rowsAdapter = new RowsAdapter();
        rows.setAdapter(rowsAdapter);
        root.addView(rows, full());

        // Top bar, above the rows: the app's name, and who's signed in.
        brand = text(15, 0x99FFFFFF, "sans-serif-medium");
        brand.setText(R.string.brand);
        brand.setLetterSpacing(0.04f);
        FrameLayout.LayoutParams brlp = new FrameLayout.LayoutParams(-2, -2);
        brlp.setMargins(dp(64), dp(40), 0, 0);
        root.addView(brand, brlp);
        chip = profileChip();
        root.addView(chip, chipParams());
        setContentView(root);

        load(false);
    }

    /** A sign-in from the iPhone while this screen is open: the profile button updates at once. */
    private final android.content.BroadcastReceiver signedIn = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, Intent i) {
            updateProfile();
            accountFetched = 0;
            loadAccount();
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(signedIn);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        android.content.IntentFilter f = new android.content.IntentFilter(SessionReceiver.IMPORTED);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(signedIn, f, android.content.Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(signedIn, f);
        updateProfile();
        loadAccount();
    }

    @Override
    protected void onDestroy() {
        work.shutdownNow();
        super.onDestroy();
    }

    // ---- data ---------------------------------------------------------------------------------------------

    /** The rows: shown from the TV's copy at once, then brought up to date from Microsoft. */
    private void load(boolean refresh) {
        work.execute(() -> {
            for (String[] r : Xbox.ROWS) {
                List<String> ids = Xbox.list(this, r[0], refresh);
                if (ids.isEmpty()) continue;
                // Long rows: the first screenfuls now, the rest after the other rows have appeared.
                List<String> first = ids.subList(0, Math.min(ids.size(), 24));
                Map<String, Xbox.Game> games = Xbox.details(this, first);
                Row row = new Row();
                row.title = r[1];
                row.games.addAll(games.values());
                main.post(() -> addRow(row));
            }
            for (String[] r : Xbox.ROWS) {
                List<String> ids = Xbox.list(this, r[0], false);
                if (ids.size() <= 24) continue;
                Map<String, Xbox.Game> games = Xbox.details(this, ids);
                main.post(() -> {
                    for (int i = 0; i < data.size(); i++) {
                        if (!data.get(i).title.equals(r[1])) continue;
                        data.get(i).games.clear();
                        data.get(i).games.addAll(games.values());
                        rowsAdapter.notifyItemChanged(i);
                    }
                });
            }
        });
    }

    private void addRow(Row row) {
        // Most Popular always leads (Jump Back In may have arrived first); the rest follow in order.
        int at = row.title.equals(Xbox.ROWS[0][1]) ? 0 : data.size();
        data.add(at, row);
        rowsAdapter.notifyItemInserted(at);
        if (shownHero == null && !row.games.isEmpty()) {
            show(row.games.get(0));
            rows.post(() -> focusFirst());
        }
    }

    private void focusFirst() {
        RecyclerView.ViewHolder h = rows.findViewHolderForAdapterPosition(0);
        if (h instanceof RowHolder) {
            View card = ((RowHolder) h).list.getChildAt(0);
            if (card != null) card.requestFocus();
        }
    }

    // ---- your Xbox ----------------------------------------------------------------------------------------

    private long accountFetched;

    /**
     * Your own Xbox, as the Xbox app shows it: your profile, Jump Back In (the games you've played that the
     * cloud can stream, then the ones started on this TV) and your friends. The TV's copy shows at once, then
     * it's brought up to date from Xbox Live.
     */
    private void loadAccount() {
        work.execute(() -> accountData(false));
        if (!Account.signedIn(this)) return;
        if (XboxLive.auth(this) == null) XboxLive.keepPending(this);
        if (XboxLive.auth(this) == null) {
            // The session ran out: xbox.com renews it out of sight, then this runs again.
            XboxLive.renew(this, () -> {
                accountFetched = 0;
                loadAccount();
            });
            return;
        }
        if (System.currentTimeMillis() - accountFetched < 2 * 60_000) return;
        accountFetched = System.currentTimeMillis();
        work.execute(() -> accountData(true));
    }

    private void accountData(boolean fetch) {
        XboxLive.Profile me = XboxLive.profile(this, fetch);
        List<XboxLive.Title> played = XboxLive.titles(this, fetch);
        List<XboxLive.Friend> friends = XboxLive.friends(this, fetch);
        java.util.Set<String> cloud = new java.util.HashSet<>(Xbox.list(this, Xbox.ROWS[Xbox.ROWS.length - 1][0], false));
        List<String> ids = new ArrayList<>();
        for (XboxLive.Title t : played) if (t.streamable || cloud.contains(t.productId)) ids.add(t.productId);
        for (String id : Xbox.recent(this)) if (!ids.contains(id)) ids.add(id);
        Map<String, Xbox.Game> games = ids.isEmpty() ? new java.util.LinkedHashMap<>() : Xbox.details(this, ids);
        main.post(() -> {
            if (isDestroyed()) return;
            updateProfile(me);
            putRow(getString(R.string.jump_back_in), new ArrayList<>(games.values()), null);
            putRow(getString(R.string.friends), null, friends);
            refreshProgress();
        });
    }

    /**
     * Puts in, updates or removes one of your rows. They sit right under Most Popular: Jump Back In, then
     * Friends, whichever order the rows arrive in.
     */
    private void putRow(String title, List<Xbox.Game> games, List<XboxLive.Friend> friends) {
        boolean empty = friends != null ? friends.isEmpty() : games.isEmpty();
        int at = -1;
        for (int i = 0; i < data.size(); i++) if (data.get(i).title.equals(title)) at = i;
        if (empty) {
            if (at >= 0) {
                data.remove(at);
                rowsAdapter.notifyItemRemoved(at);
            }
            return;
        }
        Row row = at >= 0 ? data.get(at) : new Row();
        String before = row.signature();
        row.title = title;
        row.games.clear();
        if (games != null) row.games.addAll(games);
        row.friends = friends;
        if (at >= 0) {
            if (!before.equals(row.signature())) rowsAdapter.notifyItemChanged(at);
            return;
        }
        List<String> above = new ArrayList<>();
        above.add(Xbox.ROWS[0][1]);
        if (friends != null) above.add(getString(R.string.jump_back_in));
        int pos = 0;
        for (int i = 0; i < data.size(); i++) if (above.contains(data.get(i).title)) pos = i + 1;
        data.add(pos, row);
        rowsAdapter.notifyItemInserted(pos);
    }

    /** Achievement progress on the posters on screen, once your games have loaded. */
    private void refreshProgress() {
        for (int i = 0; i < rows.getChildCount(); i++) {
            RecyclerView.ViewHolder h = rows.getChildViewHolder(rows.getChildAt(i));
            if (!(h instanceof RowHolder)) continue;
            RecyclerView list = ((RowHolder) h).list;
            for (int j = 0; j < list.getChildCount(); j++) {
                RecyclerView.ViewHolder c = list.getChildViewHolder(list.getChildAt(j));
                if (c instanceof CardHolder) ((CardHolder) c).bindProgress();
            }
        }
    }
    // ---- hero ---------------------------------------------------------------------------------------------

    private final Runnable showPending = () -> { };
    private Xbox.Game pending;

    /** The focused game, shown big behind everything (after a moment, so quick moves don't flicker). */
    private void focusGame(Xbox.Game g) {
        pending = g;
        main.removeCallbacksAndMessages(showPending);
        main.postAtTime(() -> {
            if (pending == g) show(g);
        }, showPending, android.os.SystemClock.uptimeMillis() + 220);
        title.setText(g.title);
        meta.setText(g.publisher);
        description.setText(clean(g.description));
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** First row: the game's name and art; further down: the rows take over and the art dims. */
    private void onRowFocused(int position) {
        boolean first = position <= 0;
        info.animate().alpha(first ? 1f : 0f).setDuration(250).start();
        brand.animate().alpha(first ? 1f : 0f).setDuration(250).start();
        chip.animate().alpha(first ? 1f : 0f).setDuration(250).start();
        dim.animate().alpha(first ? 0f : 1f).setDuration(300).start();
    }

    private void show(Xbox.Game g) {
        title.setText(g.title);
        meta.setText(g.publisher);
        description.setText(clean(g.description));
        String url = Xbox.sized(g.hero != null ? g.hero : g.poster, getResources().getDisplayMetrics().widthPixels);
        if (url == null || url.equals(shownHero)) return;
        shownHero = url;
        Images.load(this, url, bmp -> {
            if (!url.equals(shownHero)) return;
            // heroA sits under heroB: the new picture goes under, the old one on top fades away.
            heroB.animate().cancel();
            heroA.setImageBitmap(bmp);
            heroA.setAlpha(1f);
            heroB.animate().alpha(0f).setDuration(450).withEndAction(() -> {
                heroB.setImageBitmap(bmp);
                heroB.setAlpha(1f);
                heroA.setImageDrawable(null);
            }).start();
        });
    }

    private ImageView hero() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    // ---- profile -----------------------------------------------------------------------------------------

    private TextView profileScore;

    /** Who's signed in: gamerpic, gamertag and Gamerscore, as at the top of the Xbox app. */
    private View profileChip() {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(6), dp(6), dp(18), dp(6));
        chip.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(28));
        bg.setColor(0x33FFFFFF);
        chip.setBackground(bg);
        profilePic = new ImageView(this);
        profilePic.setScaleType(ImageView.ScaleType.CENTER_CROP);
        profilePic.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        profilePic.setClipToOutline(true);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xFF107C10);   // Xbox green until the gamerpic loads
        profilePic.setBackground(dot);
        chip.addView(profilePic, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(10), 0, 0, 0);
        profileName = text(15, 0xFFFFFFFF, "sans-serif-medium");
        profileName.setShadowLayer(0, 0, 0, 0);
        profileScore = text(12, 0xB3FFFFFF, "sans-serif-medium");
        profileScore.setShadowLayer(0, 0, 0, 0);
        profileScore.setVisibility(View.GONE);
        names.addView(profileName);
        names.addView(profileScore);
        chip.addView(names);
        chip.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? 0xFFFFFFFF : 0x33FFFFFF);
            profileName.setTextColor(f ? 0xFF000000 : 0xFFFFFFFF);
            profileScore.setTextColor(f ? 0x99000000 : 0xB3FFFFFF);
            v.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(160).start();
        });
        chip.setOnClickListener(v -> onProfile());
        return chip;
    }

    private FrameLayout.LayoutParams chipParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lp.setMargins(0, dp(30), dp(56), 0);
        return lp;
    }

    private long gamerscore = -1;

    private void updateProfile(XboxLive.Profile me) {
        if (me != null && Account.signedIn(this)) gamerscore = me.gamerscore;
        updateProfile();
    }

    private void updateProfile() {
        boolean in = Account.signedIn(this);
        String tag = Account.gamertag(this);
        profileName.setText(tag != null ? tag : in ? getString(R.string.signin_done) : getString(R.string.sign_in));
        String pic = Account.gamerpic(this);
        if (pic != null) Images.into(profilePic, pic);
        else profilePic.setImageDrawable(null);
        if (!in) gamerscore = -1;
        profileScore.setVisibility(gamerscore >= 0 ? View.VISIBLE : View.GONE);
        if (gamerscore >= 0) profileScore.setText(getString(R.string.gamerscore, score(gamerscore)));
    }

    private static String score(long n) {
        return java.text.NumberFormat.getIntegerInstance().format(n);
    }

    private void onProfile() {
        if (!Account.signedIn(this)) {
            startActivity(new Intent(this, SignInActivity.class));
            return;
        }
        String who = Account.gamertag(this);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(who != null ? who : getString(R.string.signin_done))
                .setItems(new CharSequence[]{getString(R.string.open_xbox), getString(R.string.sign_out)}, (d, which) -> {
                    if (which == 0) {
                        startActivity(new Intent(this, StreamActivity.class)
                                .putExtra(StreamActivity.EXTRA_URL, "https://www.xbox.com/" + Xbox.language() + "/play"));
                    } else {
                        Account.signedOut(this);
                        updateProfile();
                        loadAccount();   // your rows go; games started on this TV stay in Jump Back In
                    }
                })
                .show();
    }

    private void showFriend(XboxLive.Friend f) {
        String about = (f.presence == null || f.presence.isEmpty() ? "" : f.presence + "\n")
                + getString(R.string.gamerscore, score(f.gamerscore));
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(f.gamertag)
                .setMessage(about)
                .setPositiveButton(R.string.done, null)
                .show();
    }

    // ---- rows ---------------------------------------------------------------------------------------------

    private final class RowsAdapter extends RecyclerView.Adapter<RowHolder> {
        @NonNull
        @Override
        public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new RowHolder(parent.getContext());
        }

        @Override
        public void onBindViewHolder(@NonNull RowHolder h, int position) {
            Row r = data.get(position);
            if (r.friends != null) {
                int online = 0;
                for (XboxLive.Friend f : r.friends) if (f.online) online++;
                h.heading.setText(online > 0 ? getString(R.string.friends_online, online) : r.title);
                if (h.list.getAdapter() != h.people) h.list.setAdapter(h.people);
                h.people.friends = r.friends;
                h.people.notifyDataSetChanged();
            } else {
                h.heading.setText(r.title);
                if (h.list.getAdapter() != h.cards) h.list.setAdapter(h.cards);
                h.cards.games = r.games;
                h.cards.notifyDataSetChanged();
            }
        }

        @Override
        public int getItemCount() {
            return data.size();
        }
    }

    private final class RowHolder extends RecyclerView.ViewHolder {
        final TextView heading;
        final RecyclerView list;
        final CardsAdapter cards = new CardsAdapter();
        final FriendsAdapter people = new FriendsAdapter();

        RowHolder(Context c) {
            super(new LinearLayout(c));
            LinearLayout box = (LinearLayout) itemView;
            box.setOrientation(LinearLayout.VERTICAL);
            box.setClipChildren(false);
            box.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            heading = text(19, 0xE6FFFFFF, "sans-serif-medium");
            heading.setPadding(dp(64), dp(14), 0, dp(10));
            box.addView(heading);
            list = new RecyclerView(c);
            list.setLayoutManager(new LinearLayoutManager(c, LinearLayoutManager.HORIZONTAL, false));
            list.setClipToPadding(false);
            list.setClipChildren(false);
            list.setPadding(dp(64), dp(10), dp(64), dp(18));
            list.setItemAnimator(null);
            list.setAdapter(cards);
            box.addView(list, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    /** What every card in a row does: brings its row into view when focused; Up from the first row reaches the profile. */
    private void rowCard(View card) {
        card.setOnKeyListener((v, code, e) -> {
            if (code != android.view.KeyEvent.KEYCODE_DPAD_UP || e.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            View row = (View) v.getParent().getParent();
            if (rows.getChildAdapterPosition(row) != 0 || chip == null) return false;
            chip.requestFocus();
            return true;
        });
    }

    private void cardFocused(View v) {
        // Keep the focused row in view as you move down (rows scroll; the hero stays).
        View row = (View) v.getParent().getParent();
        int pos = rows.getChildAdapterPosition(row);
        onRowFocused(pos);
        // The first row sits under the game's name; later rows rise to the top of the screen.
        int target = pos <= 0 ? rows.getPaddingTop() : dp(96);
        rows.smoothScrollBy(0, row.getTop() - target);
    }

    private final class CardsAdapter extends RecyclerView.Adapter<CardHolder> {
        List<Xbox.Game> games = new ArrayList<>();

        @NonNull
        @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new CardHolder(parent.getContext());
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder h, int position) {
            Xbox.Game g = games.get(position);
            h.game = g;
            Images.into(h.poster, Xbox.sized(g.poster, dp(132)));
            h.bindProgress();
        }

        @Override
        public int getItemCount() {
            return games.size();
        }
    }

    /**
     * A game's poster: lifts and grows when focused, with a soft shadow, as on tvOS. Games you've played show
     * your achievement progress along the bottom, as in the Xbox app.
     */
    private final class CardHolder extends RecyclerView.ViewHolder {
        final ImageView poster;
        final View shade, track, fill;
        Xbox.Game game;

        CardHolder(Context c) {
            super(new FrameLayout(c));
            FrameLayout card = (FrameLayout) itemView;
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(132), dp(198));
            lp.setMarginEnd(dp(22));
            card.setLayoutParams(lp);
            GradientDrawable ph = new GradientDrawable();
            ph.setColor(0xFF1C1C1E);
            ph.setCornerRadius(dp(12));
            card.setBackground(ph);
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12));
                }
            });
            card.setClipToOutline(true);
            card.setFocusable(true);
            poster = new ImageView(c);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(poster, full());
            shade = new View(c);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xB3000000, 0x00000000}));
            card.addView(shade, new FrameLayout.LayoutParams(-1, dp(44), Gravity.BOTTOM));
            FrameLayout bar = new FrameLayout(c);
            GradientDrawable tb = new GradientDrawable();
            tb.setColor(0x59FFFFFF);
            tb.setCornerRadius(dp(2));
            bar.setBackground(tb);
            fill = new View(c);
            GradientDrawable fb = new GradientDrawable();
            fb.setColor(0xFFFFFFFF);
            fb.setCornerRadius(dp(2));
            fill.setBackground(fb);
            bar.addView(fill, new FrameLayout.LayoutParams(0, -1));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, dp(4), Gravity.BOTTOM);
            blp.setMargins(dp(10), 0, dp(10), dp(10));
            card.addView(bar, blp);
            track = bar;
            card.setOnFocusChangeListener((v, f) -> {
                v.animate().scaleX(f ? 1.14f : 1f).scaleY(f ? 1.14f : 1f).translationZ(f ? dp(18) : 0)
                        .setInterpolator(new DecelerateInterpolator(1.6f)).setDuration(f ? 220 : 160).start();
                if (f && game != null) {
                    focusGame(game);
                    cardFocused(v);
                }
            });
            rowCard(card);
            card.setOnClickListener(v -> {
                if (game != null) startActivity(new Intent(MainActivity.this, GameActivity.class).putExtra("id", game.id));
            });
        }

        void bindProgress() {
            XboxLive.Title t = game == null ? null : XboxLive.title(game.id);
            boolean show = t != null && t.achievements > 0;
            shade.setVisibility(show ? View.VISIBLE : View.GONE);
            track.setVisibility(show ? View.VISIBLE : View.GONE);
            String name = game == null ? "" : game.title;
            if (show) {
                ViewGroup.LayoutParams f = fill.getLayoutParams();
                f.width = Math.max(dp(4), dp(112) * Math.min(100, t.percent) / 100);
                fill.setLayoutParams(f);
                name += ", " + getString(R.string.progress, t.percent);
            }
            itemView.setContentDescription(name);
        }
    }

    private final class FriendsAdapter extends RecyclerView.Adapter<FriendHolder> {
        List<XboxLive.Friend> friends = new ArrayList<>();

        @NonNull
        @Override
        public FriendHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new FriendHolder(parent.getContext());
        }

        @Override
        public void onBindViewHolder(@NonNull FriendHolder h, int position) {
            h.bind(friends.get(position));
        }

        @Override
        public int getItemCount() {
            return friends.size();
        }
    }

    /** A friend: gamerpic (green dot when they're online), gamertag and what they're up to. */
    private final class FriendHolder extends RecyclerView.ViewHolder {
        final FrameLayout face;
        final ImageView pic;
        final View online;
        final TextView name, status;
        XboxLive.Friend friend;

        FriendHolder(Context c) {
            super(new LinearLayout(c));
            LinearLayout box = (LinearLayout) itemView;
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setClipChildren(false);
            box.setClipToPadding(false);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(132), -2);
            lp.setMarginEnd(dp(22));
            box.setLayoutParams(lp);
            box.setFocusable(true);
            face = new FrameLayout(c);
            face.setClipChildren(false);
            pic = new ImageView(c);
            pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable ph = new GradientDrawable();
            ph.setShape(GradientDrawable.OVAL);
            ph.setColor(0xFF2C2C2E);
            pic.setBackground(ph);
            pic.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setOval(0, 0, v.getWidth(), v.getHeight());
                }
            });
            pic.setClipToOutline(true);
            face.addView(pic, new FrameLayout.LayoutParams(dp(104), dp(104)));
            online = new View(c);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(0xFF30D158);
            dot.setStroke(dp(3), 0xFF000000);
            online.setBackground(dot);
            online.setElevation(dp(30));   // stays above the picture as it lifts
            FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.BOTTOM | Gravity.END);
            olp.setMargins(0, 0, dp(2), dp(2));
            face.addView(online, olp);
            box.addView(face, new LinearLayout.LayoutParams(dp(104), dp(104)));
            name = text(14, 0xFFFFFFFF, "sans-serif-medium");
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            name.setPadding(0, dp(12), 0, 0);
            box.addView(name, new LinearLayout.LayoutParams(-1, -2));
            status = text(12, 0x99FFFFFF, "sans-serif");
            status.setSingleLine(true);
            status.setEllipsize(TextUtils.TruncateAt.END);
            status.setGravity(Gravity.CENTER);
            status.setPadding(0, dp(2), 0, 0);
            box.addView(status, new LinearLayout.LayoutParams(-1, -2));
            box.setOnFocusChangeListener((v, f) -> {
                face.animate().scaleX(f ? 1.12f : 1f).scaleY(f ? 1.12f : 1f).setDuration(f ? 220 : 160)
                        .setInterpolator(new DecelerateInterpolator(1.6f)).start();
                pic.animate().translationZ(f ? dp(18) : 0).setDuration(f ? 220 : 160).start();
                name.animate().translationY(f ? dp(8) : 0).setDuration(f ? 220 : 160).start();
                status.animate().translationY(f ? dp(8) : 0).setDuration(f ? 220 : 160).start();
                if (f) cardFocused(v);
            });
            rowCard(box);
            box.setOnClickListener(v -> {
                if (friend != null) showFriend(friend);
            });
        }

        void bind(XboxLive.Friend f) {
            friend = f;
            name.setText(f.gamertag);
            status.setText(f.presence);
            online.setVisibility(f.online ? View.VISIBLE : View.GONE);
            if (f.pic != null) Images.into(pic, f.pic);
            else pic.setImageDrawable(null);
            itemView.setContentDescription(f.gamertag + (f.presence == null || f.presence.isEmpty() ? "" : ", " + f.presence));
        }
    }
    // ---- helpers ------------------------------------------------------------------------------------------

    private TextView text(float sp, int color, String family) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setShadowLayer(dp(8), 0, dp(1), 0x66000000);
        return t;
    }

    private static FrameLayout.LayoutParams full() {
        return new FrameLayout.LayoutParams(-1, -1);
    }
}
