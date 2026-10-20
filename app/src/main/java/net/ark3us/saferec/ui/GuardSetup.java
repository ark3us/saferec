package net.ark3us.saferec.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;

import net.ark3us.saferec.R;
import net.ark3us.saferec.guard.Guard;

/**
 * Leads the user to the next step the three presses still need. References the guard module,
 * which is compileOnly when the build switch is off: callers check BuildConfig.POWER_GESTURE.
 */
public final class GuardSetup {
    private static final String TAG = GuardSetup.class.getSimpleName();

    private GuardSetup() {}

    /** Opens the page of the first missing step, or does nothing when no step is missing. */
    public static void openNextStep(Activity activity) {
        switch (Guard.status(activity)) {
            case NEEDS_OVERLAY:
                open(activity, Guard.overlaySettingsIntent(activity));
                break;
            case NEEDS_BATTERY_EXEMPTION:
                showBatteryDialog(activity);
                break;
            default:
                break;
        }
    }

    /**
     * The battery optimization list shows every app, and the owner did not find SafeRec there.
     * The app's own page leads to "App battery usage → Unrestricted" on Android 12+.
     */
    private static void showBatteryDialog(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new AlertDialog.Builder(activity)
            .setTitle(R.string.power_gesture)
            .setMessage(R.string.three_presses_battery_help)
            .setPositiveButton(R.string.open_settings, (d, w) -> open(activity, batterySettingsIntent(activity)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private static Intent batterySettingsIntent(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return appDetailsIntent(context);
        }
        return Guard.batterySettingsIntent();
    }

    private static Intent appDetailsIntent(Context context) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.getPackageName(), null));
    }

    /** Some phones lack a settings page: the app's own page is always there. */
    private static void open(Activity activity, Intent intent) {
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No page for " + intent.getAction() + ", opening the app's page", e);
            try {
                activity.startActivity(appDetailsIntent(activity));
            } catch (ActivityNotFoundException e2) {
                Log.e(TAG, "Cannot open the app's settings page", e2);
            }
        }
    }
}
