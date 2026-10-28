package net.ark3us.saferec.guard;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GuardPressesTest {

    @Test public void theDefaultIsFour() {
        assertEquals(4, Guard.DEFAULT_PRESSES);
    }

    @Test public void threeToFiveAreKept() {
        assertEquals(3, Guard.validPresses(3));
        assertEquals(4, Guard.validPresses(4));
        assertEquals(5, Guard.validPresses(5));
    }

    @Test public void anythingElseIsTheDefault() {
        assertEquals(Guard.DEFAULT_PRESSES, Guard.validPresses(2));
        assertEquals(Guard.DEFAULT_PRESSES, Guard.validPresses(6));
        assertEquals(Guard.DEFAULT_PRESSES, Guard.validPresses(0));
    }
}
