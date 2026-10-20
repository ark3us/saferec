package net.ark3us.saferec.guard;

import static net.ark3us.saferec.guard.Guard.Status.ACTIVE;
import static net.ark3us.saferec.guard.Guard.Status.DISABLED;
import static net.ark3us.saferec.guard.Guard.Status.NEEDS_BATTERY_EXEMPTION;
import static net.ark3us.saferec.guard.Guard.Status.NEEDS_OVERLAY;
import static net.ark3us.saferec.guard.Guard.Status.STOPPED;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GuardStatusTest {

    @Test public void disabledWinsOverEverything() {
        assertEquals(DISABLED, Guard.statusOf(false, false, false, false));
        assertEquals(DISABLED, Guard.statusOf(false, true, true, true));
    }

    @Test public void theOverlayComesFirst() {
        assertEquals(NEEDS_OVERLAY, Guard.statusOf(true, false, false, true));
    }

    @Test public void thenTheBatteryExemption() {
        assertEquals(NEEDS_BATTERY_EXEMPTION, Guard.statusOf(true, true, false, true));
    }

    @Test public void allGrantedButNotRunningIsStopped() {
        assertEquals(STOPPED, Guard.statusOf(true, true, true, false));
    }

    @Test public void allGrantedAndRunningIsActive() {
        assertEquals(ACTIVE, Guard.statusOf(true, true, true, true));
    }
}
