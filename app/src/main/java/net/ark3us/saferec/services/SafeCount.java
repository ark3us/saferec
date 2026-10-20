package net.ark3us.saferec.services;

import androidx.annotation.Nullable;

import net.ark3us.saferec.net.MediaFile;

/** Which uploaded files count as "pieces safe on Drive". */
final class SafeCount {
    private SafeCount() { }

    /** True for a video or audio chunk (.mp4) of the given session. */
    static boolean isSessionChunk(@Nullable MediaFile file, @Nullable String sessionId) {
        return file != null && sessionId != null && sessionId.equals(file.sessionId)
                && "mp4".equalsIgnoreCase(file.getExtension());
    }
}
