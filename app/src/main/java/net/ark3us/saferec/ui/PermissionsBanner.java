package net.ark3us.saferec.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import net.ark3us.saferec.PermissionsManager;
import net.ark3us.saferec.R;

/**
 * The banner under the top bar after a recording permission was refused: never over the record
 * button. It says what the mode needs (the microphone, plus the camera for video). Its "Allow"
 * asks again, or opens the app's settings once Android stops showing the dialog.
 */
public final class PermissionsBanner {
    private static final String TAG = PermissionsBanner.class.getSimpleName();
    private static final String STATE_DENIED = "permissions_denied";

    private final Activity activity;
    private final PermissionsManager permissions;
    private final View banner;
    private final TextView text;
    // Refused since this screen opened
    private boolean denied;
    // The mode the banner speaks for
    private boolean audioOnly;

    public PermissionsBanner(Activity activity, PermissionsManager permissions, View banner, TextView text,
                             View allow, @Nullable Bundle savedState) {
        this.activity = activity;
        this.permissions = permissions;
        this.banner = banner;
        this.text = text;
        denied = savedState != null && savedState.getBoolean(STATE_DENIED);
        allow.setOnClickListener(v -> onAllow());
    }

    /**
     * Asks for what a recording of this kind needs. Runs onGranted once granted; a refusal shows
     * the banner.
     */
    public void ask(boolean audioOnly, @Nullable Runnable onGranted) {
        this.audioOnly = audioOnly;
        permissions.requestRecordingPermissions(audioOnly, () -> {
            if (PermissionsManager.hasRecordingPermissions(activity, audioOnly)) {
                denied = false;
                render();
                if (onGranted != null) {
                    onGranted.run();
                }
            } else {
                Log.i(TAG, "Recording permission refused, audioOnly=" + audioOnly);
                denied = true;
                render();
            }
        });
    }

    /** The screen came back or the mode changed: the banner goes once this mode can record. */
    public void refresh(boolean audioOnly) {
        this.audioOnly = audioOnly;
        if (PermissionsManager.hasRecordingPermissions(activity, audioOnly)) {
            denied = false;
        }
        render();
    }

    public void save(Bundle outState) {
        outState.putBoolean(STATE_DENIED, denied);
    }

    private void render() {
        text.setText(audioOnly ? R.string.permissions_banner_audio : R.string.permissions_banner);
        banner.setVisibility(denied ? View.VISIBLE : View.GONE);
    }

    private void onAllow() {
        if (!dialogNoLongerShown()) {
            ask(audioOnly, null);
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
        for (String permission : PermissionsManager.recordingPermissions(audioOnly)) {
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
                    && !activity.shouldShowRequestPermissionRationale(permission)) {
                return true;
            }
        }
        return false;
    }
}
