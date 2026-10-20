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
 * The gate of a start, and the banner under the top bar after a recording permission was refused:
 * never over the record button. A start needs the microphone only. In Video mode the camera is
 * asked for with it, and a refused camera records sound only, which the banner says
 * (PermissionsBannerModel). Its "Allow" asks again, or opens the app's settings once Android
 * stops showing the dialog.
 */
public final class PermissionsBanner {
    private static final String TAG = PermissionsBanner.class.getSimpleName();
    private static final String STATE_MICROPHONE_REFUSED = "microphone_refused";
    private static final String STATE_CAMERA_REFUSED = "camera_refused";
    private static final String STATE_START_WAITING = "start_waiting";

    private final Activity activity;
    private final PermissionsManager permissions;
    private final View banner;
    private final TextView text;
    // Refused since this screen opened; meaningful only while still missing
    private boolean microphoneRefused;
    private boolean cameraRefused;
    // The mode the banner speaks for
    private boolean audioOnly;
    // A start waits for the permission dialog: kept across a recreation, cleared by any answer
    private boolean startWaiting;

    public PermissionsBanner(Activity activity, PermissionsManager permissions, View banner, TextView text,
                             View allow, @Nullable Bundle savedState) {
        this.activity = activity;
        this.permissions = permissions;
        this.banner = banner;
        this.text = text;
        microphoneRefused = savedState != null && savedState.getBoolean(STATE_MICROPHONE_REFUSED);
        cameraRefused = savedState != null && savedState.getBoolean(STATE_CAMERA_REFUSED);
        startWaiting = savedState != null && savedState.getBoolean(STATE_START_WAITING);
        allow.setOnClickListener(v -> onAllow());
    }

    /**
     * Runs start once a recording of this kind can start: at once when nothing needs asking,
     * otherwise after the answer, as soon as the microphone is granted. The service records
     * video only when the camera was granted too.
     */
    public void whenCanRecord(boolean audioOnly, Runnable start) {
        this.audioOnly = audioOnly;
        if (mustAsk(audioOnly)) {
            ask(audioOnly, start);
        } else {
            start.run();
        }
    }

    /** Whether a start of this kind shows the permission dialog first (whenCanRecord then waits for it). */
    public boolean mustAsk(boolean audioOnly) {
        return PermissionsBannerModel.mustAsk(audioOnly, PermissionsManager.canRecord(activity),
                PermissionsManager.hasCameraPermission(activity), cameraRefused);
    }

    /**
     * A recreated screen whose start was waiting for the dialog: the dialog is still on display,
     * so joins its answer and starts as a fresh tap would. A refusal drops the start.
     *
     * @return true when a start was waiting, so the caller must not ask again
     */
    public boolean resumeWaitingStart(boolean audioOnly, Runnable start) {
        if (!startWaiting) {
            return false;
        }
        this.audioOnly = audioOnly;
        permissions.awaitPermissionsInFlight(answered(audioOnly, start));
        return true;
    }

    /**
     * Asks for what a recording of this kind asks for, in one request. Runs onCanRecord once the
     * microphone is granted; a refusal shows the banner.
     */
    private void ask(boolean audioOnly, @Nullable Runnable onCanRecord) {
        startWaiting = onCanRecord != null;
        permissions.requestRecordingPermissions(audioOnly, answered(audioOnly, onCanRecord));
    }

    private Runnable answered(boolean audioOnly, @Nullable Runnable onCanRecord) {
        return () -> {
            startWaiting = false;
            boolean microphone = PermissionsManager.canRecord(activity);
            microphoneRefused = !microphone;
            if (!audioOnly) {
                cameraRefused = !PermissionsManager.hasCameraPermission(activity);
            }
            Log.i(TAG, "Permissions answered: microphone " + microphone + ", camera refused " + cameraRefused
                    + ", audioOnly=" + audioOnly);
            render();
            if (microphone && onCanRecord != null) {
                onCanRecord.run();
            }
        };
    }

    /** The screen came back or the mode changed: what was granted since no longer shows. */
    public void refresh(boolean audioOnly) {
        this.audioOnly = audioOnly;
        if (PermissionsManager.canRecord(activity)) {
            microphoneRefused = false;
        }
        if (PermissionsManager.hasCameraPermission(activity)) {
            cameraRefused = false;
        }
        render();
    }

    public void save(Bundle outState) {
        outState.putBoolean(STATE_MICROPHONE_REFUSED, microphoneRefused);
        outState.putBoolean(STATE_CAMERA_REFUSED, cameraRefused);
        outState.putBoolean(STATE_START_WAITING, startWaiting);
    }

    private void render() {
        PermissionsBannerModel.Kind kind = PermissionsBannerModel.kindOf(audioOnly,
                PermissionsManager.canRecord(activity), PermissionsManager.hasCameraPermission(activity),
                microphoneRefused, cameraRefused);
        switch (kind) {
            case NEEDS_MICROPHONE:
                text.setText(R.string.permissions_banner_audio);
                break;
            case NEEDS_CAMERA_AND_MICROPHONE:
                text.setText(R.string.permissions_banner);
                break;
            case SOUND_ONLY:
                text.setText(R.string.permissions_banner_sound_only);
                break;
            default:
                break;
        }
        banner.setVisibility(kind == PermissionsBannerModel.Kind.HIDDEN ? View.GONE : View.VISIBLE);
    }

    /** Asks for what is missing: with sound only, the camera alone (the microphone is granted). */
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
