package local.cloudgaming;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;

/** A game's page, Apple TV style: its key art full screen, the title or logo, a few lines about it, and Play. */
public class GameActivity extends Activity {

    private float density;

    private int dp(float v) {
        return Math.round(v * density);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        density = getResources().getDisplayMetrics().density;
        String id = getIntent().getStringExtra("id");
        Xbox.Game g = id == null ? null : Xbox.cached(id);
        if (g == null) {
            finish();
            return;
        }
        int w = getResources().getDisplayMetrics().widthPixels;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setAlpha(0f);
        root.addView(art, new FrameLayout.LayoutParams(-1, -1));
        Images.load(this, Xbox.sized(g.hero != null ? g.hero : g.poster, w), bmp -> {
            art.setImageBitmap(bmp);
            art.animate().alpha(1f).setDuration(400).start();
        });
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF2000000, 0xB3000000, 0x00000000}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.BOTTOM);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams((int) (w * 0.46f), -1);
        clp.setMargins(dp(72), dp(64), 0, dp(72));
        root.addView(col, clp);

        // The game's logo when it has one (as Apple's apps show), otherwise its name.
        TextView name = text(44, 0xFFFFFFFF, "sans-serif-medium");
        name.setText(g.title);
        name.setMaxLines(3);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name);
        if (g.logo != null) {
            ImageView logo = new ImageView(this);
            logo.setAdjustViewBounds(true);
            logo.setScaleType(ImageView.ScaleType.FIT_START);
            logo.setMaxHeight(dp(130));
            logo.setMaxWidth(dp(360));
            logo.setVisibility(View.GONE);
            col.addView(logo, 0, new LinearLayout.LayoutParams(-2, -2));
            Images.load(this, Xbox.sized(g.logo, dp(360)), bmp -> {
                // Only a logo with transparency reads well over the art.
                if (!bmp.hasAlpha()) return;
                logo.setImageBitmap(bmp);
                logo.setVisibility(View.VISIBLE);
                name.setVisibility(View.GONE);
            });
        }
        TextView meta = text(16, 0xB3FFFFFF, "sans-serif-medium");
        meta.setText(g.publisher);
        meta.setPadding(0, dp(12), 0, 0);
        col.addView(meta);
        TextView about = text(17, 0xD9FFFFFF, "sans-serif");
        about.setText(g.description);
        about.setMaxLines(4);
        about.setEllipsize(TextUtils.TruncateAt.END);
        about.setLineSpacing(0, 1.2f);
        about.setPadding(0, dp(14), 0, dp(28));
        col.addView(about);

        TextView play = text(18, 0xFF000000, "sans-serif-medium");
        play.setText(R.string.play);
        play.setGravity(Gravity.CENTER);
        play.setShadowLayer(0, 0, 0, 0);
        play.setCompoundDrawablePadding(dp(10));
        play.setPadding(dp(36), dp(14), dp(40), dp(14));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(28));
        pill.setColor(0xFFFFFFFF);
        play.setBackground(pill);
        play.setFocusable(true);
        play.setOnFocusChangeListener((v, f) -> {
            pill.setColor(f ? 0xFFFFFFFF : 0x40FFFFFF);
            play.setTextColor(f ? 0xFF000000 : 0xFFFFFFFF);
            v.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).translationZ(f ? dp(10) : 0).setDuration(160).start();
        });
        play.setOnClickListener(v -> {
            if (!Account.signedIn(this)) {
                // Sign in first (on the iPhone), then the game starts by itself.
                startActivity(new Intent(this, SignInActivity.class).putExtra(SignInActivity.EXTRA_THEN_GAME, g.id));
                return;
            }
            startActivity(new Intent(this, StreamActivity.class)
                    .putExtra(StreamActivity.EXTRA_URL, Xbox.launchUrl(g))
                    .putExtra(StreamActivity.EXTRA_GAME, g.id));
        });
        col.addView(play, new LinearLayout.LayoutParams(-2, -2));
        setContentView(root);
        play.requestFocus();
        // Fill in anything missing (opened from Jump Back In before the catalog loaded).
        if (g.description == null || g.description.isEmpty()) {
            new Thread(() -> Xbox.details(this, Collections.singletonList(g.id))).start();
        }
    }

    private TextView text(float sp, int color, String family) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setShadowLayer(dp(10), 0, dp(1), 0x80000000);
        return t;
    }
}
