package net.ark3us.saferec;

import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.util.Patterns;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Lifecycle;

import com.google.android.material.appbar.MaterialToolbar;

import net.ark3us.saferec.data.ServiceState;
import net.ark3us.saferec.misc.Settings;
import net.ark3us.saferec.net.DriveConnection;
import net.ark3us.saferec.net.GoogleDriveClient;
import net.ark3us.saferec.services.SafeRecService;
import net.ark3us.saferec.services.SafeRecTileService;
import net.ark3us.saferec.ui.CameraGestureSetup;
import net.ark3us.saferec.ui.GuardSetup;
import net.ark3us.saferec.ui.SettingsRows;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Settings in plain-language groups. render() rebuilds every row from the current state on
 * every onResume(): a row read once went stale (the three presses said "Stopped" while the
 * guard ran).
 */
public class SettingsActivity extends AppCompatActivity {
    private static final String TAG = SettingsActivity.class.getSimpleName();
    /** While the guard starts, the row is read again after this delay, a few times at most. */
    private static final long GUARD_RECHECK_MS = 1000;
    private static final int GUARD_RECHECK_MAX = 5;
    private static final String STATE_OVERLAY_STEP_OPENED = "overlay_step_opened";

    private LinearLayout container;
    private PermissionsManager permissionsManager;
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable guardRecheck = this::render;
    private int guardRechecks = 0;
    private boolean driveConnecting = false;
    /**
     * The three presses setup just sent the user to the overlay page: on return, the next
     * step (the battery dialog) opens by itself, once.
     */
    private boolean overlayStepOpened = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Android may recreate this screen while the user is on the overlay page
        overlayStepOpened = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_OVERLAY_STEP_OPENED);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);

        View scroll = findViewById(R.id.settings_scroll);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings_root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, 0);
            // The list scrolls under the navigation bar, and its end stays reachable
            scroll.setPadding(0, 0, 0, bars.bottom);
            return insets;
        });

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        container = findViewById(R.id.settings_container);
        // Registers activity-result launchers: must be built in onCreate
        permissionsManager = new PermissionsManager(this);

        ServiceState.getInstance().getDriveAuthProblem().observe(this, problem -> {
            // onResume() renders anyway; this catches a change while the screen shows
            if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
                render();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        guardRechecks = 0;
        boolean continueGuardSetup = overlayStepOpened;
        overlayStepOpened = false;
        render();
        if (continueGuardSetup) {
            continueGuardSetup();
        }
        CameraGestureSetup.onResume(this);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_OVERLAY_STEP_OPENED, overlayStepOpened);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(guardRecheck);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(guardRecheck);
        // Lets a running Drive check finish: stopping it would drop a token the user just gave
        backgroundExecutor.shutdown();
        super.onDestroy();
    }

    /** Clears the list and adds every group, reading each state fresh. */
    private void render() {
        if (isFinishing() || isDestroyed()) return;
        container.removeAllViews();
        renderQuickStart();
        renderEvidence();
        renderVideo();
        renderDrive();
        renderAdvanced();
        renderHelp();
    }

    /** Re-renders after a switch callback returns, not while the switch is still in it. */
    private void renderLater() {
        container.post(this::render);
    }

    // ---- Quick start ----

    private void renderQuickStart() {
        SettingsRows.header(container, R.drawable.ms_bolt, getString(R.string.settings_group_quick_start));

        renderThreePresses();

        if (CameraGesture.isAvailable(this)) {
            renderCameraGesture();
        }

        SettingsRows.row(container, 0, getString(R.string.quick_start_records),
            getString(Settings.isQuickStartAudioOnly(this) ? R.string.quick_start_audio_only : R.string.quick_start_video),
            v -> showQuickStartRecordsDialog());
        SettingsRows.row(container, 0, getString(R.string.quick_start_camera),
            getString(Settings.isQuickStartFrontCamera(this) ? R.string.quick_start_camera_front : R.string.quick_start_camera_back),
            v -> showQuickStartCameraDialog());

        SettingsRows.toggle(container, 0, getString(R.string.vibrations), getString(R.string.vibrations_summary),
            Settings.isVibrationsEnabled(this), (b, checked) -> Settings.setVibrationsEnabled(this, checked));

        SettingsRows.row(container, 0, getString(R.string.tile_title), getString(R.string.tile_summary), v -> addTile());
    }

    /**
     * The three presses row. The body sits inside the constant check, so javac leaves it out of
     * a build without the guard module (compileOnly then). Its lambdas reference no guard class:
     * javac generates them even there.
     */
    private void renderThreePresses() {
        if (BuildConfig.POWER_GESTURE) {
            net.ark3us.saferec.guard.Guard.Status status = net.ark3us.saferec.guard.Guard.status(this);
            String summary;
            boolean stepMissing = false;
            boolean notRunning = false;
            switch (status) {
                case NEEDS_OVERLAY:
                    summary = getString(R.string.three_presses_step_overlay);
                    stepMissing = true;
                    break;
                case NEEDS_BATTERY_EXEMPTION:
                    // Below Android 12 the page is the battery optimization list, with other words
                    summary = getString(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? R.string.three_presses_step_battery : R.string.three_presses_step_battery_legacy);
                    stepMissing = true;
                    break;
                case STOPPED:
                    if (guardRechecks < GUARD_RECHECK_MAX) {
                        summary = getString(R.string.three_presses_starting);
                        // The screen is in the foreground, so the guard's service may start now
                        net.ark3us.saferec.guard.Guard.ensureRunning(this);
                        guardRechecks++;
                        handler.removeCallbacks(guardRecheck);
                        handler.postDelayed(guardRecheck, GUARD_RECHECK_MS);
                    } else {
                        summary = getString(R.string.three_presses_not_running);
                        notRunning = true;
                    }
                    break;
                case ACTIVE:
                    summary = getString(R.string.three_presses_on);
                    break;
                default:
                    summary = getString(R.string.three_presses_summary_off);
                    break;
            }
            View row = SettingsRows.toggle(container, 0, getString(R.string.power_gesture), summary,
                net.ark3us.saferec.guard.Guard.isEnabled(this), (b, checked) -> setThreePresses(checked));
            // With a step missing or the guard not running, a tap on the row leads to what is
            // missing and never turns the guard off: only the switch itself does
            if (stepMissing) {
                SettingsRows.markAttention(row);
                row.setOnClickListener(v -> openGuardStep());
            } else if (notRunning) {
                SettingsRows.markAttention(row);
                row.setOnClickListener(v -> restartGuard());
            }
        }
    }

    /** Opens the next setup step, remembering when it is the overlay page (see onResume()). */
    private void openGuardStep() {
        if (BuildConfig.POWER_GESTURE) {
            overlayStepOpened = net.ark3us.saferec.guard.Guard.status(this)
                == net.ark3us.saferec.guard.Guard.Status.NEEDS_OVERLAY;
            GuardSetup.openNextStep(this);
        }
    }

    /** Back from the overlay page with the permission granted: the battery step follows at once. */
    private void continueGuardSetup() {
        if (BuildConfig.POWER_GESTURE) {
            if (net.ark3us.saferec.guard.Guard.isEnabled(this)
                    && net.ark3us.saferec.guard.Guard.status(this)
                        == net.ark3us.saferec.guard.Guard.Status.NEEDS_BATTERY_EXEMPTION) {
                GuardSetup.openNextStep(this);
            }
        }
    }

    /** The re-checks ended with the guard still stopped: try again, read the row in 1 s. */
    private void restartGuard() {
        if (BuildConfig.POWER_GESTURE) {
            net.ark3us.saferec.guard.Guard.ensureRunning(this);
            handler.removeCallbacks(guardRecheck);
            handler.postDelayed(guardRecheck, GUARD_RECHECK_MS);
        }
    }

    /**
     * The three presses switch. Its own method, not the lambda: javac generates a lambda's body
     * even inside a constant false check, and the body here references the guard module.
     */
    private void setThreePresses(boolean on) {
        if (BuildConfig.POWER_GESTURE) {
            net.ark3us.saferec.guard.Guard.setEnabled(this, on);
            if (on) {
                openGuardStep();
            }
            guardRechecks = 0;
            renderLater();
        }
    }

    private void renderCameraGesture() {
        boolean enabled = CameraGesture.isEnabled(this);
        boolean ready = enabled && CameraGestureSetup.nextTry(CameraGesture.storedTries(this)) < 0;
        String summary = getString(!enabled ? R.string.camera_gesture_summary_off
            : ready ? R.string.camera_gesture_summary_ready : R.string.camera_gesture_summary_setup);
        View row = SettingsRows.toggle(container, 0, getString(R.string.camera_gesture), summary, enabled,
            (b, checked) -> {
                CameraGesture.setEnabled(this, checked);
                if (checked) {
                    CameraGestureSetup.begin(this);
                }
                renderLater();
            });
        if (enabled && !ready) {
            SettingsRows.markAttention(row);
            row.setOnClickListener(v -> CameraGestureSetup.begin(this));
        }
    }

    private void showQuickStartRecordsDialog() {
        String[] items = {getString(R.string.quick_start_video), getString(R.string.quick_start_audio_only)};
        new AlertDialog.Builder(this)
            .setTitle(R.string.quick_start_records)
            .setSingleChoiceItems(items, Settings.isQuickStartAudioOnly(this) ? 1 : 0, (d, which) -> {
                Settings.setQuickStartAudioOnly(this, which == 1);
                d.dismiss();
                render();
            })
            .show();
    }

    private void showQuickStartCameraDialog() {
        String[] items = {getString(R.string.quick_start_camera_back), getString(R.string.quick_start_camera_front)};
        new AlertDialog.Builder(this)
            .setTitle(R.string.quick_start_camera)
            .setSingleChoiceItems(items, Settings.isQuickStartFrontCamera(this) ? 1 : 0, (d, which) -> {
                Settings.setQuickStartFrontCamera(this, which == 1);
                d.dismiss();
                render();
            })
            .show();
    }

    /** Android 13+ adds the tile from a system dialog; older phones need the steps by hand. */
    private void addTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            StatusBarManager statusBar = getSystemService(StatusBarManager.class);
            if (statusBar != null) {
                statusBar.requestAddTileService(
                    new ComponentName(this, SafeRecTileService.class),
                    getString(R.string.tile_name_start),
                    Icon.createWithResource(this, R.drawable.ic_stat_shield_record),
                    getMainExecutor(),
                    this::onTileResult);
                return;
            }
        }
        showTileSteps();
    }

    private void onTileResult(Integer result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || isFinishing() || isDestroyed()) return;
        Log.i(TAG, "Tile add request result: " + result);
        switch (result) {
            case StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED:
            case StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED:
                Toast.makeText(this, R.string.tile_added, Toast.LENGTH_SHORT).show();
                break;
            case StatusBarManager.TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND:
            case StatusBarManager.TILE_ADD_REQUEST_ERROR_BAD_COMPONENT:
            case StatusBarManager.TILE_ADD_REQUEST_ERROR_MISMATCHED_PACKAGE:
            case StatusBarManager.TILE_ADD_REQUEST_ERROR_NOT_CURRENT_USER:
            case StatusBarManager.TILE_ADD_REQUEST_ERROR_NO_STATUS_BAR_SERVICE:
            case StatusBarManager.TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS:
                showTileSteps();
                break;
            default:
                // TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED: the user said no
                break;
        }
    }

    private void showTileSteps() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.tile_title)
            .setMessage(R.string.tile_steps)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    // ---- Evidence ----

    private void renderEvidence() {
        SettingsRows.header(container, R.drawable.ms_shield_fill, getString(R.string.settings_group_evidence));
        SettingsRows.toggle(container, 0, getString(R.string.timestamping_title), getString(R.string.timestamping_summary),
            Settings.isTimestampingEnabled(this), (b, checked) -> Settings.setTimestampingEnabled(this, checked));
    }

    // ---- Video ----

    private void renderVideo() {
        SettingsRows.header(container, R.drawable.ms_videocam, getString(R.string.settings_group_video));
        SettingsRows.row(container, 0, getString(R.string.video_quality), qualityLabel(Settings.getVideoQuality(this)),
            v -> showQualityDialog());
        SettingsRows.row(container, 0, getString(R.string.video_camera),
            getString(Settings.getUseFrontCamera(this) ? R.string.quick_start_camera_front : R.string.quick_start_camera_back),
            v -> showCameraDialog());
    }

    private String qualityLabel(String quality) {
        return getString("HIGH".equals(quality) ? R.string.quality_high
            : "MEDIUM".equals(quality) ? R.string.quality_medium : R.string.quality_low);
    }

    private void showQualityDialog() {
        String[] qualities = {getString(R.string.quality_high), getString(R.string.quality_medium), getString(R.string.quality_low)};
        String[] values = {"HIGH", "MEDIUM", "LOW"};
        String current = Settings.getVideoQuality(this);
        int selected = "HIGH".equals(current) ? 0 : ("MEDIUM".equals(current) ? 1 : 2);
        new AlertDialog.Builder(this)
            .setTitle(R.string.video_quality)
            .setSingleChoiceItems(qualities, selected, (d, which) -> {
                Settings.setVideoQuality(this, values[which]);
                d.dismiss();
                render();
            })
            .show();
    }

    private void showCameraDialog() {
        String[] items = {getString(R.string.quick_start_camera_back), getString(R.string.quick_start_camera_front)};
        new AlertDialog.Builder(this)
            .setTitle(R.string.video_camera)
            .setSingleChoiceItems(items, Settings.getUseFrontCamera(this) ? 1 : 0, (d, which) -> {
                Settings.setUseFrontCamera(this, which == 1);
                d.dismiss();
                render();
            })
            .show();
    }

    // ---- Google Drive ----

    private void renderDrive() {
        SettingsRows.header(container, R.drawable.ms_cloud_done, getString(R.string.settings_group_drive));

        boolean authProblem = Boolean.TRUE.equals(ServiceState.getInstance().getDriveAuthProblem().getValue());
        String token = Settings.getAccessToken(this);
        if (authProblem) {
            View row = SettingsRows.row(container, 0, getString(R.string.drive_title),
                getString(R.string.drive_reconnect), v -> connectDrive());
            SettingsRows.markAttention(row);
        } else if (token != null && !token.isEmpty()) {
            String account = Settings.getDriveAccount(this);
            String summary = account != null && !account.isEmpty()
                ? getString(R.string.drive_connected_as, account) : getString(R.string.drive_connected);
            SettingsRows.row(container, 0, getString(R.string.drive_title), summary, null);
        } else {
            SettingsRows.row(container, 0, getString(R.string.drive_title),
                getString(R.string.drive_not_connected), v -> connectDrive());
        }

        SettingsRows.row(container, R.drawable.ms_person_add, getString(R.string.share_root_folder), null,
            v -> shareRootFolder());
    }

    /** Asks Play services for a token (with its consent screen if needed), then checks it with Drive. */
    private void connectDrive() {
        if (driveConnecting) return;
        driveConnecting = true;
        permissionsManager.connectDrive(true, (token, needsUser) -> {
            if (token == null) {
                driveConnecting = false;
                Log.i(TAG, "Drive not connected, needsUser=" + needsUser);
                if (!isFinishing() && !isDestroyed()) {
                    Toast.makeText(this, R.string.not_connected_drive, Toast.LENGTH_SHORT).show();
                    render();
                }
                return;
            }
            runInBackground(() -> {
                DriveConnection.Result result = DriveConnection.accept(getApplicationContext(), token);
                Log.i(TAG, "Drive connection: " + result);
                runOnUiThread(() -> {
                    driveConnecting = false;
                    if (result == DriveConnection.Result.VALID) {
                        uploadPending();
                    }
                    render();
                });
            });
        });
    }

    /** Uploads what waits on the phone now that Drive works. Not a recording: plain startService(). */
    private void uploadPending() {
        try {
            startService(SafeRecService.createCommandIntent(this, SafeRecService.CMD_UPLOAD_PENDING));
        } catch (IllegalStateException e) {
            // The activity went to the background meanwhile: Android refuses background starts
            Log.w(TAG, "Cannot reach the service from the background", e);
        }
    }

    private void shareRootFolder() {
        String accessToken = Settings.getAccessToken(this);
        if (accessToken == null || accessToken.isEmpty()) {
            Toast.makeText(this, R.string.not_connected_drive, Toast.LENGTH_SHORT).show();
            return;
        }
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        input.setHint(R.string.share_email_hint);
        input.setText(Settings.getShareEmail(this));

        new AlertDialog.Builder(this)
            .setTitle(R.string.share_dialog_title)
            .setMessage(R.string.share_dialog_message)
            .setView(padded(input))
            .setPositiveButton(R.string.share, (dialog, which) -> {
                String email = input.getText().toString().trim();
                if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    Toast.makeText(this, R.string.share_invalid_email, Toast.LENGTH_SHORT).show();
                    return;
                }
                Settings.setShareEmail(this, email);
                shareRecordingsWith(accessToken, email);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void shareRecordingsWith(String accessToken, String email) {
        Toast.makeText(this, R.string.sharing_recordings, Toast.LENGTH_SHORT).show();
        // Toasts after the screen may be gone: the application context outlives it
        Context app = getApplicationContext();
        runInBackground(() -> {
            String message;
            try {
                new GoogleDriveClient(accessToken).shareBaseFolderWith(email);
                message = app.getString(R.string.shared_with, email);
            } catch (Exception e) {
                Log.e(TAG, "Failed to share recordings", e);
                // drive.file only reaches files this app created: a foreign file in the folder
                // makes Drive refuse any change to the folder's sharing
                message = String.valueOf(e.getMessage()).contains("appNotAuthorizedToChild")
                        ? app.getString(R.string.share_refused_foreign_files)
                        : app.getString(R.string.share_failed, e.getMessage());
            }
            String result = message;
            handler.post(() -> Toast.makeText(app, result, Toast.LENGTH_LONG).show());
        });
    }

    // ---- Advanced ----

    private void renderAdvanced() {
        SettingsRows.header(container, R.drawable.ms_tune, getString(R.string.settings_group_advanced));
        int mb = Settings.getChunkSizeMB(this);
        String summary = mb == 0 ? getString(R.string.piece_size_auto) : getString(R.string.piece_size_mb, mb);
        SettingsRows.row(container, 0, getString(R.string.piece_size_title), summary, v -> showPieceSizeDialog());
    }

    private void showPieceSizeDialog() {
        CharSequence[] items = {getString(R.string.piece_size_auto), getString(R.string.piece_size_custom)};
        int current = Settings.getChunkSizeMB(this) == 0 ? 0 : 1;
        new AlertDialog.Builder(this)
            .setTitle(R.string.piece_size_title)
            .setSingleChoiceItems(items, current, (d, which) -> {
                d.dismiss();
                if (which == 0) {
                    Settings.setChunkSizeMB(this, 0);
                    render();
                } else {
                    showPieceSizeInputDialog();
                }
            })
            .show();
    }

    private void showPieceSizeInputDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        int current = Settings.getChunkSizeMB(this);
        if (current > 0) input.setText(String.valueOf(current));

        new AlertDialog.Builder(this)
            .setTitle(R.string.piece_size_dialog)
            .setView(padded(input))
            .setPositiveButton(android.R.string.ok, (d, which) -> {
                String value = input.getText().toString();
                if (value.isEmpty()) return;
                try {
                    Settings.setChunkSizeMB(this, Math.max(0, Integer.parseInt(value)));
                    render();
                } catch (NumberFormatException e) {
                    Toast.makeText(this, R.string.chunk_invalid_input, Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---- Help ----

    private void renderHelp() {
        SettingsRows.header(container, R.drawable.ms_help, getString(R.string.settings_group_help));
        SettingsRows.row(container, 0, getString(R.string.guide_title), null, v ->
            startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_SHOW_GUIDE, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
    }

    /** A callback can arrive after onDestroy() shut the executor down: the work is then dropped. */
    private void runInBackground(Runnable work) {
        try {
            backgroundExecutor.execute(work);
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "Settings screen gone, background work dropped", e);
        }
    }

    /** A dialog's text field, with the dialog's side margins. */
    private View padded(EditText input) {
        FrameLayout frame = new FrameLayout(this);
        int side = Math.round(24 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(side);
        params.setMarginEnd(side);
        frame.addView(input, params);
        return frame;
    }
}
