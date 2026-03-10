package net.ark3us.saferec.media;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.nio.ByteBuffer;

public class AudioStreamRecorder {

    private static final String TAG = AudioStreamRecorder.class.getSimpleName();
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int SAMPLE_RATE = 44100;
    private static final int BIT_RATE = 64000;
    private static final int BUFFER_SIZE_IN_BYTES = 1024 * 10; // Adjust as needed

    private final Context context;
    private MediaCodec encoder;
    private AudioRecord audioRecord;
    private Thread processingThread;
    private volatile boolean isRecording = false;
    private MuxerSink muxer;

    public AudioStreamRecorder(Context context) {
        this.context = context;
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

    private void processAudio() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        byte[] pcmBuffer = new byte[BUFFER_SIZE_IN_BYTES];

        try {
            while (isRecording) {
                // 1. Read PCM data
                int readSize = audioRecord.read(pcmBuffer, 0, pcmBuffer.length);
                if (readSize > 0) {
                    int inputBufferIndex = encoder.dequeueInputBuffer(10000);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = encoder.getInputBuffer(inputBufferIndex);
                        if (inputBuffer != null) {
                            inputBuffer.clear();
                            inputBuffer.put(pcmBuffer, 0, readSize);
                            // Use System.nanoTime() / 1000 for timestamps (PTS)
                            encoder.queueInputBuffer(inputBufferIndex, 0, readSize, System.nanoTime() / 1000, 0);
                        }
                    }
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
                encoder.queueInputBuffer(eosIndex, 0, 0, System.nanoTime() / 1000,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
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
