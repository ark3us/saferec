package net.ark3us.saferec.net;

import androidx.annotation.Nullable;

import net.ark3us.saferec.media.ChunkPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One recorded session as the screens show it: its pieces, whether it is whole, and every
 * Drive file behind it. The screens only draw what is decided here, so a session never reads
 * "Complete" without its manifest saying so.
 */
public final class SessionSummary {
    public enum Status {
        /** The manifest lists no chunk that Drive lacks. */
        COMPLETE,
        /** Chunks the manifest lists are still on this phone, waiting to upload. */
        UPLOADING,
        /** Chunks the manifest lists are on neither Drive nor this phone. */
        INCOMPLETE,
        /** No manifest, or one that cannot be read or lists nothing: nothing proves the session whole. */
        NOT_CHECKED
    }

    /** A chunk on Drive, with the technical files that belong to it. */
    public static final class Piece {
        public final int seq;
        public final long startMillis;
        @Nullable
        public final Long durationMillis;
        public final String fileId;
        @Nullable
        public final String thumbnailLink;
        @Nullable
        public final String timestampFileId;
        @Nullable
        public final String cutFileId;
        @Nullable
        public final String cutTimestampFileId;
        public final long size;

        Piece(int seq, long startMillis, @Nullable Long durationMillis, String fileId,
              @Nullable String thumbnailLink, @Nullable String timestampFileId,
              @Nullable String cutFileId, @Nullable String cutTimestampFileId, long size) {
            this.seq = seq;
            this.startMillis = startMillis;
            this.durationMillis = durationMillis;
            this.fileId = fileId;
            this.thumbnailLink = thumbnailLink;
            this.timestampFileId = timestampFileId;
            this.cutFileId = cutFileId;
            this.cutTimestampFileId = cutTimestampFileId;
            this.size = size;
        }
    }

    public final String sessionId;
    public final String dataType;
    /** The session id is the time the session started. */
    public final long startMillis;
    /** By sequence number. */
    public final List<Piece> pieces;
    public final Status status;
    public final List<Integer> onPhone;
    public final List<Integer> lost;
    /** Null when the manifest is missing, could not be read, or lists no chunk. */
    @Nullable
    public final SessionManifest.Gaps gaps;
    /**
     * A manifest is on Drive, but it could not be read (the network, an expired token) or lists
     * no chunk. Still not checked, but not because an older version made the session.
     */
    public final boolean manifestUnreadable;
    /** Pieces, timestamps, cut originals, the manifest and its timestamp: what an export downloads. */
    public final List<DriveRecordings.RecordingItem> files;
    public final List<String> allFileIds;
    /** The distinct parents of the files (the type folder), trashed only when empty; the trash climbs to the session folder. */
    public final List<String> folderIds;

    private SessionSummary(String sessionId, String dataType, List<Piece> pieces,
                           @Nullable SessionManifest.Gaps gaps, boolean manifestUnreadable,
                           List<DriveRecordings.RecordingItem> files) {
        this.sessionId = sessionId;
        this.dataType = dataType;
        this.startMillis = parseId(sessionId);
        this.pieces = Collections.unmodifiableList(pieces);
        this.gaps = gaps;
        this.status = statusOf(gaps);
        this.manifestUnreadable = manifestUnreadable;
        this.onPhone = gaps == null ? Collections.emptyList() : Collections.unmodifiableList(gaps.onPhone);
        this.lost = gaps == null ? Collections.emptyList() : Collections.unmodifiableList(gaps.lost);
        this.files = Collections.unmodifiableList(files);
        List<String> ids = new ArrayList<>();
        Set<String> folders = new LinkedHashSet<>();
        for (DriveRecordings.RecordingItem item : files) {
            ids.add(item.driveFile.getId());
            if (item.driveFile.getParents() != null) {
                folders.addAll(item.driveFile.getParents());
            }
        }
        this.allFileIds = Collections.unmodifiableList(ids);
        this.folderIds = Collections.unmodifiableList(new ArrayList<>(folders));
    }

    private static Status statusOf(@Nullable SessionManifest.Gaps gaps) {
        if (gaps == null) {
            return Status.NOT_CHECKED;
        }
        if (!gaps.lost.isEmpty()) {
            return Status.INCOMPLETE;
        }
        if (!gaps.onPhone.isEmpty()) {
            return Status.UPLOADING;
        }
        return Status.COMPLETE;
    }

    /** First piece start to last piece end; the last piece counts a chunk's usual length when Drive does not know it. */
    public long durationMillis() {
        if (pieces.isEmpty()) {
            return 0;
        }
        Piece first = pieces.get(0);
        Piece last = pieces.get(pieces.size() - 1);
        long lastLength = last.durationMillis != null ? last.durationMillis : ChunkPolicy.DEFAULT_DURATION_MS;
        return last.startMillis - first.startMillis + lastLength;
    }

    @Nullable
    public String thumbnailLink() {
        for (Piece piece : pieces) {
            if (piece.thumbnailLink != null) {
                return piece.thumbnailLink;
            }
        }
        return null;
    }

    /**
     * One summary per session and type, newest session first. A session whose manifest is on
     * Drive but none of its pieces is included: that is a session to warn about.
     */
    public static List<SessionSummary> group(List<DriveRecordings.RecordingItem> items) {
        Map<String, List<DriveRecordings.RecordingItem>> bySession = new LinkedHashMap<>();
        for (DriveRecordings.RecordingItem item : items) {
            bySession.computeIfAbsent(item.mediaFile.sessionId + "|" + item.mediaFile.dataType,
                    k -> new ArrayList<>()).add(item);
        }
        List<SessionSummary> sessions = new ArrayList<>();
        for (List<DriveRecordings.RecordingItem> files : bySession.values()) {
            sessions.add(build(files));
        }
        sessions.sort(Comparator.comparingLong((SessionSummary s) -> s.startMillis).reversed()
                .thenComparing(s -> s.sessionId, Comparator.reverseOrder())
                .thenComparing(s -> s.dataType));
        return sessions;
    }

    private static SessionSummary build(List<DriveRecordings.RecordingItem> files) {
        MediaFile any = files.get(0).mediaFile;
        SessionManifest.Gaps gaps = null;
        boolean manifest = false;
        Map<Integer, DriveRecordings.RecordingItem> chunks = new TreeMap<>();
        Map<Integer, String> timestamps = new TreeMap<>();
        Map<Integer, String> cuts = new TreeMap<>();
        Map<Integer, String> cutTimestamps = new TreeMap<>();
        for (DriveRecordings.RecordingItem item : files) {
            int seq = item.mediaFile.sequenceNumber;
            String extension = item.mediaFile.getExtension().toLowerCase(java.util.Locale.ROOT);
            String id = item.driveFile.getId();
            switch (extension) {
                case "mp4":
                    chunks.putIfAbsent(seq, item);
                    break;
                case "tsa":
                    timestamps.putIfAbsent(seq, id);
                    break;
                case "cut":
                    cuts.putIfAbsent(seq, id);
                    break;
                case "cut.tsa":
                    cutTimestamps.putIfAbsent(seq, id);
                    break;
                case SessionManifest.EXTENSION:
                    manifest = true;
                    if (item.gaps != null) {
                        gaps = item.gaps;
                    }
                    break;
                default:
                    break;
            }
        }
        List<Piece> pieces = new ArrayList<>();
        for (Map.Entry<Integer, DriveRecordings.RecordingItem> entry : chunks.entrySet()) {
            int seq = entry.getKey();
            com.google.api.services.drive.model.File file = entry.getValue().driveFile;
            Long duration = file.getVideoMediaMetadata() != null
                    ? file.getVideoMediaMetadata().getDurationMillis() : null;
            pieces.add(new Piece(seq, entry.getValue().mediaFile.timestamp, duration, file.getId(),
                    file.getThumbnailLink(), timestamps.get(seq), cuts.get(seq), cutTimestamps.get(seq),
                    file.getSize() != null ? file.getSize() : 0));
        }
        return new SessionSummary(any.sessionId, any.dataType, pieces, gaps, manifest && gaps == null,
                new ArrayList<>(files));
    }

    private static long parseId(String sessionId) {
        try {
            return Long.parseLong(sessionId);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
