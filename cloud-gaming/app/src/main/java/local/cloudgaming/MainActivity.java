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
        refreshRecent();
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

    /** Jump Back In: the games played on this TV, newest first, right below Most Popular. */
    private void refreshRecent() {
        List<String> ids = Xbox.recent(this);
        if (ids.isEmpty()) return;
        work.execute(() -> {
            Map<String, Xbox.Game> games = Xbox.details(this, ids);
            main.post(() -> {
                String title = getString(R.string.jump_back_in);
                int at = -1;
                for (int i = 0; i < data.size(); i++) if (data.get(i).title.equals(title)) at = i;
                Row row = at >= 0 ? data.get(at) : new Row();
                row.title = title;
                row.games.clear();
                row.games.addAll(games.values());
                if (at >= 0) {
                    rowsAdapter.notifyItemChanged(at);
                } else {
                    int pos = !data.isEmpty() && data.get(0).title.equals(Xbox.ROWS[0][1]) ? 1 : 0;
                    data.add(pos, row);
                    rowsAdapter.notifyItemInserted(pos);
                }
            });
        });
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

    private View profileChip() {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(6), dp(6), dp(16), dp(6));
        chip.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
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
        chip.addView(profilePic, new LinearLayout.LayoutParams(dp(32), dp(32)));
        profileName = text(15, 0xFFFFFFFF, "sans-serif-medium");
        profileName.setPadding(dp(10), 0, 0, 0);
        chip.addView(profileName);
        chip.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? 0xFFFFFFFF : 0x33FFFFFF);
            profileName.setTextColor(f ? 0xFF000000 : 0xFFFFFFFF);
            v.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(160).start();
        });
        chip.setOnClickListener(v -> onProfile());
        return chip;
    }

    private FrameLayout.LayoutParams chipParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lp.setMargins(0, dp(32), dp(56), 0);
        return lp;
    }

    private void updateProfile() {
        String tag = Account.gamertag(this);
        profileName.setText(tag != null ? tag : Account.signedIn(this) ? getString(R.string.signin_done) : getString(R.string.sign_in));
        String pic = Account.gamerpic(this);
        if (pic != null) Images.into(profilePic, pic);
        else profilePic.setImageDrawable(null);
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
                    }
                })
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
            h.heading.setText(r.title);
            h.cards.games = r.games;
            h.cards.notifyDataSetChanged();
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
            h.poster.setContentDescription(g.title);
            Images.into(h.poster, Xbox.sized(g.poster, dp(132)));
        }

        @Override
        public int getItemCount() {
            return games.size();
        }
    }

    /** A game's poster: lifts and grows when focused, with a soft shadow, as on tvOS. */
    private final class CardHolder extends RecyclerView.ViewHolder {
        final ImageView poster;
        Xbox.Game game;

        CardHolder(Context c) {
            super(new ImageView(c));
            poster = (ImageView) itemView;
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(132), dp(198));
            lp.setMarginEnd(dp(22));
            poster.setLayoutParams(lp);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable ph = new GradientDrawable();
            ph.setColor(0xFF1C1C1E);
            ph.setCornerRadius(dp(12));
            poster.setBackground(ph);
            poster.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12));
                }
            });
            poster.setClipToOutline(true);
            poster.setFocusable(true);
            poster.setOnFocusChangeListener((v, f) -> {
                v.animate().scaleX(f ? 1.14f : 1f).scaleY(f ? 1.14f : 1f).translationZ(f ? dp(18) : 0)
                        .setInterpolator(new DecelerateInterpolator(1.6f)).setDuration(f ? 220 : 160).start();
                if (f && game != null) {
                    focusGame(game);
                    // Keep the focused row in view as you move down (rows scroll; the hero stays).
                    View row = (View) v.getParent().getParent();
                    int pos = rows.getChildAdapterPosition(row);
                    onRowFocused(pos);
                    // The first row sits under the game's name; later rows rise to the top of the screen.
                    int target = pos <= 0 ? rows.getPaddingTop() : dp(96);
                    rows.smoothScrollBy(0, row.getTop() - target);
                }
            });
            // Up from the first row reaches the profile (focus search alone can't find it across the screen).
            poster.setOnKeyListener((v, code, e) -> {
                if (code != android.view.KeyEvent.KEYCODE_DPAD_UP || e.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                View row = (View) v.getParent().getParent();
                if (rows.getChildAdapterPosition(row) != 0 || chip == null) return false;
                chip.requestFocus();
                return true;
            });
            poster.setOnClickListener(v -> {
                if (game != null) startActivity(new Intent(MainActivity.this, GameActivity.class).putExtra("id", game.id));
            });
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
