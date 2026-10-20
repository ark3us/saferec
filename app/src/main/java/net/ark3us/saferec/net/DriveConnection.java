package net.ark3us.saferec.net;

import android.content.Context;
import android.util.Log;

import net.ark3us.saferec.data.ServiceState;
import net.ark3us.saferec.misc.Settings;

/** Decides what to do with a token Play services just gave. */
public final class DriveConnection {
    private static final String TAG = DriveConnection.class.getSimpleName();

    public enum Result { VALID, UNKNOWN, INVALID }

    private DriveConnection() {
    }

    /**
     * Checks the token against Drive and stores it unless Drive rejected it.
     * Network call: worker thread only. UNKNOWN (network or Drive trouble) keeps the token,
     * since a network error is not an auth error.
     */
    public static Result accept(Context context, String token) {
        GoogleDriveClient client = new GoogleDriveClient(token);
        GoogleDriveClient.AuthStatus status = client.checkAuthentication();
        switch (status) {
            case VALID:
            case UNKNOWN:
                Settings.setAccessToken(context, token);
                ServiceState.getInstance().setDriveAuthProblem(false);
                if (status == GoogleDriveClient.AuthStatus.VALID) {
                    try {
                        Settings.setDriveAccount(context, client.accountEmail());
                    } catch (Exception e) {
                        Log.w(TAG, "Could not read the Drive account", e);
                    }
                    return Result.VALID;
                }
                return Result.UNKNOWN;
            default:
                ServiceState.getInstance().setDriveAuthProblem(true);
                return Result.INVALID;
        }
    }
}
