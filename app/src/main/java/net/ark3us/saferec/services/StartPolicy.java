package net.ark3us.saferec.services;

/**
 * What a start or camera switch command does. A start while recording is ignored, so a second
 * quick start (power key, tile, shortcut) never cuts the recording in progress. Only the explicit
 * camera switch restarts it. Pure Java, so it is unit tested.
 */
public final class StartPolicy {
    public enum Action { START, RESTART, IGNORE }

    /** A quick start ignored this soon after the last "started" pulse does not vibrate again. */
    static final long REPLAY_GAP_MS = 1000;

    private StartPolicy() {}

    /** Whether an ignored quick start replays the "started" pulses. 0 means none played yet. */
    static boolean replaysStarted(long nowMs, long lastStartedPulseMs) {
        return lastStartedPulseMs == 0 || nowMs - lastStartedPulseMs >= REPLAY_GAP_MS;
    }

    public static Action forCommand(String command, boolean recording) {
        if (SafeRecService.CMD_START.equals(command)) {
            return recording ? Action.IGNORE : Action.START;
        }
        if (SafeRecService.CMD_SWITCH_CAMERA.equals(command)) {
            return recording ? Action.RESTART : Action.IGNORE;
        }
        return Action.IGNORE;
    }
}
