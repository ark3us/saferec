package net.ark3us.saferec.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.HttpDataSource;

import com.google.api.client.http.HttpResponseException;

import net.ark3us.saferec.PermissionsManager;
import net.ark3us.saferec.misc.Settings;
import net.ark3us.saferec.net.DriveConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * What a screen does when Drive answers 401 (only a 401: a network error is not an auth error).
 * It asks Play services for a new token without any UI, at most once per screen (one instance
 * per screen), and hands it over only if Drive accepts it; the caller then gives it to its Drive
 * clients and retries the failed operation once. Main thread; the check with Drive runs on its
 * own thread, so it never waits behind an export.
 */
public final class DriveTokenRefresh {
    public interface Listener {
        /** A token Drive accepts: give it to the screen's Drive clients and retry once. */
        void onToken(String token);

        /** Play services want the user (consent or sign-in): show Connect Drive. */
        void onNeedsUser();

        /** No new token: this screen already asked once, or Play services or Drive could not answer. */
        void onFailed();
    }

    private static final String TAG = DriveTokenRefresh.class.getSimpleName();

    private final AppCompatActivity activity;
    private final PermissionsManager permissions;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    // Callers waiting for the one request in flight
    private final List<Listener> waiting = new ArrayList<>();
    private boolean tried;

    public DriveTokenRefresh(AppCompatActivity activity, PermissionsManager permissions) {
        this.activity = activity;
        this.permissions = permissions;
    }

    /** Whether Drive refused the token: HTTP 401 from the Drive client or from the player. */
    public static boolean isUnauthorized(@Nullable Throwable error) {
        return httpStatus(error) == 401;
    }

    /** The first HTTP status in the cause chain, from the Drive client or the player; 0 when none. */
    @OptIn(markerClass = UnstableApi.class)
    public static int httpStatus(@Nullable Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            // GoogleJsonResponseException, Drive's error reply, is one of these
            if (t instanceof HttpResponseException) {
                return ((HttpResponseException) t).getStatusCode();
            }
            if (t instanceof HttpDataSource.InvalidResponseCodeException) {
                return ((HttpDataSource.InvalidResponseCodeException) t).responseCode;
            }
        }
        return 0;
    }

    /**
     * Drive refused {@code usedToken}. A different token stored meanwhile (another operation's
     * refresh, or the user connected again) is handed over without asking Play services. A
     * request already in flight is joined.
     */
    public void refresh(@Nullable String usedToken, Listener listener) {
        String stored = Settings.getAccessToken(activity);
        if (stored != null && !stored.isEmpty() && !stored.equals(usedToken)) {
            listener.onToken(stored);
            return;
        }
        if (!waiting.isEmpty()) {
            waiting.add(listener);
            return;
        }
        if (tried) {
            Log.i(TAG, "Drive refused the token again: this screen already asked for a new one");
            listener.onFailed();
            return;
        }
        tried = true;
        waiting.add(listener);
        Context app = activity.getApplicationContext();
        permissions.connectDrive(false, (token, needsUser) -> {
            if (token == null) {
                finish(null, needsUser);
                return;
            }
            try {
                executor.execute(() -> {
                    DriveConnection.Result result = DriveConnection.accept(app, token);
                    Log.i(TAG, "New token from Play services: " + result);
                    mainHandler.post(() -> finish(result == DriveConnection.Result.VALID ? token : null, false));
                });
            } catch (RejectedExecutionException e) {
                Log.w(TAG, "Screen gone, token check dropped", e);
                waiting.clear();
            }
        });
    }

    private void finish(@Nullable String token, boolean needsUser) {
        List<Listener> listeners = new ArrayList<>(waiting);
        waiting.clear();
        if (activity.isDestroyed()) {
            return;
        }
        for (Listener listener : listeners) {
            if (token != null) {
                listener.onToken(token);
            } else if (needsUser) {
                listener.onNeedsUser();
            } else {
                listener.onFailed();
            }
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
