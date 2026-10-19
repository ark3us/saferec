package net.ark3us.saferec.media;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.nio.ByteBuffer;

public class AudioStreamRecorder {

    private static final String TAG = AudioStreamRecorder.class.getSimpleName();
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int SAMPLE_RATE = 44100;
    private static final int BIT_RATE = 64000;
    private static final int BUFFER_SIZE_IN_BYTES = 1024 * 10; // Adjust as needed
    private static final int BYTES_PER_FRAME = 2; // mono, 16-bit

    private final Context context;
    private MediaCodec encoder;
    private AudioRecord audioRecord;
    private Thread processingThread;
    private volatile boolean isRecording = false;
    private MuxerSink muxer;

    public AudioStreamRecorder(Context context) {
        this.context = context;
    }

    /** True while the microphone is actually being captured (the loop exits on read errors). */
    public boolean isRecording() {
        return isRecording && processingThread != null && processingThread.isAlive();
    }

    public boolean start(MuxerSink muxer) {
        if (ActivityCompat.checkSelfPermission(this.context,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted");
            return false;
        }
        if (isRecording) {
            Log.w(TAG, "AudioRecorder is already running");
            return false;
        }
        if (!setupEncoder()) {
            Log.e(TAG, "Failed to set up encoder");
            return false;
        }
        this.muxer = muxer;

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSize <= 0) {
            Log.e(TAG, "Invalid minimum audio buffer size: " + minBufferSize);
            return false;
        }

        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBufferSize);
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed");
            audioRecord.release();
            audioRecord = null;
            return false;
        }

        try {
            audioRecord.startRecording();
            encoder.start();
            isRecording = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording: ", e);
            if (audioRecord != null) {
                try {
                    audioRecord.release();
                } catch (Exception releaseException) {
                    Log.w(TAG, "Failed to release AudioRecord after start failure", releaseException);
                }
                audioRecord = null;
            }
            if (encoder != null) {
                try {
                    encoder.release();
                } catch (Exception releaseException) {
                    Log.w(TAG, "Failed to release encoder after start failure", releaseException);
                }
                encoder = null;
            }
            return false;
        }

        processingThread = new Thread(this::processAudio, "AudioStreamRecorderThread");
        processingThread.start();
        Log.i(TAG, "Audio recorder started");
        return true;
    }

    public void stop() {
        isRecording = false;
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop AudioRecord: ", e);
            }
        }
        if (processingThread != null) {
            try {
                processingThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while stopping processing thread: ", e);
            }
            processingThread = null;
        }
        if (audioRecord != null) {
            try {
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Failed to release AudioRecord: ", e);
            }
            audioRecord = null;
        }
        if (encoder != null) {
            try {
                encoder.stop();
                encoder.release();
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop encoder: ", e);
            }
            encoder = null;
        }
        Log.i(TAG, "AudioRecorder stopped");
    }

    private boolean setupEncoder() {
        MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, 1);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BUFFER_SIZE_IN_BYTES);
        try {
            this.encoder = MediaCodec.createEncoderByType(MIME_TYPE);
            this.encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to set up encoder: ", e);
            this.encoder = null;
            return false;
        }
    }

    /**
     * CLOCK_BOOTTIME time of the first captured frame. Timestamps then follow the sample count,
     * so they never jitter or go backwards, and share a clock with the video track: MuxerSink
     * brings camera timestamps to CLOCK_BOOTTIME too.
     */
    private long captureStartNs(AudioTimestamp timestamp, long framesRead) {
        if (audioRecord.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
            Log.i(TAG, "Audio clock from the capture timestamp");
            return timestamp.nanoTime - timestamp.framePosition * 1_000_000_000L / SAMPLE_RATE;
        }
        // No timestamp yet: the frames just read were captured right before now
        Log.i(TAG, "Audio clock from the read time");
        return SystemClock.elapsedRealtimeNanos() - framesRead * 1_000_000_000L / SAMPLE_RATE;
    }

    private long presentationTimeUs(long startNs, long frames) {
        return (startNs + frames * 1_000_000_000L / SAMPLE_RATE) / 1000;
    }

    private void processAudio() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        byte[] pcmBuffer = new byte[BUFFER_SIZE_IN_BYTES];
        AudioTimestamp timestamp = new AudioTimestamp();
        long framesRead = 0;
        long startNs = -1;

        try {
            while (isRecording) {
                // 1. Read PCM data
                int readSize = audioRecord.read(pcmBuffer, 0, pcmBuffer.length);
                if (readSize > 0) {
                    if (startNs < 0) {
                        startNs = captureStartNs(timestamp, readSize / BYTES_PER_FRAME);
                    }
                    // Wait for an input buffer instead of dropping the PCM: a dropped buffer
                    // shortened the audio timeline and shifted all later audio against the video
                    int inputBufferIndex;
                    while ((inputBufferIndex = encoder.dequeueInputBuffer(10000)) < 0 && isRecording) {
                        drainEncoder(bufferInfo);
                    }
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = encoder.getInputBuffer(inputBufferIndex);
                        if (inputBuffer != null) {
                            inputBuffer.clear();
                            inputBuffer.put(pcmBuffer, 0, readSize);
                            encoder.queueInputBuffer(inputBufferIndex, 0, readSize,
                                    presentationTimeUs(startNs, framesRead), 0);
                        }
                    }
                    framesRead += readSize / BYTES_PER_FRAME;
                } else if (readSize < 0) {
                    Log.w(TAG, "AudioRecord read error code: " + readSize);
                    break;
                }

                // 2. Retrieve AAC from Encoder
                drainEncoder(bufferInfo);
            }

            // Signal end-of-stream and drain remaining output
            Log.i(TAG, "Signaling EOS to encoder");
            int eosIndex = encoder.dequeueInputBuffer(10000);
            if (eosIndex >= 0) {
                long eosUs = startNs < 0 ? 0 : presentationTimeUs(startNs, framesRead);
                encoder.queueInputBuffer(eosIndex, 0, 0, eosUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
            drainEncoder(bufferInfo);
        } catch (Exception e) {
            Log.e(TAG, "Error during audio processing: ", e);
        }
    }

    private void drainEncoder(MediaCodec.BufferInfo bufferInfo) {
        int outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000);

        if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            MediaFormat newFormat = encoder.getOutputFormat();
            muxer.addAudioTrack(newFormat);
        } else {
            while (outputBufferIndex >= 0) {
                ByteBuffer outputBuffer = encoder.getOutputBuffer(outputBufferIndex);

                if (outputBuffer != null && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    muxer.writeAudioData(outputBuffer, bufferInfo);
                }

                encoder.releaseOutputBuffer(outputBufferIndex, false);
                outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0);
            }
        }
    }
}
