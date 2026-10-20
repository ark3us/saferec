package net.ark3us.saferec.ui;

import static net.ark3us.saferec.ui.CameraGestureSetup.nextTry;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CameraGestureSetupTest {
    @Test public void firstMissingTryComesNext() {
        assertEquals(0, nextTry(new boolean[]{false, false}));
        assertEquals(1, nextTry(new boolean[]{true, false}));
    }

    @Test public void storedTriesAreSkipped() {
        assertEquals(-1, nextTry(new boolean[]{true, true}));
    }

    @Test public void aLockedOnlyGapIsFoundToo() {
        assertEquals(0, nextTry(new boolean[]{false, true}));
    }
}
