package net.ark3us.saferec.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TriggerRuleTest {

    private static boolean[] feed(TriggerRule rule, int presses, long... times) {
        boolean[] out = new boolean[times.length];
        for (int i = 0; i < times.length; i++) out[i] = rule.onScreenChange(times[i], presses);
        return out;
    }

    private static void expect(boolean[] actual, boolean... expected) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("call " + i, expected[i], actual[i]);
        }
    }

    @Test public void threeChangesWithinTheSpanTrigger() {
        expect(feed(new TriggerRule(), 3, 0, 740, 830), false, false, true);
    }

    @Test public void slowChangesDoNotTrigger() {
        expect(feed(new TriggerRule(), 3, 0, 1500, 3000), false, false, false);
    }

    @Test public void aDoublePressNeverTriggers() {
        expect(feed(new TriggerRule(), 3, 0, 60), false, false);
    }

    @Test public void aBurstTriggersOnce() {
        expect(feed(new TriggerRule(), 3, 0, 100, 200, 300, 400), false, false, true, false, false);
    }

    @Test public void afterTheCooldownItTriggersAgain() {
        TriggerRule rule = new TriggerRule();
        feed(rule, 3, 0, 100, 200, 300, 400);
        expect(feed(rule, 3, 6000, 6100, 6200), false, false, true);
    }

    @Test public void theSpanIncludesItsEnd() {
        expect(feed(new TriggerRule(), 3, 0, 1250, 2500), false, false, true);
        expect(feed(new TriggerRule(), 3, 0, 1250, 2501), false, false, false);
    }

    @Test public void theCooldownEndsAtExactlyFiveSeconds() {
        TriggerRule rule = new TriggerRule();
        rule.onScreenChange(0, 3); rule.onScreenChange(400, 3); assertTrue(rule.onScreenChange(800, 3));
        rule.onScreenChange(4000, 3); rule.onScreenChange(4500, 3);
        assertFalse(rule.onScreenChange(5799, 3)); // 4999 ms after the trigger
        assertTrue(rule.onScreenChange(5800, 3));  // 5000 ms after
    }

    @Test public void theSpanGrowsOneSecondPerPress() {
        assertEquals(2500, TriggerRule.spanMs(3));
        assertEquals(3500, TriggerRule.spanMs(4));
        assertEquals(4500, TriggerRule.spanMs(5));
    }

    // A false start on a Pixel: the screen lit by lifting the phone, off 1.49 s later, on again
    // 15 ms after (the off arrives after the screen-off animation)
    @Test public void fourPressesIgnoreALiftThenOffAndOn() {
        expect(feed(new TriggerRule(), 4, 0, 1490, 1505), false, false, false);
    }

    // The owner's presses measured 0.53 and 0.81 s apart, and the last off 0.67 s late
    @Test public void fourPressesAtTheOwnersRhythmTrigger() {
        expect(feed(new TriggerRule(), 4, 0, 1130, 1340, 2820), false, false, false, true);
    }

    @Test public void fourPressesSpanIncludesItsEnd() {
        expect(feed(new TriggerRule(), 4, 0, 1000, 2000, 3500), false, false, false, true);
        expect(feed(new TriggerRule(), 4, 0, 1000, 2000, 3501), false, false, false, false);
    }

    @Test public void fivePressesNeedTheFifthChange() {
        expect(feed(new TriggerRule(), 5, 0, 1000, 2000, 3000, 4500), false, false, false, false, true);
        expect(feed(new TriggerRule(), 5, 0, 1000, 2000, 3000, 4501), false, false, false, false, false);
    }

    @Test public void aNewCountAppliesToTheChangesAlreadySeen() {
        TriggerRule rule = new TriggerRule();
        expect(feed(rule, 4, 0, 400), false, false);
        assertTrue(rule.onScreenChange(800, 3));
    }
}
