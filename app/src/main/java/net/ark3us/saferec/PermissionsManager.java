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
import java.util.List;
import java.util.Map;

public class PermissionsManager {
    /** Old combined result, kept until MainActivity stops using requestAllPermissions. */
    public interface Callback {
        void onFinish(boolean allGranted, @Nullable String accessToken);
    }

    public interface DriveCallback {
        /**
         * @param accessToken the token, or null when there is none
         * @param needsUser   true when Play services want the user (consent or sign-in)
         */
        void onResult(@Nullable String accessToken, boolean needsUser);
    }

    private static final String TAG = PermissionsManager.class.getSimpleName();
    private final AppCompatActivity context;
    private ActivityResultLauncher<String[]> requestPermissionsLauncher;
    private ActivityResultLauncher<IntentSenderRequest> startAuthIntent;
    @Nullable
    private DriveCallback driveCallback;
    // Runs once the runtime permissions are settled
    @Nullable
    private Runnable onPermissionsResult;
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
                        finishDrive(accessToken, false);
                    } catch (ApiException e) {
                        Log.e(TAG, "Authorization failed", e);
                        finishDrive(null, false);
                    }
                });
    }

    private void finishDrive(@Nullable String accessToken, boolean needsUser) {
        DriveCallback cb = driveCallback;
        driveCallback = null;
        if (cb != null) {
            cb.onResult(accessToken, needsUser);
        }
    }

    /**
     * Asks Play services for a Drive token. Never touches runtime permissions.
     *
     * @param interactive false never launches a UI: it reports needsUser=true when Play
     *                    services want a resolution. true launches it.
     */
    public void connectDrive(boolean interactive, DriveCallback callback) {
        this.driveCallback = callback;
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
                            Log.i(TAG, "Authorization requires user resolution");
                            startAuthIntent.launch(
                                    new IntentSenderRequest.Builder(result.getPendingIntent().getIntentSender()).build()
                            );
                        } else {
                            Log.i(TAG, "Authorization needs the user; not launching");
                            finishDrive(null, true);
                        }
                    } else {
                        String accessToken = result.getAccessToken();
                        Log.i(TAG, "Authorization succeeded without resolution; token received=" + (accessToken != null));
                        finishDrive(accessToken, false);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to authorize", e);
                    finishDrive(null, false);
                });
    }

    private void notifyPermissionsResult() {
        Runnable r = onPermissionsResult;
        onPermissionsResult = null;
        if (r != null) {
            r.run();
        }
    }

    /**
     * Camera is required even in audio-only mode: the service is declared as
     * camera|microphone, and on Android 14+ startForeground throws without both.
     */
    public static boolean hasRecordingPermissions(Context context) {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Asks for camera, microphone and, on API 33+, notifications. Runs onResult after the
     * answer, or at once when all are granted. Never asks for Drive.
     */
    public void requestRecordingPermissions(@Nullable Runnable onResult) {
        List<String> missing = new ArrayList<>();
        missing.add(Manifest.permission.CAMERA);
        missing.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        missing.removeIf(p -> context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED);
        if (missing.isEmpty()) {
            Log.i(TAG, "All recording permissions already granted");
            allGranted = true;
            if (onResult != null) {
                onResult.run();
            }
            return;
        }
        this.onPermissionsResult = onResult;
        Log.i(TAG, "Requesting runtime permissions count=" + missing.size());
        requestPermissionsLauncher.launch(missing.toArray(new String[0]));
    }

    /**
     * Temporary wrapper until the UI is rewired: recording permissions, then Drive
     * authorization. onPermissionsResult runs in between, so callers act without waiting for Drive.
     */
    public void requestAllPermissions(@Nullable Runnable onPermissionsResult, Callback callback) {
        requestRecordingPermissions(() -> {
            if (onPermissionsResult != null) {
                onPermissionsResult.run();
            }
            connectDrive(true, (token, needsUser) -> callback.onFinish(allGranted, token));
        });
    }
}
