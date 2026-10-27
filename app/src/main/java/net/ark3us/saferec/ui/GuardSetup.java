package net.ark3us.saferec.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import net.ark3us.saferec.R;
import net.ark3us.saferec.guard.Guard;

/**
 * Leads the user to the next step the three presses still need. References the guard module,
 * which is compileOnly when the build switch is off: callers check BuildConfig.POWER_GESTURE.
 * It is the only app class whose bytecode may name the guard (scripts/check-guard-refs.sh), so
 * every switch over Guard.Status lives here: javac builds the maps of all the enum switches of a
 * class in one synthetic class, and running any of them would load Guard.Status.
 */
public final class GuardSetup {
    private static final String TAG = GuardSetup.class.getSimpleName();

    // Guard.status() as plain ints, which callers may switch on: an int switch needs no map
    public static final int STATE_OFF = 0;
    public static final int STATE_NEEDS_OVERLAY = 1;
    public static final int STATE_NEEDS_BATTERY = 2;
    public static final int STATE_STOPPED = 3;
    public static final int STATE_ACTIVE = 4;

    private GuardSetup() {}

    /** What the three presses still need, one of the STATE_ ints. */
    public static int state(Context context) {
        switch (Guard.status(context)) {
            case NEEDS_OVERLAY:
                return STATE_NEEDS_OVERLAY;
            case NEEDS_BATTERY_EXEMPTION:
                return STATE_NEEDS_BATTERY;
            case STOPPED:
                return STATE_STOPPED;
            case ACTIVE:
                return STATE_ACTIVE;
            default:
                return STATE_OFF;
        }
    }

    /** The text of a missing step. Below Android 12 the battery page is the optimization list, with other words. */
    @StringRes
    public static int stepText(int state) {
        if (state == STATE_NEEDS_OVERLAY) {
            return R.string.three_presses_step_overlay;
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ? R.string.three_presses_step_battery : R.string.three_presses_step_battery_legacy;
    }

    /**
     * Explains the first missing step in a dialog whose button opens its page, or does nothing
     * when no step is missing.
     */
    public static void openNextStep(Activity activity) {
        switch (Guard.status(activity)) {
            case NEEDS_OVERLAY:
                showOverlayDialog(activity);
                break;
            case NEEDS_BATTERY_EXEMPTION:
                showBatteryDialog(activity);
                break;
            default:
                break;
        }
    }

    /**
     * Since Android 11 the overlay page ignores the package and lists every app: the dialog says
     * to find SafeRec there. Below, the page is SafeRec's own switch. Each dialog is titled with
     * its step: with one title for both, the battery step read as the overlay asked again.
     */
    private static void showOverlayDialog(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new AlertDialog.Builder(activity)
            .setTitle(stepText(STATE_NEEDS_OVERLAY))
            .setMessage(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? R.string.three_presses_overlay_help : R.string.three_presses_overlay_help_legacy)
            .setPositiveButton(R.string.open_settings, (d, w) -> open(activity, Guard.overlaySettingsIntent(activity)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /**
     * The battery optimization list shows every app, and the owner did not find SafeRec there.
     * The app's own page leads to "App battery usage → Unrestricted" on Android 12+; on a Pixel
     * with Android 17 "Unrestricted" sits behind the words "Allow background usage", not its
     * switch. Below, only the list exists: "All apps", SafeRec, "Don't optimize".
     */
    private static void showBatteryDialog(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new AlertDialog.Builder(activity)
            .setTitle(stepText(STATE_NEEDS_BATTERY))
            .setMessage(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? R.string.three_presses_battery_help : R.string.three_presses_battery_help_legacy)
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
