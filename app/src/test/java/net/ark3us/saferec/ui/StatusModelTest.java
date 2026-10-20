package net.ark3us.saferec.ui;

import static net.ark3us.saferec.ui.StatusModel.Kind.CONNECT_DRIVE;
import static net.ark3us.saferec.ui.StatusModel.Kind.DRIVE_OK;
import static net.ark3us.saferec.ui.StatusModel.Kind.HIDDEN;
import static net.ark3us.saferec.ui.StatusModel.Kind.OFFLINE;
import static net.ark3us.saferec.ui.StatusModel.Kind.RECONNECT_DRIVE;
import static net.ark3us.saferec.ui.StatusModel.Kind.THREE_PRESSES_OFF;
import static net.ark3us.saferec.ui.StatusModel.Kind.WAITING;
import static net.ark3us.saferec.ui.StatusModel.isAttention;
import static net.ark3us.saferec.ui.StatusModel.kindOf;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StatusModelTest {
    @Test public void noTokenAsksToConnectEvenOffline() { assertEquals(CONNECT_DRIVE, kindOf(false, false, false, false, 3, true)); }
    @Test public void authProblemAsksToReconnect() { assertEquals(RECONNECT_DRIVE, kindOf(true, true, true, true, 0, false)); }
    @Test public void threePressesOffShowsWhenIdle() { assertEquals(THREE_PRESSES_OFF, kindOf(false, true, false, true, 2, true)); }
    @Test public void threePressesOffHidesWhileRecording() { assertEquals(HIDDEN, kindOf(true, true, false, true, 0, true)); }
    @Test public void waitingWhenIdleWithFiles() { assertEquals(WAITING, kindOf(false, true, false, true, 2, false)); }
    @Test public void waitingWhileRecordingOffline() { assertEquals(WAITING, kindOf(true, true, false, false, 1, false)); }
    @Test public void noWaitingNoiseWhileRecordingOnline() { assertEquals(HIDDEN, kindOf(true, true, false, true, 4, false)); }
    @Test public void offlineWithNothingWaiting() { assertEquals(OFFLINE, kindOf(false, true, false, false, 0, false)); }
    @Test public void allSafe() { assertEquals(DRIVE_OK, kindOf(false, true, false, true, 0, false)); }
    @Test public void onlyDriveAndGuardProblemsAreAttention() {
        for (StatusModel.Kind k : StatusModel.Kind.values()) {
            assertEquals(k == RECONNECT_DRIVE || k == THREE_PRESSES_OFF, isAttention(k));
        }
    }
}
