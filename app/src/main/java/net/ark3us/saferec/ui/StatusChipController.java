package net.ark3us.saferec.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.google.android.material.chip.Chip;

import net.ark3us.saferec.BuildConfig;
import net.ark3us.saferec.R;
import net.ark3us.saferec.data.ServiceState;
import net.ark3us.saferec.misc.Settings;

/**
 * The viewfinder's status chip: the state StatusModel picks, drawn with its icon and tint, and
 * what a tap on it does. Main thread.
 */
public final class StatusChipController {
    public interface Actions {
        void connectDrive();

        void openSettings();

        /** Waiting or offline: say that recordings wait on the phone. */
        void explainWaiting();
    }

    /** GuardService reports running only once it has started: read it again after this. */
    private static final long GUARD_RECHECK_MS = 1000;

    private final Chip chip;
    private final Actions actions;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable guardRecheck = () -> {
        guardStarting = false;
        render();
    };
    private StatusModel.Kind kind = StatusModel.Kind.HIDDEN;
    private boolean guardStarting = false;
    private boolean recording = false;
    private boolean online = false;
    private int pendingFiles = 0;

    public StatusChipController(Chip chip, Actions actions) {
        this.chip = chip;
        this.actions = actions;
        chip.setOnClickListener(v -> onClick());
    }

    /** Draws the chip for these inputs; the token, the auth flag and the guard are read here. */
    public void render(boolean recording, boolean online, int pendingFiles) {
        this.recording = recording;
        this.online = online;
        this.pendingFiles = pendingFiles;
        render();
    }

    /**
     * Call after Guard.ensureRunning(): a stopped guard is not reported for a second, or the chip
     * would flash "Three presses off" at every launch.
     */
    public void guardStarting() {
        guardStarting = true;
        handler.removeCallbacks(guardRecheck);
        handler.postDelayed(guardRecheck, GUARD_RECHECK_MS);
    }

    public void release() {
        handler.removeCallbacks(guardRecheck);
    }

    private void render() {
        Context context = chip.getContext();
        String token = Settings.getAccessToken(context);
        boolean hasToken = token != null && !token.isEmpty();
        boolean authProblem = Boolean.TRUE.equals(ServiceState.getInstance().getDriveAuthProblem().getValue());
        kind = StatusModel.kindOf(recording, hasToken, authProblem, online, pendingFiles, threePressesOff(context));
        bind(context);
    }

    /**
     * The guard is on but not running. The body sits inside the constant check, so a build
     * without the guard module never reaches its classes.
     */
    private boolean threePressesOff(Context context) {
        if (BuildConfig.POWER_GESTURE) {
            net.ark3us.saferec.guard.Guard.Status status = net.ark3us.saferec.guard.Guard.status(context);
            return net.ark3us.saferec.guard.Guard.isEnabled(context)
                    && status != net.ark3us.saferec.guard.Guard.Status.ACTIVE
                    && !(guardStarting && status == net.ark3us.saferec.guard.Guard.Status.STOPPED);
        }
        return false;
    }

    /** Green means safe, orange asks the user to act. */
    private void bind(Context context) {
        if (kind == StatusModel.Kind.HIDDEN) {
            chip.setVisibility(View.GONE);
            return;
        }
        int icon;
        CharSequence text;
        switch (kind) {
            case DRIVE_OK:
                icon = R.drawable.ms_cloud_done;
                text = context.getString(R.string.status_drive_ok);
                break;
            case WAITING:
                icon = online ? R.drawable.ms_cloud_upload : R.drawable.ms_cloud_off;
                text = context.getResources().getQuantityString(R.plurals.status_waiting, pendingFiles, pendingFiles);
                break;
            case OFFLINE:
                icon = R.drawable.ms_cloud_off;
                text = context.getString(R.string.status_offline);
                break;
            case CONNECT_DRIVE:
                icon = R.drawable.ms_add_to_drive;
                text = context.getString(R.string.status_connect_drive);
                break;
            case RECONNECT_DRIVE:
                icon = R.drawable.ms_sync_problem;
                text = context.getString(R.string.status_reconnect_drive);
                break;
            case THREE_PRESSES_OFF:
            default:
                icon = R.drawable.ms_power_settings_new;
                text = context.getString(R.string.status_three_presses_off);
                break;
        }
        int tint = kind == StatusModel.Kind.DRIVE_OK ? R.color.safe_green
                : StatusModel.isAttention(kind) ? R.color.attention_orange : R.color.on_glass;
        chip.setChipIconResource(icon);
        chip.setChipIconTint(ColorStateList.valueOf(ContextCompat.getColor(context, tint)));
        chip.setText(text);
        // Drive connected: nothing to do, so no ripple and no "button" for TalkBack
        chip.setClickable(kind != StatusModel.Kind.DRIVE_OK);
        chip.setVisibility(View.VISIBLE);
    }

    private void onClick() {
        switch (kind) {
            case CONNECT_DRIVE:
            case RECONNECT_DRIVE:
                actions.connectDrive();
                break;
            case THREE_PRESSES_OFF:
                actions.openSettings();
                break;
            case WAITING:
            case OFFLINE:
                actions.explainWaiting();
                break;
            default:
                break; // Drive connected: nothing to do
        }
    }
}
