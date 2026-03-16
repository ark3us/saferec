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
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import android.view.Menu;
import android.view.SubMenu;
import android.view.ViewGroup;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import net.ark3us.saferec.data.LiveData;
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

    private TextureView videoPreview;
    private VideoStreamRecorder previewRecorder;
    private boolean previewSurfaceAvailable = false;

    private ImageButton btnStart;
    private ImageButton btnSwitchCamera;
    private ImageButton btnToggleCamera;
    private ImageButton btnShareRoot;
    private ImageButton btnRecordings;
    private ImageButton btnSettings;
    private View recordRing;
    private TextView recordingLabel;
    private View errorContainer;
    private TextView errorMessageText;
    private PermissionsManager permissionsManager;
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    private static boolean isStarted(@Nullable String status) {
        return SafeRecService.STATUS_STARTED.equals(status);
    }

    private static boolean isStopped(@Nullable String status) {
        return SafeRecService.STATUS_STOPPED.equals(status);
    }

    private static boolean isReady(@Nullable String status) {
        return SafeRecService.STATUS_READY.equals(status);
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

        LiveData.getInstance().getStatus().observe(this, status -> {
            Log.i(TAG, "Service status changed: " + status);
            updateStartButtonState(isStarted(status));
            if (isStopped(status) || isStarted(status)) {
                tryStartPreview();
            }
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

        updateStartButtonState(isStarted(LiveData.getInstance().getStatus().getValue()));

        btnSwitchCamera = findViewById(R.id.btn_switch_camera);
        btnSwitchCamera.setOnClickListener(v -> {
            boolean useFrontCamera = Settings.getUseFrontCamera(this);
            boolean newValue = !useFrontCamera;
            Settings.setUseFrontCamera(this, newValue);
            Log.i(TAG, "Switching camera. New useFrontCamera=" + newValue);

            if (isStarted(LiveData.getInstance().getStatus().getValue())) {
                Log.i(TAG, "Recording in progress, restarting service to switch camera");
                startSafeRecService(SafeRecService.CMD_START);
            } else {
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

        btnShareRoot = findViewById(R.id.btn_share_root);
        btnShareRoot.setOnClickListener(v -> shareRootFolder());

        btnSettings = findViewById(R.id.btn_settings);
        btnSettings.setOnClickListener(v -> showSettingsPopup(v));

    }



    private void showSettingsPopup(View v) {
        PopupMenu popup = new PopupMenu(this, v);
        Menu menu = popup.getMenu();

        menu.add(0, 0, 0, R.string.show_tutorial);

        boolean autoStart = Settings.isAutoStartOnLaunch(this);
        menu.add(0, 1, 1, getString(R.string.auto_start) + (autoStart ? " ✓" : ""));

        boolean tsEnabled = Settings.isTimestampingEnabled(this);
        menu.add(0, 8, 4, getString(R.string.enable_timestamping) + (tsEnabled ? " ✓" : ""));

        // Use a SubMenu for quality grouping
        SubMenu qualityMenu = menu.addSubMenu(0, 5, 2, R.string.video_quality);
        String currentQuality = Settings.getVideoQuality(this);
        qualityMenu.add(0, 2, 0, getString(R.string.quality_high) + ("HIGH".equals(currentQuality) ? " ✓" : ""));
        qualityMenu.add(0, 3, 1, getString(R.string.quality_medium) + ("MEDIUM".equals(currentQuality) ? " ✓" : ""));
        qualityMenu.add(0, 4, 2, getString(R.string.quality_low) + ("LOW".equals(currentQuality) ? " ✓" : ""));

        // SubMenu for Chunk Size
        SubMenu chunkMenu = menu.addSubMenu(0, 10, 3, R.string.chunk_size);
        int currentChunk = Settings.getChunkSizeMB(this);
        chunkMenu.add(0, 6, 0, getString(R.string.chunk_auto) + (currentChunk == 0 ? " ✓" : ""));
        chunkMenu.add(0, 7, 1, getString(R.string.chunk_custom) + (currentChunk > 0 ? " (" + currentChunk + " MB) ✓" : ""));

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 0:
                    showTutorialOverlay(-1);
                    break;
                case 1:
                    boolean newValue = !Settings.isAutoStartOnLaunch(this);
                    Settings.setAutoStartOnLaunch(this, newValue);
                    Toast.makeText(this, (newValue ? getString(R.string.enabled) : getString(R.string.disabled)) + ": " + getString(R.string.auto_start),
                            Toast.LENGTH_SHORT).show();
                    break;
                case 8:
                    boolean newTsEnabled = !Settings.isTimestampingEnabled(this);
                    Settings.setTimestampingEnabled(this, newTsEnabled);
                    Toast.makeText(this, (newTsEnabled ? getString(R.string.enabled) : getString(R.string.disabled)) + ": " + getString(R.string.enable_timestamping),
                            Toast.LENGTH_SHORT).show();
                    break;
                case 2:
                    Settings.setVideoQuality(this, "HIGH");
                    tryStartPreview();
                    break;
                case 3:
                    Settings.setVideoQuality(this, "MEDIUM");
                    tryStartPreview();
                    break;
                case 4:
                    Settings.setVideoQuality(this, "LOW");
                    tryStartPreview();
                    break;
                case 6:
                    Settings.setChunkSizeMB(this, 0);
                    Toast.makeText(this, getString(R.string.chunk_size) + ": " + getString(R.string.chunk_auto), Toast.LENGTH_SHORT).show();
                    break;
                case 7:
                    showChunkSizeInputDialog();
                    break;

            }
            return true;
        });
        popup.show();
    }

    private void showChunkSizeInputDialog() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        int current = Settings.getChunkSizeMB(this);
        if (current > 0) input.setText(String.valueOf(current));
        
        new AlertDialog.Builder(this)
            .setTitle(R.string.chunk_manual_title)
            .setView(input)
            .setPositiveButton("OK", (dialog, which) -> {
                String val = input.getText().toString();
                if (!val.isEmpty()) {
                    try {
                        int size = Integer.parseInt(val);
                        if (size > 0) {
                            Settings.setChunkSizeMB(this, size);
                            Toast.makeText(this, getString(R.string.chunk_size) + ": " + size + " MB", Toast.LENGTH_SHORT).show();
                        } else {
                            Settings.setChunkSizeMB(this, 0);
                            Toast.makeText(this, getString(R.string.chunk_size) + ": " + getString(R.string.chunk_auto), Toast.LENGTH_SHORT).show();
                        }
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "Invalid manual chunk size value: " + val, e);
                        Toast.makeText(this, R.string.chunk_invalid_input, Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void shareRootFolder() {
        String accessToken = Settings.getAccessToken(this);
        if (accessToken == null || accessToken.isEmpty()) {
            Toast.makeText(this, R.string.not_connected_drive, Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, R.string.generating_link, Toast.LENGTH_SHORT).show();

        backgroundExecutor.execute(() -> {
            try {
                GoogleDriveClient client = new GoogleDriveClient(accessToken);
                String link = client.shareBaseFolder();

                runOnUiThread(() -> {
                    if (link != null) {
                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType("text/plain");
                        shareIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.shared_folder_subject));
                        shareIntent.putExtra(Intent.EXTRA_TEXT, getString(R.string.shared_folder_text, link));
                        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_folder_link)));
                    } else {
                        Toast.makeText(MainActivity.this, R.string.failed_get_link, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to share root folder", e);
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, R.string.failed_get_link, Toast.LENGTH_SHORT).show());
            }
        });
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
                target = btnShareRoot;
                message = getText(R.string.tutorial_step_share);
                break;
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

        permissionsManager.requestAllPermissions((allGranted, accessToken) -> {
            if (allGranted && accessToken != null) {
                Log.i(TAG, "All permissions granted and access token obtained.");
                Settings.setAccessToken(this, accessToken);
                errorContainer.setVisibility(View.GONE);
                tryStartPreview();
                startSafeRecService(SafeRecService.CMD_UPLOAD_PENDING);

                String command = getCommand();
                boolean fromTile = getIntent().getBooleanExtra(SafeRecService.EXTRA_FROM_TILE, false);
                boolean fromNotification = getIntent().getBooleanExtra(SafeRecService.EXTRA_FROM_NOTIFICATION, false);
                boolean autoStart = Settings.isAutoStartOnLaunch(this);

                Log.i(TAG, "Launch info: command=" + command + ", fromTile=" + fromTile + ", fromNotification="
                        + fromNotification + ", autoStart=" + autoStart);

                if (SafeRecService.CMD_START.equals(command)) {
                    if ((fromTile || fromNotification) && !autoStart) {
                        Log.i(TAG, "Ignoring CMD_START from tile/notification because autoStart is disabled");
                        command = null;
                    }
                } else if (fromNotification && autoStart) {
                    if (!isStarted(LiveData.getInstance().getStatus().getValue())) {
                        Log.i(TAG, "Auto-starting recording from notification launch");
                        command = SafeRecService.CMD_START;
                    } else {
                        Log.i(TAG, "Already recording, ignoring auto-start from notification launch");
                    }
                }

                if (command != null) {
                    startSafeRecService(command);
                }
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

    private void tryStartPreview() {
        stopPreview(false);
        if (Settings.onlyAudio(this) || !previewSurfaceAvailable) {
            return;
        }
        previewRecorder = VideoStreamRecorder.getInstance();
        if (isStarted(LiveData.getInstance().getStatus().getValue())) {
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
            if (isStarted(LiveData.getInstance().getStatus().getValue())) {
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
    protected void onResume() {
        super.onResume();
        videoPreview.post(this::tryStartPreview);
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
        startForegroundService(intent);
    }

    private void stopRecording() {
        Log.i(TAG, "Stopping recording");
        updateStartButtonState(null);
        Intent intent = SafeRecService.createCommandIntent(this, SafeRecService.CMD_STOP);
        startForegroundService(intent);
    }

    @Override
    protected void onDestroy() {
        backgroundExecutor.shutdownNow();
        super.onDestroy();
    }
}
