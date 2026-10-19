package net.ark3us.saferec.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChunkPolicyTest {

    @Test
    public void autoSplitsEveryTenSecondsWhateverTheSize() {
        ChunkPolicy auto = ChunkPolicy.fromSetting(0);
        assertEquals(0, auto.maxBytes);
        assertFalse(auto.isFull(500L * 1024 * 1024, 9_999)); // a huge chunk is not full early
        assertTrue(auto.isFull(1, 10_000)); // a silent, tiny one is still cut at 10 s
    }

    @Test
    public void manualSizeSplitsBySizeOnly() {
        ChunkPolicy manual = ChunkPolicy.fromSetting(2);
        assertEquals(2L * 1024 * 1024, manual.maxBytes);
        assertEquals(0, manual.maxDurationMs);
        assertFalse(manual.isFull(2L * 1024 * 1024 - 1, 3_600_000));
        assertTrue(manual.isFull(2L * 1024 * 1024, 0));
    }

    @Test
    public void negativeSettingMeansAuto() {
        assertEquals(ChunkPolicy.DEFAULT_DURATION_MS, ChunkPolicy.fromSetting(-1).maxDurationMs);
    }
}
