package net.ark3us.saferec.ui;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;

import net.ark3us.saferec.PermissionsManager;

/**
 * The banner under the top bar after the camera or the microphone was refused: never over the
 * record button. Its "Allow" asks again, or opens the app's settings once Android stops showing
 * the dialog.
 */
public final class PermissionsBanner {
    private static final String TAG = PermissionsBanner.class.getSimpleName();
    private static final String STATE_DENIED = "permissions_denied";
    private static final String[] RECORDING = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};

    private final Activity activity;
    private final PermissionsManager permissions;
    private final View banner;
    // Refused since this screen opened
    private boolean denied;

    public PermissionsBanner(Activity activity, PermissionsManager permissions, View banner, View allow,
                             @Nullable Bundle savedState) {
        this.activity = activity;
        this.permissions = permissions;
        this.banner = banner;
        denied = savedState != null && savedState.getBoolean(STATE_DENIED);
        allow.setOnClickListener(v -> onAllow());
        refresh();
    }

    /** Asks for the recording permissions. Runs onGranted once granted; a refusal shows the banner. */
    public void ask(@Nullable Runnable onGranted) {
        permissions.requestRecordingPermissions(() -> {
            if (PermissionsManager.hasRecordingPermissions(activity)) {
                denied = false;
                refresh();
                if (onGranted != null) {
                    onGranted.run();
                }
            } else {
                Log.i(TAG, "Camera or microphone refused");
                denied = true;
                refresh();
            }
        });
    }

    /** Hides the banner once granted, for example in the app's settings. */
    public void refresh() {
        if (PermissionsManager.hasRecordingPermissions(activity)) {
            denied = false;
        }
        banner.setVisibility(denied ? View.VISIBLE : View.GONE);
    }

    public void save(Bundle outState) {
        outState.putBoolean(STATE_DENIED, denied);
    }

    private void onAllow() {
        if (!dialogNoLongerShown()) {
            ask(null);
            return;
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.getPackageName(), null)));
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No app settings page", e);
        }
    }

    /** After a refusal, no rationale means Android answers "denied" without asking the user. */
    private boolean dialogNoLongerShown() {
        for (String permission : RECORDING) {
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
                    && !activity.shouldShowRequestPermissionRationale(permission)) {
                return true;
            }
        }
        return false;
    }
}
