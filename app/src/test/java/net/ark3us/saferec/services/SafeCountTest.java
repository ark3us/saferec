package net.ark3us.saferec.services;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;

import net.ark3us.saferec.net.MediaFile;

import org.junit.Test;

public class SafeCountTest {
    @Test
    public void aChunkOfTheSessionCounts() {
        assertTrue(SafeCount.isSessionChunk(MediaFile.fromFile(new File("1_video_2.0.mp4")), "1"));
    }

    @Test
    public void aTimestampDoesNotCount() {
        assertFalse(SafeCount.isSessionChunk(MediaFile.fromFile(new File("1_video_2.0.tsa")), "1"));
    }

    @Test
    public void anotherSessionDoesNotCount() {
        assertFalse(SafeCount.isSessionChunk(MediaFile.fromFile(new File("9_video_2.0.mp4")), "1"));
    }

    @Test
    public void unknownFilesDoNotCount() {
        assertFalse(SafeCount.isSessionChunk(null, "1"));
        assertFalse(SafeCount.isSessionChunk(MediaFile.fromFile(new File("1_video_2.0.mp4")), null));
    }
}
