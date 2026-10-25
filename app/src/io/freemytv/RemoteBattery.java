package io.freemytv;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.UUID;

/**
 * The remote's battery, read from the remote itself: Android 11 doesn't keep the level of a Bluetooth LE remote where
 * apps can see it, but such a remote carries the standard Battery Service, as the TV's own Settings shows. To save its
 * battery the remote is only connected for a little while after a key press, so it's read then: when the home screen
 * opens (the Home button just woke it), when a key is let go on the home screen, and when the remote connects while
 * another app is in front (the home screen, still running, hears it). At most every 30 minutes; the last level is kept,
 * so the pill shows it after a restart too.
 */
final class RemoteBattery {
    private RemoteBattery() {}

    static final UUID BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    static final UUID BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

    interface Result { void level(String remote, int percent); }   // percent: -1 when the remote didn't say

    /** The last level read (-1: none yet) and when; the home screen's info pill shows it. */
    static volatile int last = -1;
    static volatile long lastAt;
    private static volatile long triedAt;
    private static volatile String address = "";   // the remote that answered, read first from then on
    private static volatile boolean reading, loaded;
    /** How often it's read again: a level read, every 30 minutes; a remote that didn't answer, every 5. */
    private static final long EVERY = 30 * 60_000L, RETRY = 5 * 60_000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context app;
    private static Runnable changed = () -> {};
    private static final Runnable READ = RemoteBattery::readNow;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("remote-battery", Context.MODE_PRIVATE);
    }

    /** The last level kept, and who to tell when a new one comes (the home screen's pill). */
    static void start(Context c, Runnable onChange) {
        app = c.getApplicationContext();
        changed = onChange;
        if (loaded) return;
        loaded = true;
        SharedPreferences p = prefs(app);
        last = p.getInt("level", -1);
        lastAt = p.getLong("at", 0);
        address = p.getString("address", "");
    }

    /** The level, in `delayMs` (a later call moves it): only if it's due and a remote is connected then. */
    static void soon(long delayMs) {
        if (app == null || reading) return;
        long now = System.currentTimeMillis();
        if (last >= 0 && now - lastAt < EVERY || now - triedAt < RETRY) return;
        MAIN.removeCallbacks(READ);
        MAIN.postDelayed(READ, delayMs);
    }

    /** While the home screen runs (even behind another app): the remote just woke up, so its level can be read. */
    static BroadcastReceiver listen(Context c) {
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null && isRemote(d)) soon(2500);   // its services ready a moment after it connects
            }
        };
        c.registerReceiver(r, new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED));
        return r;
    }

    /** The remote that answered before or, until one has, a Bluetooth remote or keyboard (never headphones or phones). */
    private static boolean isRemote(BluetoothDevice d) {
        try {
            if (!address.isEmpty()) return address.equals(d.getAddress());
            BluetoothClass k = d.getBluetoothClass();
            return d.getBondState() == BluetoothDevice.BOND_BONDED
                    && (k == null || k.getMajorDeviceClass() == BluetoothClass.Device.Major.PERIPHERAL
                        || k.getMajorDeviceClass() == BluetoothClass.Device.Major.UNCATEGORIZED);
        } catch (SecurityException e) {   // no Bluetooth permission (Android 12 and newer ask for it)
            return false;
        }
    }

    private static void readNow() {
        if (app == null || reading) return;
        BluetoothDevice remote = connectedRemote(app);
        if (remote == null) return;   // asleep again: not a try, the next wake reads it
        reading = true;
        triedAt = System.currentTimeMillis();
        try {
            read(app, remote, (who, percent) -> {
                reading = false;
                if (percent < 0) {
                    AppLog.w("remote battery: " + who);
                    return;
                }
                last = percent;
                lastAt = System.currentTimeMillis();
                address = remote.getAddress();
                prefs(app).edit().putInt("level", last).putLong("at", lastAt).putString("address", address).apply();
                changed.run();
            });
        } catch (RuntimeException e) {
            reading = false;
            AppLog.w("remote battery: " + e.getMessage());
        }
    }

    /** The remote among the connected Bluetooth LE devices: the one that answered before, else any bonded remote. */
    private static BluetoothDevice connectedRemote(Context c) {
        try {
            BluetoothManager bm = c.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
            if (adapter == null || !adapter.isEnabled()) return null;
            BluetoothDevice any = null;
            for (BluetoothDevice d : bm.getConnectedDevices(BluetoothProfile.GATT)) {
                if (!address.isEmpty() && address.equals(d.getAddress())) return d;
                if (any == null && isRemote(d)) any = d;
            }
            return address.isEmpty() ? any : null;
        } catch (SecurityException e) {
            return null;
        }
    }

    /** Reads the level of `remote` (connected); `done` gets it on the main thread, once. */
    static void read(Context c, BluetoothDevice remote, Result done) {
        String name = remote.getName() + " " + remote.getAddress();
        boolean[] answered = {false};
        BluetoothGatt[] gatt = {null};
        BluetoothGattCallback callback = new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
                if (state == BluetoothProfile.STATE_CONNECTED) g.discoverServices();
                else if (state == BluetoothProfile.STATE_DISCONNECTED) finish(g, -1, "disconnected (status " + status + ")");
            }

            @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
                BluetoothGattService s = g.getService(BATTERY_SERVICE);
                BluetoothGattCharacteristic level = s == null ? null : s.getCharacteristic(BATTERY_LEVEL);
                if (level == null || !g.readCharacteristic(level)) finish(g, -1, "no battery service");
            }

            @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
                byte[] v = ch.getValue();
                finish(g, status == BluetoothGatt.GATT_SUCCESS && v != null && v.length > 0 ? v[0] & 0xFF : -1, "status " + status);
            }

            private void finish(BluetoothGatt g, int percent, String why) {
                synchronized (answered) {
                    if (answered[0]) return;
                    answered[0] = true;
                }
                g.close();
                MAIN.post(() -> done.level(name + (percent < 0 ? " (" + why + ")" : ""), percent));
            }
        };
        gatt[0] = remote.connectGatt(c, false, callback, BluetoothDevice.TRANSPORT_LE);
        // No answer within 8 s (the remote went to sleep): given up, tried again in 5 minutes.
        MAIN.postDelayed(() -> {
            synchronized (answered) {
                if (answered[0]) return;
                answered[0] = true;
            }
            if (gatt[0] != null) gatt[0].close();
            done.level(name + " (no answer in 8 s)", -1);
        }, 8000);
    }
}
