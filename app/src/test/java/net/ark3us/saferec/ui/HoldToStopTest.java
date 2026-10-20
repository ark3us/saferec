package net.ark3us.saferec.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HoldToStopTest {
    private final HoldToStop h = new HoldToStop();

    @Test public void releasedEarlyNeverStops() { h.down(0); assertFalse(h.consumeStop(999)); h.cancel(); assertFalse(h.consumeStop(5000)); }
    @Test public void heldOneSecondStopsOnce() { h.down(0); assertTrue(h.consumeStop(1000)); assertFalse(h.consumeStop(1500)); }
    @Test public void progressFillsAndResets() { h.down(100); assertEquals(0.5f, h.progress(600), 1e-6); h.cancel(); assertEquals(0f, h.progress(600), 0f); }
    @Test public void aNewHoldStartsFromZero() { h.down(0); h.consumeStop(1000); h.cancel(); h.down(2000); assertFalse(h.consumeStop(2999)); assertTrue(h.consumeStop(3000)); }
}
