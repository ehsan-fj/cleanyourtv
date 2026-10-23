package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

/**
 * Automates connecting to external ARC/CEC audio system (sound bar)
 * via Sony's BraviaSyncService IPC.
 */
public final class AudioSystemEnforcer {

    private static final String TAG = "AudioSystemEnforcer";
    private static final String SONY_SYNC_PKG = "com.sony.dtv.braviasyncservice";
    private static final String SONY_SYNC_SVC = "com.sony.dtv.braviasyncservice.BraviaSyncService";

    // MSG_CHANGE_PEER_AUDIO_MODE in BraviaSyncServiceConstants
    public static final int MSG_CHANGE_PEER_AUDIO_MODE = 17;
    public static final int MODE_TV_SPEAKERS = 0;
    public static final int MODE_AUDIO_SYSTEM = 1;

    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private AudioSystemEnforcer() {}

    /**
     * Schedules switching audio output to external audio system (LG sound bar) after delayMillis.
     *
     * <p>When Bluetooth A2DP headphones are connected the sound bar must stay quiet: Sony's
     * Bravia Sync route is independent of Android's A2DP route, so forcing the audio system
     * here makes sound come out of both the headphones and the sound bar. In that case this
     * keeps the TV speakers selected instead (the headphones still receive the A2DP stream).
     */
    public static void ensureAudioSystem(final Context context, long delayMillis) {
        setAudioMode(context, MODE_AUDIO_SYSTEM, delayMillis, true);
    }

    /**
     * Sends MSG_CHANGE_PEER_AUDIO_MODE to BraviaSyncService with the specified mode.
     * Mode: 1 = Audio System (sound bar), 0 = TV Speakers.
     */
    public static void setAudioMode(final Context context, final int mode, long delayMillis) {
        setAudioMode(context, mode, delayMillis, false);
    }

    private static void setAudioMode(final Context context, final int requestedMode, long delayMillis,
                                     final boolean respectA2dp) {
        if (context == null) return;
        final Context appContext = context.getApplicationContext();

        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                int mode = requestedMode;
                if (respectA2dp && mode == MODE_AUDIO_SYSTEM && isA2dpConnected(appContext)) {
                    Log.i(TAG, "A2DP output is connected; keeping TV speakers instead of the audio system");
                    mode = MODE_TV_SPEAKERS;
                }
                final int effectiveMode = mode;
                try {
                    Intent intent = new Intent();
                    intent.setComponent(new ComponentName(SONY_SYNC_PKG, SONY_SYNC_SVC));
                    intent.setAction(SONY_SYNC_SVC);

                    ServiceConnection conn = new ServiceConnection() {
                        @Override
                        public void onServiceConnected(ComponentName name, IBinder service) {
                            Log.i(TAG, "Connected to BraviaSyncService, sending mode: " + effectiveMode);
                            try {
                                Messenger messenger = new Messenger(service);
                                Message msg = Message.obtain();
                                msg.what = MSG_CHANGE_PEER_AUDIO_MODE;
                                msg.arg1 = effectiveMode;
                                messenger.send(msg);
                                Log.i(TAG, "Sent MSG_CHANGE_PEER_AUDIO_MODE (" + effectiveMode + ") successfully");
                            } catch (Exception e) {
                                Log.w(TAG, "Failed to send audio mode message", e);
                            }

                            // Keep connection briefly to allow message dispatch, then unbind
                            final ServiceConnection self = this;
                            sHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        appContext.unbindService(self);
                                        Log.i(TAG, "Unbound from BraviaSyncService");
                                    } catch (Exception ignored) {}
                                }
                            }, 3000L);
                        }

                        @Override
                        public void onServiceDisconnected(ComponentName name) {
                            Log.i(TAG, "Disconnected from BraviaSyncService");
                        }
                    };

                    boolean bound = appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE);
                    Log.i(TAG, "Requested bindService to BraviaSyncService, result=" + bound);
                } catch (Exception e) {
                    Log.w(TAG, "Exception while attempting to bind BraviaSyncService", e);
                }
            }
        }, delayMillis);
    }

    /** True while an A2DP (Bluetooth headphone/speaker) output is connected or routed. */
    @SuppressWarnings("deprecation")
    public static boolean isA2dpConnected(Context context) {
        if (context == null) return false;
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        if (am.isBluetoothA2dpOn()) return true;
        AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        if (devices != null) {
            for (AudioDeviceInfo device : devices) {
                if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return true;
            }
        }
        return false;
    }
}
