package net.ark3us.saferec.ui;

/** The rule of the stop button: a hold of {@link #HOLD_MS} stops, anything shorter does nothing. */
public final class HoldToStop {
    static final long HOLD_MS = 1000;

    private boolean held;
    private boolean consumed;
    private long downAt;

    public void down(long nowMs) {
        held = true;
        consumed = false;
        downAt = nowMs;
    }

    public void cancel() {
        held = false;
    }

    public float progress(long nowMs) {
        if (!held) return 0f;
        return Math.max(0f, Math.min(1f, (nowMs - downAt) / (float) HOLD_MS));
    }

    /** True once per hold, when it lasted at least {@link #HOLD_MS}. */
    public boolean consumeStop(long nowMs) {
        if (!held || consumed || nowMs - downAt < HOLD_MS) return false;
        consumed = true;
        return true;
    }
}
