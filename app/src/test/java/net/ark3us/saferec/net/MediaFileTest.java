package net.ark3us.saferec.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.File;

import org.junit.Test;

public class MediaFileTest {

    @Test
    public void localNameRoundTrips() {
        MediaFile original = new MediaFile("1792394615054", "video", 1792394620112L, 7);
        assertEquals("1792394615054_video_1792394620112.7.mp4", original.getFileName());

        MediaFile parsed = MediaFile.fromFile(new File("/outbox/" + original.getFileName()));
        assertNotNull(parsed);
        assertEquals("1792394615054", parsed.sessionId);
        assertEquals("video", parsed.dataType);
        assertEquals(1792394620112L, parsed.timestamp);
        assertEquals(7, parsed.sequenceNumber);
        assertEquals("video/mp4", parsed.getMimeType());
    }

    @Test
    public void timestampReplyKeepsItsExtensionAndType() {
        MediaFile tsa = MediaFile.fromFile(new File("1792394615054_audio_1792394620112.3.tsa"));
        assertNotNull(tsa);
        assertEquals("tsa", tsa.getExtension());
        assertEquals("application/timestamp-reply", tsa.getMimeType());
    }

    @Test
    public void driveNameParsesWithTheFolderNames() {
        MediaFile drive = MediaFile.fromDriveData("1792394615054", "audio", "1792394620112.12.mp4");
        assertNotNull(drive);
        assertEquals(12, drive.sequenceNumber);
        assertEquals("1792394615054_audio_1792394620112.12.mp4", drive.getFileName());
    }

    @Test
    public void foreignNamesAreRejected() {
        assertNull(MediaFile.fromFile(new File("notes.txt")));
        assertNull(MediaFile.fromFile(new File("a_b_c_d.0.mp4")));
        assertNull(MediaFile.fromFile(new File("1_video_abc.0.mp4")));
        assertNull(MediaFile.fromDriveData("1", "video", "clip.mp4"));
    }
}
