package net.ark3us.saferec.ui;

import androidx.annotation.Nullable;

import net.ark3us.saferec.media.ChunkPolicy;
import net.ark3us.saferec.net.SessionSummary;

import java.util.ArrayList;
import java.util.List;

/**
 * What the session player plays: the pieces on Drive, in recording order, as one video. Each
 * piece stays its own source (its own orientation); the player puts them on one timeline,
 * where a piece starts at the sum of the earlier pieces' durations. Pure Java.
 */
public final class SessionPlaylist {
    private SessionPlaylist() {
    }

    /** File ids of the pieces by sequence number; a piece without a file id is skipped. */
    public static List<String> fileIds(@Nullable SessionSummary session) {
        List<String> ids = new ArrayList<>();
        if (session == null) {
            return ids;
        }
        for (SessionSummary.Piece piece : playable(session)) {
            ids.add(piece.fileId);
        }
        return ids;
    }

    /**
     * Each piece's length until the player has read it, in the order of {@link #fileIds}:
     * Drive's duration, else a usual chunk. The player needs one for every piece to show the
     * whole session on one time bar, and replaces it with the real length once it knows it.
     */
    public static List<Long> placeholderDurationsMs(@Nullable SessionSummary session) {
        List<Long> durations = new ArrayList<>();
        if (session == null) {
            return durations;
        }
        for (SessionSummary.Piece piece : playable(session)) {
            Long known = piece.durationMillis;
            durations.add(known != null && known > 0 ? known : ChunkPolicy.DEFAULT_DURATION_MS);
        }
        return durations;
    }

    /** Where piece {@code index} starts in the session: the sum of the earlier pieces' durations. */
    public static long startOffsetMs(List<Long> durations, int index) {
        long offset = 0;
        for (int i = 0; i < Math.min(index, durations.size()); i++) {
            offset += durations.get(i);
        }
        return offset;
    }

    /** The piece a request was for, from its URL; -1 when it is none of them. */
    public static int indexOfUrl(List<String> fileIds, @Nullable String url) {
        if (url == null) {
            return -1;
        }
        for (int i = 0; i < fileIds.size(); i++) {
            if (url.equals(url(fileIds.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    /** The media content of a Drive file, read with a bearer token. */
    public static String url(String fileId) {
        return "https://www.googleapis.com/drive/v3/files/" + fileId + "?alt=media";
    }

    private static List<SessionSummary.Piece> playable(SessionSummary session) {
        List<SessionSummary.Piece> pieces = new ArrayList<>();
        for (SessionSummary.Piece piece : session.pieces) {
            if (piece.fileId != null && !piece.fileId.isEmpty()) {
                pieces.add(piece);
            }
        }
        return pieces;
    }
}
