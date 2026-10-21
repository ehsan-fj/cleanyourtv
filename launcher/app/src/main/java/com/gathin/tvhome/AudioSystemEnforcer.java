package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
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
     */
    public static void ensureAudioSystem(final Context context, long delayMillis) {
        setAudioMode(context, MODE_AUDIO_SYSTEM, delayMillis);
    }

    /**
     * Sends MSG_CHANGE_PEER_AUDIO_MODE to BraviaSyncService with the specified mode.
     * Mode: 1 = Audio System (sound bar), 0 = TV Speakers.
     */
    public static void setAudioMode(final Context context, final int mode, long delayMillis) {
        if (context == null) return;
        final Context appContext = context.getApplicationContext();

        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent intent = new Intent();
                    intent.setComponent(new ComponentName(SONY_SYNC_PKG, SONY_SYNC_SVC));
                    intent.setAction(SONY_SYNC_SVC);

                    ServiceConnection conn = new ServiceConnection() {
                        @Override
                        public void onServiceConnected(ComponentName name, IBinder service) {
                            Log.i(TAG, "Connected to BraviaSyncService, sending mode: " + mode);
                            try {
                                Messenger messenger = new Messenger(service);
                                Message msg = Message.obtain();
                                msg.what = MSG_CHANGE_PEER_AUDIO_MODE;
                                msg.arg1 = mode;
                                messenger.send(msg);
                                Log.i(TAG, "Sent MSG_CHANGE_PEER_AUDIO_MODE (" + mode + ") successfully");
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
}
