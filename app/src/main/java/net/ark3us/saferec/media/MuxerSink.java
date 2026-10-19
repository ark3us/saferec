package net.ark3us.saferec.media;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;
import android.util.Log;

import net.ark3us.saferec.net.MediaFile;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class MuxerSink {
    private static final String TAG = MuxerSink.class.getSimpleName();
    public static final String DATA_TYPE_VIDEO = "video";
    public static final String DATA_TYPE_AUDIO = "audio";
    // A chunk's time base starts this long before its first packet, so packets of the other
    // track captured just before it (audio comes out of its encoder late) are kept, not
    // dropped. The MP4 writer moves the earliest sample to 0, so this adds no silence.
    private static final long CHUNK_LEAD_US = 1_000_000;
    private static final long CLOCK_UNKNOWN = Long.MIN_VALUE;

    private MediaMuxer muxer;
    private MediaFormat videoFormat;
    private MediaFormat audioFormat;
    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;
    private int videoRotation = -1;
    private int pendingRotation = -1; // applied at the next key frame

    public interface FileSavedCallback {
        /**
         * The chunk is complete and still in workDir. The receiver moves it to the upload
         * outbox once ready (hashed for the TSA): from there it may be uploaded and deleted
         * at any time.
         */
        void onFileSaved(File file);
    }

    private final FileSavedCallback fileSavedCallback;
    // Chunks are written in workDir and handed over only once closed, so the outbox never
    // holds a half-written file and can be scanned any time.
    private final File workDir;
    private final String dataType;
    private final String sessionId;
    private final ChunkPolicy chunkPolicy;
    private long chunkStartMs = 0;
    private File currentFile;
    private int sequenceNumber = 0;
    private boolean isStarted = false;
    private boolean isInitialized = false;

    public void setSequenceNumber(int sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    private boolean expectedAudio;
    private boolean expectedVideo;
    private long bytesWritten = 0;
    // Both tracks are normalized against one base on one clock (CLOCK_BOOTTIME): normalizing
    // each track to its own first packet put audio ~0.2-1 s behind the video (the camera
    // starts later than the microphone).
    private long sessionBaseUs = -1;
    private long chunkNormalizedBaseUs = -1;
    private long videoToBootUs = CLOCK_UNKNOWN;

    private static class PendingPacket {
        boolean isVideo;
        byte[] data;
        MediaCodec.BufferInfo info;

        PendingPacket(boolean isVideo, ByteBuffer buffer, MediaCodec.BufferInfo info) {
            this.isVideo = isVideo;
            this.data = new byte[info.size];
            buffer.position(info.offset);
            buffer.get(this.data);
            this.info = new MediaCodec.BufferInfo();
            this.info.set(0, info.size, info.presentationTimeUs, info.flags);
        }
    }

    private final List<PendingPacket> pendingPackets = new ArrayList<>();

    public MuxerSink(File workDir, FileSavedCallback fileSavedCallback, String dataType,
            String sessionId, ChunkPolicy chunkPolicy) {
        this.workDir = workDir;
        this.fileSavedCallback = fileSavedCallback;
        this.dataType = dataType;
        this.sessionId = sessionId;
        this.chunkPolicy = chunkPolicy;
        this.expectedAudio = true;
        this.expectedVideo = DATA_TYPE_VIDEO.equals(dataType);

        if (!workDir.exists() && !workDir.mkdirs()) {
            Log.e(TAG, "Storage directory cannot be created: " + workDir.getAbsolutePath());
        }
    }

    public synchronized void setExpectedAudio(boolean expectedAudio) {
        this.expectedAudio = expectedAudio;
        if (tracksReady())
            start();
    }

    /** Called when the camera is lost mid-session: the next chunks carry audio only. */
    public synchronized void setExpectedVideo(boolean expectedVideo) {
        this.expectedVideo = expectedVideo;
        if (!expectedVideo) {
            pendingPackets.removeIf(p -> p.isVideo);
        }
        if (tracksReady())
            start();
    }

    /**
     * The next chunk gets the new orientation hint (one MP4 holds one). The split waits for
     * the next key frame: a chunk that starts without one cannot be played until it arrives.
     */
    public synchronized void updateVideoRotation(int newRotation) {
        if (!isStarted) {
            videoRotation = newRotation; // the track is not added yet: it takes this one
            return;
        }
        pendingRotation = newRotation == videoRotation ? -1 : newRotation;
        if (pendingRotation >= 0) {
            Log.i(TAG, "Video rotation changed: " + videoRotation + "° → " + newRotation + "°, new chunk at the next key frame");
        }
    }

    public synchronized void rotate() {
        Log.i(TAG, "Rotating muxer file. Current sequence: " + sequenceNumber);
        closeFile();
        if (expectedVideo && videoFormat != null)
            addVideoTrack(videoFormat, videoRotation);
        if (expectedAudio && audioFormat != null)
            addAudioTrack(audioFormat);
        start();
    }

    // --- Track management ---

    public synchronized void addVideoTrack(MediaFormat format, int rotation) {
        if (!expectedVideo) {
            // Camera already dropped: the muxer may be started audio-only, addTrack would throw
            return;
        }
        init();
        videoFormat = format;
        // Strip KEY_ROTATION from the encoder format — we control rotation
        // exclusively via setOrientationHint to avoid double-rotation.
        if (format.containsKey(MediaFormat.KEY_ROTATION)) {
            format.setInteger(MediaFormat.KEY_ROTATION, 0);
        }
        videoTrackIndex = muxer.addTrack(format);
        if (rotation >= 0) {
            videoRotation = rotation;
            muxer.setOrientationHint(rotation);
            Log.i(TAG, "Video rotation: " + rotation + "°");
        }
        Log.i(TAG, "Video track added: " + videoTrackIndex);
        start();
    }

    public synchronized void addAudioTrack(MediaFormat format) {
        init();
        audioFormat = format;
        audioTrackIndex = muxer.addTrack(format);
        Log.i(TAG, "Audio track added: " + audioTrackIndex);
        start();
    }

    // --- Write data ---

    public synchronized boolean writeVideoData(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        if (!expectedVideo) {
            return true; // camera dropped: late packets have no track to go to
        }
        if (videoToBootUs == CLOCK_UNKNOWN) {
            videoToBootUs = videoToBootOffsetUs(info.presentationTimeUs);
        }
        info.presentationTimeUs += videoToBootUs;
        boolean isKeyFrame = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        if (isKeyFrame && (isChunkFull() || pendingRotation >= 0)) {
            if (pendingRotation >= 0) {
                videoRotation = pendingRotation;
                pendingRotation = -1;
            }
            rotate();
        }
        return writeData(buffer, info, true);
    }

    public synchronized boolean writeAudioData(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        // Without video there are no key frames to split on: audio drives the chunks
        if (!expectedVideo && isChunkFull()) {
            rotate();
        }
        return writeData(buffer, info, false);
    }

    /**
     * Camera timestamps use CLOCK_BOOTTIME on some devices and CLOCK_MONOTONIC on others, while
     * audio uses CLOCK_BOOTTIME. A frame reaches the muxer well under a second after capture,
     * so the clock its timestamp is closest to is the camera's clock.
     */
    private static long videoToBootOffsetUs(long ptsUs) {
        long bootUs = SystemClock.elapsedRealtimeNanos() / 1000;
        long monotonicUs = System.nanoTime() / 1000;
        boolean boot = Math.abs(bootUs - ptsUs) <= Math.abs(monotonicUs - ptsUs);
        Log.i(TAG, "Video clock: " + (boot ? "BOOTTIME" : "MONOTONIC") + ", first frame "
                + ((boot ? bootUs : monotonicUs) - ptsUs) / 1000 + " ms old");
        return boot ? 0 : bootUs - monotonicUs;
    }

    /** True once the open chunk hits the size limit or the duration limit, whichever comes first. */
    private boolean isChunkFull() {
        return isStarted && chunkPolicy.isFull(bytesWritten, SystemClock.elapsedRealtime() - chunkStartMs);
    }

    private boolean writeData(ByteBuffer buffer, MediaCodec.BufferInfo info, boolean isVideo) {
        int trackIndex = isVideo ? videoTrackIndex : audioTrackIndex;
        if (trackIndex == -1) {
            if (pendingPackets.size() < 100) { // Safety limit to avoid OOM
                pendingPackets.add(new PendingPacket(isVideo, buffer, info));
            } else {
                Log.w(TAG, "Dropped " + (isVideo ? "video" : "audio") + " packet: track not ready and pending queue full");
            }
            return true;
        }
    
        if (!isStarted) {
            if (pendingPackets.size() < 100) {
                pendingPackets.add(new PendingPacket(isVideo, buffer, info));
            } else {
                Log.w(TAG, "Dropped " + (isVideo ? "video" : "audio") + " packet: muxer not started and pending queue full");
            }
            return true;
        }
    
        if (sessionBaseUs == -1) {
            sessionBaseUs = info.presentationTimeUs;
            Log.i(TAG, "Session base set: " + sessionBaseUs + " us (" + (isVideo ? "video" : "audio") + ")");
        }
        long normalizedUs = info.presentationTimeUs - sessionBaseUs;
        if (chunkNormalizedBaseUs == -1) {
            chunkNormalizedBaseUs = normalizedUs - CHUNK_LEAD_US;
            Log.i(TAG, "Chunk " + (sequenceNumber - 1) + " starts at " + normalizedUs + " us (" + (isVideo ? "video" : "audio") + ")");
        }
        long finalUs = normalizedUs - chunkNormalizedBaseUs;
    
        if (finalUs < 0) {
            Log.w(TAG, "Skipping " + (isVideo ? "video" : "audio") + " frame captured "
                    + -finalUs / 1000 + " ms before the chunk lead");
            return true;
        }
        info.presentationTimeUs = finalUs;
    
        try {
            muxer.writeSampleData(trackIndex, buffer, info);
            bytesWritten += info.size;
        } catch (Exception e) {
            Log.e(TAG, "Error writing data to track " + trackIndex, e);
        }
        return true;
    }

    // --- Lifecycle ---

    public synchronized void stop() {
        closeFile();
        sessionBaseUs = -1;
        videoToBootUs = CLOCK_UNKNOWN;
        pendingPackets.clear();
    }

    private void closeFile() {
        isInitialized = false;
        isStarted = false;
        chunkNormalizedBaseUs = -1;
        audioTrackIndex = -1;
        videoTrackIndex = -1;
        if (muxer != null) {
            try {
                muxer.stop();
            } catch (Exception ignored) {
            }
            try {
                muxer.release();
            } catch (Exception ignored) {
            }
            muxer = null;
        }
        if (currentFile != null) {
            if (bytesWritten > 0) {
                if (fileSavedCallback != null) {
                    fileSavedCallback.onFileSaved(currentFile);
                }
            } else if (!currentFile.delete()) {
                Log.w(TAG, "Failed to delete empty chunk: " + currentFile.getName());
            }
            currentFile = null;
            bytesWritten = 0;
        }
    }

    // --- Private helpers ---

    private void init() {
        if (isInitialized)
            return;
        try {
            MediaFile mediaFile = new MediaFile(sessionId, dataType, System.currentTimeMillis(), sequenceNumber++);
            currentFile = new File(workDir, mediaFile.getFileName());
            // Reset bytesWritten when creating a new file
            bytesWritten = 0;
            muxer = new MediaMuxer(currentFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            isInitialized = true;
            Log.i(TAG, "Muxer initialized: " + currentFile.getName());
        } catch (IOException e) {
            Log.e(TAG, "Failed to create MediaMuxer", e);
        }
    }

    private boolean tracksReady() {
        return (!expectedAudio || audioTrackIndex != -1) && (!expectedVideo || videoTrackIndex != -1);
    }

    private void start() {
        if (isStarted || muxer == null || !tracksReady())
            return;
        muxer.start();
        isStarted = true;
        chunkStartMs = SystemClock.elapsedRealtime();

        List<PendingPacket> toFlush = new ArrayList<>(pendingPackets);
        pendingPackets.clear();
        for (PendingPacket p : toFlush) {
            ByteBuffer buf = ByteBuffer.wrap(p.data);
            writeData(buf, p.info, p.isVideo);
        }
    }
}