package net.ark3us.saferec.guard;

/**
 * N screen on/off changes within {@link #spanMs(int)} are the power key pressed N times.
 * Pure rule, fed with a monotonic clock: it never looks at time itself.
 */
final class TriggerRule {
    static final long COOLDOWN_MS = 5000;

    // The newest change last; enough for the largest count the user can pick
    private final long[] last = new long[Guard.MAX_PRESSES];
    private int count;
    private boolean triggered;
    private long lastTriggerMs;

    /**
     * 2.5 s for three changes, one more second for each further one. A change that turns the
     * screen off arrives after the screen-off animation (up to 0.67 s on a Pixel), and the last
     * change of an even count is one.
     */
    static long spanMs(int presses) {
        return 2500 + 1000L * (presses - 3);
    }

    /**
     * True when this change and the presses - 1 before it fall within the span, outside the
     * cooldown. The count may change between calls: the changes already seen are kept.
     */
    boolean onScreenChange(long elapsedMs, int presses) {
        System.arraycopy(last, 1, last, 0, last.length - 1);
        last[last.length - 1] = elapsedMs;
        if (count < last.length) count++;
        if (count < presses || elapsedMs - last[last.length - presses] > spanMs(presses)) return false;
        if (triggered && elapsedMs - lastTriggerMs < COOLDOWN_MS) return false;
        triggered = true;
        lastTriggerMs = elapsedMs;
        return true;
    }
}
