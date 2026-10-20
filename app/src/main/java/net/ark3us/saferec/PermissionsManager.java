package net.ark3us.saferec;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.google.api.services.drive.DriveScopes;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class PermissionsManager {
    public interface DriveCallback {
        /**
         * @param accessToken the token, or null when there is none
         * @param needsUser   true when Play services want the user (consent or sign-in)
         */
        void onResult(@Nullable String accessToken, boolean needsUser);
    }

    private static final String TAG = PermissionsManager.class.getSimpleName();
    /** Needed by every recording: without it nothing records. */
    private static final String MICROPHONE = Manifest.permission.RECORD_AUDIO;
    /** Asked for in Video mode, never needed: without it a video start records sound only. */
    private static final String CAMERA = Manifest.permission.CAMERA;
    private final AppCompatActivity context;
    private ActivityResultLauncher<String[]> requestPermissionsLauncher;
    private ActivityResultLauncher<IntentSenderRequest> startAuthIntent;
    // Main thread only. Callers waiting for the one resolution screen on display.
    private final List<DriveCallback> pendingDrive = new ArrayList<>();
    private boolean resolutionShown = false;
    // Callers waiting for the one permission dialog in flight (null: none in flight).
    private final List<Runnable> pendingPermissions = new ArrayList<>();
    private boolean permissionsInFlight = false;
    boolean allGranted = false;

    public PermissionsManager(AppCompatActivity context) {
        this.context = context;
        init();
    }

    private void init() {
        requestPermissionsLauncher = context.registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    allGranted = true;
                    for (Map.Entry<String, Boolean> entry : result.entrySet()) {
                        Boolean granted = entry.getValue();
                        String permission = entry.getKey();
                        Log.i(TAG, "Permission " + permission + " granted: " + granted);
                        if (!Boolean.TRUE.equals(granted)) {
                            allGranted = false;
                            break;
                        }
                    }
                    Log.i(TAG, "Permission request completed. allGranted=" + allGranted);
                    notifyPermissionsResult();
                });

        startAuthIntent = context.registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                activityResult -> {
                    try {
                        AuthorizationResult result =
                                Identity.getAuthorizationClient(context)
                                        .getAuthorizationResultFromIntent(activityResult.getData());
                        String accessToken = result.getAccessToken();
                        Log.i(TAG, "Authorization succeeded; access token received=" + (accessToken != null));
                        finishResolution(accessToken);
                    } catch (ApiException e) {
                        Log.e(TAG, "Authorization failed", e);
                        finishResolution(null);
                    }
                });
    }

    private void finishResolution(@Nullable String accessToken) {
        resolutionShown = false;
        List<DriveCallback> waiting = new ArrayList<>(pendingDrive);
        pendingDrive.clear();
        for (DriveCallback cb : waiting) {
            cb.onResult(accessToken, false);
        }
    }

    /**
     * Asks Play services for a Drive token. Never touches runtime permissions.
     * Every callback fires once, with its own result.
     *
     * @param interactive false never launches a UI: it reports needsUser=true when Play
     *                    services want a resolution. true launches it, or joins the one
     *                    already on screen.
     */
    public void connectDrive(boolean interactive, DriveCallback callback) {
        if (interactive && resolutionShown) {
            pendingDrive.add(callback);
            return;
        }
        AuthorizationRequest req = AuthorizationRequest.builder()
                .setRequestedScopes(Collections.singletonList(new Scope(DriveScopes.DRIVE_FILE)))
                .build();

        Log.i(TAG, "Requesting authorization, interactive=" + interactive);

        Identity.getAuthorizationClient(context)
                .authorize(req)
                .addOnSuccessListener(result -> {
                    Log.i(TAG, "Authorization result: " + result);
                    if (result.hasResolution() && result.getPendingIntent() != null) {
                        if (interactive) {
                            pendingDrive.add(callback);
                            if (!resolutionShown) {
                                resolutionShown = true;
                                Log.i(TAG, "Authorization requires user resolution");
                                try {
                                    startAuthIntent.launch(
                                            new IntentSenderRequest.Builder(result.getPendingIntent().getIntentSender()).build()
                                    );
                                } catch (RuntimeException e) {
                                    Log.e(TAG, "Could not launch the authorization", e);
                                    finishResolution(null);
                                }
                            }
                        } else {
                            Log.i(TAG, "Authorization needs the user; not launching");
                            callback.onResult(null, true);
                        }
                    } else {
                        String accessToken = result.getAccessToken();
                        Log.i(TAG, "Authorization succeeded without resolution; token received=" + (accessToken != null));
                        callback.onResult(accessToken, false);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to authorize", e);
                    callback.onResult(null, false);
                });
    }

    /**
     * After a recreation the dialog of the old screen is still on display and its answer comes to
     * this instance with nobody waiting. Joins it: onResult runs after that answer, and a request
     * made meanwhile joins it too, instead of showing a second dialog.
     */
    public void awaitPermissionsInFlight(Runnable onResult) {
        permissionsInFlight = true;
        pendingPermissions.add(onResult);
    }

    private void notifyPermissionsResult() {
        permissionsInFlight = false;
        List<Runnable> waiting = new ArrayList<>(pendingPermissions);
        pendingPermissions.clear();
        for (Runnable r : waiting) {
            r.run();
        }
    }

    /**
     * What a recording of this kind asks for: the microphone always, the camera only for video.
     * The one list behind every recording request. Only the microphone is needed to start
     * (canRecord): SafeRecService records sound only when the camera is refused. An audio-only
     * recording enters the foreground as microphone only, so it never asks for the camera.
     */
    public static String[] recordingPermissions(boolean audioOnly) {
        return audioOnly ? new String[]{MICROPHONE} : new String[]{CAMERA, MICROPHONE};
    }

    /** Whether a recording may start, in any mode: the microphone is granted. */
    public static boolean canRecord(Context context) {
        return granted(context, MICROPHONE);
    }

    /** Whether a video start records video: otherwise it records sound only. */
    public static boolean hasCameraPermission(Context context) {
        return granted(context, CAMERA);
    }

    /** Camera and microphone: everything a video recording asks for. */
    public static boolean hasRecordingPermissions(Context context) {
        return hasRecordingPermissions(context, false);
    }

    /**
     * Whether everything a recording of this kind asks for is granted: the microphone, plus the
     * camera for video. Not the gate of a start, which needs only the microphone (canRecord).
     */
    public static boolean hasRecordingPermissions(Context context, boolean audioOnly) {
        for (String permission : recordingPermissions(audioOnly)) {
            if (!granted(context, permission)) {
                return false;
            }
        }
        return true;
    }

    private static boolean granted(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Asks for camera, microphone and, on API 33+, notifications. Runs onResult after the
     * answer, or at once when all are granted. Never asks for Drive.
     */
    public void requestRecordingPermissions(@Nullable Runnable onResult) {
        requestRecordingPermissions(false, onResult);
    }

    /**
     * Asks for what a recording of this kind asks for (recordingPermissions) and, on API 33+,
     * notifications, all in one request: an audio-only recording never asks for the camera. Runs
     * onResult after the answer, or at once when all are granted. A request already on screen is
     * joined: onResult then runs after that answer, and should check what it needs itself. Never
     * asks for Drive.
     */
    public void requestRecordingPermissions(boolean audioOnly, @Nullable Runnable onResult) {
        if (permissionsInFlight) {
            if (onResult != null) {
                pendingPermissions.add(onResult);
            }
            return;
        }
        List<String> missing = new ArrayList<>(Arrays.asList(recordingPermissions(audioOnly)));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        missing.removeIf(p -> granted(context, p));
        if (missing.isEmpty()) {
            Log.i(TAG, "All recording permissions already granted");
            allGranted = true;
            if (onResult != null) {
                onResult.run();
            }
            return;
        }
        permissionsInFlight = true;
        if (onResult != null) {
            pendingPermissions.add(onResult);
        }
        Log.i(TAG, "Requesting runtime permissions count=" + missing.size());
        try {
            requestPermissionsLauncher.launch(missing.toArray(new String[0]));
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not launch the permission request", e);
            allGranted = false;
            notifyPermissionsResult();
        }
    }
}
