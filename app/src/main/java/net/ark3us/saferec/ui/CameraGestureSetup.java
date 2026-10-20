package net.ark3us.saferec.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import net.ark3us.saferec.BuildConfig;
import net.ark3us.saferec.CameraGesture;
import net.ark3us.saferec.R;

/**
 * The setup of the camera double press. Android stores "SafeRec, Always" once per action, and
 * only the user can pick it in the chooser. After each try the setup asks Android whether the
 * action now resolves to SafeRec, and offers the try again if not (a tap on SafeRec under
 * "Use a different app" opens it just once). Tries already stored are skipped.
 */
public final class CameraGestureSetup {
    private static final int NO_TRY = -1;
    /**
     * The try waiting for the user to come back (an index of CameraGesture.tryIntents()).
     * Static: a try that reaches SafeRec recreates the activity (QuickStartActivity opens
     * MainActivity with CLEAR_TOP), so a field would be lost.
     */
    private static int pendingTry = NO_TRY;
    /** Consecutive "not saved" results of the same try; reset when it is stored or the setup starts over. */
    private static int notSaved = 0;
    private static int notSavedTry = NO_TRY;

    private CameraGestureSetup() {}

    /** The index of the first try not stored yet, or -1 when every action resolves to SafeRec. */
    public static int nextTry(boolean[] stored) {
        for (int i = 0; i < stored.length; i++) {
            if (!stored[i]) return i;
        }
        return NO_TRY;
    }

    /** Android's last-choice trap: the same try failed twice in a row, so a third would loop. */
    public static boolean isStuck(int consecutiveNotSaved) {
        return consecutiveNotSaved >= 2;
    }

    private static void resetCount() {
        notSaved = 0;
        notSavedTry = NO_TRY;
    }

    /** Starts the setup: explains, then tries the first action not stored yet. */
    public static void begin(Activity activity) {
        resetCount();
        int next = nextTry(CameraGesture.storedTries(activity));
        if (next == NO_TRY) return;
        showDialog(activity, next);
    }

    /** Call from onResume(): checks the try the user just came back from. */
    public static void onResume(Activity activity) {
        if (pendingTry == NO_TRY) return;
        int tried = pendingTry;
        pendingTry = NO_TRY;
        if (!CameraGesture.isEnabled(activity)) return;
        boolean[] stored = CameraGesture.storedTries(activity);
        if (tried < stored.length && !stored[tried]) {
            notSaved = notSavedTry == tried ? notSaved + 1 : 1;
            notSavedTry = tried;
            if (isStuck(notSaved)) {
                resetCount();
                // Never leave a chooser behind the lock screen: the double press goes back to the camera
                CameraGesture.setEnabled(activity, false);
                new AlertDialog.Builder(activity)
                    .setTitle(R.string.camera_gesture)
                    .setMessage(BuildConfig.POWER_GESTURE
                        ? R.string.camera_gesture_stuck : R.string.camera_gesture_stuck_no_guard)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
                return;
            }
            new AlertDialog.Builder(activity)
                .setTitle(R.string.camera_gesture)
                .setMessage(R.string.camera_gesture_not_saved)
                .setPositiveButton(R.string.camera_gesture_try_again, (d, w) -> launch(activity, tried))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return;
        }
        resetCount();
        int next = nextTry(stored);
        if (next == NO_TRY) {
            new AlertDialog.Builder(activity)
                .setTitle(R.string.camera_gesture)
                .setMessage(R.string.camera_gesture_ready)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        } else {
            showDialog(activity, next);
        }
    }

    /** Step 0 explains everything, the next steps only say what is left. */
    private static void showDialog(Activity activity, int step) {
        String message = step == 0
            ? activity.getString(R.string.camera_gesture_choose) + "\n\n" + activity.getString(R.string.camera_gesture_tries)
                + "\n\n" + activity.getString(R.string.camera_gesture_lock_screen) + "\n\n" + activity.getString(R.string.camera_gesture_undo)
            : activity.getString(R.string.camera_gesture_try_locked);
        new AlertDialog.Builder(activity)
            .setTitle(R.string.camera_gesture)
            .setMessage(message)
            .setPositiveButton(R.string.camera_gesture_try, (d, w) -> launch(activity, step))
            .setNegativeButton(android.R.string.ok, null)
            .show();
    }

    private static void launch(Activity activity, int step) {
        pendingTry = step;
        try {
            activity.startActivity(CameraGesture.tryIntents()[step]);
        } catch (ActivityNotFoundException e) {
            pendingTry = NO_TRY;
            Toast.makeText(activity, R.string.camera_gesture_no_app, Toast.LENGTH_SHORT).show();
        }
    }
}
