package net.ark3us.saferec.ui;

import android.content.Context;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import net.ark3us.saferec.PermissionsManager;
import net.ark3us.saferec.data.ServiceState;
import net.ark3us.saferec.misc.Settings;
import net.ark3us.saferec.net.DriveConnection;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Connects Google Drive from a screen without ever blocking it: a silent check with no UI, and
 * the consent screen when the user asks. Main thread; the check with Drive runs on a worker.
 */
public final class DriveConnector {
    public interface Listener {
        /** Drive accepted the token: what waits on the phone can go now. */
        void onDriveReady();

        /** The user asked to connect, and it did not work. */
        void onConnectFailed();

        /** The stored token may have changed. */
        void onDriveChanged();
    }

    private static final String TAG = DriveConnector.class.getSimpleName();

    private final AppCompatActivity activity;
    private final PermissionsManager permissions;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean connecting = false;
    private boolean checking = false;

    public DriveConnector(AppCompatActivity activity, PermissionsManager permissions, Listener listener) {
        this.activity = activity;
        this.permissions = permissions;
        this.listener = listener;
    }

    /**
     * Asks Play services for a fresh token without any UI, only with a token stored and a
     * validated network: offline, Play services ask for the user too, and the app would say
     * "Reconnect Drive" for nothing.
     */
    public void silentCheck(boolean online) {
        String stored = Settings.getAccessToken(activity);
        if (!online || stored == null || stored.isEmpty() || checking || connecting) {
            return;
        }
        checking = true;
        permissions.connectDrive(false, (token, needsUser) -> {
            checking = false;
            if (needsUser) {
                Log.i(TAG, "Drive needs the user again");
                ServiceState.getInstance().setDriveAuthProblem(true);
            } else if (token != null) {
                accept(token, false);
            }
        });
    }

    /** The consent screen if Play services need it, then a check with Drive. */
    public void connect() {
        if (connecting) {
            return;
        }
        connecting = true;
        permissions.connectDrive(true, (token, needsUser) -> {
            if (token != null) {
                accept(token, true);
                return;
            }
            connecting = false;
            Log.i(TAG, "Drive not connected, needsUser=" + needsUser);
            if (alive()) {
                listener.onConnectFailed();
            }
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private void accept(String token, boolean asked) {
        Context app = activity.getApplicationContext();
        try {
            executor.execute(() -> {
                DriveConnection.Result result = DriveConnection.accept(app, token, asked);
                Log.i(TAG, (asked ? "Drive connection: " : "Silent Drive check: ") + result);
                activity.runOnUiThread(() -> {
                    if (asked) {
                        connecting = false;
                    }
                    if (!alive()) {
                        return;
                    }
                    if (result == DriveConnection.Result.VALID) {
                        listener.onDriveReady();
                    } else if (asked && result == DriveConnection.Result.INVALID) {
                        listener.onConnectFailed();
                    }
                    listener.onDriveChanged();
                });
            });
        } catch (RejectedExecutionException e) {
            // A callback after shutdown(): the screen is gone
            Log.w(TAG, "Screen gone, Drive check dropped", e);
        }
    }

    private boolean alive() {
        return !activity.isFinishing() && !activity.isDestroyed();
    }
}
