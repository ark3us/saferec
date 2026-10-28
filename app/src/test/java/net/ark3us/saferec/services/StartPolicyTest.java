package net.ark3us.saferec.services;

import static net.ark3us.saferec.services.StartPolicy.Action.IGNORE;
import static net.ark3us.saferec.services.StartPolicy.Action.RESTART;
import static net.ark3us.saferec.services.StartPolicy.Action.START;
import static net.ark3us.saferec.services.StartPolicy.Action.STOP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StartPolicyTest {
    @Test public void startWhileIdleStarts() { assertEquals(START, StartPolicy.forCommand(SafeRecService.CMD_START, false)); }
    @Test public void startWhileRecordingIsIgnored() { assertEquals(IGNORE, StartPolicy.forCommand(SafeRecService.CMD_START, true)); }
    @Test public void switchWhileRecordingRestarts() { assertEquals(RESTART, StartPolicy.forCommand(SafeRecService.CMD_SWITCH_CAMERA, true)); }
    @Test public void switchWhileIdleIsIgnored() { assertEquals(IGNORE, StartPolicy.forCommand(SafeRecService.CMD_SWITCH_CAMERA, false)); }
    @Test public void anUnknownCommandIsIgnored() {
        assertEquals(IGNORE, StartPolicy.forCommand("SOMETHING_ELSE", true));
        assertEquals(IGNORE, StartPolicy.forCommand("SOMETHING_ELSE", false));
    }
    // The power key presses toggle: the same presses again stop the recording
    @Test public void aToggleStartWhileRecordingStops() { assertEquals(STOP, StartPolicy.forCommand(SafeRecService.CMD_START, true, true)); }
    @Test public void aToggleStartWhileIdleStarts() { assertEquals(START, StartPolicy.forCommand(SafeRecService.CMD_START, false, true)); }
    @Test public void aPlainStartWhileRecordingIsStillIgnored() { assertEquals(IGNORE, StartPolicy.forCommand(SafeRecService.CMD_START, true, false)); }
    @Test public void onlyAStartToggles() {
        assertEquals(RESTART, StartPolicy.forCommand(SafeRecService.CMD_SWITCH_CAMERA, true, true));
        assertEquals(IGNORE, StartPolicy.forCommand("SOMETHING_ELSE", true, true));
    }
    @Test public void videoWithTheCamera() { assertTrue(StartPolicy.recordsVideo(true, true)); }
    @Test public void aRefusedCameraRecordsSoundOnly() { assertFalse(StartPolicy.recordsVideo(true, false)); }
    @Test public void audioOnlyStaysAudioOnly() {
        assertFalse(StartPolicy.recordsVideo(false, true));
        assertFalse(StartPolicy.recordsVideo(false, false));
    }
    @Test public void replayAfterTheGap() { assertTrue(StartPolicy.replaysStarted(5000, 4000)); }
    @Test public void noReplayRightAfterAStart() { assertFalse(StartPolicy.replaysStarted(4999, 4000)); }
    @Test public void replayWhenNothingVibratedYet() { assertTrue(StartPolicy.replaysStarted(10, 0)); }
}
