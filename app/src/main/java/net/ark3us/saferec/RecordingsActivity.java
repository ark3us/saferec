package net.ark3us.saferec;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import net.ark3us.saferec.data.ServiceState;
import net.ark3us.saferec.misc.Settings;
import net.ark3us.saferec.net.DriveConnection;
import net.ark3us.saferec.net.DriveRecordings;
import net.ark3us.saferec.net.EvidencePackage;
import net.ark3us.saferec.net.GoogleDriveFileUploader;
import net.ark3us.saferec.net.SessionSummary;
import net.ark3us.saferec.services.SafeCount;
import net.ark3us.saferec.services.SafeRecService;
import net.ark3us.saferec.ui.DriveTokenRefresh;
import net.ark3us.saferec.ui.SessionsAdapter;
import net.ark3us.saferec.ui.ThumbnailLoader;
import net.ark3us.saferec.ui.TrashedSessions;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The recordings on Drive, one card per session. It never closes by itself: with no token, a
 * failure or nothing to show, it says so and offers the way forward.
 */
public class RecordingsActivity extends AppCompatActivity {

    private static final String TAG = RecordingsActivity.class.getSimpleName();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private SessionsAdapter adapter;
    private ThumbnailLoader thumbnails;
    private PermissionsManager permissionsManager;
    private DriveTokenRefresh tokenRefresh;
    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private View progress;
    private View emptyState;
    private TextView emptyStateText;
    private MaterialButton emptyStateAction;
    private View waitingBanner;
    private TextView waitingBannerText;
    private MaterialButton waitingBannerAction;
    private int pendingPieces;
    // One per screen: it owns a thread, so a load never makes its own; a new token replaces its old one
    private DriveRecordings drive;
    private String driveToken;
    private boolean connecting;
    // Each load takes a number; only the latest may draw, so a slow older answer never wins
    private int loadGeneration;
    // onCreate already loaded: the resume that follows must not load again
    private boolean loadedInCreate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_recordings);
        // Day-old shares, and what an export or a merge cut short left behind
        backgroundExecutor.execute(() -> {
            for (String dir : new String[]{"merged_sharing", "merging"}) {
                EvidencePackage.deleteOld(new File(getCacheDir(), dir), 24 * 60 * 60 * 1000L, System.currentTimeMillis());
            }
        });
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.recordings_root), (v, insets) -> {
            // The cutout too, as on the viewfinder: in landscape it sits on a side
            Insets systemBars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        permissionsManager = new PermissionsManager(this);
        tokenRefresh = new DriveTokenRefresh(this, permissionsManager);
        thumbnails = new ThumbnailLoader(this);
        recyclerView = findViewById(R.id.recycler_view);
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        progress = findViewById(R.id.progress_bar);
        emptyState = findViewById(R.id.empty_state);
        emptyStateText = findViewById(R.id.empty_state_text);
        emptyStateAction = findViewById(R.id.empty_state_action);
        waitingBanner = findViewById(R.id.waiting_banner);
        waitingBannerText = findViewById(R.id.waiting_banner_text);
        waitingBannerAction = findViewById(R.id.waiting_banner_action);

        adapter = new SessionsAdapter(thumbnails, this::openSession);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        swipeRefreshLayout.setOnRefreshListener(this::loadSessions);
        swipeRefreshLayout.setColorSchemeResources(R.color.on_glass);
        swipeRefreshLayout.setProgressBackgroundColorSchemeResource(R.color.surface_card);

        pendingPieces = countPendingPieces();
        ServiceState.getInstance().getPendingPieces().observe(this, count -> {
            pendingPieces = count != null ? count : 0;
            renderBanner();
        });
        renderBanner();

        loadSessions();
        loadedInCreate = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (loadedInCreate) {
            loadedInCreate = false;
            return;
        }
        // A trash made on the session screen must show
        pendingPieces = countPendingPieces();
        renderBanner();
        loadSessions();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        thumbnails.shutdown();
        if (drive != null) {
            drive.shutdown();
        }
        tokenRefresh.shutdown();
        backgroundExecutor.shutdown();
    }

    // ---- Loading ----

    private void loadSessions() {
        loadSessions(false);
    }

    /** @param afterRefresh the token was just refreshed for this load: a second 401 is not refreshed again */
    private void loadSessions(boolean afterRefresh) {
        int generation = ++loadGeneration;
        String token = Settings.getAccessToken(this);
        renderBanner();
        if (token == null || token.isEmpty()) {
            adapter.submitList(null);
            showMessage(R.string.recordings_not_connected, R.string.status_connect_drive);
            return;
        }
        if (adapter.getItemCount() == 0) {
            emptyState.setVisibility(View.GONE);
            progress.setVisibility(View.VISIBLE);
        }
        if (drive == null) {
            drive = new DriveRecordings(this, token);
        } else if (!token.equals(driveToken)) {
            drive.setAccessToken(token);
        }
        driveToken = token;
        drive.listSessions(new DriveRecordings.Callback<List<SessionSummary>>() {
            @Override
            public void onSuccess(List<SessionSummary> sessions) {
                mainHandler.post(() -> {
                    if (isDestroyed() || generation != loadGeneration) {
                        return;
                    }
                    swipeRefreshLayout.setRefreshing(false);
                    progress.setVisibility(View.GONE);
                    adapter.setAccessToken(token);
                    // A session just trashed may still be in Drive's answer
                    long now = System.currentTimeMillis();
                    List<SessionSummary> shown = new java.util.ArrayList<>();
                    for (SessionSummary s : sessions) {
                        if (!TrashedSessions.isHidden(s.sessionId, s.dataType, now)) {
                            shown.add(s);
                        }
                    }
                    // A newer session lands above the anchored first card: stay on top when we were
                    LinearLayoutManager layout = (LinearLayoutManager) recyclerView.getLayoutManager();
                    boolean atTop = adapter.getItemCount() == 0
                            || (layout != null && layout.findFirstVisibleItemPosition() <= 0);
                    adapter.submitList(shown, atTop ? () -> recyclerView.scrollToPosition(0) : null);
                    if (shown.isEmpty()) {
                        showMessage(R.string.recordings_empty, 0);
                    } else {
                        emptyState.setVisibility(View.GONE);
                    }
                });
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Could not list the sessions", e);
                mainHandler.post(() -> {
                    if (isDestroyed() || generation != loadGeneration) {
                        return;
                    }
                    if (!afterRefresh && DriveTokenRefresh.isUnauthorized(e)) {
                        refreshToken(token, generation);
                        return;
                    }
                    showLoadFailed();
                });
            }
        });
    }

    private void showLoadFailed() {
        adapter.submitList(null);
        // Pull to refresh stays; the button is for whoever cannot pull (a screen reader)
        showMessage(R.string.recordings_load_failed, R.string.retry_short);
    }

    /**
     * Drive refused the token: one silent refresh for the screen, then the list once more with the
     * new token (DriveConnection stored it). Play services wanting the user: Connect Drive. A
     * newer load meanwhile has its own answer.
     */
    private void refreshToken(String used, int generation) {
        tokenRefresh.refresh(used, new DriveTokenRefresh.Listener() {
            @Override
            public void onToken(String token) {
                if (generation == loadGeneration) {
                    loadSessions(true);
                }
            }

            @Override
            public void onNeedsUser() {
                if (generation == loadGeneration) {
                    adapter.submitList(null);
                    showMessage(R.string.recordings_not_connected, R.string.status_connect_drive);
                }
            }

            @Override
            public void onFailed() {
                if (generation == loadGeneration) {
                    showLoadFailed();
                }
            }
        });
    }

    /** A message in place of the list, with its way forward: Connect Drive, Retry, or none (0). */
    private void showMessage(@StringRes int text, @StringRes int action) {
        swipeRefreshLayout.setRefreshing(false);
        progress.setVisibility(View.GONE);
        emptyStateText.setText(text);
        if (action == 0) {
            emptyStateAction.setVisibility(View.GONE);
        } else {
            emptyStateAction.setText(action);
            emptyStateAction.setOnClickListener(v -> {
                if (action == R.string.status_connect_drive) {
                    connectDrive();
                } else {
                    loadSessions();
                }
            });
            emptyStateAction.setVisibility(View.VISIBLE);
        }
        emptyState.setVisibility(View.VISIBLE);
    }

    // ---- Waiting banner ----

    private boolean hasToken() {
        String token = Settings.getAccessToken(this);
        return token != null && !token.isEmpty();
    }

    /** The same count as the service's: chunks only, not their timestamps or the manifest copy. */
    private int countPendingPieces() {
        return SafeCount.pieces(GoogleDriveFileUploader.outboxDir(this).list());
    }

    private void renderBanner() {
        if (pendingPieces <= 0) {
            waitingBanner.setVisibility(View.GONE);
            return;
        }
        waitingBannerText.setText(getResources().getQuantityString(R.plurals.status_waiting,
                pendingPieces, pendingPieces));
        if (hasToken()) {
            waitingBannerAction.setText(R.string.check_upload_now);
            waitingBannerAction.setOnClickListener(v -> uploadPending());
        } else {
            waitingBannerAction.setText(R.string.status_connect_drive);
            waitingBannerAction.setOnClickListener(v -> connectDrive());
        }
        waitingBanner.setVisibility(View.VISIBLE);
    }

    /** Not a recording: a plain startService(), the activity is in front. */
    private void uploadPending() {
        try {
            startService(SafeRecService.createCommandIntent(this, SafeRecService.CMD_UPLOAD_PENDING));
        } catch (IllegalStateException e) {
            Log.w(TAG, "Cannot reach the service from the background", e);
        }
    }

    // ---- Drive ----

    /** Asks Play services for a token (with its consent screen if needed), checks it with Drive, then reloads. */
    private void connectDrive() {
        if (connecting) {
            return;
        }
        connecting = true;
        permissionsManager.connectDrive(true, (token, needsUser) -> {
            if (token == null) {
                connecting = false;
                Log.i(TAG, "Drive not connected, needsUser=" + needsUser);
                return;
            }
            try {
                backgroundExecutor.execute(() -> {
                    DriveConnection.Result result = DriveConnection.accept(getApplicationContext(), token, true);
                    Log.i(TAG, "Drive connection: " + result);
                    mainHandler.post(() -> {
                        connecting = false;
                        if (isDestroyed()) {
                            return;
                        }
                        if (result == DriveConnection.Result.VALID) {
                            uploadPending();
                        }
                        renderBanner();
                        loadSessions();
                    });
                });
            } catch (RejectedExecutionException e) {
                Log.w(TAG, "Screen gone, Drive check dropped", e);
            }
        });
    }

    // ---- Cards ----

    /** The one place a card tap goes through. */
    private void openSession(SessionSummary session) {
        SessionActivity.start(this, session.sessionId, session.dataType);
    }
}
