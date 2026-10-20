package net.ark3us.saferec.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class TrashedSessionsTest {
    @Before
    public void setUp() {
        TrashedSessions.clear();
    }

    @Test
    public void hiddenRightAfterHide() {
        TrashedSessions.hide("1", "video", 1000);
        assertTrue(TrashedSessions.isHidden("1", "video", 1001));
    }

    @Test
    public void otherDataTypeStaysVisible() {
        TrashedSessions.hide("1", "video", 1000);
        assertFalse(TrashedSessions.isHidden("1", "audio", 1001));
    }

    @Test
    public void visibleAfterTheWindow() {
        TrashedSessions.hide("1", "video", 1000);
        assertTrue(TrashedSessions.isHidden("1", "video", 1000 + TrashedSessions.HIDE_MS - 1));
        assertFalse(TrashedSessions.isHidden("1", "video", 1000 + TrashedSessions.HIDE_MS));
    }

    @Test
    public void neverHiddenIsVisible() {
        assertFalse(TrashedSessions.isHidden("2", "video", 1000));
    }
}
