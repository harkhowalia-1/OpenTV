package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The launcher's always-on helper (an accessibility service, so the system keeps it running):
 * <ul>
 *   <li>Home button: a press opens this launcher, holding it opens Control Center;</li>
 *   <li>if one of Google's own home screens ever comes up, this launcher replaces it;</li>
 *   <li>hosts the phone/Mac remote ({@link RemoteServer}): arrows, OK, Back and Home as system actions,
 *       media keys, wake/sleep, and typing into the focused text field.</li>
 * </ul>
 * Enabled once over adb, see debloat.txt.
 */
public class HomeKeyService extends AccessibilityService implements RemoteServer.Actions, ControlServer.Actions {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private static volatile HomeKeyService current;

    /** Control Center's "Close Background Apps". */
    static void closeBackgroundApps() {
        HomeKeyService s = current;
        if (s != null) s.handler.post(() -> AppCloser.closeAll(s));
    }

    private boolean homeDown;
    private RemoteServer remote;
    private AppleHome appleHome;
    private ControlServer control;
    private ScreenReceiver screen;
    private PointerOverlay pointer;
    private volatile ControlServer.Client pointerOwner;
    private View codeCard;
    private final android.accounts.OnAccountsUpdateListener accountsChanged = accounts -> Profiles.syncAccounts(this);

    private final Runnable longPress = () -> {
        homeDown = false;
        ControlCenterActivity.toggle(this);
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        current = this;
        remote = new RemoteServer(this, this);
        remote.start();
        appleHome = new AppleHome(this, this);
        appleHome.start();
        control = new ControlServer(this, this);
        control.start();
        screen = new ScreenReceiver(this);
        screen.start();
        // The Mac's two-finger click is the Home button.
        pointer = new PointerOverlay(this, () -> handler.post(this::goHome));
        // A Google account added or removed anywhere on the TV gets (or loses) its profile straight away.
        try {
            android.accounts.AccountManager.get(this).addOnAccountsUpdatedListener(accountsChanged, handler, true,
                    new String[]{"com.google"});
        } catch (RuntimeException e) {
            Profiles.syncAccounts(this);
        }
    }

    @Override
    public void onDestroy() {
        if (current == this) current = null;
        if (remote != null) remote.stop();
        if (appleHome != null) appleHome.stop();
        try {
            android.accounts.AccountManager.get(this).removeOnAccountsUpdatedListener(accountsChanged);
        } catch (RuntimeException ignored) {
        }
        if (control != null) control.stop();
        if (screen != null) screen.stop();
        if (pointer != null) pointer.hide();
        hideCode();
        super.onDestroy();
    }

    // ---- Home button and Google's home screens --------------------------------------------------

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // Back closes the pairing code.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && codeCard != null) {
            if (event.getAction() == KeyEvent.ACTION_UP) hideCodeNow();
            return true;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        // Key repeats are made after accessibility sees the key, so time the press instead.
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            homeDown = true;
            handler.postDelayed(longPress, (long) (ViewConfiguration.getLongPressTimeout() * 0.8f));
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(longPress);
            if (homeDown) goHome();
            homeDown = false;
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence cls = event.getClassName();
        CharSequence pkg = event.getPackageName();
        if (cls == null || pkg == null) return;
        String c = cls.toString();
        String p = pkg.toString();
        if (!p.equals(getPackageName())) AppCloser.opened.add(p);
        boolean stockHome = (p.equals("com.google.android.tungsten.setupwraith") && c.endsWith("RecoveryActivity"))
                || (p.equals("com.android.tv.settings") && c.endsWith("FallbackHome"))
                || p.equals("com.google.android.apps.tv.launcherx");
        if (stockHome) goHome();
    }

    private void goHome() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacks(longPress);
    }

    // ---- remote actions (called from the server's threads) ----------------------------------------

    @Override
    public void key(String name) {
        handler.post(() -> {
            boolean wasDreaming = ClockDream.active != null;
            wakeIfAsleep(name);
            // Moving or selecting while the screensaver was up only wakes the screen, as on a real remote.
            if (wasDreaming && !name.equals("home") && !name.equals("power") && !name.startsWith("vol")
                    && !name.equals("mute") && !name.equals("playpause")) return;
            switch (name) {
                case "up": performGlobalAction(GLOBAL_ACTION_DPAD_UP); break;
                case "down": performGlobalAction(GLOBAL_ACTION_DPAD_DOWN); break;
                case "left": performGlobalAction(GLOBAL_ACTION_DPAD_LEFT); break;
                case "right": performGlobalAction(GLOBAL_ACTION_DPAD_RIGHT); break;
                case "select": performGlobalAction(GLOBAL_ACTION_DPAD_CENTER); break;
                case "back": performGlobalAction(GLOBAL_ACTION_BACK); break;
                case "home": goHome(); break;
                case "control": ControlCenterActivity.toggle(this); break;
                case "playpause": media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); break;
                case "next": media(KeyEvent.KEYCODE_MEDIA_NEXT); break;
                case "previous": media(KeyEvent.KEYCODE_MEDIA_PREVIOUS); break;
                case "volup": volume(AudioManager.ADJUST_RAISE); break;
                case "voldown": volume(AudioManager.ADJUST_LOWER); break;
                case "mute": volume(AudioManager.ADJUST_TOGGLE_MUTE); break;
                case "power": power(); break;
                default: break;
            }
        });
    }

    /** Any key wakes a sleeping TV, or closes the screensaver, first, like a real remote. */
    private void wakeIfAsleep(String name) {
        ClockDream dream = ClockDream.active;
        if (dream != null) dream.finish();
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm == null || pm.isInteractive() || name.equals("power")) return;
        wake(pm);
    }

    @SuppressWarnings("deprecation")
    private void wake(PowerManager pm) {
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP, "tvhome:remote");
        wl.acquire(3000);
    }

    /** Power: wakes a sleeping TV, otherwise puts it to sleep. */
    private void power() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && !pm.isInteractive()) wake(pm);
        else SleepTimerReceiver.sleepNow(this);
    }

    private void media(int keyCode) {
        AudioManager am = getSystemService(AudioManager.class);
        if (am == null) return;
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private void volume(int direction) {
        AudioManager am = getSystemService(AudioManager.class);
        if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
    }

    /** Types into whatever text field has focus (a search box, a sign-in field). */
    @Override
    public void text(String value) {
        handler.post(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            AccessibilityNodeInfo field = root != null ? root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) : null;
            if (field == null || !field.isEditable()) return;
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        });
    }

    @Override
    public String open(String url, double seconds) {
        handler.post(() -> wakeIfAsleep("open"));
        return MediaHandoff.open(this, url, seconds);
    }

    /** Typing from a keyboard: adds characters to the focused field ("\b" deletes, "\n" submits). */
    @Override
    public void type(String chars) {
        handler.post(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            AccessibilityNodeInfo field = root != null ? root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) : null;
            if (field == null || !field.isEditable()) return;
            CharSequence cur = field.getText();
            // A field showing only its hint reports the hint as its text.
            String text = cur == null || (field.isShowingHintText()) ? "" : cur.toString();
            for (char ch : chars.toCharArray()) {
                if (ch == '\b') {
                    if (!text.isEmpty()) text = text.substring(0, text.length() - 1);
                } else if (ch == '\n') {
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
                    }
                    return;
                } else {
                    text += ch;
                }
            }
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        });
    }

    // ---- the Mac's cursor ----------------------------------------------------------------------------

    @Override
    public void pointerEnter(ControlServer.Client from, float along, String edge) {
        handler.post(() -> {
            wakeIfAsleep("pointer");
            pointerOwner = from;
            pointer.enter(along, edge, at -> {
                ControlServer.Client owner = pointerOwner;
                pointerOwner = null;
                if (owner != null) owner.pointerLeft(at);
            });
        });
    }

    @Override
    public void pointerMove(int dx, int dy) {
        pointer.move(dx, dy);
    }

    @Override
    public void pointerButton(int button, boolean down) {
        pointer.button(button, down);
    }

    @Override
    public void pointerScroll(int dx, int dy, int phase) {
        pointer.scroll(dx, dy, phase);
    }

    @Override
    public void pointerExit() {
        handler.post(() -> {
            pointerOwner = null;
            pointer.hide();
        });
    }

    @Override
    public void clientGone(ControlServer.Client c) {
        handler.post(() -> {
            if (pointerOwner == c) {
                pointerOwner = null;
                pointer.hide();
            }
        });
    }

    // ---- pairing code card -------------------------------------------------------------------------

    @Override
    public void showCode(String code) {
        handler.post(() -> {
            hideCodeNow();
            float d = getResources().getDisplayMetrics().density;
            WindowManager wm = getSystemService(WindowManager.class);
            boolean blur = android.os.Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled();

            // tvOS style: the screen behind blurs and dims; the code sits in the middle in four large glass boxes.
            FrameLayout screen = new FrameLayout(this);
            if (blur) {
                screen.setBackgroundColor(0x80000000);
            } else {
                // No compositor blur on this TV: deep frosted glass with a soft glow behind the code instead.
                android.graphics.drawable.GradientDrawable glass = new android.graphics.drawable.GradientDrawable();
                glass.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
                glass.setGradientRadius(700 * d);
                glass.setColors(new int[]{0xF5262630, 0xFA0B0B0F});
                screen.setBackground(glass);
            }
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams centered = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            centered.gravity = Gravity.CENTER_HORIZONTAL;

            TextView app = new TextView(this);
            app.setText(R.string.remote_pair_app);
            app.setTextColor(0x99FFFFFF);
            app.setTextSize(15);
            app.setLetterSpacing(0.04f);
            app.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            card.addView(app, centered);
            TextView title = new TextView(this);
            title.setText(R.string.remote_pair_title);
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(30);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setPadding(0, (int) (6 * d), 0, (int) (26 * d));
            card.addView(title, centered);

            LinearLayout boxes = new LinearLayout(this);
            for (char ch : code.toCharArray()) {
                TextView digit = new TextView(this);
                digit.setText(String.valueOf(ch));
                digit.setTextColor(0xFFFFFFFF);
                digit.setTextSize(54);
                digit.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                digit.setGravity(Gravity.CENTER);
                android.graphics.drawable.GradientDrawable box = new android.graphics.drawable.GradientDrawable();
                box.setColor(0x2EFFFFFF);
                box.setCornerRadius(16 * d);
                box.setStroke(Math.max(1, (int) d), 0x33FFFFFF);
                digit.setBackground(box);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (86 * d), (int) (106 * d));
                lp.setMargins((int) (8 * d), 0, (int) (8 * d), 0);
                boxes.addView(digit, lp);
            }
            card.addView(boxes, centered);

            TextView hint = new TextView(this);
            hint.setText(R.string.remote_pair_hint);
            hint.setTextColor(0xB3FFFFFF);
            hint.setTextSize(16);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, (int) (26 * d), 0, 0);
            card.addView(hint, centered);
            String address = RemoteServer.address(this);
            if (address != null) {
                TextView web = new TextView(this);
                web.setText(getString(R.string.remote_pair_web, address));
                web.setTextColor(0x66FFFFFF);
                web.setTextSize(13);
                web.setGravity(Gravity.CENTER);
                web.setPadding(0, (int) (8 * d), 0, 0);
                card.addView(web, centered);
            }
            TextView back = new TextView(this);
            back.setText(R.string.remote_pair_cancel);
            back.setTextColor(0x66FFFFFF);
            back.setTextSize(13);
            back.setPadding(0, (int) (22 * d), 0, 0);
            card.addView(back, centered);
            screen.addView(card, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    // Focusable, like a tvOS sheet: the remote's Back reaches it however the key arrives.
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | (blur ? WindowManager.LayoutParams.FLAG_BLUR_BEHIND : 0),
                    PixelFormat.TRANSLUCENT);
            if (blur) lp.setBlurBehindRadius((int) (40 * d));
            screen.setFocusable(true);
            screen.setOnKeyListener((v, keyCode, e) -> {
                if (keyCode != KeyEvent.KEYCODE_BACK) return false;
                if (e.getAction() == KeyEvent.ACTION_UP) hideCodeNow();
                return true;
            });
            screen.setAlpha(0f);
            wm.addView(screen, lp);
            screen.requestFocus();
            screen.animate().alpha(1f).setDuration(220).start();
            codeCard = screen;
            handler.postDelayed(this::hideCodeNow, 120_000);
        });
    }

    @Override
    public void hideCode() {
        handler.post(this::hideCodeNow);
    }

    private void hideCodeNow() {
        if (codeCard == null) return;
        try {
            getSystemService(WindowManager.class).removeView(codeCard);
        } catch (RuntimeException ignored) {
        }
        codeCard = null;
    }
}
