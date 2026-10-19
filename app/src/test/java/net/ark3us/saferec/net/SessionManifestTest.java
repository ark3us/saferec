package net.ark3us.saferec.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import org.junit.Test;

public class SessionManifestTest {
    private static final byte[] HASH = {(byte) 0xab, 0x01};

    private static MediaFile chunk(int seq) {
        return new MediaFile("1792394615054", "video", 1792394620000L + seq, seq, "mp4");
    }

    private static String manifest(String... lines) {
        return "# SafeRec session 1792394615054, video: ...\n" + String.join("\n", lines) + "\n";
    }

    @Test
    public void linesNameTheDriveFilesWithSizeAndHash() {
        assertEquals("started 2 1792394620002.2.mp4", SessionManifest.startedLine(chunk(2)));
        assertEquals("chunk 2 1792394620002.2.mp4 1000 sha256=ab01",
                SessionManifest.chunkLine(SessionManifest.CHUNK, chunk(2), 1000, HASH));
        MediaFile original = new MediaFile("1792394615054", "video", 1792394620002L, 2, "cut");
        assertEquals("repaired 2 1792394620002.2.mp4 900 sha256=ab01 original 1792394620002.2.cut 950 sha256=ab01",
                SessionManifest.repairedLine(chunk(2), 900, HASH, original, 950, HASH));
        assertEquals("chunk 2 1792394620002.2.mp4 1000 sha256=unknown",
                SessionManifest.chunkLine(SessionManifest.CHUNK, chunk(2), 1000, null));
    }

    @Test
    public void eachChunkEndsInItsLastState() {
        Map<Integer, String> states = SessionManifest.parse(manifest(
                SessionManifest.startedLine(chunk(0)),
                SessionManifest.chunkLine(SessionManifest.CHUNK, chunk(0), 10, HASH),
                SessionManifest.startedLine(chunk(1)),
                SessionManifest.chunkLine(SessionManifest.REPAIRED, chunk(1), 10, HASH),
                SessionManifest.startedLine(chunk(1)), // a recovery done twice must not undo it
                SessionManifest.startedLine(chunk(2)),
                "unknown 3 something",
                "chunk x not-a-number"));
        assertEquals("chunk", states.get(0));
        assertEquals("repaired", states.get(1));
        assertEquals("started", states.get(2)); // never closed
        assertEquals(3, states.size());
    }

    @Test
    public void aChunkDriveLacksIsAGapOnThePhoneOrLost() {
        String text = manifest(
                SessionManifest.startedLine(chunk(0)),
                SessionManifest.chunkLine(SessionManifest.CHUNK, chunk(0), 10, HASH),
                SessionManifest.startedLine(chunk(1)),
                SessionManifest.chunkLine(SessionManifest.CHUNK, chunk(1), 10, HASH),
                SessionManifest.startedLine(chunk(2)),
                SessionManifest.startedLine(chunk(3)));
        // Drive has 0 and 2; this phone still holds 1; 3 started and vanished
        SessionManifest.Gaps gaps = SessionManifest.gaps(text, Arrays.asList(0, 2), Collections.singletonList(1));
        assertEquals(Collections.singletonList(1), gaps.onPhone);
        assertEquals(Collections.singletonList(3), gaps.lost);
    }

    @Test
    public void aCompleteSessionHasNoGap() {
        String text = manifest(SessionManifest.startedLine(chunk(0)),
                SessionManifest.chunkLine(SessionManifest.CHUNK, chunk(0), 10, HASH));
        assertTrue(SessionManifest.gaps(text, Collections.singletonList(0), Collections.emptyList()).isEmpty());
    }

    @Test
    public void theManifestAndItsTimestampAreUpdatedInPlace() {
        assertTrue(SessionManifest.isUpdatedInPlace(MediaFile.fromFile(new java.io.File(
                SessionManifest.copyName("1792394615054", "video")))));
        assertTrue(SessionManifest.isUpdatedInPlace(MediaFile.fromFile(new java.io.File(
                SessionManifest.copyName("1792394615054", "video") + ".tsa"))));
        assertTrue(!SessionManifest.isUpdatedInPlace(chunk(0)));
        assertEquals("text/plain", MediaFile.fromFile(new java.io.File(
                SessionManifest.copyName("1792394615054", "video"))).getMimeType());
    }
}
