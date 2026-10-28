package net.ark3us.saferec.guard;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

/** The power-key presses that start a recording. */
public final class Guard {
    private static final String TAG = Guard.class.getSimpleName();
    private static final String PREFS = "saferec_guard";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PRESSES = "presses";

    // How many presses start a recording, picked by the user. Four by default: lifting a Pixel
    // lights its screen, and a lift then off and on again made three. Five quick presses call
    // emergency services on many phones.
    public static final int MIN_PRESSES = 3;
    public static final int MAX_PRESSES = 5;
    public static final int DEFAULT_PRESSES = 4;

    /** What the feature is doing, in the order the user has to fix it. */
    public enum Status { DISABLED, NEEDS_OVERLAY, NEEDS_BATTERY_EXEMPTION, STOPPED, ACTIVE }

    private Guard() {}

    public static Status statusOf(boolean enabled, boolean canDrawOverlays,
                                  boolean ignoringBatteryOptimizations, boolean running) {
        if (!enabled) return Status.DISABLED;
        if (!canDrawOverlays) return Status.NEEDS_OVERLAY;
        if (!ignoringBatteryOptimizations) return Status.NEEDS_BATTERY_EXEMPTION;
        return running ? Status.ACTIVE : Status.STOPPED;
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static synchronized void setEnabled(Context context, boolean enabled) {
        Context app = context.getApplicationContext();
        prefs(app).edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (enabled) {
            ensureRunning(app);
            GuardWatchdog.schedule(app);
        } else {
            app.stopService(new Intent(app, GuardService.class));
            GuardWatchdog.cancel(app);
        }
    }

    public static int presses(Context context) {
        return validPresses(prefs(context).getInt(KEY_PRESSES, DEFAULT_PRESSES));
    }

    public static void setPresses(Context context, int presses) {
        prefs(context).edit().putInt(KEY_PRESSES, validPresses(presses)).apply();
    }

    static int validPresses(int presses) {
        return presses >= MIN_PRESSES && presses <= MAX_PRESSES ? presses : DEFAULT_PRESSES;
    }

    public static Status status(Context context) {
        return statusOf(isEnabled(context), Settings.canDrawOverlays(context),
                ignoringBatteryOptimizations(context), GuardService.running);
    }

    /**
     * Starts the service when the guard is enabled and allowed to draw over other apps. It runs
     * without the battery exemption too: the status then warns.
     */
    public static synchronized void ensureRunning(Context context) {
        Context app = context.getApplicationContext();
        if (!isEnabled(app)) return;
        // KEEP makes this a no-op when the job exists; a restored or reset WorkManager loses it
        GuardWatchdog.schedule(app);
        if (!Settings.canDrawOverlays(app) || GuardService.running) return;
        try {
            app.startForegroundService(new Intent(app, GuardService.class));
        } catch (RuntimeException e) {
            // ForegroundServiceStartNotAllowedException (API 31+) is one of these
            Log.w(TAG, "Guard cannot start", e);
        }
    }

    public static Intent overlaySettingsIntent(Context context) {
        return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.getPackageName()));
    }

    public static Intent batterySettingsIntent() {
        return new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
    }

    private static boolean ignoringBatteryOptimizations(Context context) {
        PowerManager pm = context.getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
