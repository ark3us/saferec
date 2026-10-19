package net.ark3us.saferec.media;

/**
 * When a chunk is full. A chunk is only safe once closed and uploaded, so its length is the most
 * media that can be lost if the phone is taken or destroyed. Pure Java, so it is unit tested.
 */
public final class ChunkPolicy {
    public static final long DEFAULT_DURATION_MS = 10_000;

    public final long maxBytes; // 0 = no size limit
    public final long maxDurationMs; // 0 = no duration limit

    private ChunkPolicy(long maxBytes, long maxDurationMs) {
        this.maxBytes = maxBytes;
        this.maxDurationMs = maxDurationMs;
    }

    /**
     * A manual size (MB > 0) splits by size only. Auto splits every 10 s: by time, not bytes,
     * because a silent or static scene compresses well and would stretch a size-based chunk.
     */
    public static ChunkPolicy fromSetting(int chunkSizeMB) {
        return chunkSizeMB > 0
                ? new ChunkPolicy(chunkSizeMB * 1024L * 1024L, 0)
                : new ChunkPolicy(0, DEFAULT_DURATION_MS);
    }

    public boolean isFull(long bytesWritten, long elapsedMs) {
        return (maxBytes > 0 && bytesWritten >= maxBytes)
                || (maxDurationMs > 0 && elapsedMs >= maxDurationMs);
    }

    @Override
    public String toString() {
        return maxBytes > 0 ? "max " + maxBytes + " bytes" : "max " + maxDurationMs + " ms";
    }
}
