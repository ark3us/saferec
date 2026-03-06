package net.ark3us.saferec.media;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

public class MediaMerger {
    private static final String TAG = "MediaMerger";

    public static void merge(List<File> inputFiles, File outputFile) throws IOException {
        if (inputFiles == null || inputFiles.isEmpty()) {
            throw new IllegalArgumentException("Input files list is empty");
        }

        MediaMuxer muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        try {
            int videoTrackIndex = -1;
            int audioTrackIndex = -1;
            long videoOffset = 0;
            long audioOffset = 0;

            MediaFormat videoFormat = null;
            MediaFormat audioFormat = null;

            int rotation = 0;

            // First pass: find formats from the first file that has them
            for (File file : inputFiles) {
                MediaExtractor extractor = new MediaExtractor();
                extractor.setDataSource(file.getAbsolutePath());

                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime.startsWith("video/") && videoFormat == null) {
                        videoFormat = format;
                        if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                            rotation = format.getInteger(MediaFormat.KEY_ROTATION);
                        }
                    } else if (mime.startsWith("audio/") && audioFormat == null) {
                        audioFormat = format;
                    }
                }
                extractor.release();
                if (videoFormat != null && audioFormat != null)
                    break;
            }

            if (videoFormat != null) {
                // Apply rotation via setOrientationHint only — remove KEY_ROTATION
                // from format to prevent double-rotation on some Android versions.
                if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                    videoFormat.setInteger(MediaFormat.KEY_ROTATION, 0);
                }
                if (rotation != 0) {
                    muxer.setOrientationHint(rotation);
                    Log.i(TAG, "Applied orientation hint to merged video: " + rotation);
                }
                videoTrackIndex = muxer.addTrack(videoFormat);
            }
            if (audioFormat != null) {
                audioTrackIndex = muxer.addTrack(audioFormat);
            }

            muxer.start();

            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024 * 2); // 2MB buffer
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            for (File file : inputFiles) {
                MediaExtractor extractor = new MediaExtractor();
                extractor.setDataSource(file.getAbsolutePath());

                int fileVideoTrack = -1;
                int fileAudioTrack = -1;

                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime.startsWith("video/")) {
                        fileVideoTrack = i;
                    } else if (mime.startsWith("audio/")) {
                        fileAudioTrack = i;
                    }
                }

                if (fileVideoTrack != -1 && videoTrackIndex != -1) {
                    extractor.selectTrack(fileVideoTrack);
                }
                if (fileAudioTrack != -1 && audioTrackIndex != -1) {
                    extractor.selectTrack(fileAudioTrack);
                }

                long fileVideoMaxTsUs = 0;
                long fileAudioMaxTsUs = 0;
                long startVideoOffsetUs = videoOffset;
                long startAudioOffsetUs = audioOffset;

                while (true) {
                    int trackIndex = extractor.getSampleTrackIndex();
                    if (trackIndex < 0) {
                        break; // No more samples
                    }

                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) {
                        extractor.advance();
                        continue;
                    }

                    long pts = extractor.getSampleTime();
                    bufferInfo.offset = 0;
                    bufferInfo.size = sampleSize;
                    int flags = extractor.getSampleFlags();
                    bufferInfo.flags = 0;
                    if ((flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        bufferInfo.flags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
                    }
                    if ((flags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                        bufferInfo.flags |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
                    }

                    try {
                        if (trackIndex == fileVideoTrack && videoTrackIndex != -1) {
                            bufferInfo.presentationTimeUs = startVideoOffsetUs + pts;
                            muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo);
                            if (pts > fileVideoMaxTsUs) {
                                fileVideoMaxTsUs = pts;
                            }
                        } else if (trackIndex == fileAudioTrack && audioTrackIndex != -1) {
                            bufferInfo.presentationTimeUs = startAudioOffsetUs + pts;
                            muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo);
                            if (pts > fileAudioMaxTsUs) {
                                fileAudioMaxTsUs = pts;
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to write sample data for track: " + trackIndex, e);
                    }

                    extractor.advance();
                }

                extractor.release();

                // Update offsets for the next file
                if (fileVideoTrack != -1 && videoTrackIndex != -1) {
                    videoOffset += fileVideoMaxTsUs + 33333; // ~30fps frame duration
                }
                if (fileAudioTrack != -1 && audioTrackIndex != -1) {
                    audioOffset += fileAudioMaxTsUs + 23220; // ~1024 samples at 44.1kHz
                }
            }

            muxer.stop();
        } finally {
            muxer.release();
        }
    }
}
