package net.ark3us.saferec;

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
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import com.google.android.material.appbar.MaterialToolbar;

import net.ark3us.saferec.misc.Settings;
import net.ark3us.saferec.net.DriveConnection;
import net.ark3us.saferec.net.DriveRecordings;
import net.ark3us.saferec.net.SessionSummary;
import net.ark3us.saferec.ui.SessionPlaylist;
import net.ark3us.saferec.ui.SessionsAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * One session: its pieces played as one video, whether it is whole, and (from the next task)
 * what can be done with it. It loads that one session by id, so no list travels with the
 * intent. It never finishes by itself: a session Drive no longer has says so.
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
    @Nullable
    private SessionSummary session;
    private List<String> fileIds = new ArrayList<>();

    private ExoPlayer player;
    private PlayerView playerView;
    private View playerHolder;
    private View toolbar;
    private View body;
    private View errorBar;
    private boolean started;
    // Where the player was when onStop released it
    private int savedItem;
    private long savedPosition;
    // A 401 asks Play services for a new token once per screen
    private boolean refreshTried;
    private boolean fullscreen;
    private OnBackPressedCallback leaveFullscreen;

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
        findViewById(R.id.player_retry).setOnClickListener(v -> retry());
        playerView.setFullscreenButtonClickListener(this::setFullscreen);
        leaveFullscreen = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                setFullscreen(false);
            }
        };
        getOnBackPressedDispatcher().addCallback(this, leaveFullscreen);

        View technicalBody = findViewById(R.id.session_technical_body);
        View chevron = findViewById(R.id.session_technical_chevron);
        findViewById(R.id.session_technical_header).setOnClickListener(v -> {
            boolean open = technicalBody.getVisibility() != View.VISIBLE;
            technicalBody.setVisibility(open ? View.VISIBLE : View.GONE);
            chevron.setRotation(open ? 180f : 0f);
        });

        loadSession();
    }

    // ---- Loading ----

    private void loadSession() {
        String sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        String dataType = getIntent().getStringExtra(EXTRA_DATA_TYPE);
        String token = Settings.getAccessToken(this);
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
        if (token == null || token.isEmpty()) {
            showState(R.string.recordings_not_connected, R.string.status_connect_drive);
            return;
        }
        hideState();
        if (drive != null) {
            drive.shutdown();
        }
        drive = new DriveRecordings(this, token);
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
                    if (!isDestroyed()) {
                        showState(R.string.session_load_failed, R.string.retry_short);
                    }
                });
            }
        });
    }

    /**
     * The session cannot be shown: say why, and offer the way forward when there is one
     * (connect Drive when there is no token, retry after an error).
     */
    private void showState(int message, int action) {
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
                    DriveConnection.Result result = DriveConnection.accept(app, token);
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
        playerHolder.setVisibility(fileIds.isEmpty() ? View.GONE : View.VISIBLE);
        if (started && player == null && !fileIds.isEmpty()) {
            buildPlayer(0, 0);
        }
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
                return getString(R.string.session_explain_not_checked);
        }
    }

    // ---- Player ----

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (player == null && !fileIds.isEmpty()) {
            buildPlayer(savedItem, savedPosition);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        releasePlayer();
    }

    /** Paused at the given piece and position: a recording is never played by surprise. */
    private void buildPlayer(int item, long position) {
        String token = Settings.getAccessToken(this);
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + (token != null ? token : ""));
        ProgressiveMediaSource.Factory factory = new ProgressiveMediaSource.Factory(
                new DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers));
        List<MediaSource> sources = new ArrayList<>();
        for (String id : fileIds) {
            sources.add(factory.createMediaSource(MediaItem.fromUri(SessionPlaylist.url(id))));
        }
        errorBar.setVisibility(View.GONE);
        player = new ExoPlayer.Builder(this).build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                onPlaybackError(error);
            }
        });
        playerView.setPlayer(player);
        player.setMediaSources(sources, Math.min(item, sources.size() - 1), position);
        player.setPlayWhenReady(false);
        player.prepare();
    }

    private void releasePlayer() {
        if (player == null) {
            return;
        }
        savedItem = player.getCurrentMediaItemIndex();
        savedPosition = player.getCurrentPosition();
        playerView.setPlayer(null);
        player.release();
        player = null;
    }

    private void onPlaybackError(PlaybackException error) {
        Log.w(TAG, "Playback error", error);
        if (isUnauthorized(error) && !refreshTried) {
            refreshTried = true;
            refreshToken();
            return;
        }
        showPlayerError();
    }

    private static boolean isUnauthorized(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof HttpDataSource.InvalidResponseCodeException
                    && ((HttpDataSource.InvalidResponseCodeException) t).responseCode == 401) {
                return true;
            }
        }
        return false;
    }

    private void showPlayerError() {
        // The message sits in the body, which fullscreen hides
        setFullscreen(false);
        errorBar.setVisibility(View.VISIBLE);
    }

    /** Same piece, same position; a second failure shows the message again. */
    private void retry() {
        if (player == null) {
            return;
        }
        errorBar.setVisibility(View.GONE);
        player.prepare();
    }

    /**
     * Play services may hand out a new token without asking the user. Only a token Drive
     * accepts replaces the player: anything else is the plain error message.
     */
    private void refreshToken() {
        permissionsManager.connectDrive(false, (token, needsUser) -> {
            if (token == null) {
                showPlayerError();
                return;
            }
            Context app = getApplicationContext();
            try {
                executor.execute(() -> {
                    DriveConnection.Result result = DriveConnection.accept(app, token);
                    mainHandler.post(() -> {
                        if (isDestroyed()) {
                            return;
                        }
                        if (result == DriveConnection.Result.VALID && player != null) {
                            releasePlayer();
                            buildPlayer(savedItem, savedPosition);
                        } else {
                            showPlayerError();
                        }
                    });
                });
            } catch (RejectedExecutionException e) {
                Log.w(TAG, "Screen gone, token check dropped", e);
            }
        });
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
        executor.shutdownNow();
    }
}
