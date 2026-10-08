package io.github.cliproxy.android;

import android.app.Application;
import android.util.Log;

import com.google.android.material.color.DynamicColors;

public final class CpaApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        try {
            AppPaths.ensureWorkspace(this);
        } catch (Exception e) {
            Log.e("CPA", "Failed to prepare app workspace", e);
        }
    }
}
