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
        return item(id, seq, null);
    }

    private static DriveRecordings.RecordingItem item(String id, int seq, Long durationMillis) {
        String name = (seq * 10_000L) + "." + seq + ".mp4";
        File file = new File().setId(id).setName(name).setSize(100L)
                .setParents(Arrays.asList("folder"));
        if (durationMillis != null) {
            file.setVideoMediaMetadata(new File.VideoMediaMetadata().setDurationMillis(durationMillis));
        }
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
    public void placeholdersAreDrivesDurationsElseAUsualChunk() {
        SessionSummary session = SessionSummary.group(Arrays.asList(
                item("a0", 0, 9_500L), item("a1", 1, null), item("a2", 2, 0L), item("a3", 3, 4_000L))).get(0);
        assertEquals(Arrays.asList(9_500L, 10_000L, 10_000L, 4_000L), SessionPlaylist.placeholderDurationsMs(session));
        assertEquals(SessionPlaylist.fileIds(session).size(), SessionPlaylist.placeholderDurationsMs(session).size());
        assertTrue(SessionPlaylist.placeholderDurationsMs(null).isEmpty());
    }

    @Test
    public void aPieceStartsWhereTheEarlierOnesEnd() {
        List<Long> durations = Arrays.asList(10_000L, 8_000L, 10_000L);
        assertEquals(0, SessionPlaylist.startOffsetMs(durations, 0));
        assertEquals(10_000, SessionPlaylist.startOffsetMs(durations, 1));
        assertEquals(18_000, SessionPlaylist.startOffsetMs(durations, 2));
    }

    @Test
    public void anOffsetOutsideThePlaylistIsClamped() {
        List<Long> durations = Arrays.asList(10_000L, 8_000L);
        assertEquals(0, SessionPlaylist.startOffsetMs(durations, -1));
        assertEquals(18_000, SessionPlaylist.startOffsetMs(durations, 5));
        assertEquals(0, SessionPlaylist.startOffsetMs(new ArrayList<>(), 0));
    }

    @Test
    public void aFailedRequestNamesItsPiece() {
        List<String> ids = Arrays.asList("a0", "a1", "a2");
        assertEquals(1, SessionPlaylist.indexOfUrl(ids, SessionPlaylist.url("a1")));
        assertEquals(-1, SessionPlaylist.indexOfUrl(ids, SessionPlaylist.url("zz")));
        assertEquals(-1, SessionPlaylist.indexOfUrl(ids, null));
    }

    @Test
    public void driveMediaUrl() {
        assertEquals("https://www.googleapis.com/drive/v3/files/x1?alt=media", SessionPlaylist.url("x1"));
    }
}
