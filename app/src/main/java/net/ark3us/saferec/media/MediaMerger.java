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
    private static final String TAG = MediaMerger.class.getSimpleName();

    private static final long MIN_TRACK_GAP_US = 1;

    public static void merge(List<File> inputFiles, File outputFile) throws IOException {
        if (inputFiles == null || inputFiles.isEmpty()) {
            throw new IllegalArgumentException("Input files list is empty");
        }

        Log.i(TAG, "Merging " + inputFiles.size() + " files into " + outputFile.getAbsolutePath());

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
                Log.i(TAG, "Scanning tracks from " + file.getName());
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

            boolean firstFile = true;
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
                // Each track continues where it ended in the previous file. Inside a chunk the
                // audio may start a little before the video (late audio packets are kept), so
                // the gap between the tracks only counts in the first file, where it is the
                // real offset between microphone and camera start.
                long fileVideoFirstTsUs = firstFile ? 0 : -1;
                long fileAudioFirstTsUs = firstFile ? 0 : -1;
                long previousVideoPtsUs = -1;
                long previousAudioPtsUs = -1;
                long videoFrameDurationUs = 0;
                long audioFrameDurationUs = 0;
                long startVideoOffsetUs = videoOffset;
                long startAudioOffsetUs = audioOffset;

                Log.i(TAG, "Appending " + file.getName()
                        + " with offsets video=" + startVideoOffsetUs
                        + "us audio=" + startAudioOffsetUs + "us");

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
                            if (fileVideoFirstTsUs < 0) {
                                fileVideoFirstTsUs = pts;
                            }
                            long relativeUs = pts - fileVideoFirstTsUs;
                            bufferInfo.presentationTimeUs = startVideoOffsetUs + relativeUs;
                            muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo);
                            if (relativeUs > fileVideoMaxTsUs) {
                                fileVideoMaxTsUs = relativeUs;
                            }
                            if (previousVideoPtsUs >= 0 && pts > previousVideoPtsUs) {
                                videoFrameDurationUs = pts - previousVideoPtsUs;
                            }
                            previousVideoPtsUs = pts;
                        } else if (trackIndex == fileAudioTrack && audioTrackIndex != -1) {
                            if (fileAudioFirstTsUs < 0) {
                                fileAudioFirstTsUs = pts;
                            }
                            long relativeUs = pts - fileAudioFirstTsUs;
                            bufferInfo.presentationTimeUs = startAudioOffsetUs + relativeUs;
                            muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo);
                            if (relativeUs > fileAudioMaxTsUs) {
                                fileAudioMaxTsUs = relativeUs;
                            }
                            if (previousAudioPtsUs >= 0 && pts > previousAudioPtsUs) {
                                audioFrameDurationUs = pts - previousAudioPtsUs;
                            }
                            previousAudioPtsUs = pts;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to write sample data for track: " + trackIndex, e);
                    }

                    extractor.advance();
                }

                extractor.release();
                firstFile = false;

                // Update offsets for the next file
                if (fileVideoTrack != -1 && videoTrackIndex != -1) {
                    videoOffset += fileVideoMaxTsUs + Math.max(videoFrameDurationUs, MIN_TRACK_GAP_US);
                }
                if (fileAudioTrack != -1 && audioTrackIndex != -1) {
                    audioOffset += fileAudioMaxTsUs + Math.max(audioFrameDurationUs, MIN_TRACK_GAP_US);
                }

                Log.i(TAG, "Finished " + file.getName()
                        + " lastVideoPts=" + fileVideoMaxTsUs
                        + "us lastAudioPts=" + fileAudioMaxTsUs
                        + "us nextVideoOffset=" + videoOffset
                        + "us nextAudioOffset=" + audioOffset + "us");
            }

            muxer.stop();
        } finally {
            muxer.release();
        }
    }
}
