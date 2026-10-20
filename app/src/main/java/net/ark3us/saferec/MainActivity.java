package net.ark3us.saferec;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.view.ViewGroup;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import net.ark3us.saferec.data.ServiceState;
import net.ark3us.saferec.ui.CameraGestureSetup;
import net.ark3us.saferec.ui.TutorialOverlayView;

import net.ark3us.saferec.media.VideoStreamRecorder;
import net.ark3us.saferec.misc.Settings;
import net.ark3us.saferec.net.GoogleDriveClient;
import net.ark3us.saferec.services.SafeRecService;

import javax.annotation.Nullable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getSimpleName();
    private static final String STATE_LAUNCH_COMMAND_HANDLED = "launch_command_handled";
    /** Opens the guide (from Settings → Help). Never carries a command. */
    public static final String EXTRA_SHOW_GUIDE = "net.ark3us.saferec.extra.SHOW_GUIDE";

    private TextureView videoPreview;
    private VideoStreamRecorder previewRecorder;
    private boolean previewSurfaceAvailable = false;

    private ImageButton btnStart;
    private ImageButton btnSwitchCamera;
    private ImageButton btnToggleCamera;
    private ImageButton btnRecordings;
    private ImageButton btnSettings;
    private View recordRing;
    private TextView recordingLabel;
    private View errorContainer;
    private TextView errorMessageText;
    private PermissionsManager permissionsManager;
    private boolean launchCommandHandled = false;
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    private static boolean isStarted(@Nullable String status) {
        return SafeRecService.STATUS_STARTED.equals(status);
    }

    private static boolean isStopped(@Nullable String status) {
        return SafeRecService.STATUS_STOPPED.equals(status);
    }

    private @Nullable String getCommand() {
        return getIntent() != null ? getIntent().getStringExtra(SafeRecService.EXTRA_COMMAND) : null;
    }

    private void updateToggleButtonState(boolean onlyAudio) {
        Log.i(TAG, "Updating toggle camera button state. onlyAudio: " + onlyAudio);
        if (!onlyAudio) {
            btnToggleCamera.setImageDrawable(AppCompatResources.getDrawable(this, R.drawable.baseline_videocam_off_24));
            btnToggleCamera.setColorFilter(getColor(R.color.control_icon));
        } else {
            btnToggleCamera.setImageDrawable(AppCompatResources.getDrawable(this, R.drawable.baseline_videocam_24));
            btnToggleCamera.setColorFilter(getColor(R.color.control_icon_active));
        }
    }

    private void updateStartButtonState(@Nullable Boolean isRecording) {
        Log.i(TAG, "Updating start button state. isRecording: " + isRecording);
        if (isRecording == null) {
            btnStart.setEnabled(false);
            btnStart.setAlpha(0.4f);
            return;
        }
        btnStart.setEnabled(true);
        btnStart.setAlpha(1.0f);

        if (isRecording) {
            btnStart.setBackground(AppCompatResources.getDrawable(this, R.drawable.stop_button_bg));
            btnStart.setImageDrawable(AppCompatResources.getDrawable(this, R.drawable.outline_stop_circle_24));
            btnStart.setContentDescription(getString(R.string.stop_recording));
            recordingLabel.setVisibility(View.VISIBLE);
            recordingLabel.setText(R.string.recording_indicator);

            // Pulse animation on the ring
            Animation pulse = AnimationUtils.loadAnimation(this, R.anim.pulse);
            recordRing.startAnimation(pulse);

            btnStart.setOnClickListener(v -> stopRecording());
        } else {
            btnStart.setBackground(AppCompatResources.getDrawable(this, R.drawable.record_button_bg));
            btnStart.setImageDrawable(AppCompatResources.getDrawable(this, R.drawable.outline_screen_record_24));
            btnStart.setContentDescription(getString(R.string.start_recording));
            recordingLabel.setVisibility(View.GONE);
            recordRing.clearAnimation();

            btnStart.setOnClickListener(v -> startSafeRecService(SafeRecService.CMD_START));
        }
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
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        btnStart = findViewById(R.id.btn_start);
        recordRing = findViewById(R.id.record_ring);
        recordingLabel = findViewById(R.id.recording_label);

        ServiceState.getInstance().getStatus().observe(this, status -> {
            Log.i(TAG, "Service status changed: " + status);
            updateStartButtonState(isStarted(status));
            if (isStopped(status) || isStarted(status)) {
                tryStartPreview();
            }
        });
        ServiceState.getInstance().getError().observe(this, error -> {
            if (error == null) {
                return;
            }
            showError(error);
            ServiceState.getInstance().clearError();
        });

        videoPreview = findViewById(R.id.video_preview);
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

        errorContainer = findViewById(R.id.error_container);
        errorMessageText = findViewById(R.id.error_message);
        permissionsManager = new PermissionsManager(this);
        findViewById(R.id.btn_retry).setOnClickListener(v -> checkPermissionsAndDrive(false));
        findViewById(R.id.btn_close_error).setOnClickListener(v -> errorContainer.setVisibility(View.GONE));

        checkPermissionsAndDrive(!Settings.isTutorialShown(this));

        updateStartButtonState(isStarted(ServiceState.getInstance().getStatus().getValue()));

        btnSwitchCamera = findViewById(R.id.btn_switch_camera);
        btnSwitchCamera.setOnClickListener(v -> {
            if (isStarted(ServiceState.getInstance().getStatus().getValue())) {
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

        btnToggleCamera = findViewById(R.id.btn_toggle_camera);
        updateToggleButtonState(Settings.onlyAudio(this));
        btnToggleCamera.setOnClickListener(v -> {
            boolean onlyAudio = !Settings.onlyAudio(this);
            Settings.setOnlyAudio(this, onlyAudio);
            updateToggleButtonState(onlyAudio);
            if (onlyAudio) {
                stopPreview(true);
            } else {
                tryStartPreview();
            }
        });

        btnRecordings = findViewById(R.id.btn_recordings);
        btnRecordings.setOnClickListener(v -> {
            Intent intent = new Intent(this, RecordingsActivity.class);
            startActivity(intent);
        });

        btnSettings = findViewById(R.id.btn_settings);
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        // Once per launch: a recreated activity keeps its intent and must not show the guide again
        if (savedInstanceState == null && Settings.isTutorialShown(this)) {
            showGuideIfRequested(getIntent());
        }
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showGuideIfRequested(intent);
    }

    /** Settings → Help → Guide. Until the first run is done, the tutorial shows anyway. */
    private void showGuideIfRequested(@Nullable Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_SHOW_GUIDE, false)) {
            return;
        }
        intent.removeExtra(EXTRA_SHOW_GUIDE);
        btnStart.post(() -> showTutorialOverlay(-1));
    }

    private void showTutorialOverlay(int step) {
        View target = null;
        CharSequence title = null;
        CharSequence message = "";

        switch (step) {
            case -1:
                title = getText(R.string.tutorial_general_title);
                message = getText(R.string.tutorial_general_message);
                break;
            case 0:
                target = btnStart;
                message = getText(R.string.tutorial_step_start);
                break;
            case 1:
                target = btnSwitchCamera;
                message = getText(R.string.tutorial_step_camera);
                break;
            case 2:
                target = btnToggleCamera;
                message = getText(R.string.tutorial_step_mode);
                break;
            case 3:
                // The share button moved to settings: skip its step
                showTutorialOverlay(step + 1);
                return;
            case 4:
                target = btnRecordings;
                message = getText(R.string.tutorial_step_list);
                break;
            case 5:
                target = btnSettings;
                title = getText(R.string.tutorial_step_settings_title);
                message = getText(R.string.tutorial_step_settings_message);
                break;
            case 6:
                // Tile tutorial step
                title = getText(R.string.tile_name_start);
                message = getText(R.string.tutorial_step_tile);
                // No specific target view for tile since it's outside the app
                break;
            default:
                // End of tutorial
                View overlay = findViewById(R.id.tutorial_overlay);
                if (overlay != null) {
                    ((ViewGroup) overlay.getParent()).removeView(overlay);
                }
                Settings.setTutorialShown(this, true);
                checkPermissionsAndDrive(false);
                return;
        }

        TutorialOverlayView overlay = findViewById(R.id.tutorial_overlay);
        if (overlay == null) {
            overlay = new TutorialOverlayView(this);
            overlay.setId(R.id.tutorial_overlay);
            addContentView(overlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        final int nextStep = step + 1;
        overlay.setTarget(target, title, message, () -> showTutorialOverlay(nextStep));
    }

    private void checkPermissionsAndDrive(boolean showTutorial) {
        Log.i(TAG, "Checking permissions and Google Drive connection...");
        errorContainer.setVisibility(View.GONE);

        if (showTutorial) {
            // Wait for layout to be ready to get view positions
            btnStart.post(() -> showTutorialOverlay(-1));
            return;
        }

        // Record first, sync later: a launch command never waits for Google Drive.
        // Posted on a view so the service starts only once the activity is visible.
        Runnable launchIfPermitted = () -> {
            if (PermissionsManager.hasRecordingPermissions(this)) {
                btnStart.post(this::handleLaunchCommand);
            }
        };
        launchIfPermitted.run();

        permissionsManager.requestAllPermissions(launchIfPermitted, (allGranted, accessToken) -> {
            if (allGranted && accessToken != null) {
                backgroundExecutor.execute(() -> {
                    GoogleDriveClient client = new GoogleDriveClient(accessToken);
                    GoogleDriveClient.AuthStatus authStatus = client.checkAuthentication();

                    runOnUiThread(() -> {
                        if (authStatus != GoogleDriveClient.AuthStatus.INVALID) {
                            Log.i(TAG, "All permissions granted, access token status: " + authStatus);
                            // UNKNOWN means Drive is unreachable right now: keep the token
                            // anyway, chunks wait on disk until uploads work again.
                            Settings.setAccessToken(this, accessToken);
                            if (authStatus == GoogleDriveClient.AuthStatus.VALID) {
                                errorContainer.setVisibility(View.GONE);
                                startSafeRecService(SafeRecService.CMD_UPLOAD_PENDING);
                            } else {
                                errorContainer.setVisibility(View.VISIBLE);
                                errorMessageText.setText(R.string.google_drive_failed);
                            }
                            tryStartPreview();
                        } else {
                            Log.e(TAG, "Access token rejected by Drive");
                            Settings.setAccessToken(this, null);
                            errorContainer.setVisibility(View.VISIBLE);
                            errorMessageText.setText(R.string.google_drive_failed);
                        }
                    });
                });
            } else {
                Log.e(TAG, "Failed to initialize: allGranted=" + allGranted + ", token=" + (accessToken != null));
                errorContainer.setVisibility(View.VISIBLE);
                if (!allGranted) {
                    errorMessageText.setText(R.string.permissions_required);
                } else {
                    errorMessageText.setText(R.string.google_drive_failed);
                }
            }
        });
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

    private void tryStartPreview() {
        stopPreview(false);
        if (Settings.onlyAudio(this) || !previewSurfaceAvailable) {
            return;
        }
        previewRecorder = VideoStreamRecorder.getInstance();
        if (isStarted(ServiceState.getInstance().getStatus().getValue())) {
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
            if (isStarted(ServiceState.getInstance().getStatus().getValue())) {
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

    @Override
    protected void onPause() {
        super.onPause();
        stopPreview(false);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_LAUNCH_COMMAND_HANDLED, launchCommandHandled);
    }

    @Override
    protected void onResume() {
        super.onResume();
        videoPreview.post(this::tryStartPreview);
        CameraGestureSetup.onResume(this);
        if (BuildConfig.POWER_GESTURE) {
            // The app is in the foreground, so the guard's foreground service may start now
            net.ark3us.saferec.guard.Guard.ensureRunning(this);
        }
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
            updateStartButtonState(null);
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

    private void stopRecording() {
        Log.i(TAG, "Stopping recording");
        updateStartButtonState(null);
        startServiceCommand(SafeRecService.createCommandIntent(this, SafeRecService.CMD_STOP));
    }

    /** Shows an error reported by the service. Never stops the recording: losing Drive must not lose evidence. */
    private void showError(String error) {
        Log.e(TAG, "Recording service reported an error: " + error);
        if (ServiceState.ERROR_AUTH.equals(error)) {
            errorContainer.setVisibility(View.VISIBLE);
            errorMessageText.setText(R.string.google_drive_failed);
        } else if (ServiceState.ERROR_CAMERA.equals(error)) {
            Toast.makeText(this, R.string.error_camera_lost, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, R.string.error_recording_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        backgroundExecutor.shutdownNow();
        super.onDestroy();
    }
}
