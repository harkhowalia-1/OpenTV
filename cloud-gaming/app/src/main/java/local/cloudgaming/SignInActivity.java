package local.cloudgaming;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Signing in to Xbox, made for a TV: you sign in on your iPhone (Otv Remote, with your saved passwords), and
 * this screen finishes by itself when the iPhone hands the sign-in over. Signing in on the TV's own web page is
 * still there as a fallback. Opened from Play (then the game starts) or from the profile button.
 */
public class SignInActivity extends Activity {

    static final String EXTRA_THEN_GAME = "thenGame";

    private TextView title, body;
    private float density;

    private int dp(float v) {
        return Math.round(v * density);
    }

    private final BroadcastReceiver done = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            finishSignedIn(i.getStringExtra("gamertag"));
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        density = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        View glow = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.6f);
        g.setColors(new int[]{0x55107C10, 0x00000000});
        glow.setBackground(g);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(col, new FrameLayout.LayoutParams(dp(640), -2, Gravity.CENTER));

        title = text(38, 0xFFFFFFFF, "sans-serif-medium");
        title.setText(R.string.signin_title);
        title.setGravity(Gravity.CENTER);
        col.addView(title);
        body = text(18, 0xCCFFFFFF, "sans-serif");
        body.setText(R.string.signin_phone);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(0, 1.25f);
        body.setPadding(0, dp(18), 0, dp(10));
        col.addView(body);

        // Where to find it on the iPhone, as a short path of steps.
        TextView steps = text(17, 0xFFFFFFFF, "sans-serif-medium");
        steps.setText(R.string.signin_steps);
        steps.setGravity(Gravity.CENTER);
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(0x26FFFFFF);
        chip.setCornerRadius(dp(14));
        steps.setBackground(chip);
        steps.setPadding(dp(22), dp(14), dp(22), dp(14));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = dp(8);
        slp.bottomMargin = dp(34);
        col.addView(steps, slp);

        TextView web = button(getString(R.string.signin_on_tv));
        web.setOnClickListener(v -> startActivity(new Intent(this, StreamActivity.class)
                .putExtra(StreamActivity.EXTRA_URL, "https://www.xbox.com/" + Xbox.language() + "/play")
                .putExtra(StreamActivity.EXTRA_SIGNIN, true)));
        col.addView(web, new LinearLayout.LayoutParams(-2, -2));
        setContentView(root);
        web.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(SessionReceiver.IMPORTED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(done, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(done, f);
        // Back from signing in on the TV's page.
        if (Account.signedIn(this)) finishSignedIn(Account.gamertag(this));
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(done);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private boolean finished;

    private void finishSignedIn(String gamertag) {
        if (finished) return;
        finished = true;
        title.setText(gamertag != null ? getString(R.string.signin_welcome, gamertag) : getString(R.string.signin_done));
        body.setText(R.string.signin_ready);
        String game = getIntent().getStringExtra(EXTRA_THEN_GAME);
        title.postDelayed(() -> {
            Xbox.Game g = game == null ? null : Xbox.cached(game);
            if (g != null) {
                startActivity(new Intent(this, StreamActivity.class)
                        .putExtra(StreamActivity.EXTRA_URL, Xbox.launchUrl(g))
                        .putExtra(StreamActivity.EXTRA_GAME, g.id));
            }
            finish();
        }, 1600);
    }

    private TextView button(String label) {
        TextView t = text(17, 0xFFFFFFFF, "sans-serif-medium");
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(30), dp(13), dp(30), dp(13));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(26));
        pill.setColor(0x40FFFFFF);
        t.setBackground(pill);
        t.setFocusable(true);
        t.setOnFocusChangeListener((v, f) -> {
            pill.setColor(f ? 0xFFFFFFFF : 0x40FFFFFF);
            t.setTextColor(f ? 0xFF000000 : 0xFFFFFFFF);
            v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(150).start();
        });
        return t;
    }

    private TextView text(float sp, int color, String family) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        return t;
    }
}
