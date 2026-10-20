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

    /**
     * GuardService reports running only once it has started: while it starts, it is read again
     * after this delay, a few times at most, as Settings does. A single 1 s window showed "Three
     * presses off" for a guard that took longer, until something else drew the chip again.
     */
    private static final long GUARD_RECHECK_MS = 1000;
    private static final int GUARD_RECHECK_MAX = 5;

    private final Chip chip;
    private final Actions actions;
    private final Handler handler = new Handler(Looper.getMainLooper());
    // A method reference, not a lambda: its body may name the guard (see threePressesOff())
    private final Runnable guardRecheck = this::recheckGuard;
    private StatusModel.Kind kind = StatusModel.Kind.HIDDEN;
    // Re-checks left while the guard starts; a stopped guard is not reported meanwhile
    private int guardRechecksLeft = 0;
    private boolean recording = false;
    private boolean online = false;
    private int pendingPieces = 0;

    public StatusChipController(Chip chip, Actions actions) {
        this.chip = chip;
        this.actions = actions;
        chip.setOnClickListener(v -> onClick());
    }

    /** Draws the chip for these inputs; the token, the auth flag and the guard are read here. */
    public void render(boolean recording, boolean online, int pendingPieces) {
        this.recording = recording;
        this.online = online;
        this.pendingPieces = pendingPieces;
        render();
    }

    /**
     * Call after Guard.ensureRunning(): a stopped guard is read again every second, up to five
     * times, before the chip says "Three presses off", or it would flash at every launch. The
     * checks end as soon as the guard runs.
     */
    public void guardStarting() {
        guardRechecksLeft = GUARD_RECHECK_MAX;
        handler.removeCallbacks(guardRecheck);
        handler.postDelayed(guardRecheck, GUARD_RECHECK_MS);
    }

    /** On pause or destroy: no more re-checks. The next guardStarting() begins again. */
    public void release() {
        guardRechecksLeft = 0;
        handler.removeCallbacks(guardRecheck);
    }

    /** One re-check: another one follows while the guard is still stopped and checks are left. */
    private void recheckGuard() {
        guardRechecksLeft--;
        if (guardRechecksLeft > 0 && guardStopped()) {
            if (BuildConfig.POWER_GESTURE) {
                // The screen is still in the foreground, so the guard's service may start now
                net.ark3us.saferec.guard.Guard.ensureRunning(chip.getContext());
            }
            handler.postDelayed(guardRecheck, GUARD_RECHECK_MS);
        } else {
            guardRechecksLeft = 0;
        }
        render();
    }

    /** The guard is on, with every step done, but its service does not run (yet). */
    private boolean guardStopped() {
        return BuildConfig.POWER_GESTURE && GuardSetup.state(chip.getContext()) == GuardSetup.STATE_STOPPED;
    }

    private void render() {
        Context context = chip.getContext();
        String token = Settings.getAccessToken(context);
        boolean hasToken = token != null && !token.isEmpty();
        boolean authProblem = Boolean.TRUE.equals(ServiceState.getInstance().getDriveAuthProblem().getValue());
        kind = StatusModel.kindOf(recording, hasToken, authProblem, online, pendingPieces, threePressesOff(context));
        bind(context);
    }

    /**
     * The guard is on but not running, or a step is missing. A stopped guard is not reported while
     * it starts. Through GuardSetup's int states: no guard type is named here, and the guard
     * calls sit inside the constant check, so a build without the guard module never reaches them.
     */
    private boolean threePressesOff(Context context) {
        if (BuildConfig.POWER_GESTURE) {
            int state = GuardSetup.state(context);
            return state != GuardSetup.STATE_OFF && state != GuardSetup.STATE_ACTIVE
                    && !(guardRechecksLeft > 0 && state == GuardSetup.STATE_STOPPED);
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
                text = context.getResources().getQuantityString(R.plurals.status_waiting, pendingPieces, pendingPieces);
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
