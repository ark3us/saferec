package net.ark3us.saferec.media;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds a playable chunk from one a crash or a power cut left without its MP4 index: the
 * samples found with the sample log go, unchanged, into a new MP4 with the logged formats,
 * orientation and times. The cut file itself is never modified.
 */
public final class ChunkRepair implements WorkDir.Repairer {
    private static final String TAG = ChunkRepair.class.getSimpleName();

    @Override
    public boolean repair(File cut, File journalFile, File out) {
        MediaMuxer muxer = null;
        try {
            SampleJournal journal = SampleJournal.read(journalFile);
            if (journal == null) {
                Log.w(TAG, "Not a sample log: " + journalFile.getName());
                return false;
            }
            try (RandomAccessFile file = new RandomAccessFile(cut, "r")) {
                List<ChunkIndex.Located> samples = ChunkIndex.locate(file, journal);
                if (samples == null || samples.isEmpty()) {
                    Log.w(TAG, "Nothing to repair in " + cut.getName() + (samples == null ? ": complete or unknown layout" : ": no sample found"));
                    return false;
                }
                int[] counts = new int[2];
                for (ChunkIndex.Located sample : samples) {
                    counts[sample.track]++;
                }
                long loggedVideo = journal.samples.stream().filter(s -> s.track == SampleJournal.VIDEO).count();
                if (loggedVideo > 0 && counts[SampleJournal.VIDEO] == 0) {
                    // The video samples did not match the log: the writer laid them out differently
                    Log.w(TAG, "No logged video sample found in " + cut.getName() + ", not repairing");
                    return false;
                }

                muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int[] tracks = {-1, -1};
                for (int track : new int[]{SampleJournal.VIDEO, SampleJournal.AUDIO}) {
                    Map<String, Object> fields = journal.formats.get(track);
                    if (fields != null && counts[track] > 0) {
                        tracks[track] = muxer.addTrack(toFormat(fields));
                    }
                }
                if (tracks[SampleJournal.VIDEO] >= 0 && journal.rotation >= 0) {
                    muxer.setOrientationHint(journal.rotation);
                }
                muxer.start();
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                byte[] data = new byte[0];
                long lastPtsUs = 0;
                for (ChunkIndex.Located sample : samples) {
                    if (tracks[sample.track] < 0) {
                        continue;
                    }
                    if (data.length < sample.size) {
                        data = new byte[sample.size];
                    }
                    file.seek(sample.offset);
                    file.readFully(data, 0, sample.size);
                    if (sample.track == SampleJournal.VIDEO) {
                        // In the file a video sample starts with its length; MediaMuxer takes the
                        // encoder's start code instead and writes the length itself
                        data[0] = 0;
                        data[1] = 0;
                        data[2] = 0;
                        data[3] = 1;
                    }
                    info.set(0, sample.size, sample.ptsUs, sample.flags);
                    muxer.writeSampleData(tracks[sample.track], ByteBuffer.wrap(data, 0, sample.size), info);
                    lastPtsUs = Math.max(lastPtsUs, sample.ptsUs);
                }
                muxer.stop();
                Log.i(TAG, "Repaired " + cut.getName() + ": " + counts[SampleJournal.VIDEO] + " video and "
                        + counts[SampleJournal.AUDIO] + " audio samples of " + journal.samples.size() + " logged, up to "
                        + lastPtsUs / 1000 + " ms");
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Repair failed: " + cut.getName(), e);
            return false;
        } finally {
            if (muxer != null) {
                try {
                    muxer.release(); // after a failure the caller deletes the partial output
                } catch (Exception ignored) {
                }
            }
        }
    }

    static MediaFormat toFormat(Map<String, Object> fields) {
        MediaFormat format = new MediaFormat();
        for (Map.Entry<String, Object> field : fields.entrySet()) {
            Object value = field.getValue();
            if (value instanceof Integer) {
                format.setInteger(field.getKey(), (Integer) value);
            } else if (value instanceof Long) {
                format.setLong(field.getKey(), (Long) value);
            } else if (value instanceof byte[]) {
                format.setByteBuffer(field.getKey(), ByteBuffer.wrap((byte[]) value));
            } else {
                format.setString(field.getKey(), String.valueOf(value));
            }
        }
        return format;
    }
}
