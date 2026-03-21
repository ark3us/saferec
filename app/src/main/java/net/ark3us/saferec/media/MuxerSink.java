package net.ark3us.saferec.media;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
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
    public static final int DEFAULT_AUDIO_CHUNK = 2 * 1024 * 1024; // 2 MB

    private MediaMuxer muxer;
    private MediaFormat videoFormat;
    private MediaFormat audioFormat;
    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;
    private int videoRotation = -1;

    public interface FileSavedCallback {
        void onFileSaved(File file);
    }

    private final FileSavedCallback fileSavedCallback;
    private final File storeDir;
    private final String dataType;
    private final String sessionId;
    private final int partSize;
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
    private long sessionVideoBaseUs = -1;
    private long sessionAudioBaseUs = -1;
    private long chunkNormalizedBaseUs = -1;

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

    public MuxerSink(File storeDir, FileSavedCallback fileSavedCallback, String dataType, String sessionId, int partSize) {
        this.storeDir = storeDir;
        this.fileSavedCallback = fileSavedCallback;
        this.dataType = dataType;
        this.sessionId = sessionId;
        this.partSize = partSize;
        this.expectedAudio = true;
        this.expectedVideo = DATA_TYPE_VIDEO.equals(dataType);

        if (!storeDir.exists() && !storeDir.mkdirs()) {
            Log.e(TAG, "Storage directory cannot be created: " + storeDir.getAbsolutePath());
        }
    }

    public synchronized void setExpectedAudio(boolean expectedAudio) {
        this.expectedAudio = expectedAudio;
        if (tracksReady())
            start();
    }

    /**
     * Updates the video rotation and forces a chunk rotation so the new file
     * gets the correct orientation hint. No-op if rotation hasn't changed.
     */
    public synchronized void updateVideoRotation(int newRotation) {
        if (videoRotation == newRotation) return;
        Log.i(TAG, "Video rotation changed: " + videoRotation + "° → " + newRotation + "°");
        videoRotation = newRotation;
        if (isStarted) {
            rotate();
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
        boolean isKeyFrame = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        if (bytesWritten >= partSize && isKeyFrame) {
            rotate();
        }
        return writeData(buffer, info, true);
    }

    public synchronized boolean writeAudioData(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        if (DATA_TYPE_AUDIO.equals(dataType) && bytesWritten >= partSize) {
            rotate();
        }
        return writeData(buffer, info, false);
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
    
        long baseUs;
        if (isVideo) {
            if (sessionVideoBaseUs == -1) {
                sessionVideoBaseUs = info.presentationTimeUs;
                Log.i(TAG, "Video session base set: " + sessionVideoBaseUs + " us");
            }
            baseUs = sessionVideoBaseUs;
        } else {
            if (sessionAudioBaseUs == -1) {
                sessionAudioBaseUs = info.presentationTimeUs;
                Log.i(TAG, "Audio session base set: " + sessionAudioBaseUs + " us");
            }
            baseUs = sessionAudioBaseUs;
        }
    
        long normalizedUs = info.presentationTimeUs - baseUs;
        if (chunkNormalizedBaseUs == -1) {
            chunkNormalizedBaseUs = normalizedUs;
            Log.i(TAG, "Chunk " + sequenceNumber + " normalized base set: " + chunkNormalizedBaseUs + " us (" + (isVideo ? "video" : "audio") + ")");
        }
        long finalUs = normalizedUs - chunkNormalizedBaseUs;
    
        if (finalUs < 0) {
            Log.w(TAG, "Skipping " + (isVideo ? "video" : "audio") + " frame with negative PTS: " + finalUs);
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
        sessionVideoBaseUs = -1;
        sessionAudioBaseUs = -1;
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
            if (fileSavedCallback != null && bytesWritten > 0) {
                fileSavedCallback.onFileSaved(currentFile);
            }
        }
    }

    // --- Private helpers ---

    private void init() {
        if (isInitialized)
            return;
        try {
            MediaFile mediaFile = new MediaFile(sessionId, dataType, System.currentTimeMillis(), sequenceNumber++);
            currentFile = new File(storeDir, mediaFile.getFileName());
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
        if (isStarted || !tracksReady())
            return;
        muxer.start();
        isStarted = true;

        List<PendingPacket> toFlush = new ArrayList<>(pendingPackets);
        pendingPackets.clear();
        for (PendingPacket p : toFlush) {
            ByteBuffer buf = ByteBuffer.wrap(p.data);
            writeData(buf, p.info, p.isVideo);
        }
    }
}