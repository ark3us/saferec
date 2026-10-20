package net.ark3us.saferec.ui;

/**
 * What a touch on the record button may do, decided by the state the touch began in. A completed
 * hold stops the recording before the finger lifts, so judging the release by the state at that
 * moment turned the end of a stop into a new start. Pure Java.
 */
public final class RecordGesture {

    public enum Release { START, HINT, NOTHING }

    private enum Began { NONE, READY, RECORDING }

    private Began began = Began.NONE;

    public void down(boolean recording) {
        began = recording ? Began.RECORDING : Began.READY;
    }

    /** The touch was cancelled, or a command is on its way: its release does nothing. */
    public void cancel() {
        began = Began.NONE;
    }

    /**
     * @param recording the state now
     * @param inside    the finger lifted inside the button
     * @param holding   the hold to stop still runs: neither confirmed nor cancelled
     * @return START only for a touch that began ready and is still ready; HINT only for an early
     *         release of a hold while still recording. A touch that began recording never starts.
     */
    public Release release(boolean recording, boolean inside, boolean holding) {
        Began from = began;
        began = Began.NONE;
        if (from == Began.READY) {
            return !recording && inside ? Release.START : Release.NOTHING;
        }
        if (from == Began.RECORDING) {
            return recording && holding ? Release.HINT : Release.NOTHING;
        }
        return Release.NOTHING;
    }
}
