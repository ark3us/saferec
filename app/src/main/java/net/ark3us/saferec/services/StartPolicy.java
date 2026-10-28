package net.ark3us.saferec.services;

/**
 * What a start or camera switch command does. A start while recording is ignored, so a second
 * quick start (tile, shortcut, camera press) never cuts the recording in progress. The power key
 * presses toggle instead: the same presses again stop it (the owner's choice). Only the explicit
 * camera switch restarts it. Pure Java, so it is unit tested.
 */
public final class StartPolicy {
    public enum Action { START, RESTART, STOP, IGNORE }

    /** A quick start ignored this soon after the last "started" pulse does not vibrate again. */
    static final long REPLAY_GAP_MS = 1000;

    private StartPolicy() {}

    /** Whether an ignored quick start replays the "started" pulses. 0 means none played yet. */
    static boolean replaysStarted(long nowMs, long lastStartedPulseMs) {
        return lastStartedPulseMs == 0 || nowMs - lastStartedPulseMs >= REPLAY_GAP_MS;
    }

    /**
     * Whether a start records video: when the start asks for it and the camera is granted. A
     * refused camera never blocks a recording: it records sound only, as when the camera is lost.
     */
    static boolean recordsVideo(boolean videoWanted, boolean cameraGranted) {
        return videoWanted && cameraGranted;
    }

    public static Action forCommand(String command, boolean recording) {
        return forCommand(command, recording, false);
    }

    /** A start that toggles (the power key presses) stops a running recording. */
    public static Action forCommand(String command, boolean recording, boolean toggles) {
        if (SafeRecService.CMD_START.equals(command)) {
            if (!recording) return Action.START;
            return toggles ? Action.STOP : Action.IGNORE;
        }
        if (SafeRecService.CMD_SWITCH_CAMERA.equals(command)) {
            return recording ? Action.RESTART : Action.IGNORE;
        }
        return Action.IGNORE;
    }
}
