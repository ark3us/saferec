package net.ark3us.saferec.guard;

/**
 * Three screen on/off changes within {@link #SPAN_MS} are the power key pressed three times.
 * Pure rule, fed with a monotonic clock: it never looks at time itself.
 */
final class TriggerRule {
    static final long SPAN_MS = 2500;
    static final long COOLDOWN_MS = 5000;

    private final long[] last = new long[3];
    private int count;
    private boolean triggered;
    private long lastTriggerMs;

    /** True when this change and the two before it fall within the span, outside the cooldown. */
    boolean onScreenChange(long elapsedMs) {
        last[0] = last[1];
        last[1] = last[2];
        last[2] = elapsedMs;
        if (count < 3) count++;
        if (count < 3 || last[2] - last[0] > SPAN_MS) return false;
        if (triggered && elapsedMs - lastTriggerMs < COOLDOWN_MS) return false;
        triggered = true;
        lastTriggerMs = elapsedMs;
        return true;
    }
}
