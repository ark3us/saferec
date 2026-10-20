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
import net.ark3us.saferec.ui.TrashedSessions;
import net.ark3us.saferec.services.SafeCount;
import net.ark3us.saferec.services.SafeRecService;
import net.ark3us.saferec.ui.SessionsAdapter;
import net.ark3us.saferec.ui.ThumbnailLoader;

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
    // One per token: it owns a thread, so a load never makes its own
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
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        permissionsManager = new PermissionsManager(this);
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
        emptyStateAction.setOnClickListener(v -> connectDrive());

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
        backgroundExecutor.shutdown();
    }

    // ---- Loading ----

    private void loadSessions() {
        int generation = ++loadGeneration;
        String token = Settings.getAccessToken(this);
        renderBanner();
        if (token == null || token.isEmpty()) {
            adapter.submitList(null);
            showMessage(R.string.recordings_not_connected, true);
            return;
        }
        if (adapter.getItemCount() == 0) {
            emptyState.setVisibility(View.GONE);
            progress.setVisibility(View.VISIBLE);
        }
        if (drive == null || !token.equals(driveToken)) {
            if (drive != null) {
                drive.shutdown();
            }
            drive = new DriveRecordings(this, token);
            driveToken = token;
        }
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
                    adapter.submitList(shown);
                    if (shown.isEmpty()) {
                        showMessage(R.string.recordings_empty, false);
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
                    swipeRefreshLayout.setRefreshing(false);
                    progress.setVisibility(View.GONE);
                    adapter.submitList(null);
                    showMessage(R.string.recordings_load_failed, false);
                });
            }
        });
    }

    private void showMessage(@StringRes int text, boolean offerConnect) {
        swipeRefreshLayout.setRefreshing(false);
        progress.setVisibility(View.GONE);
        emptyStateText.setText(text);
        emptyStateAction.setVisibility(offerConnect ? View.VISIBLE : View.GONE);
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
