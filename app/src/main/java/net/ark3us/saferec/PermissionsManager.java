package net.ark3us.saferec;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
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
import java.util.Map;

public class PermissionsManager {
    public interface Callback {
        void onFinish(boolean allGranted, @Nullable String accessToken);
    }

    private static final String TAG = PermissionsManager.class.getSimpleName();
    private final AppCompatActivity context;
    private ActivityResultLauncher<String[]> requestPermissionsLauncher;
    private ActivityResultLauncher<IntentSenderRequest> startAuthIntent;
    private Callback callback;
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
                    requestDriveAuthorization();
                });

        startAuthIntent = context.registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                activityResult -> {
                    try {
                        AuthorizationResult result =
                                Identity.getAuthorizationClient(context)
                                        .getAuthorizationResultFromIntent(activityResult.getData());
                        String accessToken = result.getAccessToken();
                        Log.i(TAG, "Authorization succeeded, access token: " + accessToken);
                        callback.onFinish(allGranted, accessToken);
                    } catch (ApiException e) {
                        Log.e(TAG, "Authorization failed", e);
                        callback.onFinish(false, null);
                    }
                });
    }

    private void requestDriveAuthorization() {
        AuthorizationRequest req = AuthorizationRequest.builder()
                .setRequestedScopes(Collections.singletonList(new Scope(DriveScopes.DRIVE_FILE)))
                .build();

        Log.i(TAG, "Requesting authorization");

        Identity.getAuthorizationClient(context)
                .authorize(req)
                .addOnSuccessListener(result -> {
                    Log.i(TAG, "Authorization result: " + result);
                    if (result.hasResolution() && result.getPendingIntent() != null) {
                        startAuthIntent.launch(
                                new IntentSenderRequest.Builder(result.getPendingIntent().getIntentSender()).build()
                        );
                    } else {
                        // Already granted
                        String accessToken = result.getAccessToken();
                        Log.i(TAG, "Authorization succeeded, access token: " + accessToken);
                        callback.onFinish(allGranted, accessToken);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to authorize", e);
                    callback.onFinish(false, null);
                });
    }

    public void requestAllPermissions(Callback callback) {
        try {
            this.callback = callback;
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
            String[] requested = pi.requestedPermissions;
            if (requested == null || requested.length == 0) {
                requestDriveAuthorization();
                return;
            }
            requestPermissionsLauncher.launch(requested);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Failed to get package info: ", e);
            callback.onFinish(false, null);
        }
    }
}
