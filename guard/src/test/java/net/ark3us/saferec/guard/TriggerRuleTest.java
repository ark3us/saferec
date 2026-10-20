package net.ark3us.saferec.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TriggerRuleTest {

    private static boolean[] feed(TriggerRule rule, long... times) {
        boolean[] out = new boolean[times.length];
        for (int i = 0; i < times.length; i++) out[i] = rule.onScreenChange(times[i]);
        return out;
    }

    private static void expect(boolean[] actual, boolean... expected) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("call " + i, expected[i], actual[i]);
        }
    }

    @Test public void threeChangesWithinTheSpanTrigger() {
        expect(feed(new TriggerRule(), 0, 740, 830), false, false, true);
    }

    @Test public void slowChangesDoNotTrigger() {
        expect(feed(new TriggerRule(), 0, 1500, 3000), false, false, false);
    }

    @Test public void aDoublePressNeverTriggers() {
        expect(feed(new TriggerRule(), 0, 60), false, false);
    }

    @Test public void aBurstTriggersOnce() {
        expect(feed(new TriggerRule(), 0, 100, 200, 300, 400), false, false, true, false, false);
    }

    @Test public void afterTheCooldownItTriggersAgain() {
        TriggerRule rule = new TriggerRule();
        feed(rule, 0, 100, 200, 300, 400);
        expect(feed(rule, 6000, 6100, 6200), false, false, true);
    }

    @Test public void theSpanIncludesItsEnd() {
        expect(feed(new TriggerRule(), 0, 1250, 2500), false, false, true);
        expect(feed(new TriggerRule(), 0, 1250, 2501), false, false, false);
    }

    @Test public void theCooldownEndsAtExactlyFiveSeconds() {
        TriggerRule rule = new TriggerRule();
        rule.onScreenChange(0); rule.onScreenChange(400); assertTrue(rule.onScreenChange(800));
        rule.onScreenChange(4000); rule.onScreenChange(4500);
        assertFalse(rule.onScreenChange(5799)); // 4999 ms after the trigger
        assertTrue(rule.onScreenChange(5800));  // 5000 ms after
    }
}
