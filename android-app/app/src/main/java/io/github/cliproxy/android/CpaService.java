package io.github.cliproxy.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CpaService extends Service {
    public static final String ACTION_START = "io.github.cliproxy.android.START";
    public static final String ACTION_STOP = "io.github.cliproxy.android.STOP";
    public static final String ACTION_RESTART = "io.github.cliproxy.android.RESTART";
    private static final int NOTIFICATION_ID = 8317;
    private static final String CHANNEL_ID = "cpa_service";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean manualStop = new AtomicBoolean(false);
    private Process process;
    private Thread logThread;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    public static void start(Context context) {
        Intent i = new Intent(context, CpaService.class).setAction(ACTION_START);
        ContextCompat.startForegroundService(context, i);
    }

    public static void stop(Context context) {
        Intent i = new Intent(context, CpaService.class).setAction(ACTION_STOP);
        ContextCompat.startForegroundService(context, i);
    }

    public static void restart(Context context) {
        Intent i = new Intent(context, CpaService.class).setAction(ACTION_RESTART);
        ContextCompat.startForegroundService(context, i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startAsForeground(getString(R.string.service_starting));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            manualStop.set(true);
            stopNative();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            manualStop.set(false);
            stopNative();
            handler.postDelayed(this::startNativeIfNeeded, 350);
            return START_STICKY;
        }
        manualStop.set(false);
        startNativeIfNeeded();
        return START_STICKY;
    }

    private synchronized void startNativeIfNeeded() {
        if (process != null && isProcessAlive(process)) return;
        try {
            AppPaths.ensureWorkspace(this);
            File binary = AppPaths.nativeBinary(this);
            if (!binary.isFile()) throw new IllegalStateException("Native core is missing: " + binary);

            SharedPreferences p = getSharedPreferences("background", MODE_PRIVATE);
            acquireLocks(p);

            ProcessBuilder pb = new ProcessBuilder(
                    binary.getAbsolutePath(),
                    "-config", AppPaths.config(this).getAbsolutePath()
            );
            pb.directory(AppPaths.root(this));
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("HOME", getFilesDir().getAbsolutePath());
            env.put("TMPDIR", getCacheDir().getAbsolutePath());
            env.put("MANAGEMENT_STATIC_PATH", AppPaths.staticDir(this).getAbsolutePath());
            env.put("GODEBUG", "netdns=cgo");
            env.put("LD_LIBRARY_PATH", getApplicationInfo().nativeLibraryDir);

            process = pb.start();
            long pid = processId(process);
            appendLog("android", "native process started" + (pid > 0 ? ", pid=" + pid : ""));
            startAsForeground(pid > 0 ? getString(R.string.service_running_pid, pid) : getString(R.string.service_running));
            consumeLogs(process);
            watchProcess(process);
        } catch (Throwable t) {
            Log.e("CPA", "Failed to start native process", t);
            appendLog("android", "start failed: " + t);
            startAsForeground(getString(R.string.service_start_failed));
            scheduleRestartIfAllowed();
        }
    }

    private void consumeLogs(Process watched) {
        logThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(watched.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) appendLog("core", line);
            } catch (Exception e) {
                appendLog("android", "log reader ended: " + e.getMessage());
            }
        }, "cpa-log-reader");
        logThread.setDaemon(true);
        logThread.start();
    }

    private void watchProcess(Process watched) {
        Thread t = new Thread(() -> {
            try {
                int code = watched.waitFor();
                appendLog("android", "native process exited with code " + code);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            synchronized (CpaService.this) {
                if (process == watched) process = null;
            }
            if (!manualStop.get()) scheduleRestartIfAllowed();
        }, "cpa-process-watcher");
        t.setDaemon(true);
        t.start();
    }

    private void scheduleRestartIfAllowed() {
        SharedPreferences p = getSharedPreferences("background", MODE_PRIVATE);
        if (p.getBoolean("auto_restart", true) && !manualStop.get()) {
            handler.postDelayed(this::startNativeIfNeeded, 2000);
        }
    }

    private synchronized void stopNative() {
        Process p = process;
        process = null;
        if (p != null) {
            try {
                p.destroy();
                handler.postDelayed(() -> {
                    if (isProcessAlive(p) && Build.VERSION.SDK_INT >= 26) p.destroyForcibly();
                }, 1500);
            } catch (Throwable ignored) {}
        }
        releaseLocks();
        appendLog("android", "native process stopped");
    }

    private void acquireLocks(SharedPreferences p) {
        releaseLocks();
        if (p.getBoolean("cpu_wakelock", true)) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CLIProxyAPI:Service");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
        if (p.getBoolean("wifi_lock", false)) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CLIProxyAPI:WiFi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Throwable ignored) {}
        wakeLock = null;
        wifiLock = null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel c = new NotificationChannel(CHANNEL_ID, getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW);
        c.setDescription(getString(R.string.service_channel_description));
        nm.createNotificationChannel(c);
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, CpaService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPi)
                .addAction(0, getString(R.string.service_stop_action), stopPi)
                .build();
    }

    private void startAsForeground(String text) {
        Notification n = notification(text);
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, 0);
        }
    }

    private synchronized void appendLog(String source, String line) {
        try {
            File file = AppPaths.wrapperLog(this);
            file.getParentFile().mkdirs();
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            String record = ts + " [" + source + "] " + line + "\n";
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(record.getBytes(StandardCharsets.UTF_8));
            }
            if (file.length() > 8L * 1024L * 1024L) {
                File old = new File(file.getParentFile(), "android-wrapper.log.1");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(old);
            }
        } catch (Throwable ignored) {}
    }


    private static boolean isProcessAlive(Process p) {
        if (p == null) return false;
        if (Build.VERSION.SDK_INT >= 26) return p.isAlive();
        try { p.exitValue(); return false; } catch (IllegalThreadStateException e) { return true; }
    }

    private static long processId(Process p) {
        // Android's java.lang.Process API does not expose a portable child-PID
        // accessor in the Android SDK stubs.  The PID is cosmetic here (it is
        // only used in the foreground-notification text), so avoid hidden APIs
        // or reflection and simply omit it.  Process lifecycle management uses
        // the Process object itself and is unaffected.
        return -1L;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        manualStop.set(true);
        stopNative();
        super.onDestroy();
    }
}
