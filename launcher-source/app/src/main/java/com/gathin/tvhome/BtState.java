package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Which Bluetooth devices are connected right now, asked the supported way: the audio (A2DP, headset) and input
 * (HID) services, and Bluetooth LE (most TV remotes). Android 14 no longer lets apps ask a device directly.
 */
final class BtState {

    private static final int HID_HOST = 4;   // BluetoothProfile.HID_HOST (input devices: keyboards, controllers)
    private static final List<BluetoothProfile> proxies = new CopyOnWriteArrayList<>();
    private static boolean started;

    private BtState() {
    }

    @SuppressLint("MissingPermission")
    static void start(Context ctx) {
        if (started) return;
        started = true;
        Context c = ctx.getApplicationContext();
        BluetoothManager bm = c.getSystemService(BluetoothManager.class);
        BluetoothAdapter a = bm == null ? null : bm.getAdapter();
        if (a == null) return;
        for (int profile : new int[]{BluetoothProfile.A2DP, BluetoothProfile.HEADSET, HID_HOST}) {
            try {
                a.getProfileProxy(c, new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int p, BluetoothProfile proxy) {
                        proxies.add(proxy);
                    }

                    @Override
                    public void onServiceDisconnected(int p) {
                    }
                }, profile);
            } catch (Exception ignored) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    static boolean connected(Context c, BluetoothDevice d) {
        start(c);
        try {
            for (BluetoothProfile p : proxies) if (p.getConnectedDevices().contains(d)) return true;
            BluetoothManager bm = c.getSystemService(BluetoothManager.class);
            if (bm != null && bm.getConnectedDevices(BluetoothProfile.GATT).contains(d)) return true;
        } catch (Exception ignored) {
        }
        // Remotes, keyboards and controllers over Bluetooth LE work through Android's input system: one that's
        // there as an input device right now is connected.
        try {
            String name = d.getName();
            android.hardware.input.InputManager im = c.getSystemService(android.hardware.input.InputManager.class);
            if (name != null && im != null) {
                for (int id : im.getInputDeviceIds()) {
                    android.view.InputDevice in = im.getInputDevice(id);
                    if (in == null || in.isVirtual()) continue;
                    if (name.equals(in.getName())) return true;
                    // A TV's own Bluetooth remote often comes through the TV's remote driver (Chromecast:
                    // "input_btrcu") rather than under its own name: while that's active, the paired remote is connected.
                    String n = in.getName() == null ? "" : in.getName().toLowerCase(java.util.Locale.ROOT);
                    if ((n.contains("btrcu") || n.contains("rcu")) && name.toLowerCase(java.util.Locale.ROOT).contains("remote")) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {   // older Android still answers directly
            return (Boolean) d.getClass().getMethod("isConnected").invoke(d);
        } catch (Exception e) {
            return false;
        }
    }
}
