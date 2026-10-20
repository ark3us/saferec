package net.ark3us.saferec.ui;

import static net.ark3us.saferec.ui.FirstRunSteps.Step.ABOUT;
import static net.ark3us.saferec.ui.FirstRunSteps.Step.PERMISSIONS;
import static net.ark3us.saferec.ui.FirstRunSteps.Step.THREE_PRESSES;
import static net.ark3us.saferec.ui.FirstRunSteps.steps;
import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public class FirstRunStepsTest {
    @Test public void allThreeStepsOnAFreshPhone() { assertEquals(List.of(ABOUT, PERMISSIONS, THREE_PRESSES), steps(true, false)); }
    @Test public void noThreePressesStepWithoutTheGuard() { assertEquals(List.of(ABOUT, PERMISSIONS), steps(false, false)); }
    @Test public void grantedPermissionsAreSkipped() { assertEquals(List.of(ABOUT, THREE_PRESSES), steps(true, true)); }
}
