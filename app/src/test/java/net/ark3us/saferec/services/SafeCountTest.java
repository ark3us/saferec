package net.ark3us.saferec.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;

import net.ark3us.saferec.net.MediaFile;

import org.junit.Test;

public class SafeCountTest {
    private static MediaFile file(String name) {
        return MediaFile.fromFile(new File(name));
    }

    @Test
    public void aChunkOfTheSessionCounts() {
        assertTrue(SafeCount.isSessionChunk(file("1_video_2.0.mp4"), "1"));
    }

    @Test
    public void aTimestampDoesNotCount() {
        assertFalse(SafeCount.isSessionChunk(file("1_video_2.0.tsa"), "1"));
    }

    @Test
    public void anotherSessionDoesNotCount() {
        assertFalse(SafeCount.isSessionChunk(file("9_video_2.0.mp4"), "1"));
    }

    @Test
    public void unknownFilesDoNotCount() {
        assertFalse(SafeCount.isSessionChunk(null, "1"));
        assertFalse(SafeCount.isSessionChunk(file("1_video_2.0.mp4"), null));
    }

    @Test
    public void videoAndAudioChunksArePieces() {
        assertTrue(SafeCount.isPiece(file("1_video_2.0.mp4")));
        assertTrue(SafeCount.isPiece(file("1_audio_2.3.mp4")));
    }

    @Test
    public void timestampsManifestsAndCutOriginalsAreNotPieces() {
        assertFalse(SafeCount.isPiece(file("1_video_2.0.tsa")));
        assertFalse(SafeCount.isPiece(file("1_video_2.0.cut")));
        assertFalse(SafeCount.isPiece(file("1_video_2.0.cut.tsa")));
        assertFalse(SafeCount.isPiece(file("1_video_1.0.manifest")));
        assertFalse(SafeCount.isPiece(file("1_video_1.0.manifest.tsa")));
        assertFalse(SafeCount.isPiece(null));
    }

    @Test
    public void piecesCountsOnlyTheChunksOfAnOutbox() {
        String[] outbox = {
                "1_video_2.0.mp4", "1_video_2.0.tsa", "1_video_1.0.manifest",
                "1_video_3.1.mp4", "1_video_3.1.cut", "1_video_3.1.cut.tsa",
                "7_audio_8.0.mp4", "notes.txt"
        };
        assertEquals(3, SafeCount.pieces(outbox));
    }

    @Test
    public void anOutboxWithoutPiecesCountsNone() {
        assertEquals(0, SafeCount.pieces(new String[] {"1_video_1.0.manifest", "1_video_2.0.tsa"}));
        assertEquals(0, SafeCount.pieces(new String[0]));
        assertEquals(0, SafeCount.pieces(null)); // File.list() of a missing directory
    }
}
