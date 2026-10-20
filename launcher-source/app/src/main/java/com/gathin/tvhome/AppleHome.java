package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import io.github.hapjava.accessories.Bridge;
import io.github.hapjava.accessories.TelevisionAccessory;
import io.github.hapjava.characteristics.HomekitCharacteristicChangeCallback;
import io.github.hapjava.characteristics.impl.audio.MuteCharacteristic;
import io.github.hapjava.characteristics.impl.common.ConfiguredNameCharacteristic;
import io.github.hapjava.characteristics.impl.common.IdentifierCharacteristic;
import io.github.hapjava.characteristics.impl.common.IsConfiguredCharacteristic;
import io.github.hapjava.characteristics.impl.common.IsConfiguredEnum;
import io.github.hapjava.characteristics.impl.common.NameCharacteristic;
import io.github.hapjava.characteristics.impl.inputsource.CurrentVisibilityStateCharacteristic;
import io.github.hapjava.characteristics.impl.inputsource.CurrentVisibilityStateEnum;
import io.github.hapjava.characteristics.impl.inputsource.InputSourceTypeCharacteristic;
import io.github.hapjava.characteristics.impl.inputsource.InputSourceTypeEnum;
import io.github.hapjava.characteristics.impl.television.PowerModeCharacteristic;
import io.github.hapjava.characteristics.impl.television.PowerModeEnum;
import io.github.hapjava.characteristics.impl.television.RemoteKeyEnum;
import io.github.hapjava.characteristics.impl.television.SleepDiscoveryModeEnum;
import io.github.hapjava.characteristics.impl.televisionspeaker.VolumeControlTypeCharacteristic;
import io.github.hapjava.characteristics.impl.televisionspeaker.VolumeControlTypeEnum;
import io.github.hapjava.characteristics.impl.televisionspeaker.VolumeSelectorCharacteristic;
import io.github.hapjava.characteristics.impl.televisionspeaker.VolumeSelectorEnum;
import io.github.hapjava.server.HomekitAuthInfo;
import io.github.hapjava.server.impl.HomekitRoot;
import io.github.hapjava.server.impl.HomekitServer;
import io.github.hapjava.server.impl.HomekitStandaloneAccessoryServer;
import io.github.hapjava.services.Service;
import io.github.hapjava.services.impl.InputSourceService;
import io.github.hapjava.services.impl.TelevisionService;
import io.github.hapjava.services.impl.TelevisionSpeakerService;

/**
 * Makes the TV an Apple Home (HomeKit) television. Once added in the Home app it can be turned on and off,
 * switched between apps (its "inputs"), and controlled from the Apple TV Remote in the iPhone's Control
 * Center: arrows, select, back, play/pause, the (i) button (opens Control Center) and the volume buttons.
 * Runs inside HomeKeyService; pairing data stays on the TV in the "homekit" preferences.
 */
final class AppleHome {

    private static final String TAG = "AppleHome";
    private static final int PORT = 9123;
    private static final int CATEGORY_TELEVISION = 31;
    /** Input 1 is the Home screen; the dock apps follow. */
    private static final int HOME_INPUT = 1;

    static volatile AppleHome current;

    private final Context ctx;
    private final RemoteServer.Actions actions;
    private final SharedPreferences prefs;
    private final Auth auth;
    private HomekitServer server;
    private HomekitRoot root;
    private WifiManager.MulticastLock multicast;
    private HomekitCharacteristicChangeCallback activeCallback;
    private final List<String> inputs = new ArrayList<>();
    private int activeInput = HOME_INPUT;
    private Service tvService;

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            HomekitCharacteristicChangeCallback cb = activeCallback;
            if (cb != null) cb.changed();
        }
    };

    AppleHome(Context ctx, RemoteServer.Actions actions) {
        this.ctx = ctx.getApplicationContext();
        this.actions = actions;
        this.prefs = ctx.getSharedPreferences("homekit", Context.MODE_PRIVATE);
        this.auth = new Auth();
    }

    void start() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                InetAddress address = wifiAddress();
                if (address == null) {
                    Log.w(TAG, "no network, not starting");
                    return;
                }
                WifiManager wifi = ctx.getSystemService(WifiManager.class);
                if (wifi != null) {
                    multicast = wifi.createMulticastLock("tvhome:homekit");
                    multicast.setReferenceCounted(false);
                    multicast.acquire();
                }
                server = new HomekitServer(address, PORT);
                HomekitStandaloneAccessoryServer standalone =
                        server.createStandaloneAccessory(auth, new Television(), CATEGORY_TELEVISION);
                Field f = HomekitStandaloneAccessoryServer.class.getDeclaredField("root");
                f.setAccessible(true);
                root = (HomekitRoot) f.get(standalone);
                standalone.start();
                current = this;
                IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
                filter.addAction(Intent.ACTION_SCREEN_OFF);
                ctx.registerReceiver(screen, filter);
                Log.i(TAG, "HomeKit television ready on " + address.getHostAddress() + ":" + PORT);
            } catch (Throwable e) {
                Log.w(TAG, "HomeKit unavailable", e);
            }
        });
    }

    void stop() {
        if (current == this) current = null;
        try {
            ctx.unregisterReceiver(screen);
        } catch (RuntimeException ignored) {
        }
        if (server != null) server.stop();
        if (multicast != null) multicast.release();
    }

    // ---- what the TV's Apple Home screen shows ------------------------------------------------------

    boolean isPaired() {
        return auth.hasUser();
    }

    /** "123-45-678", typed into the Home app. */
    String pin() {
        return auth.getPin();
    }

    /** The setup code as Apple's QR payload (X-HM://…), scanned by the Home app's camera. */
    String setupUri() {
        long code = Long.parseLong(pin().replace("-", ""));
        long payload = ((long) CATEGORY_TELEVISION << 31) | (2L << 27) | code;   // flag 2: IP accessory
        String base36 = Long.toString(payload, 36).toUpperCase(Locale.ROOT);
        while (base36.length() < 9) base36 = "0" + base36;
        return "X-HM://" + base36 + auth.getSetupId();
    }

    /** Forgets every paired Home and makes a new setup code, so the TV can be added again. */
    void reset() {
        prefs.edit().clear().apply();
        auth.reload();
        refresh();
    }

    private void refresh() {
        try {
            if (root != null) root.refreshAuthInfo();
        } catch (Exception e) {
            Log.w(TAG, "refresh failed", e);
        }
    }

    private InetAddress wifiAddress() {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        if (cm == null) return null;
        LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
        if (lp == null) return null;
        for (LinkAddress la : lp.getLinkAddresses()) {
            if (la.getAddress() instanceof Inet4Address) return la.getAddress();
        }
        return null;
    }

    // ---- the accessory --------------------------------------------------------------------------------

    /**
     * The TV. A standalone HomeKit accessory must have ID 1; HAP-Java only allows that for a {@link Bridge},
     * which is just a marker there, so the TV wears it.
     */
    private final class Television implements TelevisionAccessory, Bridge {

        @Override
        public int getId() {
            return 1;
        }

        @Override
        public CompletableFuture<String> getName() {
            return CompletableFuture.completedFuture(MainActivity.tvName(ctx));
        }

        @Override
        public void identify() {
            Log.i(TAG, "identify");
        }

        @Override
        public CompletableFuture<String> getSerialNumber() {
            return CompletableFuture.completedFuture(auth.getMac().replace(":", ""));
        }

        @Override
        public CompletableFuture<String> getModel() {
            return CompletableFuture.completedFuture(Brand.MODEL);
        }

        @Override
        public CompletableFuture<String> getManufacturer() {
            return CompletableFuture.completedFuture(Brand.NAME);
        }

        @Override
        public CompletableFuture<String> getFirmwareRevision() {
            return CompletableFuture.completedFuture(Build.VERSION.RELEASE + ".0.0");
        }

        // Power

        @Override
        public CompletableFuture<Boolean> isActive() {
            PowerManager pm = ctx.getSystemService(PowerManager.class);
            return CompletableFuture.completedFuture(pm != null && pm.isInteractive());
        }

        @Override
        public CompletableFuture<Void> setActive(boolean on) {
            PowerManager pm = ctx.getSystemService(PowerManager.class);
            boolean awake = pm != null && pm.isInteractive();
            if (on != awake) actions.key("power");
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void subscribeActive(HomekitCharacteristicChangeCallback cb) {
            activeCallback = cb;
        }

        @Override
        public void unsubscribeActive() {
            activeCallback = null;
        }

        // Inputs: the Home screen and the dock apps

        @Override
        public CompletableFuture<Integer> getActiveIdentifier() {
            return CompletableFuture.completedFuture(activeInput);
        }

        @Override
        public CompletableFuture<Void> setActiveIdentifier(Integer id) {
            activeInput = id;
            if (id == HOME_INPUT) {
                actions.key("home");
            } else if (id - 2 >= 0 && id - 2 < inputs.size()) {
                String pkg = inputs.get(id - 2);
                Intent launch = AppAdapter.launchIntentFor(ctx, pkg);
                if (launch != null) ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void subscribeActiveIdentifier(HomekitCharacteristicChangeCallback cb) {
        }

        @Override
        public void unsubscribeActiveIdentifier() {
        }

        @Override
        public CompletableFuture<String> getConfiguredName() {
            return CompletableFuture.completedFuture(MainActivity.tvName(ctx));
        }

        @Override
        public CompletableFuture<Void> setConfiguredName(String name) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void subscribeConfiguredName(HomekitCharacteristicChangeCallback cb) {
        }

        @Override
        public void unsubscribeConfiguredName() {
        }

        // The iPhone's Apple TV Remote

        @Override
        public CompletableFuture<Void> setRemoteKey(RemoteKeyEnum key) {
            String name;
            switch (key) {
                case ARROW_UP: name = "up"; break;
                case ARROW_DOWN: name = "down"; break;
                case ARROW_LEFT: name = "left"; break;
                case ARROW_RIGHT: name = "right"; break;
                case SELECT: name = "select"; break;
                case BACK: name = "back"; break;
                case EXIT: name = "home"; break;
                case PLAY_PAUSE: name = "playpause"; break;
                case INFO: name = "control"; break;
                case NEXT_TRACK: case FAST_FORWARD: name = "next"; break;
                case PREV_TRACK: case REWIND: name = "previous"; break;
                default: return CompletableFuture.completedFuture(null);
            }
            actions.key(name);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<SleepDiscoveryModeEnum> getSleepDiscoveryMode() {
            return CompletableFuture.completedFuture(SleepDiscoveryModeEnum.ALWAYS_DISCOVERABLE);
        }

        @Override
        public void subscribeSleepDiscoveryMode(HomekitCharacteristicChangeCallback cb) {
        }

        @Override
        public void unsubscribeSleepDiscoveryMode() {
        }

        /**
         * The television with its speaker (volume buttons) and inputs linked to it, as Home expects. HAP-Java
         * registers linked services itself, so only the television is returned.
         */
        @Override
        public Collection<Service> getServices() {
            List<Service> services = new ArrayList<>();
            TelevisionService tv = new TelevisionService(this);
            tvService = tv;
            // "Settings" in the Home app's TV tile opens Control Center.
            tv.addOptionalCharacteristic(new PowerModeCharacteristic(mode -> {
                if (mode == PowerModeEnum.SHOW) actions.key("control");
            }));
            services.add(tv);

            TelevisionSpeakerService speaker = new TelevisionSpeakerService(new MuteCharacteristic(
                    () -> CompletableFuture.completedFuture(false),
                    muted -> actions.key("mute"), cb -> { }, () -> { }));
            speaker.addOptionalCharacteristic(new VolumeControlTypeCharacteristic(
                    () -> CompletableFuture.completedFuture(VolumeControlTypeEnum.RELATIVE), cb -> { }, () -> { }));
            speaker.addOptionalCharacteristic(new VolumeSelectorCharacteristic(
                    v -> actions.key(v == VolumeSelectorEnum.INCREMENT ? "volup" : "voldown")));
            tv.addLinkedService(speaker);

            inputs.clear();
            PackageManager pm = ctx.getPackageManager();
            Service home = input(HOME_INPUT, ctx.getString(R.string.home_screen), InputSourceTypeEnum.HOME_SCREEN);
            tv.addLinkedService(home);
            for (String pkg : ctx.getResources().getStringArray(R.array.dock_apps)) {
                if (pkg.equals("com.android.tv.settings") || AppAdapter.launchIntentFor(ctx, pkg) == null) continue;
                String label;
                try {
                    label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                } catch (PackageManager.NameNotFoundException e) {
                    continue;
                }
                inputs.add(pkg);
                Service s = input(inputs.size() + 1, label, InputSourceTypeEnum.APPLICATION);
                tv.addLinkedService(s);
            }
            return services;
        }

        @Override
        public Service getPrimaryService() {
            return tvService;
        }

        private Service input(int id, String name, InputSourceTypeEnum type) {
            InputSourceService s = new InputSourceService(
                    new ConfiguredNameCharacteristic(() -> CompletableFuture.completedFuture(name), n -> { }, cb -> { }, () -> { }),
                    new InputSourceTypeCharacteristic(() -> CompletableFuture.completedFuture(type), cb -> { }, () -> { }),
                    new IsConfiguredCharacteristic(() -> CompletableFuture.completedFuture(IsConfiguredEnum.CONFIGURED),
                            v -> { }, cb -> { }, () -> { }),
                    new CurrentVisibilityStateCharacteristic(
                            () -> CompletableFuture.completedFuture(CurrentVisibilityStateEnum.SHOWN), cb -> { }, () -> { }));
            s.addOptionalCharacteristic(new IdentifierCharacteristic(() -> CompletableFuture.completedFuture(id)));
            s.addOptionalCharacteristic(new NameCharacteristic(() -> CompletableFuture.completedFuture(name)));
            return s;
        }
    }

    // ---- pairing data ---------------------------------------------------------------------------------

    /** Keys, setup code and paired iPhones/Home hubs, kept in the launcher's preferences. */
    private final class Auth implements HomekitAuthInfo {
        private String pin, mac, setupId;
        private BigInteger salt;
        private byte[] privateKey;

        Auth() {
            reload();
        }

        void reload() {
            try {
                if (!prefs.contains("pin")) {
                    SecureRandom r = new SecureRandom();
                    String chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
                    StringBuilder id = new StringBuilder();
                    for (int i = 0; i < 4; i++) id.append(chars.charAt(r.nextInt(chars.length())));
                    prefs.edit()
                            .putString("pin", HomekitServer.generatePin())
                            .putString("mac", HomekitServer.generateMac())
                            .putString("salt", HomekitServer.generateSalt().toString(16))
                            .putString("key", Base64.encodeToString(HomekitServer.generateKey(), Base64.NO_WRAP))
                            .putString("setup_id", id.toString())
                            .commit();
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            pin = prefs.getString("pin", null);
            mac = deviceId(prefs.getString("mac", null));
            setupId = prefs.getString("setup_id", null);
            salt = new BigInteger(prefs.getString("salt", "0"), 16);
            privateKey = Base64.decode(prefs.getString("key", ""), Base64.NO_WRAP);
        }

        /** HAP-Java's random ID can drop leading zeros ("e:31:…"); HomeKit wants six two-digit pairs. */
        private String deviceId(String raw) {
            StringBuilder id = new StringBuilder();
            for (String part : raw.split(":")) {
                if (id.length() > 0) id.append(':');
                id.append(String.format(Locale.ROOT, "%02X", Integer.parseInt(part, 16)));
            }
            return id.toString();
        }

        @Override
        public String getPin() {
            return pin;
        }

        @Override
        public String getSetupId() {
            return setupId;
        }

        @Override
        public String getMac() {
            return mac;
        }

        @Override
        public BigInteger getSalt() {
            return salt;
        }

        @Override
        public byte[] getPrivateKey() {
            return privateKey;
        }

        @Override
        public void createUser(String username, byte[] publicKey, boolean admin) {
            Set<String> users = new HashSet<>(prefs.getStringSet("users", new HashSet<>()));
            users.add(username);
            prefs.edit()
                    .putStringSet("users", users)
                    .putString("user." + username, Base64.encodeToString(publicKey, Base64.NO_WRAP))
                    .putBoolean("admin." + username, admin)
                    .commit();
            refresh();
            actions.hideCode();
        }

        @Override
        public void createUser(String username, byte[] publicKey) {
            createUser(username, publicKey, true);
        }

        @Override
        public void removeUser(String username) {
            Set<String> users = new HashSet<>(prefs.getStringSet("users", new HashSet<>()));
            users.remove(username);
            prefs.edit().putStringSet("users", users).remove("user." + username).remove("admin." + username).commit();
            refresh();
        }

        @Override
        public Collection<String> listUsers() {
            return new HashSet<>(prefs.getStringSet("users", new HashSet<>()));
        }

        @Override
        public byte[] getUserPublicKey(String username) {
            String k = prefs.getString("user." + username, null);
            return k == null ? null : Base64.decode(k, Base64.NO_WRAP);
        }

        @Override
        public boolean userIsAdmin(String username) {
            return prefs.getBoolean("admin." + username, true);
        }

        @Override
        public boolean hasUser() {
            return !prefs.getStringSet("users", new HashSet<>()).isEmpty();
        }
    }
}
