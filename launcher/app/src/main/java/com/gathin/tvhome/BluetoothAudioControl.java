package com.gathin.tvhome;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.reflect.Method;

/**
 * Keeps Bluetooth headphones usable on a Sony TV whose stock A2DP auto-connect is disabled.
 *
 * <p>The Sony Bravia Sync audio route is independent of Android's A2DP route, so forcing the
 * sound bar while headphones connect makes sound come out of both. This switches Bravia Sync to
 * the TV speakers while an A2DP headset is connected and restores the audio system afterwards.
 * Android also only auto-connects A2DP when Bluetooth turns on, not when a bonded headset powers
 * on later; the ACL receiver starts A2DP as soon as the headset connects.
 *
 * <p>Acquire/release are reference counted because both MainActivity and the optional
 * InputKeyService run in the same process and must not register duplicate receivers.
 */
public final class BluetoothAudioControl {

    private static final String TAG = "BluetoothAudioControl";
    private static final long AUDIO_SWITCH_DELAY_MS = 1000L;

    private static final Object LOCK = new Object();
    private static BluetoothAudioControl instance;
    private static int refCount;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothA2dp a2dp;
    private BluetoothDevice pendingDevice;
    private boolean proxyRequested;
    private boolean registered;

    private final BroadcastReceiver aclReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            if (intent == null) return;
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null || !isAudioDevice(device)) return;

            if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(intent.getAction())) {
                if (!AudioSystemEnforcer.isA2dpConnected(context)) {
                    Log.i(TAG, "Audio device disconnected; restoring the audio system");
                    AudioSystemEnforcer.ensureAudioSystem(context, AUDIO_SWITCH_DELAY_MS);
                }
                return;
            }

            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction())) {
                Log.i(TAG, "Audio device connected: " + device.getAddress());
                AudioSystemEnforcer.setAudioMode(context, AudioSystemEnforcer.MODE_TV_SPEAKERS,
                        AUDIO_SWITCH_DELAY_MS);
                connectA2dp(device);
            }
        }
    };

    private final BluetoothProfile.ServiceListener a2dpListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != BluetoothProfile.A2DP) return;
            proxyRequested = false;
            a2dp = (BluetoothA2dp) proxy;
            BluetoothDevice device = pendingDevice;
            pendingDevice = null;
            if (device != null) connectNow(device);
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile != BluetoothProfile.A2DP) return;
            proxyRequested = false;
            a2dp = null;
        }
    };

    private BluetoothAudioControl(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Registers the Bluetooth listeners once for the process. */
    public static void acquire(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            if (instance == null) {
                instance = new BluetoothAudioControl(context);
                instance.startInternal();
            }
            refCount++;
        }
    }

    /** Unregisters the listeners when the last owner goes away. */
    public static void release() {
        synchronized (LOCK) {
            if (refCount > 0) refCount--;
            if (refCount == 0 && instance != null) {
                instance.stopInternal();
                instance = null;
            }
        }
    }

    private void startInternal() {
        if (registered) return;
        registered = true;

        if (canUseBluetooth()) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            context.registerReceiver(aclReceiver, filter);
            requestProxy();
        }

        // Home may start after the headphones were already connected.
        if (AudioSystemEnforcer.isA2dpConnected(context)) {
            AudioSystemEnforcer.setAudioMode(context, AudioSystemEnforcer.MODE_TV_SPEAKERS,
                    AUDIO_SWITCH_DELAY_MS);
        }
    }

    private void stopInternal() {
        if (!registered) return;
        registered = false;

        try {
            context.unregisterReceiver(aclReceiver);
        } catch (Exception ignored) {
        }
        if (a2dp != null) {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
            a2dp = null;
        }
        proxyRequested = false;
        pendingDevice = null;
        handler.removeCallbacksAndMessages(null);
    }

    private boolean canUseBluetooth() {
        // Android 12+ needs BLUETOOTH_CONNECT at runtime and blocks the hidden A2DP connect API.
        // Target the Android 9/10/11 TVs this helper is for; newer firmware keeps its own route.
        return Build.VERSION.SDK_INT < 31;
    }

    private static boolean isAudioDevice(BluetoothDevice device) {
        if (device.getBondState() != BluetoothDevice.BOND_BONDED) return false;
        BluetoothClass bluetoothClass = device.getBluetoothClass();
        return bluetoothClass != null
                && bluetoothClass.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO;
    }

    private void requestProxy() {
        if (a2dp != null || proxyRequested) return;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return;
        try {
            proxyRequested = adapter.getProfileProxy(context, a2dpListener, BluetoothProfile.A2DP);
        } catch (Exception e) {
            Log.w(TAG, "Could not request the A2DP profile proxy", e);
        }
    }

    private void connectA2dp(BluetoothDevice device) {
        if (a2dp != null) {
            connectNow(device);
            return;
        }
        pendingDevice = device;
        requestProxy();
    }

    private void connectNow(BluetoothDevice device) {
        try {
            Method connect = a2dp.getClass().getMethod("connect", BluetoothDevice.class);
            Object result = connect.invoke(a2dp, device);
            Log.i(TAG, "A2DP connect(" + device.getAddress() + ") -> " + result);
        } catch (Exception e) {
            Log.w(TAG, "A2DP connect failed for " + device.getAddress(), e);
        }
    }
}
