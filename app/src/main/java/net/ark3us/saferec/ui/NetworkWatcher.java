package net.ark3us.saferec.ui;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

/**
 * Follows whether the default network is validated, between start() and stop(). Main thread:
 * the listener hears only real changes, never the state already read by start().
 */
public final class NetworkWatcher {
    public interface Listener {
        void onOnlineChanged(boolean online);
    }

    private static final String TAG = NetworkWatcher.class.getSimpleName();

    private final ConnectivityManager connectivity;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean online;
    private boolean watching;

    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
            update(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        }

        @Override
        public void onLost(@NonNull Network network) {
            update(false);
        }
    };

    public NetworkWatcher(Context context, Listener listener) {
        this.connectivity = context.getSystemService(ConnectivityManager.class);
        this.listener = listener;
    }

    /** Reads the state at once, then follows it. */
    public void start() {
        if (watching) return;
        online = validatedNow();
        try {
            connectivity.registerDefaultNetworkCallback(callback, main);
            watching = true;
        } catch (RuntimeException e) {
            // Too many callbacks in the process, or no permission: the state read above stays
            Log.w(TAG, "Cannot follow the network", e);
        }
    }

    public void stop() {
        if (!watching) return;
        watching = false;
        try {
            connectivity.unregisterNetworkCallback(callback);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Network callback already gone", e);
        }
    }

    /** True when the default network is validated: it can reach the internet. */
    public boolean isOnline() {
        return online;
    }

    private boolean validatedNow() {
        Network network = connectivity.getActiveNetwork();
        NetworkCapabilities capabilities = network != null ? connectivity.getNetworkCapabilities(network) : null;
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void update(boolean now) {
        // A callback queued before stop() is dropped
        if (!watching || online == now) return;
        online = now;
        listener.onOnlineChanged(now);
    }
}
