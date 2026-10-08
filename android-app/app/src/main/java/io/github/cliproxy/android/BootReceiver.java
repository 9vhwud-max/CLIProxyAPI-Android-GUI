package io.github.cliproxy.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences p = context.getSharedPreferences("background", Context.MODE_PRIVATE);
        if (!p.getBoolean("start_on_boot", false)) return;
        try {
            CpaService.start(context);
        } catch (Throwable t) {
            Log.e("CPA", "Unable to start after boot/package update", t);
        }
    }
}
