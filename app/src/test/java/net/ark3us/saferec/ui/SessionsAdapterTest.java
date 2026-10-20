package net.ark3us.saferec.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SessionsAdapterTest {

    @Test
    public void durationUnderAnHourIsMinutesAndSeconds() {
        assertEquals("0:00", SessionsAdapter.formatDuration(0));
        assertEquals("0:59", SessionsAdapter.formatDuration(59_000));
        assertEquals("1:01", SessionsAdapter.formatDuration(61_000));
    }

    @Test
    public void durationFromAnHourHasHours() {
        assertEquals("1:01:01", SessionsAdapter.formatDuration(3_661_000));
    }
}
