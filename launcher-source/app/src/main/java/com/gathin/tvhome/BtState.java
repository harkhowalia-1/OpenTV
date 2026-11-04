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
    /** Devices with a live link right now, kept up to date by OtvOS from Android's connect/disconnect events. */
    private static final java.util.Set<String> links = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static boolean started;

    private BtState() {
    }

    @SuppressLint("MissingPermission")
    static void start(Context ctx) {
        if (started) return;
        started = true;
        Context c = ctx.getApplicationContext();
        android.content.IntentFilter f = new android.content.IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        c.registerReceiver(new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context cx, android.content.Intent i) {
                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(i.getAction())) {
                    if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0) != BluetoothAdapter.STATE_ON) links.clear();
                    return;
                }
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null) return;
                if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(i.getAction())) links.add(d.getAddress());
                else links.remove(d.getAddress());
            }
        }, f);
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
        // The live Bluetooth link itself (remotes and other LE devices), from connect and disconnect events.
        if (links.contains(d.getAddress())) return true;
        try {   // older Android still answers directly
            return (Boolean) d.getClass().getMethod("isConnected").invoke(d);
        } catch (Exception e) {
            return false;
        }
    }
}
