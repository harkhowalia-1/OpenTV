package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The TV's settings for the iPhone remote's Settings screen (/api/tv/…): HDMI inputs, the wallpaper library
 * (with previews and live download progress), Bluetooth accessories (scan, pair, forget), the screensaver, and
 * software updates. Called on the remote server's threads.
 */
final class TvApi {

    private static final String TAG = "OtvTvApi";

    private TvApi() {
    }

    /** A reply: JSON, or a picture. */
    static final class Reply {
        JSONObject json;
        byte[] jpeg;
        int code = 200;

        static Reply ok(JSONObject o) {
            Reply r = new Reply();
            r.json = o;
            return r;
        }
    }

    static Reply handle(Context c, String path, Map<String, String> query, JSONObject in) throws Exception {
        switch (path) {
            case "/api/tv/inputs": return Reply.ok(new JSONObject().put("inputs", inputs(c)));
            case "/api/tv/input": return Reply.ok(new JSONObject().put("ok", switchInput(c, in.optString("id"))));
            case "/api/tv/wallpapers": return Reply.ok(wallpapers(c));
            case "/api/tv/wallpaper/thumb": return thumb(c, query.get("kind"));
            case "/api/tv/wallpaper/set": return Reply.ok(new JSONObject().put("ok", setWallpaper(c, in.optString("kind"))));
            case "/api/tv/wallpaper/progress": return Reply.ok(progress());
            case "/api/tv/bluetooth": return Reply.ok(bluetooth(c));
            case "/api/tv/bluetooth/scan": return Reply.ok(new JSONObject().put("ok", scan(c)));
            case "/api/tv/bluetooth/pair": return Reply.ok(new JSONObject().put("ok", pair(c, in.optString("address"))));
            case "/api/tv/bluetooth/forget": return Reply.ok(new JSONObject().put("ok", forget(c, in.optString("address"))));
            case "/api/tv/screensaver": return Reply.ok(screensaver(c, in));
            case "/api/tv/screensaver/start": return Reply.ok(new JSONObject().put("ok", startScreensaver(c)));
            case "/api/tv/update": return Reply.ok(update(c, in));
            default: {
                Reply r = Reply.ok(new JSONObject().put("error", "not found"));
                r.code = 404;
                return r;
            }
        }
    }

    // ---- inputs -------------------------------------------------------------------------------------------

    private static JSONArray inputs(Context c) throws Exception {
        JSONArray a = new JSONArray();
        TvInputManager tim = c.getSystemService(TvInputManager.class);
        if (tim == null) return a;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (!info.isPassthroughInput() || info.isHidden(c)) continue;
            CharSequence label = info.loadCustomLabel(c);
            if (label == null) label = info.loadLabel(c);
            a.put(new JSONObject().put("id", info.getId()).put("name", String.valueOf(label)));
        }
        return a;
    }

    private static boolean switchInput(Context c, String id) {
        if (id.isEmpty()) return false;
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- wallpapers ---------------------------------------------------------------------------------------

    private static JSONObject item(String kind, String name) throws Exception {
        return new JSONObject().put("kind", kind).put("name", name);
    }

    private static JSONObject section(String title, JSONArray items) throws Exception {
        return new JSONObject().put("title", title).put("items", items);
    }

    /** The same library as Settings › Wallpaper, in the same sections. */
    private static JSONObject wallpapers(Context c) throws Exception {
        JSONArray sections = new JSONArray();
        JSONArray featured = new JSONArray();
        featured.put(item("", c.getString(R.string.wallpaper_glow)));
        featured.put(item("bing", c.getString(R.string.wallpaper_bing)));
        sections.put(section("", featured));
        JSONArray aerials = new JSONArray();
        for (Aerials.Aerial a : Aerials.list(c)) aerials.put(item("aerial:" + a.id, a.name));
        sections.put(section(c.getString(R.string.wallpaper_aerials), aerials));
        if (MacOS.supported()) {
            JSONArray mac = new JSONArray();
            for (MacOS.Item it : MacOS.list(c)) {
                mac.put(item("macos:" + it.name, it.name).put("dynamic", MacOS.isDynamic(c, it.name)));
            }
            sections.put(section(c.getString(R.string.wallpaper_macos), mac));
        }
        JSONArray yours = new JSONArray();
        for (File f : Wallpapers.uploads(c)) {
            String n = f.getName().replaceFirst("\\.jpg$", "");
            yours.put(item("upload:" + n, n));
        }
        if (yours.length() > 0) sections.put(section(c.getString(R.string.wallpaper_yours), yours));
        return new JSONObject().put("current", Wallpapers.kind(c)).put("sections", sections).put("progress", progress());
    }

    /** A small preview of a wallpaper (made and kept the first time it's asked for). */
    private static Reply thumb(Context c, String kind) throws Exception {
        File f = null;
        if (kind == null) kind = "";
        if (kind.equals("bing")) {
            f = Wallpapers.fetchBing(c);
        } else if (kind.startsWith("aerial:")) {
            for (Aerials.Aerial a : Aerials.list(c)) if (kind.equals("aerial:" + a.id)) f = Aerials.thumbnail(c, a);
        } else if (kind.startsWith("macos:")) {
            MacOS.Item it = macItem(c, kind.substring(6));
            if (it != null) f = MacOS.thumbnail(c, it);
        } else if (kind.startsWith("upload:")) {
            f = new File(Wallpapers.dir(c, "uploads"), kind.substring(7) + ".jpg");
        }
        Reply r = new Reply();
        if (f == null || !f.exists()) {
            r.code = 404;
            r.json = new JSONObject().put("error", "no preview");
            return r;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= 480) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) {
            r.code = 404;
            r.json = new JSONObject().put("error", "no preview");
            return r;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 80, out);
        b.recycle();
        r.jpeg = out.toByteArray();
        return r;
    }

    private static MacOS.Item macItem(Context c, String name) {
        for (MacOS.Item it : MacOS.list(c)) if (it.name.equals(name)) return it;
        return null;
    }

    private static volatile boolean busy;
    private static volatile float fraction = -1;
    private static volatile boolean downloading;
    private static volatile String settingKind = "", error = "";

    private static JSONObject progress() throws Exception {
        return new JSONObject().put("busy", busy).put("kind", settingKind).put("fraction", fraction)
                .put("downloading", downloading).put("error", error);
    }

    /** Sets a wallpaper in the background; the phone follows along through /api/tv/wallpaper/progress. */
    private static boolean setWallpaper(Context c, String kind) {
        if (busy) return false;
        busy = true;
        settingKind = kind;
        fraction = -1;
        downloading = false;
        error = "";
        new Thread(() -> {
            Wallpapers.progress.set((f, dl) -> {
                fraction = f;
                downloading = dl;
            });
            try {
                Wallpapers.apply(c, kind);
                fraction = 1;
            } catch (Exception e) {
                Log.w(TAG, "couldn't set " + kind, e);
                error = c.getString(R.string.wallpaper_failed);
            } finally {
                Wallpapers.progress.remove();
                busy = false;
            }
        }, "otv-set-wallpaper").start();
        return true;
    }

    // ---- Bluetooth ----------------------------------------------------------------------------------------

    private static final Map<String, BluetoothDevice> found = new LinkedHashMap<>();
    private static BroadcastReceiver receiver;

    private static BluetoothAdapter adapter(Context c) {
        BluetoothManager m = c.getSystemService(BluetoothManager.class);
        return m == null ? null : m.getAdapter();
    }

    @SuppressLint("MissingPermission")
    private static JSONObject bluetooth(Context c) throws Exception {
        BluetoothAdapter a = adapter(c);
        JSONObject o = new JSONObject();
        JSONArray devices = new JSONArray();
        if (a == null) return o.put("available", false).put("devices", devices);
        o.put("available", true).put("enabled", a.isEnabled());
        try {
            o.put("scanning", a.isDiscovering());
            for (BluetoothDevice d : a.getBondedDevices()) devices.put(device(d, true));
            synchronized (found) {
                for (BluetoothDevice d : found.values()) {
                    if (d.getBondState() != BluetoothDevice.BOND_BONDED) devices.put(device(d, false));
                }
            }
        } catch (SecurityException e) {
            o.put("error", "permission");
        }
        return o.put("devices", devices);
    }

    @SuppressLint("MissingPermission")
    private static JSONObject device(BluetoothDevice d, boolean paired) throws Exception {
        String name = d.getName();
        boolean connected = false;
        try {
            connected = (Boolean) d.getClass().getMethod("isConnected").invoke(d);
        } catch (Exception ignored) {
        }
        int major = d.getBluetoothClass() != null ? d.getBluetoothClass().getMajorDeviceClass() : 0;
        String type = major == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO ? "audio"
                : major == android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL ? "input"
                : major == android.bluetooth.BluetoothClass.Device.Major.PHONE ? "phone" : "other";
        return new JSONObject().put("address", d.getAddress()).put("name", name == null ? d.getAddress() : name)
                .put("paired", paired).put("connected", connected).put("type", type)
                .put("pairing", d.getBondState() == BluetoothDevice.BOND_BONDING);
    }

    /** Looks for nearby accessories for about 12 seconds (what Android's own search does). */
    @SuppressLint("MissingPermission")
    private static boolean scan(Context c) {
        BluetoothAdapter a = adapter(c);
        if (a == null || !a.isEnabled()) return false;
        Context app = c.getApplicationContext();
        synchronized (found) {
            if (receiver == null) {
                receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context cx, Intent i) {
                        BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                        if (d == null) return;
                        synchronized (found) {
                            found.put(d.getAddress(), d);
                        }
                    }
                };
                IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_FOUND);
                f.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
                app.registerReceiver(receiver, f);
            }
            found.clear();
        }
        try {
            if (a.isDiscovering()) a.cancelDiscovery();
            return a.startDiscovery();
        } catch (SecurityException e) {
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private static boolean pair(Context c, String address) {
        BluetoothAdapter a = adapter(c);
        if (a == null || address.isEmpty()) return false;
        try {
            a.cancelDiscovery();   // pairing is slow and unreliable while still searching
            return a.getRemoteDevice(address).createBond();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean forget(Context c, String address) {
        BluetoothAdapter a = adapter(c);
        if (a == null || address.isEmpty()) return false;
        try {
            BluetoothDevice d = a.getRemoteDevice(address);
            return (Boolean) d.getClass().getMethod("removeBond").invoke(d);
        } catch (Exception e) {
            return false;
        }
    }

    // ---- screensaver and updates --------------------------------------------------------------------------

    private static JSONObject screensaver(Context c, JSONObject in) throws Exception {
        android.content.SharedPreferences.Editor e = ScreenSaver.prefs(c).edit();
        if (in.has("type")) e.putString("type", in.getString("type"));
        if (in.has("theme")) e.putString("theme", in.getString("theme"));
        if (in.has("clock")) e.putBoolean("clock", in.getBoolean("clock"));
        if (in.has("music")) e.putBoolean("music", in.getBoolean("music"));
        e.apply();
        if (in.has("startAfter") && ScreenSaver.canSetStartAfter(c)) ScreenSaver.setStartAfter(c, in.getInt("startAfter"));
        JSONArray themes = new JSONArray().put(ScreenSaver.ALL);
        for (String t : Aerials.THEMES) themes.put(t);
        JSONArray after = new JSONArray();
        for (int m : ScreenSaver.START_AFTER) after.put(m);
        return new JSONObject().put("type", ScreenSaver.type(c)).put("theme", ScreenSaver.theme(c))
                .put("clock", ScreenSaver.clock(c)).put("music", ScreenSaver.duringMusic(c))
                .put("startAfter", ScreenSaver.startAfter(c)).put("canSetStartAfter", ScreenSaver.canSetStartAfter(c))
                .put("themes", themes).put("startAfterChoices", after);
    }

    private static boolean startScreensaver(Context c) {
        try {
            c.startActivity(new Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static JSONObject update(Context c, JSONObject in) throws Exception {
        JSONObject o = new JSONObject().put("installed", Updater.installedVersion(c, c.getPackageName()));
        if (in.optBoolean("install")) {
            Updater.autoUpdate(c);
            return o.put("installing", true);
        }
        try {
            Updater.Release r = Updater.check(c);
            o.put("latest", r.version).put("available", !r.apps.isEmpty()).put("notes", r.notes);
        } catch (Exception e) {
            o.put("error", "offline");
        }
        return o;
    }

}
