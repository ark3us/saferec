package net.ark3us.saferec.ui;

/**
 * Decides what the viewfinder's status chip says. Pure Java: no Android imports.
 * The first matching rule wins.
 */
public final class StatusModel {

    public enum Kind { HIDDEN, DRIVE_OK, WAITING, OFFLINE, CONNECT_DRIVE, RECONNECT_DRIVE, THREE_PRESSES_OFF }

    private StatusModel() {}

    /**
     * @param recording       a recording is running
     * @param hasToken        a Drive token is stored
     * @param authProblem     Drive needs the user to reconnect
     * @param online          the default network is available
     * @param pendingPieces   recording pieces (chunks) waiting in the outbox
     * @param threePressesOff the power-key gesture is switched off
     */
    public static Kind kindOf(boolean recording, boolean hasToken, boolean authProblem, boolean online,
                              int pendingPieces, boolean threePressesOff) {
        if (!hasToken) return Kind.CONNECT_DRIVE;
        if (authProblem) return Kind.RECONNECT_DRIVE;
        if (threePressesOff && !recording) return Kind.THREE_PRESSES_OFF;
        // While recording online, the safe chip shows progress, so a waiting count would be noise.
        if (pendingPieces > 0 && (!online || !recording)) return Kind.WAITING;
        if (!online) return Kind.OFFLINE;
        return recording ? Kind.HIDDEN : Kind.DRIVE_OK;
    }

    /** True for the states that ask the user to act: they are drawn in attention orange. */
    public static boolean isAttention(Kind kind) {
        return kind == Kind.RECONNECT_DRIVE || kind == Kind.THREE_PRESSES_OFF;
    }
}
