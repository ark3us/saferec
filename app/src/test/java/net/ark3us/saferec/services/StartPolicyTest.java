package net.ark3us.saferec.services;

import static net.ark3us.saferec.services.StartPolicy.Action.IGNORE;
import static net.ark3us.saferec.services.StartPolicy.Action.RESTART;
import static net.ark3us.saferec.services.StartPolicy.Action.START;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StartPolicyTest {
    @Test public void startWhileIdleStarts() { assertEquals(START, StartPolicy.forCommand(SafeRecService.CMD_START, false)); }
    @Test public void startWhileRecordingIsIgnored() { assertEquals(IGNORE, StartPolicy.forCommand(SafeRecService.CMD_START, true)); }
    @Test public void switchWhileRecordingRestarts() { assertEquals(RESTART, StartPolicy.forCommand(SafeRecService.CMD_SWITCH_CAMERA, true)); }
    @Test public void switchWhileIdleIsIgnored() { assertEquals(IGNORE, StartPolicy.forCommand(SafeRecService.CMD_SWITCH_CAMERA, false)); }
}
