package net.ark3us.saferec;

import android.net.Uri;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.widget.PopupMenu;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec;
import com.google.android.material.progressindicator.IndeterminateDrawable;
import net.ark3us.saferec.media.MediaMerger;
import net.ark3us.saferec.net.SessionManifest;
import net.ark3us.saferec.services.SafeRecService;
import java.io.File;
import java.util.Date;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import com.google.android.material.appbar.MaterialToolbar;

import net.ark3us.saferec.misc.Settings;
import net.ark3us.saferec.net.DriveConnection;
import net.ark3us.saferec.net.DriveRecordings;
import net.ark3us.saferec.net.SessionSummary;
import net.ark3us.saferec.ui.DriveTokenRefresh;
import net.ark3us.saferec.ui.SessionPlaylist;
import net.ark3us.saferec.ui.SessionsAdapter;
import net.ark3us.saferec.ui.TrashedSessions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * One session: its pieces played as one video, whether it is whole, and what can be done with
 * it (export, share, trash, technical details). It loads that one session by id, so no list
 * travels with the intent. It never finishes by itself: a session Drive no longer has says so.
 */
@OptIn(markerClass = UnstableApi.class)
public class SessionActivity extends AppCompatActivity {

    private static final String TAG = SessionActivity.class.getSimpleName();
    private static final String EXTRA_SESSION_ID = "sessionId";
    private static final String EXTRA_DATA_TYPE = "dataType";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private PermissionsManager permissionsManager;
    // One per screen: it owns a thread
    private DriveRecordings drive;
    // The token of the screen, for Drive and the player; a refresh replaces it. Player threads read it
    private volatile String token;
    // The token the player's last request carried: the one Drive refused when it answers 401
    private volatile String streamedWith;
    private DriveTokenRefresh tokenRefresh;
    @Nullable
    private SessionSummary session;
    private List<String> fileIds = new ArrayList<>();
    // Each piece's length until the player has read it, in the order of fileIds
    private List<Long> placeholders = new ArrayList<>();

    private ExoPlayer player;
    private PlayerView playerView;
    private View playerHolder;
    private View toolbar;
    private View body;
    private View errorBar;
    private boolean started;
    // Where the player was in the session when onStop released it
    private long savedPosition;
    private boolean fullscreen;
    // A load state (not connected, failed, gone) is on screen: the actions wait for a session again
    private boolean stateShown;
    private OnBackPressedCallback leaveFullscreen;
    private View technicalBody;
    private View technicalChevron;
    private View technicalHeader;
    private MaterialButton exportButton;
    private MaterialButton shareVideoButton;
    private MaterialButton trashButton;
    // The button showing progress while an export or a share runs; null when idle
    @Nullable
    private MaterialButton busyButton;

    public static void start(Context context, String sessionId, String dataType) {
        context.startActivity(new Intent(context, SessionActivity.class)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_DATA_TYPE, dataType));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_session);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.session_root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        MaterialToolbar bar = findViewById(R.id.toolbar);
        bar.setNavigationOnClickListener(v -> finish());
        toolbar = bar;
        playerView = findViewById(R.id.player_view);
        playerHolder = findViewById(R.id.player_holder);
        body = findViewById(R.id.session_body);
        errorBar = findViewById(R.id.player_error_bar);

        permissionsManager = new PermissionsManager(this);
        tokenRefresh = new DriveTokenRefresh(this, permissionsManager);
        findViewById(R.id.player_retry).setOnClickListener(v -> retry());
        playerView.setFullscreenButtonClickListener(this::setFullscreen);
        leaveFullscreen = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                setFullscreen(false);
            }
        };
        getOnBackPressedDispatcher().addCallback(this, leaveFullscreen);

        View header = findViewById(R.id.session_technical_header);
        technicalBody = findViewById(R.id.session_technical_body);
        technicalChevron = findViewById(R.id.session_technical_chevron);
        technicalHeader = header;
        header.setOnClickListener(v -> setTechnicalExpanded(technicalBody.getVisibility() != View.VISIBLE));
        ViewCompat.setStateDescription(header, getString(R.string.state_collapsed));
        // Screen readers offer the opposite action; the state is its state description
        ViewCompat.setAccessibilityDelegate(header, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                boolean open = technicalBody.getVisibility() == View.VISIBLE;
                info.addAction(open ? AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_COLLAPSE
                        : AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_EXPAND);
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == AccessibilityNodeInfoCompat.ACTION_EXPAND) {
                    setTechnicalExpanded(true);
                    return true;
                }
                if (action == AccessibilityNodeInfoCompat.ACTION_COLLAPSE) {
                    setTechnicalExpanded(false);
                    return true;
                }
                return super.performAccessibilityAction(host, action, args);
            }
        });

        exportButton = findViewById(R.id.export_evidence);
        shareVideoButton = findViewById(R.id.session_share_video);
        trashButton = findViewById(R.id.session_trash);
        exportButton.setOnClickListener(v -> runExport());
        shareVideoButton.setOnClickListener(v -> shareVideo());
        trashButton.setOnClickListener(v -> confirmTrash());

        loadSession();
    }

    // ---- Loading ----

    private void loadSession() {
        loadSession(false);
    }

    /** @param afterRefresh the token was just refreshed for this load: a second 401 is not refreshed again */
    private void loadSession(boolean afterRefresh) {
        String sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        String dataType = getIntent().getStringExtra(EXTRA_DATA_TYPE);
        String stored = Settings.getAccessToken(this);
        try {
            ((MaterialToolbar) toolbar).setTitle(
                    SessionsAdapter.formatTitle(this, Long.parseLong(sessionId)));
        } catch (NumberFormatException | NullPointerException e) {
            // The title comes with the session
        }
        if (sessionId == null || dataType == null) {
            showState(R.string.session_not_found, 0);
            return;
        }
        if (stored == null || stored.isEmpty()) {
            showState(R.string.recordings_not_connected, R.string.status_connect_drive);
            return;
        }
        hideState();
        useToken(stored);
        drive.session(sessionId, dataType, new DriveRecordings.Callback<SessionSummary>() {
            @Override
            public void onSuccess(SessionSummary result) {
                mainHandler.post(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    if (result == null) {
                        showState(R.string.session_not_found, 0);
                    } else {
                        bind(result);
                    }
                });
            }

            @Override
            public void onError(Exception e) {
                Log.w(TAG, "Session load failed", e);
                mainHandler.post(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    Runnable failed = () -> showState(R.string.session_load_failed, R.string.retry_short);
                    if (!afterRefresh && DriveTokenRefresh.isUnauthorized(e)) {
                        afterUnauthorized(stored, () -> loadSession(true), failed);
                    } else {
                        failed.run();
                    }
                });
            }
        });
    }

    /** The token for Drive and the player from now on: the next request of each carries it. */
    private void useToken(String newToken) {
        token = newToken;
        if (drive == null) {
            drive = new DriveRecordings(this, newToken);
        } else {
            drive.setAccessToken(newToken);
        }
    }

    /**
     * Drive refused the token: one silent refresh for the whole screen, then the operation once
     * more with the new token. When Play services want the user, the screen offers Connect Drive;
     * otherwise {@code failed} says what did not work.
     */
    private void afterUnauthorized(@Nullable String used, Runnable retry, Runnable failed) {
        tokenRefresh.refresh(used, new DriveTokenRefresh.Listener() {
            @Override
            public void onToken(String newToken) {
                useToken(newToken);
                retry.run();
            }

            @Override
            public void onNeedsUser() {
                endBusy();
                showState(R.string.recordings_not_connected, R.string.status_connect_drive);
            }

            @Override
            public void onFailed() {
                failed.run();
            }
        });
    }

    /**
     * The session cannot be shown: say why, and offer the way forward when there is one
     * (connect Drive when there is no token, retry after an error).
     */
    private void showState(int message, int action) {
        stateShown = true;
        // Nothing plays hidden, and the message is in the body, which fullscreen hides
        setFullscreen(false);
        if (player != null) {
            player.pause();
        }
        updateActions();
        playerHolder.setVisibility(View.GONE);
        findViewById(R.id.session_status).setVisibility(View.GONE);
        ((TextView) findViewById(R.id.session_explain)).setText(message);
        Button button = findViewById(R.id.session_state_action);
        if (action == 0) {
            button.setVisibility(View.GONE);
            return;
        }
        button.setText(action);
        button.setEnabled(true);
        button.setVisibility(View.VISIBLE);
        button.setOnClickListener(v -> {
            if (action == R.string.status_connect_drive) {
                connectDrive(button);
            } else {
                loadSession();
            }
        });
    }

    private void hideState() {
        stateShown = false;
        findViewById(R.id.session_state_action).setVisibility(View.GONE);
    }

    /** The consent screen if Play services need it, then a check with Drive; then the load. */
    private void connectDrive(Button button) {
        button.setEnabled(false);
        permissionsManager.connectDrive(true, (token, needsUser) -> {
            if (token == null) {
                button.setEnabled(true);
                return;
            }
            Context app = getApplicationContext();
            try {
                executor.execute(() -> {
                    DriveConnection.Result result = DriveConnection.accept(app, token, true);
                    mainHandler.post(() -> {
                        if (isDestroyed()) {
                            return;
                        }
                        if (result == DriveConnection.Result.VALID) {
                            loadSession();
                        } else {
                            button.setEnabled(true);
                        }
                    });
                });
            } catch (RejectedExecutionException e) {
                Log.w(TAG, "Screen gone, Drive check dropped", e);
            }
        });
    }

    private void bind(SessionSummary summary) {
        session = summary;
        ((MaterialToolbar) toolbar).setTitle(SessionsAdapter.formatTitle(this, summary.startMillis));
        TextView status = findViewById(R.id.session_status);
        status.setVisibility(View.VISIBLE);
        SessionsAdapter.bindStatus(status, summary.status);
        ((TextView) findViewById(R.id.session_explain)).setText(explain(summary));

        fileIds = SessionPlaylist.fileIds(summary);
        placeholders = SessionPlaylist.placeholderDurationsMs(summary);
        playerHolder.setVisibility(fileIds.isEmpty() ? View.GONE : View.VISIBLE);
        if (started && player == null && !fileIds.isEmpty()) {
            buildPlayer(savedPosition);
        } else if (player != null && player.getPlayerError() != null) {
            // Loaded again (Drive connected, or the network back): the player tries again too
            retry();
        }
        updateActions();
        bindTechnical(summary);
    }

    private void setTechnicalExpanded(boolean open) {
        technicalBody.setVisibility(open ? View.VISIBLE : View.GONE);
        technicalChevron.setRotation(open ? 180f : 0f);
        // The state description is what a screen reader speaks; changing it also notifies it
        ViewCompat.setStateDescription(technicalHeader,
                getString(open ? R.string.state_expanded : R.string.state_collapsed));
    }

    // ---- Actions ----

    /** The three actions work on a loaded session, one at a time. */
    private void updateActions() {
        boolean ready = session != null && busyButton == null && !stateShown;
        exportButton.setEnabled(ready && !session.files.isEmpty());
        shareVideoButton.setEnabled(ready && !fileIds.isEmpty());
        trashButton.setEnabled(ready && !session.allFileIds.isEmpty());
    }

    /** Disables the three actions and shows a spinner in the one that runs. */
    private void startBusy(MaterialButton button) {
        busyButton = button;
        CircularProgressIndicatorSpec spec = new CircularProgressIndicatorSpec(this, null, 0,
                com.google.android.material.R.style.Widget_Material3_CircularProgressIndicator_ExtraSmall);
        spec.indicatorColors = new int[]{button.getTextColors().getDefaultColor()};
        button.setIcon(IndeterminateDrawable.createCircularDrawable(this, spec));
        button.setIconTint(null);
        updateActions();
    }

    private void endBusy() {
        if (busyButton != null) {
            busyButton.setIcon(null);
            busyButton = null;
        }
        updateActions();
    }

    private void runExport() {
        if (session == null || busyButton != null) {
            return;
        }
        startBusy(exportButton);
        Toast.makeText(this, R.string.exporting_evidence, Toast.LENGTH_SHORT).show();
        export(session, false);
    }

    private void export(SessionSummary summary, boolean afterRefresh) {
        String used = token;
        new EvidenceExport(this, drive, executor).export(summary, new EvidenceExport.Listener() {
            @Override
            public void onDone() {
                if (!isDestroyed()) {
                    endBusy();
                }
            }

            @Override
            public void onUnauthorized(Exception e) {
                if (isDestroyed()) {
                    return;
                }
                Runnable failed = () -> actionFailed(R.string.download_failed, e);
                if (afterRefresh) {
                    failed.run();
                } else {
                    afterUnauthorized(used, () -> export(summary, true), failed);
                }
            }
        });
    }

    /** An action stopped: its spinner goes, and a toast says why. */
    private void actionFailed(int message, Exception e) {
        endBusy();
        Toast.makeText(this, getString(message, e.getMessage()), Toast.LENGTH_LONG).show();
    }

    /** The pieces downloaded, merged into one video and handed to the share sheet. */
    private void shareVideo() {
        if (session == null || busyButton != null) {
            return;
        }
        startBusy(shareVideoButton);
        shareVideo(session, false);
    }

    private void shareVideo(SessionSummary summary, boolean afterRefresh) {
        List<DriveRecordings.RecordingItem> chunks = new ArrayList<>();
        for (DriveRecordings.RecordingItem item : summary.files) {
            if ("mp4".equalsIgnoreCase(item.mediaFile.getExtension())) {
                chunks.add(item);
            }
        }
        if (chunks.isEmpty()) {
            endBusy();
            return;
        }
        chunks.sort((a, b) -> Integer.compare(a.mediaFile.sequenceNumber, b.mediaFile.sequenceNumber));
        String sessionId = summary.sessionId;
        String used = token;
        Toast.makeText(this, R.string.downloading_chunks, Toast.LENGTH_SHORT).show();
        drive.downloadFiles(chunks, new DriveRecordings.Callback<List<File>>() {
            @Override
            public void onSuccess(List<File> localFiles) {
                try {
                    executor.execute(() -> mergeAndShare(sessionId, localFiles));
                } catch (RejectedExecutionException e) {
                    for (File f : localFiles) {
                        f.delete();
                    }
                }
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Download for merge failed", e);
                mainHandler.post(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    Runnable failed = () -> actionFailed(R.string.download_failed, e);
                    if (!afterRefresh && DriveTokenRefresh.isUnauthorized(e)) {
                        afterUnauthorized(used, () -> shareVideo(summary, true), failed);
                    } else {
                        failed.run();
                    }
                });
            }
        });
    }

    private void mergeAndShare(String sessionId, List<File> localFiles) {
        try {
            File shareDir = new File(getCacheDir(), "merged_sharing");
            if (!shareDir.isDirectory() && !shareDir.mkdirs()) {
                throw new IllegalStateException("Failed to create share dir: " + shareDir.getAbsolutePath());
            }
            File shareFile = new File(shareDir, "merged_" + sessionId + ".mp4");
            MediaMerger.merge(localFiles, shareFile);
            Uri contentUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", shareFile);
            mainHandler.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                endBusy();
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("video/mp4");
                intent.putExtra(Intent.EXTRA_STREAM, contentUri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(intent, getString(R.string.share_merged_recording)));
            });
        } catch (Exception e) {
            Log.e(TAG, "Merge failed", e);
            mainHandler.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                endBusy();
                Toast.makeText(this, getString(R.string.merge_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            });
        } finally {
            for (File f : localFiles) {
                f.delete();
            }
        }
    }

    private void confirmTrash() {
        if (session == null || busyButton != null) {
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(R.string.session_trash_confirm)
                .setPositiveButton(R.string.session_trash, (d, which) -> trash())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The service trashes (never deletes for good); the list reloads itself when it resumes. */
    private void trash() {
        if (session == null) {
            return;
        }
        Intent intent = SafeRecService.createCommandIntent(this, SafeRecService.CMD_DELETE);
        intent.putExtra(SafeRecService.EXTRA_FILE_IDS, session.allFileIds.toArray(new String[0]));
        intent.putExtra(SafeRecService.EXTRA_FOLDER_IDS, session.folderIds.toArray(new String[0]));
        startService(intent); // the activity is in front: a plain start is allowed
        // The list reloads before the service has trashed anything
        TrashedSessions.hide(session.sessionId, session.dataType, System.currentTimeMillis());
        int count = session.allFileIds.size();
        Toast.makeText(this, getResources().getQuantityString(R.plurals.deleting_recordings_bg, count, count),
                Toast.LENGTH_SHORT).show();
        finish();
    }

    // ---- Technical details ----

    private void bindTechnical(SessionSummary summary) {
        ViewGroup list = findViewById(R.id.session_technical_body);
        list.removeAllViews();
        android.view.LayoutInflater inflater = getLayoutInflater();
        java.text.DateFormat timeFormat = DateFormat.getTimeFormat(this);
        for (SessionSummary.Piece piece : summary.pieces) {
            View row = inflater.inflate(R.layout.item_session_piece, list, false);
            ((TextView) row.findViewById(R.id.piece_title)).setText(getString(R.string.piece_row,
                    piece.seq, timeFormat.format(new Date(piece.startMillis))));
            ((TextView) row.findViewById(R.id.piece_size)).setText(Formatter.formatShortFileSize(this, piece.size));
            row.findViewById(R.id.piece_shield).setVisibility(piece.timestampFileId != null ? View.VISIBLE : View.INVISIBLE);
            row.findViewById(R.id.piece_repaired).setVisibility(piece.cutFileId != null ? View.VISIBLE : View.GONE);
            row.setOnClickListener(v -> playFrom(piece));
            describePieceRow(row, piece.timestampFileId != null, piece.cutFileId != null);
            if (piece.timestampFileId != null) {
                row.setOnLongClickListener(v -> {
                    PopupMenu menu = new PopupMenu(this, v);
                    menu.getMenu().add(R.string.session_share_timestamp);
                    menu.setOnMenuItemClickListener(item -> {
                        shareTimestamp(piece.timestampFileId);
                        return true;
                    });
                    menu.show();
                    return true;
                });
            }
            list.addView(row);
        }
        bindManifestRow(summary, list, inflater);
    }

    /** Only a session with a manifest has the row; its shield says the manifest has a time stamp. */
    private void bindManifestRow(SessionSummary summary, ViewGroup list, android.view.LayoutInflater inflater) {
        boolean manifest = false;
        boolean stamped = false;
        for (DriveRecordings.RecordingItem item : summary.files) {
            String extension = item.mediaFile.getExtension();
            manifest |= SessionManifest.EXTENSION.equalsIgnoreCase(extension);
            stamped |= (SessionManifest.EXTENSION + ".tsa").equalsIgnoreCase(extension);
        }
        if (!manifest) {
            return;
        }
        View row = inflater.inflate(R.layout.item_session_piece, list, false);
        ((TextView) row.findViewById(R.id.piece_title)).setText(R.string.session_manifest_row);
        row.findViewById(R.id.piece_size).setVisibility(View.GONE);
        row.findViewById(R.id.piece_shield).setVisibility(stamped ? View.VISIBLE : View.INVISIBLE);
        describePieceRow(row, stamped, false);
        list.addView(row);
    }

    /**
     * One screen reader stop per row, reading its text: the shield and the repaired note are
     * parts of it, not stops of their own.
     */
    private void describePieceRow(View row, boolean stamped, boolean repaired) {
        StringBuilder text = new StringBuilder(((TextView) row.findViewById(R.id.piece_title)).getText());
        TextView size = row.findViewById(R.id.piece_size);
        if (size.getVisibility() == View.VISIBLE) {
            text.append(", ").append(size.getText());
        }
        if (stamped) {
            text.append(", ").append(getString(R.string.piece_time_stamped));
        }
        if (repaired) {
            text.append(", ").append(getString(R.string.piece_repaired));
        }
        row.setFocusable(true);
        row.setContentDescription(text);
    }

    /** Plays from a piece. Its row maps to the playlist through the file ids: a piece without one is not in it. */
    private void playFrom(SessionSummary.Piece piece) {
        int index = fileIds.indexOf(piece.fileId);
        if (index < 0) {
            return;
        }
        long start = pieceStartMs(index);
        if (player != null) {
            player.seekTo(start);
        } else {
            savedPosition = start;
            if (started && !fileIds.isEmpty()) {
                buildPlayer(start);
            }
        }
        ((NestedScrollView) findViewById(R.id.session_scroll)).smoothScrollTo(0, 0);
    }

    /**
     * Where a piece starts in the session: the sum of the earlier pieces' durations. The
     * player's timeline holds the real length of each piece it has read and the placeholder of
     * the others; without a player, the placeholders alone.
     */
    private long pieceStartMs(int index) {
        if (player != null) {
            Timeline timeline = player.getCurrentTimeline();
            if (timeline.getPeriodCount() == fileIds.size() && index >= 0 && index < fileIds.size()) {
                return timeline.getPeriod(index, new Timeline.Period()).getPositionInWindowMs();
            }
        }
        return SessionPlaylist.startOffsetMs(placeholders, index);
    }

    private void shareTimestamp(String fileId) {
        DriveRecordings.RecordingItem stamp = null;
        for (DriveRecordings.RecordingItem item : session.files) {
            if (fileId.equals(item.driveFile.getId())) {
                stamp = item;
            }
        }
        if (stamp == null || busyButton != null) {
            return;
        }
        shareTimestamp(stamp, false);
    }

    private void shareTimestamp(DriveRecordings.RecordingItem stamp, boolean afterRefresh) {
        List<DriveRecordings.RecordingItem> single = new ArrayList<>();
        single.add(stamp);
        String used = token;
        drive.shareFiles(single, new DriveRecordings.Callback<List<Uri>>() {
            @Override
            public void onSuccess(List<Uri> uris) {
                mainHandler.post(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("application/timestamp-reply");
                    intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(intent, getString(R.string.share_tsa_signature)));
                });
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Share TSA failed", e);
                mainHandler.post(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    Runnable failed = () -> Toast.makeText(SessionActivity.this,
                            getString(R.string.share_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                    if (!afterRefresh && DriveTokenRefresh.isUnauthorized(e)) {
                        afterUnauthorized(used, () -> shareTimestamp(stamp, true), failed);
                    } else {
                        failed.run();
                    }
                });
            }
        });
    }

    private String explain(SessionSummary summary) {
        switch (summary.status) {
            case COMPLETE:
                return getString(R.string.session_explain_complete);
            case UPLOADING:
                return getResources().getQuantityString(R.plurals.session_explain_uploading,
                        summary.onPhone.size(), summary.onPhone.size());
            case INCOMPLETE:
                return getResources().getQuantityString(R.plurals.session_explain_incomplete,
                        summary.lost.size(), summary.lost.size());
            default:
                // Not checked: no manifest (an older version), or one that could not be read
                return getString(summary.manifestUnreadable
                        ? R.string.session_explain_unreadable : R.string.session_explain_not_checked);
        }
    }

    // ---- Player ----

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        // Behind a load state (not connected, gone) the player waits for the next load
        if (player == null && !fileIds.isEmpty() && !stateShown) {
            buildPlayer(savedPosition);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        releasePlayer();
    }

    /** Paused at the given position of the session: a recording is never played by surprise. */
    private void buildPlayer(long position) {
        ProgressiveMediaSource.Factory factory = new ProgressiveMediaSource.Factory(streamSource());
        errorBar.setVisibility(View.GONE);
        player = new ExoPlayer.Builder(this).build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                onPlaybackError(error);
            }
        });
        playerView.setPlayer(player);
        player.setMediaSource(sessionSource(factory), Math.max(0, position));
        player.setPlayWhenReady(false);
        player.prepare();
    }

    /**
     * Drive's media with the screen's current token as a header, read at each request: a retry,
     * or the next piece, after a refresh carries the new token. The token is never in the URL.
     */
    private DataSource.Factory streamSource() {
        return new ResolvingDataSource.Factory(new DefaultHttpDataSource.Factory(), spec -> {
            String current = token;
            streamedWith = current;
            return spec.withAdditionalHeaders(Collections.singletonMap("Authorization",
                    "Bearer " + (current != null ? current : "")));
        });
    }

    /**
     * The whole session as one video: one window, so the time bar and seeking cover every piece.
     * Each piece stays its own source, so each keeps its own orientation. A piece needs a length
     * before it is read: the player starts with the placeholders and uses the real lengths as it
     * reads the pieces.
     */
    private MediaSource sessionSource(ProgressiveMediaSource.Factory factory) {
        ConcatenatingMediaSource2.Builder session = new ConcatenatingMediaSource2.Builder();
        for (int i = 0; i < fileIds.size(); i++) {
            session.add(factory.createMediaSource(MediaItem.fromUri(SessionPlaylist.url(fileIds.get(i)))),
                    placeholders.get(i));
        }
        return session.build();
    }

    private void releasePlayer() {
        if (player == null) {
            return;
        }
        savedPosition = player.getCurrentPosition();
        playerView.setPlayer(null);
        player.release();
        player = null;
    }

    private void onPlaybackError(PlaybackException error) {
        Log.w(TAG, "Playback error", error);
        HttpDataSource.InvalidResponseCodeException response = invalidResponse(error);
        int code = response != null ? response.responseCode : 0;
        if (code == 401) {
            // Released meanwhile (the screen stopped): onStart builds a new player, which tries again
            afterUnauthorized(streamedWith, () -> {
                if (player != null) {
                    retry();
                }
            }, () -> {
                if (player != null) {
                    showPlayerError();
                }
            });
            return;
        }
        if ((code == 404 || code == 410) && skipPiece(response)) {
            return;
        }
        showPlayerError();
    }

    @Nullable
    private static HttpDataSource.InvalidResponseCodeException invalidResponse(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof HttpDataSource.InvalidResponseCodeException) {
                return (HttpDataSource.InvalidResponseCodeException) t;
            }
        }
        return null;
    }

    /**
     * A piece trashed on Drive after the screen loaded: playback goes on from the next one.
     * The failed request names the piece; false when it was the last one, or is unknown.
     */
    private boolean skipPiece(HttpDataSource.InvalidResponseCodeException response) {
        if (player == null) {
            return false;
        }
        int failed = SessionPlaylist.indexOfUrl(fileIds,
                response.dataSpec != null ? String.valueOf(response.dataSpec.uri) : null);
        if (failed < 0) {
            failed = player.getCurrentPeriodIndex();
        }
        if (failed < 0 || failed >= fileIds.size() - 1) {
            return false;
        }
        Log.w(TAG, "Piece " + failed + " is gone from Drive: playing on from the next one");
        player.seekTo(pieceStartMs(failed + 1));
        player.prepare();
        return true;
    }

    private void showPlayerError() {
        // The message sits in the body, which fullscreen hides
        setFullscreen(false);
        errorBar.setVisibility(View.VISIBLE);
    }

    /** Same position, with the current token; a second failure shows the message again. */
    private void retry() {
        if (player == null) {
            return;
        }
        errorBar.setVisibility(View.GONE);
        player.prepare();
    }

    // ---- Fullscreen ----

    /** Only the player is left, and the system bars hide until the user swipes them in. */
    private void setFullscreen(boolean on) {
        if (fullscreen == on) {
            return;
        }
        fullscreen = on;
        toolbar.setVisibility(on ? View.GONE : View.VISIBLE);
        body.setVisibility(on ? View.GONE : View.VISIBLE);
        ConstraintLayout.LayoutParams video = (ConstraintLayout.LayoutParams) playerView.getLayoutParams();
        video.dimensionRatio = on ? null : "16:9";
        playerView.setLayoutParams(video);
        ViewGroup.LayoutParams holder = playerHolder.getLayoutParams();
        holder.height = on ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
        playerHolder.setLayoutParams(holder);
        playerView.setFullscreenButtonState(on);
        leaveFullscreen.setEnabled(on);

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (on) {
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (fullscreen) {
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                    .show(WindowInsetsCompat.Type.systemBars());
        }
        releasePlayer();
        if (drive != null) {
            drive.shutdown();
        }
        tokenRefresh.shutdown();
        executor.shutdownNow();
    }
}
