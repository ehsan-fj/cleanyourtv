package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class AudioSyncReceiver extends BroadcastReceiver {
    private static final String TAG = "AudioSyncReceiver";
    public static final String ACTION_SYNC_AUDIO = "com.gathin.tvhome.ACTION_SYNC_AUDIO";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.i(TAG, "Received broadcast action: " + action);

        if (ACTION_SYNC_AUDIO.equals(action)) {
            int mode = intent.getIntExtra("mode", AudioSystemEnforcer.MODE_AUDIO_SYSTEM);
            long delay = intent.getLongExtra("delay", 0L);
            Log.i(TAG, "Explicit audio mode request: mode=" + mode + ", delay=" + delay);
            AudioSystemEnforcer.setAudioMode(context, mode, delay);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            Log.i(TAG, "BOOT_COMPLETED received, scheduling sound bar sync (3.5s and 7s)");
            AudioSystemEnforcer.ensureAudioSystem(context, 3500L);
            AudioSystemEnforcer.ensureAudioSystem(context, 7000L);
        }
    }
}
