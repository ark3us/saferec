package net.ark3us.saferec.net;

import static net.ark3us.saferec.net.SessionSummary.Status.COMPLETE;
import static net.ark3us.saferec.net.SessionSummary.Status.INCOMPLETE;
import static net.ark3us.saferec.net.SessionSummary.Status.NOT_CHECKED;
import static net.ark3us.saferec.net.SessionSummary.Status.UPLOADING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.api.services.drive.model.File;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SessionSummaryTest {
    private static final String SESSION = "1000000";
    private static int nextId = 0;

    private static DriveRecordings.RecordingItem item(String session, String type, String name, Long duration) {
        File file = new File().setId("id" + (nextId++)).setName(name).setSize(100L)
                .setParents(Arrays.asList("folder-" + session + "-" + type))
                .setThumbnailLink("thumb-" + name);
        if (duration != null) {
            file.setVideoMediaMetadata(new File.VideoMediaMetadata().setDurationMillis(duration));
        }
        return new DriveRecordings.RecordingItem(MediaFile.fromDriveData(session, type, name), file);
    }

    private static DriveRecordings.RecordingItem piece(int seq) {
        return piece(seq, seq * 10_000L, 10_000L);
    }

    private static DriveRecordings.RecordingItem piece(int seq, long start, Long duration) {
        return item(SESSION, "video", start + "." + seq + ".mp4", duration);
    }

    private static DriveRecordings.RecordingItem withManifest(SessionManifest.Gaps gaps) {
        DriveRecordings.RecordingItem manifest = item(SESSION, "video", SESSION + ".0.manifest", null);
        manifest.gaps = gaps;
        return manifest;
    }

    private static SessionManifest.Gaps gaps(List<Integer> onPhone, List<Integer> lost) {
        SessionManifest.Gaps gaps = new SessionManifest.Gaps();
        gaps.onPhone.addAll(onPhone);
        gaps.lost.addAll(lost);
        return gaps;
    }

    private static SessionManifest.Gaps noGaps() {
        return gaps(ints(), ints());
    }

    private static List<Integer> ints(Integer... values) {
        return Arrays.asList(values);
    }

    private static SessionSummary summary(DriveRecordings.RecordingItem manifest, DriveRecordings.RecordingItem... pieces) {
        List<DriveRecordings.RecordingItem> items = new ArrayList<>(Arrays.asList(pieces));
        if (manifest != null) {
            items.add(manifest);
        }
        List<SessionSummary> sessions = SessionSummary.group(items);
        assertEquals(1, sessions.size());
        return sessions.get(0);
    }

    @Test
    public void completeWhenTheManifestHasNoGap() {
        assertEquals(COMPLETE, summary(withManifest(noGaps()), piece(0), piece(1)).status);
    }

    @Test
    public void uploadingWhenPiecesWaitOnThisPhone() {
        SessionSummary s = summary(withManifest(gaps(ints(2), ints())), piece(0), piece(1));
        assertEquals(UPLOADING, s.status);
        assertEquals(ints(2), s.onPhone);
    }

    @Test
    public void incompleteWhenPiecesAreLost() {
        SessionSummary s = summary(withManifest(gaps(ints(2), ints(1))), piece(0));
        assertEquals(INCOMPLETE, s.status);
        assertEquals(ints(1), s.lost);
    }

    @Test
    public void notCheckedWithoutAManifest() {
        assertEquals(NOT_CHECKED, summary(null, piece(0)).status);
    }

    @Test
    public void notCheckedWhenTheManifestCannotBeRead() {
        SessionSummary s = summary(withManifest(null), piece(0));
        assertEquals(NOT_CHECKED, s.status);
        assertTrue(s.manifestUnreadable);
    }

    @Test
    public void noManifestIsNotAnUnreadableOne() {
        SessionSummary s = summary(null, piece(0));
        assertEquals(NOT_CHECKED, s.status);
        assertFalse(s.manifestUnreadable);
    }

    @Test
    public void anEmptyManifestIsNeverComplete() {
        // Its header only: it lists no chunk, so it proves nothing
        String empty = "# SafeRec session " + SESSION + ", video\n";
        SessionSummary s = summary(withManifest(SessionManifest.gaps(empty, ints(0), ints())), piece(0));
        assertEquals(NOT_CHECKED, s.status);
        assertTrue(s.manifestUnreadable);
        assertEquals(NOT_CHECKED, summary(withManifest(SessionManifest.gaps("", ints(0), ints())), piece(0)).status);
    }

    @Test
    public void aReadManifestIsNotUnreadable() {
        assertFalse(summary(withManifest(noGaps()), piece(0)).manifestUnreadable);
    }

    @Test
    public void durationFromStartsAndTheLastPiece() {
        assertEquals(25_000, summary(withManifest(noGaps()), piece(0, 0, 10_000L),
                piece(1, 10_000, 10_000L), piece(2, 20_000, 5_000L)).durationMillis());
    }

    @Test
    public void unknownLastDurationCountsTenSeconds() {
        assertEquals(20_000, summary(null, piece(0, 0, null), piece(1, 10_000, null)).durationMillis());
    }

    @Test
    public void noPieceNoDuration() {
        assertEquals(0, summary(withManifest(gaps(ints(0), ints()))).durationMillis());
    }

    @Test
    public void thumbnailIsTheFirstPiecesThatHasOne() {
        DriveRecordings.RecordingItem first = piece(0);
        first.driveFile.setThumbnailLink(null);
        assertEquals("thumb-10000.1.mp4", summary(null, first, piece(1)).thumbnailLink());
        assertNull(summary(null, first).thumbnailLink());
    }

    @Test
    public void piecesCarryTheirTechnicalFiles() {
        SessionSummary s = summary(null, piece(0),
                item(SESSION, "video", "0.0.tsa", null),
                item(SESSION, "video", "0.0.cut", null),
                item(SESSION, "video", "0.0.cut.tsa", null));
        SessionSummary.Piece p = s.pieces.get(0);
        assertEquals(1, s.pieces.size());
        assertEquals(10_000L, (long) p.durationMillis);
        assertEquals(100, p.size);
        assertTrue(p.timestampFileId != null && p.cutFileId != null && p.cutTimestampFileId != null);
    }

    @Test
    public void allFilesIncludesTechnical() {
        DriveRecordings.RecordingItem manifest = withManifest(noGaps());
        SessionSummary s = summary(manifest, piece(0),
                item(SESSION, "video", "0.0.tsa", null),
                item(SESSION, "video", "0.0.cut", null),
                item(SESSION, "video", "0.0.cut.tsa", null),
                item(SESSION, "video", SESSION + ".0.manifest.tsa", null));
        assertEquals(6, s.allFileIds.size());
        assertEquals(6, s.files.size());
        assertEquals(1, s.pieces.size());
        assertEquals(ints(), s.onPhone);
    }

    @Test
    public void foldersAreTheDistinctParents() {
        SessionSummary s = summary(withManifest(noGaps()), piece(0), piece(1));
        assertEquals(Arrays.asList("folder-" + SESSION + "-video"), s.folderIds);
    }

    @Test
    public void aSessionWithOnlyAManifestIsListed() {
        SessionSummary s = summary(withManifest(gaps(ints(0), ints())));
        assertEquals(UPLOADING, s.status);
        assertTrue(s.pieces.isEmpty());
    }

    @Test
    public void newestSessionFirst() {
        List<SessionSummary> sessions = SessionSummary.group(Arrays.asList(
                item("1000", "video", "0.0.mp4", null), item("2000", "video", "0.0.mp4", null)));
        assertEquals("2000", sessions.get(0).sessionId);
        assertEquals(2000, sessions.get(0).startMillis);
        assertEquals("1000", sessions.get(1).sessionId);
    }

    @Test
    public void videoAndAudioOfOneSessionAreTwoSummaries() {
        assertEquals(2, SessionSummary.group(Arrays.asList(
                item("1000", "video", "0.0.mp4", null), item("1000", "audio", "0.0.mp4", null))).size());
    }
}
