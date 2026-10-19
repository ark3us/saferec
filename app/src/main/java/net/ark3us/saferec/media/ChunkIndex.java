package net.ark3us.saferec.media;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Finds every sample of a chunk MediaMuxer did not finish, using its SampleJournal.
 *
 * MediaMuxer (MPEG4Writer) writes ftyp, a free box reserved for the index, then one mdat box
 * whose size is filled in, with the moov index, only when the chunk is closed. Inside mdat the
 * samples of each track come in their recording order, in runs of one track at a time. A video
 * sample starts with its own length (4 bytes, the sample size minus 4), so at each position the
 * next video sample of the log either matches, or the next audio sample comes. Checked on a
 * complete chunk: this rebuilt its index exactly (715 of 715 samples).
 */
public final class ChunkIndex {

    public static final class Located {
        public final int track;
        public final long offset;
        public final int size;
        public final long ptsUs;
        public final int flags;

        Located(SampleJournal.Sample sample, long offset) {
            this.track = sample.track;
            this.offset = offset;
            this.size = sample.size;
            this.ptsUs = sample.ptsUs;
            this.flags = sample.flags;
        }
    }

    private ChunkIndex() {
    }

    /**
     * The samples found in the file, in file order. Empty when the file holds none of them,
     * null when it does not need repairing (it has its index) or is not laid out as expected.
     */
    public static List<Located> locate(RandomAccessFile file, SampleJournal journal) throws IOException {
        long length = file.length();
        long dataStart = -1;
        long dataEnd = length;
        long position = 0;
        while (position + 8 <= length) {
            file.seek(position);
            long size = file.readInt() & 0xFFFFFFFFL;
            int type = file.readInt();
            int header = 8;
            if (size == 1) {
                size = file.readLong();
                header = 16;
            }
            if (type == fourCc("moov")) {
                return null; // complete: the index is there
            }
            if (type == fourCc("mdat")) {
                dataStart = position + header;
                // Until the chunk is closed the size is a placeholder
                boolean sizeValid = size >= header && position + size <= length;
                if (!sizeValid) {
                    break;
                }
                dataEnd = position + size;
            }
            if (size < 8 || position + size > length) {
                break;
            }
            position += size;
        }
        if (dataStart < 0) {
            return null;
        }

        Deque<SampleJournal.Sample> video = new ArrayDeque<>();
        Deque<SampleJournal.Sample> audio = new ArrayDeque<>();
        for (SampleJournal.Sample sample : journal.samples) {
            (sample.track == SampleJournal.VIDEO ? video : audio).add(sample);
        }
        boolean hasVideo = !video.isEmpty();
        List<Located> located = new ArrayList<>();
        long p = dataStart;
        while (p < dataEnd) {
            SampleJournal.Sample next = null;
            SampleJournal.Sample v = video.peek();
            if (v != null && p + 4 <= dataEnd) {
                file.seek(p);
                if ((file.readInt() & 0xFFFFFFFFL) == v.size - 4L) {
                    next = video.poll();
                }
            } else if (v == null && hasVideo && looksLikeVideo(file, p, dataEnd)) {
                // The log stopped before the file (not flushed when the power went): this video
                // sample is not in it, and taking it for the next logged audio would add garbage
                break;
            }
            if (next == null) {
                next = audio.poll();
            }
            if (next == null || p + next.size > dataEnd) {
                break; // the log ran out, or the last sample was cut
            }
            located.add(new Located(next, p));
            p += next.size;
        }
        return located;
    }

    /** A plausible length and the header of an H.264 slice (non-IDR or IDR). */
    private static boolean looksLikeVideo(RandomAccessFile file, long p, long end) throws IOException {
        if (p + 5 > end) {
            return false;
        }
        file.seek(p);
        long length = file.readInt() & 0xFFFFFFFFL;
        int nal = file.readUnsignedByte();
        int type = nal & 0x1F;
        return length > 0 && p + 4 + length <= end && (nal & 0x80) == 0 && (type == 1 || type == 5);
    }

    static int fourCc(String code) {
        return (code.charAt(0) << 24) | (code.charAt(1) << 16) | (code.charAt(2) << 8) | code.charAt(3);
    }
}
