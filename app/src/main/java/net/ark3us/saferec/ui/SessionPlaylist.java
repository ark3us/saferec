package net.ark3us.saferec.ui;

import androidx.annotation.Nullable;

import net.ark3us.saferec.net.SessionSummary;

import java.util.ArrayList;
import java.util.List;

/** What the session player plays: the pieces on Drive, in recording order. Pure Java. */
public final class SessionPlaylist {
    private SessionPlaylist() {
    }

    /** File ids of the pieces by sequence number; a piece without a file id is skipped. */
    public static List<String> fileIds(@Nullable SessionSummary session) {
        List<String> ids = new ArrayList<>();
        if (session == null) {
            return ids;
        }
        for (SessionSummary.Piece piece : session.pieces) {
            if (piece.fileId != null && !piece.fileId.isEmpty()) {
                ids.add(piece.fileId);
            }
        }
        return ids;
    }

    /** The media content of a Drive file, read with a bearer token. */
    public static String url(String fileId) {
        return "https://www.googleapis.com/drive/v3/files/" + fileId + "?alt=media";
    }
}
