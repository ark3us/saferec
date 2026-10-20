package net.ark3us.saferec.guard;

/** The three power-key presses that start a recording. */
public final class Guard {

    /** What the feature is doing, in the order the user has to fix it. */
    public enum Status { DISABLED, NEEDS_OVERLAY, NEEDS_BATTERY_EXEMPTION, STOPPED, ACTIVE }

    private Guard() {}

    public static Status statusOf(boolean enabled, boolean canDrawOverlays,
                                  boolean ignoringBatteryOptimizations, boolean running) {
        if (!enabled) return Status.DISABLED;
        if (!canDrawOverlays) return Status.NEEDS_OVERLAY;
        if (!ignoringBatteryOptimizations) return Status.NEEDS_BATTERY_EXEMPTION;
        return running ? Status.ACTIVE : Status.STOPPED;
    }
}
