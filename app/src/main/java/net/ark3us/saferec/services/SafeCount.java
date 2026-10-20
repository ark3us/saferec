package net.ark3us.saferec.services;

import androidx.annotation.Nullable;

import java.io.File;

import net.ark3us.saferec.net.MediaFile;

/**
 * Which files count as recording pieces: "N pieces safe on Drive" while recording, and
 * "N pieces waiting on this phone" on the viewfinder. A piece is a video or audio chunk (.mp4).
 * Its timestamp (.tsa), the manifest copy and a cut original (.cut, .cut.tsa) travel with the
 * pieces but are not pieces: counting them showed "2 pieces waiting" with one piece recorded.
 */
public final class SafeCount {
    private SafeCount() { }

    /** True for a video or audio chunk (.mp4), of any session. */
    public static boolean isPiece(@Nullable MediaFile file) {
        return file != null && "mp4".equalsIgnoreCase(file.getExtension());
    }

    /** True for a video or audio chunk (.mp4) of the given session. */
    static boolean isSessionChunk(@Nullable MediaFile file, @Nullable String sessionId) {
        return isPiece(file) && sessionId != null && sessionId.equals(file.sessionId);
    }

    /** The pieces among these outbox file names (File.list(), null for a missing directory). */
    public static int pieces(@Nullable String[] names) {
        if (names == null) {
            return 0;
        }
        int count = 0;
        for (String name : names) {
            if (isPiece(MediaFile.fromFile(new File(name)))) {
                count++;
            }
        }
        return count;
    }
}
