package net.ark3us.saferec.ui;

import static net.ark3us.saferec.ui.RecordGesture.Release.HINT;
import static net.ark3us.saferec.ui.RecordGesture.Release.NOTHING;
import static net.ark3us.saferec.ui.RecordGesture.Release.START;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RecordGestureTest {
    private final RecordGesture g = new RecordGesture();

    @Test public void aTapWhileReadyStarts() { g.down(false); assertEquals(START, g.release(false, true, false)); }
    @Test public void aReleaseOutsideDoesNotStart() { g.down(false); assertEquals(NOTHING, g.release(false, false, false)); }
    // Seen on the S10+: the hold stopped at 1 s, the status came back before the finger lifted
    @Test public void aHoldThatStoppedNeverStartsOnRelease() { g.down(true); assertEquals(NOTHING, g.release(false, true, false)); }
    @Test public void anEarlyReleaseWhileRecordingHints() { g.down(true); assertEquals(HINT, g.release(true, true, true)); }
    @Test public void aConfirmedHoldDoesNothingMore() { g.down(true); assertEquals(NOTHING, g.release(true, true, false)); }
    @Test public void aTouchBegunReadyNeverActsOnceRecording() { g.down(false); assertEquals(NOTHING, g.release(true, true, false)); }
    @Test public void aCancelledTouchDoesNothing() { g.down(false); g.cancel(); assertEquals(NOTHING, g.release(false, true, false)); }
    @Test public void aReleaseWithoutDownDoesNothing() { assertEquals(NOTHING, g.release(false, true, false)); }
    @Test public void eachTouchIsJudgedAlone() {
        g.down(true);
        assertEquals(NOTHING, g.release(false, true, false));
        g.down(false);
        assertEquals(START, g.release(false, true, false));
    }
}
