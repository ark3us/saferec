package net.ark3us.saferec.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.api.services.drive.model.File;

import net.ark3us.saferec.net.DriveRecordings;
import net.ark3us.saferec.net.MediaFile;
import net.ark3us.saferec.net.SessionSummary;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SessionPlaylistTest {
    private static final String SESSION = "1000000";

    private static DriveRecordings.RecordingItem item(String id, int seq) {
        String name = (seq * 10_000L) + "." + seq + ".mp4";
        File file = new File().setId(id).setName(name).setSize(100L)
                .setParents(Arrays.asList("folder"));
        return new DriveRecordings.RecordingItem(MediaFile.fromDriveData(SESSION, "video", name), file);
    }

    /** Pieces given by seq, in the order listed: the ids are "a" + seq. */
    private static SessionSummary summaryWithPieces(int... seqs) {
        List<DriveRecordings.RecordingItem> items = new ArrayList<>();
        for (int seq : seqs) {
            items.add(item("a" + seq, seq));
        }
        if (items.isEmpty()) {
            return null;
        }
        return SessionSummary.group(items).get(0);
    }

    @Test
    public void piecesInOrder() {
        assertEquals(Arrays.asList("a0", "a1", "a2"), SessionPlaylist.fileIds(summaryWithPieces(2, 0, 1)));
    }

    @Test
    public void aSessionWithoutPiecesHasNoPlaylist() {
        assertTrue(SessionPlaylist.fileIds(null).isEmpty());
    }

    @Test
    public void driveMediaUrl() {
        assertEquals("https://www.googleapis.com/drive/v3/files/x1?alt=media", SessionPlaylist.url("x1"));
    }
}
