package net.ark3us.saferec;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Chronometer;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.snackbar.Snackbar;

import net.ark3us.saferec.data.ServiceState;
import net.ark3us.saferec.media.VideoStreamRecorder;
import net.ark3us.saferec.misc.Settings;
import net.ark3us.saferec.net.GoogleDriveFileUploader;
import net.ark3us.saferec.services.SafeRecService;
import net.ark3us.saferec.ui.CameraGestureSetup;
import net.ark3us.saferec.ui.DriveConnector;
import net.ark3us.saferec.ui.NetworkWatcher;
import net.ark3us.saferec.ui.PermissionsBanner;
import net.ark3us.saferec.ui.RecordButton;
import net.ark3us.saferec.ui.StatusChipController;
import net.ark3us.saferec.ui.FirstRun;

/**
 * The viewfinder. Nothing covers or disables the record button: not Drive, the network, an
 * error, a permission or a dialog. Errors go to the status chip or to a Snackbar above the
 * bottom controls.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getSimpleName();
    private static final String STATE_LAUNCH_COMMAND_HANDLED = "launch_command_handled";
    /** Opens the guide (from Settings → Help). Never carries a command. */
    public static final String EXTRA_SHOW_GUIDE = "net.ark3us.saferec.extra.SHOW_GUIDE";
    /** A command the service ignores posts no status: the button is never dimmed for longer. */
    private static final long BUSY_TIMEOUT_MS = 3000;

    private TextureView videoPreview;
    private VideoStreamRecorder previewRecorder;
    private boolean previewSurfaceAvailable = false;

    private FirstRun firstRun;
    private RecordButton btnRecord;
    private View btnSwitchCamera;
    private View btnRecordings;
    private View btnSettings;
    private View btnGallery;
    private MaterialButtonToggleGroup modeToggle;
    private Chip safeChip;
    private View audioOnlySurface;
    private View recordingInfo;
    private Chronometer recordingTime;
    private View holdHint;

    private StatusChipController statusChip;
    private PermissionsBanner permissionsBanner;
    private DriveConnector driveConnector;
    private NetworkWatcher networkWatcher;
    private boolean launchCommandHandled = false;
    // Files in the outbox: counted on resume, then as the service publishes them
    private int pendingFiles = 0;
    // The last audio-only value of the recording, null before the first one
    private @Nullable Boolean recordingAudioOnly;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable clearBusy = () -> btnRecord.setBusy(false);

    private static boolean isStarted(@Nullable String status) {
        return SafeRecService.STATUS_STARTED.equals(status);
    }

    private static boolean isStopped(@Nullable String status) {
        return SafeRecService.STATUS_STOPPED.equals(status);
    }

    /** Recording means STATUS_STARTED only: the session start can outlive a session. */
    private static boolean isRecording() {
        return isStarted(ServiceState.getInstance().getStatus().getValue());
    }

    /** What the viewfinder shows: the running recording's type, otherwise the chosen mode. */
    private boolean showsAudioOnly() {
        if (isRecording()) {
            return Boolean.TRUE.equals(ServiceState.getInstance().getAudioOnly().getValue());
        }
        return Settings.onlyAudio(this);
    }

    private @Nullable String getCommand() {
        return getIntent() != null ? getIntent().getStringExtra(SafeRecService.EXTRA_COMMAND) : null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // A recreated activity or a relaunch from Recents carries the old intent:
        // replaying its command could restart a recording the user already stopped.
        launchCommandHandled = (savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_LAUNCH_COMMAND_HANDLED))
                || (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0;
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        padWithInsets(findViewById(R.id.top_bar), true);
        padWithInsets(findViewById(R.id.bottom_controls), false);

        videoPreview = findViewById(R.id.video_preview);
        btnRecord = findViewById(R.id.btn_record);
        btnSwitchCamera = findViewById(R.id.btn_switch_camera);
        btnRecordings = findViewById(R.id.btn_recordings);
        btnSettings = findViewById(R.id.btn_settings);
        btnGallery = findViewById(R.id.btn_gallery);
        modeToggle = findViewById(R.id.mode_toggle);
        safeChip = findViewById(R.id.safe_chip);
        audioOnlySurface = findViewById(R.id.audio_only_surface);
        recordingInfo = findViewById(R.id.recording_info);
        recordingTime = findViewById(R.id.recording_time);
        holdHint = findViewById(R.id.hold_hint);

        PermissionsManager permissionsManager = new PermissionsManager(this);
        firstRun = new FirstRun(this, findViewById(R.id.first_run_card), permissionsManager);
        permissionsBanner = new PermissionsBanner(this, permissionsManager, findViewById(R.id.permissions_banner),
                findViewById(R.id.permissions_banner_text), findViewById(R.id.permissions_allow), savedInstanceState);
        driveConnector = new DriveConnector(this, permissionsManager, new DriveConnector.Listener() {
            @Override
            public void onDriveReady() {
                startSafeRecService(SafeRecService.CMD_UPLOAD_PENDING);
            }

            @Override
            public void onConnectFailed() {
                snackbar(R.string.drive_connect_failed);
            }

            @Override
            public void onDriveChanged() {
                renderStatusChip();
            }
        });
        networkWatcher = new NetworkWatcher(this, this::onOnlineChanged);
        statusChip = new StatusChipController(findViewById(R.id.status_chip), new StatusChipController.Actions() {
            @Override
            public void connectDrive() {
                driveConnector.connect();
            }

            @Override
            public void openSettings() {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }

            @Override
            public void explainWaiting() {
                snackbar(R.string.status_waiting_explained);
            }
        });

        videoPreview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                previewSurfaceAvailable = true;
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                if (previewRecorder != null) {
                    previewRecorder.configureTransform(videoPreview);
                }
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                previewSurfaceAvailable = false;
                stopPreview(false);
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            }
        });

        // Never checks Drive, the network or the first run
        btnRecord.setListener(new RecordButton.Listener() {
            @Override
            public void onStartRequested() {
                startRecording();
            }

            @Override
            public void onStopConfirmed() {
                startSafeRecService(SafeRecService.CMD_STOP);
            }

            @Override
            public void onStopHintNeeded() {
                pulseHoldHint();
            }
        });

        btnSwitchCamera.setOnClickListener(v -> {
            if (isRecording()) {
                // The service switches from the camera the recording uses, which a quick start
                // may have picked, and stores the new one as the viewfinder's camera
                Log.i(TAG, "Recording in progress, asking the service to switch camera");
                startSafeRecService(SafeRecService.CMD_SWITCH_CAMERA);
            } else {
                boolean newValue = !Settings.getUseFrontCamera(this);
                Settings.setUseFrontCamera(this, newValue);
                Log.i(TAG, "Switching camera. New useFrontCamera=" + newValue);
                tryStartPreview();
            }
        });

        modeToggle.check(Settings.onlyAudio(this) ? R.id.mode_audio : R.id.mode_video);
        modeToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                setOnlyAudio(checkedId == R.id.mode_audio);
            }
        });

        View.OnClickListener openRecordings = v -> startActivity(new Intent(this, RecordingsActivity.class));
        btnRecordings.setOnClickListener(openRecordings);
        btnGallery.setOnClickListener(openRecordings);
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        observeServiceState();

        if (savedInstanceState != null && firstRun.restore(savedInstanceState)) {
            // The card is back on its step
        } else if (!Settings.isFirstRunDone(this)) {
            firstRun.show();
        } else if (savedInstanceState == null) {
            // Once per launch: a recreated activity keeps its intent and must not show the guide again
            showGuideIfRequested(getIntent());
        }

        // Record first, sync later: a launch command waits for neither Drive nor the first run
        launchWhenPermitted();
    }

    private void observeServiceState() {
        ServiceState state = ServiceState.getInstance();
        state.getStatus().observe(this, status -> {
            Log.i(TAG, "Service status changed: " + status);
            setBusy(false);
            render();
            if (isStopped(status) || isStarted(status)) {
                tryStartPreview();
            }
        });
        state.getError().observe(this, error -> {
            if (error == null) {
                return;
            }
            showError(error);
            state.clearError();
        });
        state.getAudioOnly().observe(this, audioOnly -> {
            boolean value = Boolean.TRUE.equals(audioOnly);
            boolean changed = recordingAudioOnly != null && recordingAudioOnly != value;
            recordingAudioOnly = value;
            render();
            if (changed && isRecording()) {
                // The camera was lost, or the start was audio only: no preview to attach
                tryStartPreview();
            }
        });
        state.getSessionStart().observe(this, start -> renderRecordingInfo());
        state.getSafeChunks().observe(this, count -> renderRecordingInfo());
        state.getPendingFiles().observe(this, count -> {
            pendingFiles = count != null ? count : 0;
            renderStatusChip();
        });
        state.getDriveAuthProblem().observe(this, problem -> renderStatusChip());
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showGuideIfRequested(intent);
    }

    /** Settings → Help → Guide. The cards also show on a first run. */
    private void showGuideIfRequested(@Nullable Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_SHOW_GUIDE, false)) {
            return;
        }
        intent.removeExtra(EXTRA_SHOW_GUIDE);
        firstRun.show();
    }

    /**
     * Runs the launch command. Only a start records, with the viewfinder's mode: it waits for what
     * that mode needs, asked first if missing. Other commands, and no command, need nothing.
     */
    private void launchWhenPermitted() {
        boolean audioOnly = Settings.onlyAudio(this);
        if (launchCommandHandled || !SafeRecService.CMD_START.equals(getCommand())
                || PermissionsManager.hasRecordingPermissions(this, audioOnly)) {
            // Posted on a view so the service starts only once the activity is visible
            btnRecord.post(this::handleLaunchCommand);
        } else {
            permissionsBanner.ask(audioOnly, () -> btnRecord.post(this::handleLaunchCommand));
        }
    }

    /** Runs the command the activity was launched with, once per launch. */
    private void handleLaunchCommand() {
        if (launchCommandHandled) {
            return;
        }
        launchCommandHandled = true;

        String command = getCommand();
        Log.i(TAG, "Launch command: " + command);

        if (command == null) {
            return;
        }
        // This activity is exported: any installed app can launch it with a command. Only Start
        // is accepted, so no other app can stop a recording. Debug builds accept every command:
        // scripts/device-tests.sh stops recordings this way.
        if (!SafeRecService.CMD_START.equals(command) && !BuildConfig.DEBUG) {
            Log.w(TAG, "Launch command ignored: " + command);
            return;
        }
        startSafeRecService(command);
    }

    // ---- Recording and permissions ----

    /**
     * Waits only for what the mode records with (the microphone, plus the camera for video), never
     * for Drive, the network or the first run.
     */
    private void startRecording() {
        boolean audioOnly = Settings.onlyAudio(this);
        if (PermissionsManager.hasRecordingPermissions(this, audioOnly)) {
            startSafeRecService(SafeRecService.CMD_START);
        } else {
            permissionsBanner.ask(audioOnly, () -> startSafeRecService(SafeRecService.CMD_START));
        }
    }

    /** While a start or stop is on its way; the next status, or the timeout, ends it. */
    private void setBusy(boolean busy) {
        handler.removeCallbacks(clearBusy);
        btnRecord.setBusy(busy);
        if (busy) {
            handler.postDelayed(clearBusy, BUSY_TIMEOUT_MS);
        }
    }

    /** A tap while recording: the hint grows once and is read aloud. */
    private void pulseHoldHint() {
        holdHint.animate().cancel();
        holdHint.setScaleX(1f);
        holdHint.setScaleY(1f);
        holdHint.animate().scaleX(1.15f).scaleY(1.15f).setDuration(150)
                .withEndAction(() -> holdHint.animate().scaleX(1f).scaleY(1f).setDuration(150))
                .start();
        holdHint.announceForAccessibility(getString(R.string.record_hold_to_stop));
    }

    private void setOnlyAudio(boolean onlyAudio) {
        if (onlyAudio == Settings.onlyAudio(this)) {
            return;
        }
        Log.i(TAG, "Viewfinder mode: onlyAudio=" + onlyAudio);
        Settings.setOnlyAudio(this, onlyAudio);
        permissionsBanner.refresh(onlyAudio);
        if (onlyAudio) {
            stopPreview(true);
        } else {
            tryStartPreview();
        }
        render();
    }

    // ---- Rendering ----

    /** Draws the screen from the service's state. The record button is never disabled here. */
    private void render() {
        boolean recording = isRecording();
        boolean audioOnly = showsAudioOnly();
        btnRecord.setRecording(recording);
        show(recordingInfo, recording);
        holdHint.setVisibility(recording ? View.VISIBLE : View.INVISIBLE);
        show(modeToggle, !recording);
        show(btnGallery, !recording);
        show(btnRecordings, !recording);
        show(btnSettings, !recording);
        // A switch restarts a video recording; an audio-only one has no camera to switch
        show(btnSwitchCamera, !(recording && audioOnly));
        show(audioOnlySurface, audioOnly);
        renderRecordingInfo();
        renderStatusChip();
    }

    private void renderRecordingInfo() {
        if (!isRecording()) {
            recordingTime.stop();
            return;
        }
        ServiceState state = ServiceState.getInstance();
        Long start = state.getSessionStart().getValue();
        long elapsed = start != null && start > 0 ? Math.max(0, System.currentTimeMillis() - start) : 0;
        recordingTime.setBase(SystemClock.elapsedRealtime() - elapsed);
        recordingTime.start();
        Integer safe = state.getSafeChunks().getValue();
        int count = safe != null ? safe : 0;
        // From the first safe piece only: "0 pieces safe" would show a shield with nothing behind it
        show(safeChip, count > 0);
        safeChip.setText(getResources().getQuantityString(R.plurals.notif_safe_chunks, count, count));
    }

    private void renderStatusChip() {
        statusChip.render(isRecording(), networkWatcher.isOnline(), pendingFiles);
    }

    private static void show(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Above the bottom controls, never over the record button. */
    private void snackbar(@StringRes int text) {
        Snackbar.make(findViewById(R.id.main), text, Snackbar.LENGTH_LONG)
                .setAnchorView(R.id.bottom_controls)
                .show();
    }

    /**
     * Pads a bar by the system bars and the cutout, on top of its own padding. The bars use
     * symmetric padding in XML: start/end padding would be lost by setPadding().
     */
    private static void padWithInsets(View bar, boolean top) {
        int left = bar.getPaddingLeft();
        int right = bar.getPaddingRight();
        int topPadding = bar.getPaddingTop();
        int bottomPadding = bar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(bar, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(left + bars.left, topPadding + (top ? bars.top : 0),
                    right + bars.right, bottomPadding + (top ? 0 : bars.bottom));
            return insets;
        });
    }

    // ---- Drive ----

    private void onOnlineChanged(boolean online) {
        renderStatusChip();
        if (online) {
            driveConnector.silentCheck(true);
        }
    }

    private int countOutbox() {
        String[] names = GoogleDriveFileUploader.outboxDir(this).list();
        return names != null ? names.length : 0;
    }

    // ---- Preview ----

    private void tryStartPreview() {
        stopPreview(false);
        if (showsAudioOnly() || !previewSurfaceAvailable) {
            return;
        }
        previewRecorder = VideoStreamRecorder.getInstance();
        if (isRecording()) {
            // Recording in progress: attach preview to the existing session
            Log.i(TAG, "Attaching preview to ongoing recording session");
            previewRecorder.attachPreview(videoPreview.getSurfaceTexture());
        } else {
            boolean useFrontCamera = Settings.getUseFrontCamera(this);
            previewRecorder.startPreview(this, videoPreview.getSurfaceTexture(), useFrontCamera, success -> {
                if (!success)
                    Log.e(TAG, "Preview failed to start");
            });
        }
        videoPreview.setVisibility(View.VISIBLE);
        previewRecorder.configureTransform(videoPreview);
    }

    private void stopPreview(boolean clearSurface) {
        if (previewRecorder != null) {
            if (isRecording()) {
                // Recording in progress: detach the preview surface from the
                // capture session so it won't crash when the SurfaceTexture is
                // released, but keep the camera/encoder running for the service.
                Log.i(TAG, "Detaching preview — recording in progress");
                previewRecorder.detachPreview();
            } else {
                previewRecorder.stop();
            }
            previewRecorder = null;
        }
        // Clear the surface to avoid showing the last frame if requested
        if (clearSurface && videoPreview != null) {
            videoPreview.setVisibility(View.INVISIBLE);
        }
    }

    // ---- Lifecycle ----

    @Override
    protected void onStart() {
        super.onStart();
        networkWatcher.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        videoPreview.post(this::tryStartPreview);
        CameraGestureSetup.onResume(this);
        firstRun.onResume();
        if (BuildConfig.POWER_GESTURE) {
            // The app is in the foreground, so the guard's foreground service may start now
            net.ark3us.saferec.guard.Guard.ensureRunning(this);
            statusChip.guardStarting();
        }
        pendingFiles = countOutbox();
        permissionsBanner.refresh(Settings.onlyAudio(this));
        render();
        driveConnector.silentCheck(networkWatcher.isOnline());
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPreview(false);
    }

    @Override
    protected void onStop() {
        networkWatcher.stop();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_LAUNCH_COMMAND_HANDLED, launchCommandHandled);
        permissionsBanner.save(outState);
        firstRun.save(outState);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (previewRecorder != null) {
            previewRecorder.configureTransform(videoPreview);
        }
    }

    private void startSafeRecService(@Nullable String command) {
        Log.i(TAG, "Sending command to service: " + command);
        if (SafeRecService.CMD_START.equals(command) || SafeRecService.CMD_STOP.equals(command)) {
            setBusy(true);
        }
        Intent intent = SafeRecService.createCommandIntent(this, command);
        if (SafeRecService.CMD_START.equals(command)) {
            startForegroundService(intent);
        } else {
            startServiceCommand(intent);
        }
    }

    /**
     * Commands that do not record use startService(): with startForegroundService() the service
     * would have to enter the foreground even when it has nothing to do.
     */
    private void startServiceCommand(Intent intent) {
        try {
            startService(intent);
        } catch (IllegalStateException e) {
            // The activity went to the background meanwhile: Android refuses background starts
            Log.w(TAG, "Cannot reach the service from the background", e);
        }
    }

    /**
     * Shows an error reported by the service. Never stops the recording: losing Drive must not
     * lose evidence. ERROR_AUTH has no message: the status chip says "Reconnect Drive".
     */
    private void showError(String error) {
        Log.e(TAG, "Recording service reported an error: " + error);
        if (ServiceState.ERROR_CAMERA.equals(error)) {
            snackbar(R.string.error_camera_lost);
        } else if (ServiceState.ERROR_RECORDING.equals(error)) {
            snackbar(R.string.error_recording_failed);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        statusChip.release();
        driveConnector.shutdown();
        super.onDestroy();
    }
}
