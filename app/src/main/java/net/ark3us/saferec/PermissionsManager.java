package net.ark3us.saferec;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
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
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

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
                    Log.i(TAG, "Permission request completed. allGranted=" + allGranted);
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
                        Log.i(TAG, "Authorization succeeded; access token received=" + (accessToken != null));
                        if (callback != null) {
                            callback.onFinish(allGranted, accessToken);
                        }
                    } catch (ApiException e) {
                        Log.e(TAG, "Authorization failed", e);
                        if (callback != null) {
                            callback.onFinish(false, null);
                        }
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
                        Log.i(TAG, "Authorization requires user resolution");
                        startAuthIntent.launch(
                                new IntentSenderRequest.Builder(result.getPendingIntent().getIntentSender()).build()
                        );
                    } else {
                        // Already granted
                        String accessToken = result.getAccessToken();
                        Log.i(TAG, "Authorization succeeded without resolution; token received=" + (accessToken != null));
                        if (callback != null) {
                            callback.onFinish(allGranted, accessToken);
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to authorize", e);
                    if (callback != null) {
                        callback.onFinish(false, null);
                    }
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
            List<String> runtimePermissions = new ArrayList<>();
            for (String permission : requested) {
                try {
                    PermissionInfo permissionInfo = pm.getPermissionInfo(permission, 0);
                    int baseProtection = permissionInfo.getProtection() & PermissionInfo.PROTECTION_MASK_BASE;
                    if (baseProtection == PermissionInfo.PROTECTION_DANGEROUS) {
                        runtimePermissions.add(permission);
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    Log.w(TAG, "Permission not found in package manager: " + permission, e);
                }
            }

            if (runtimePermissions.isEmpty()) {
                Log.i(TAG, "No runtime permissions to request");
                requestDriveAuthorization();
                return;
            }
            Log.i(TAG, "Requesting runtime permissions count=" + runtimePermissions.size());
            requestPermissionsLauncher.launch(runtimePermissions.toArray(new String[0]));
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Failed to get package info: ", e);
            if (callback != null) {
                callback.onFinish(false, null);
            }
        }
    }
}
