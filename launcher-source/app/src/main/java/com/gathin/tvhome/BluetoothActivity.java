package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings › Bluetooth, as tvOS lays out Remotes and Devices: your devices (connected or not), other devices
 * nearby (searching while the page is open), and accessories. Choosing a nearby device pairs it (Android asks
 * for a code when the device needs one); choosing one of yours offers to forget it.
 */
public class BluetoothActivity extends TvSettingsActivity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, BluetoothDevice> nearby = new LinkedHashMap<>();
    private LinearLayout mine, others;
    private TextView searching;
    private BluetoothAdapter adapter;

    @Override
    protected String screenTitle() {
        return getString(R.string.bluetooth);
    }

    @Override
    protected View fill(LinearLayout list) {
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        adapter = bm == null ? null : bm.getAdapter();

        section(list, getString(R.string.bt_my_devices));
        mine = new LinearLayout(this);
        mine.setOrientation(LinearLayout.VERTICAL);
        mine.setClipChildren(false);
        list.addView(mine);

        section(list, getString(R.string.bt_other_devices));
        others = new LinearLayout(this);
        others.setOrientation(LinearLayout.VERTICAL);
        others.setClipChildren(false);
        list.addView(others);
        searching = new TextView(this);
        searching.setTextColor(0x80FFFFFF);
        searching.setTextSize(14);
        searching.setPadding(dp(16), dp(4), dp(16), dp(8));
        list.addView(searching);

        section(list, getString(R.string.bt_accessories));
        View pair = row(list, R.drawable.ic_gamepad, getString(R.string.bt_pair_accessory), getString(R.string.bt_pair_accessory_sub),
                new Intent("com.google.android.intent.action.CONNECT_INPUT"));
        row(list, R.drawable.ic_remote, getString(R.string.bt_remote_buttons), getString(R.string.bt_remote_buttons_sub),
                new Intent("com.google.android.tv.IR_WIZARD"));
        row(list, R.drawable.ic_settings, getString(R.string.bt_more), null, this::openAndroidBluetooth);

        refresh();
        View first = mine.getChildCount() > 0 ? mine.getChildAt(0) : others.getChildCount() > 0 ? others.getChildAt(0) : pair;
        return first;
    }

    // ---- searching ------------------------------------------------------------------------------------------

    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (BluetoothDevice.ACTION_FOUND.equals(i.getAction()) && d != null) nearby.put(d.getAddress(), d);
            if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(i.getAction())) main.postDelayed(BluetoothActivity.this::search, 4000);
            main.post(BluetoothActivity.this::refresh);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(events, f);
        BtState.start(this);
        search();
        main.postDelayed(this::refresh, 700);   // once the audio and input services have answered
        if (mine != null) refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(events);
        } catch (IllegalArgumentException ignored) {
        }
        main.removeCallbacksAndMessages(null);
        try {
            if (adapter != null) adapter.cancelDiscovery();
        } catch (SecurityException ignored) {
        }
    }

    /** Looks for nearby devices, again and again while the page is open (as tvOS does). */
    @SuppressLint("MissingPermission")
    private void search() {
        try {
            if (adapter != null && adapter.isEnabled() && !adapter.isDiscovering()) adapter.startDiscovery();
        } catch (SecurityException ignored) {
        }
    }

    // ---- the lists ------------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private void refresh() {
        if (mine == null) return;
        View focused = getCurrentFocus();
        Object focusTag = focused == null ? null : focused.getTag();
        mine.removeAllViews();
        others.removeAllViews();
        if (adapter == null) {
            searching.setText(R.string.bt_none);
            return;
        }
        if (!adapter.isEnabled()) {
            searching.setText(R.string.bt_off);
            row(others, R.drawable.ic_bluetooth, getString(R.string.bt_turn_on), null, this::openAndroidBluetooth);
            return;
        }
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                View r = row(mine, icon(d), name(d), getString(BtState.connected(this, d) ? R.string.bt_connected : R.string.bt_not_connected),
                        () -> forget(d));
                r.setTag(d.getAddress());
            }
            for (BluetoothDevice d : nearby.values()) {
                if (d.getBondState() == BluetoothDevice.BOND_BONDED || d.getName() == null) continue;
                String state = d.getBondState() == BluetoothDevice.BOND_BONDING ? getString(R.string.bt_pairing) : null;
                View r = row(others, icon(d), name(d), state, () -> pair(d));
                r.setTag(d.getAddress());
            }
            searching.setText(adapter.isDiscovering() ? getString(R.string.bt_searching) : others.getChildCount() == 0
                    ? getString(R.string.bt_none_nearby) : "");
        } catch (SecurityException e) {
            searching.setText(R.string.bt_permission);
        }
        if (mine.getChildCount() == 0) {
            TextView none = new TextView(this);
            none.setText(R.string.bt_no_devices);
            none.setTextColor(0x80FFFFFF);
            none.setTextSize(15);
            none.setPadding(dp(16), dp(6), 0, dp(10));
            mine.addView(none);
        }
        // Keep focus on the same device as the lists change.
        if (focusTag != null) {
            View same = getWindow().getDecorView().findViewWithTag(focusTag);
            if (same != null) same.requestFocus();
        }
    }

    @SuppressLint("MissingPermission")
    private void pair(BluetoothDevice d) {
        try {
            adapter.cancelDiscovery();   // pairing is faster and more reliable without a search running
            d.createBond();
        } catch (SecurityException ignored) {
        }
        refresh();
    }

    private void forget(BluetoothDevice d) {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(name(d))
                .setItems(new CharSequence[]{getString(R.string.bt_forget)}, (dlg, which) -> {
                    try {
                        d.getClass().getMethod("removeBond").invoke(d);
                    } catch (Exception ignored) {
                    }
                    main.postDelayed(this::refresh, 600);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openAndroidBluetooth() {
        Intent slice = new Intent("android.settings.SLICE_SETTINGS")
                .putExtra("slice_uri", "content://com.google.android.tv.btservices.settings.sliceprovider/general");
        try {
            startActivity(slice);
        } catch (Exception e) {
            open(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
        }
    }

    @SuppressLint("MissingPermission")
    private String name(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null || n.isEmpty() ? d.getAddress() : n;
        } catch (SecurityException e) {
            return d.getAddress();
        }
    }

    /** Headphones and speakers, keyboards, game controllers, remotes, or plain Bluetooth. */
    @SuppressLint("MissingPermission")
    private static int icon(BluetoothDevice d) {
        BluetoothClass c;
        try {
            c = d.getBluetoothClass();
        } catch (SecurityException e) {
            return R.drawable.ic_bluetooth;
        }
        if (c == null) return R.drawable.ic_bluetooth;
        int major = c.getMajorDeviceClass();
        if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) return R.drawable.ic_volume_up;
        if (major == BluetoothClass.Device.Major.PERIPHERAL) {
            int dev = c.getDeviceClass();
            if ((dev & 0x40) != 0) return R.drawable.ic_keyboard;           // keyboard
            if ((dev & 0x0C) == 0x0C) return R.drawable.ic_remote;          // remote control
            return R.drawable.ic_gamepad;
        }
        return R.drawable.ic_bluetooth;
    }
}
