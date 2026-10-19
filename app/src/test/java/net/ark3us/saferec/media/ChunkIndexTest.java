package net.ark3us.saferec.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Builds chunks laid out like MPEG4Writer's (ftyp, free, mdat with runs of samples of one
 * track) and checks the samples are found from the sample log alone.
 */
public class ChunkIndexTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Random random = new Random(7);

    /** One sample as MPEG4Writer stores it: video starts with its length minus 4. */
    private byte[] sample(int track, int size) {
        byte[] data = new byte[size];
        random.nextBytes(data);
        if (track == SampleJournal.VIDEO) {
            int length = size - 4;
            data[0] = (byte) (length >>> 24);
            data[1] = (byte) (length >>> 16);
            data[2] = (byte) (length >>> 8);
            data[3] = (byte) length;
            data[4] = 0x41; // a P slice
        } else {
            data[0] = 0x21; // an AAC raw frame, nothing to recognise it by
        }
        return data;
    }

    /** Runs of video and audio samples, the log in recording order, and the expected offsets. */
    private static final class Chunk {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final List<SampleJournal.Sample> log = new ArrayList<>();
        final List<Long> offsets = new ArrayList<>(); // in file order
        final List<Integer> tracks = new ArrayList<>();
    }

    private Chunk build(int runs, boolean complete) throws Exception {
        Chunk chunk = new Chunk();
        DataOutputStream out = new DataOutputStream(chunk.bytes);
        out.writeInt(24);
        out.writeBytes("ftypisom");
        out.write(new byte[12]);
        out.writeInt(3314);
        out.writeBytes("free");
        out.write(new byte[3314 - 8]);
        long mdatStart = chunk.bytes.size();
        out.writeInt(1);
        out.writeBytes("mdat");
        out.writeLong(0x3F3F3F3F3F3F3F3FL); // placeholder, as in a cut chunk
        List<SampleJournal.Sample> video = new ArrayList<>();
        List<SampleJournal.Sample> audio = new ArrayList<>();
        long pts = 0;
        for (int run = 0; run < runs; run++) {
            int track = run % 2 == 0 ? SampleJournal.AUDIO : SampleJournal.VIDEO;
            for (int i = 0; i < 3 + random.nextInt(5); i++) {
                int size = track == SampleJournal.VIDEO ? 200 + random.nextInt(3000) : 150 + random.nextInt(60);
                chunk.offsets.add((long) chunk.bytes.size());
                chunk.tracks.add(track);
                out.write(sample(track, size));
                (track == SampleJournal.VIDEO ? video : audio).add(new SampleJournal.Sample(track, size, pts += 1000, 0));
            }
        }
        // The log interleaves the tracks in their own order: only per-track order is known
        int vi = 0, ai = 0;
        while (vi < video.size() || ai < audio.size()) {
            if (ai < audio.size()) chunk.log.add(audio.get(ai++));
            if (ai < audio.size()) chunk.log.add(audio.get(ai++));
            if (vi < video.size()) chunk.log.add(video.get(vi++));
        }
        if (complete) {
            byte[] all = chunk.bytes.toByteArray();
            long mdatSize = all.length - mdatStart;
            for (int i = 0; i < 8; i++) {
                all[(int) mdatStart + 8 + i] = (byte) (mdatSize >>> (56 - 8 * i));
            }
            chunk.bytes.reset();
            chunk.bytes.write(all);
            out.writeInt(16);
            out.writeBytes("moov");
            out.write(new byte[8]);
        }
        return chunk;
    }

    private List<ChunkIndex.Located> locate(Chunk chunk, int cutBytes) throws Exception {
        File file = tmp.newFile();
        byte[] all = chunk.bytes.toByteArray();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(all, 0, all.length - cutBytes);
        }
        SampleJournal journal = journalOf(chunk.log);
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            return ChunkIndex.locate(raf, journal);
        }
    }

    private SampleJournal journalOf(List<SampleJournal.Sample> samples) throws Exception {
        File file = tmp.newFile();
        try (SampleJournal.Writer writer = new SampleJournal.Writer(file)) {
            for (SampleJournal.Sample s : samples) {
                writer.sample(s.track, s.size, s.ptsUs, s.flags);
            }
        }
        return SampleJournal.read(file);
    }

    @Test
    public void everySampleOfACutChunkIsFound() throws Exception {
        Chunk chunk = build(12, false);
        List<ChunkIndex.Located> found = locate(chunk, 10); // the last sample is cut (all are > 10 bytes)
        assertEquals(chunk.offsets.size() - 1, found.size());
        for (int i = 0; i < found.size(); i++) {
            assertEquals("sample " + i, (long) chunk.offsets.get(i), found.get(i).offset);
            assertEquals("sample " + i, (int) chunk.tracks.get(i), found.get(i).track);
        }
    }

    @Test
    public void aChunkWithItsIndexNeedsNoRepair() throws Exception {
        assertNull(locate(build(6, true), 0));
    }

    @Test
    public void aLogBehindTheFileRepairsWhatItKnows() throws Exception {
        Chunk chunk = build(10, false);
        int known = chunk.log.size() - 5; // the last log batch never reached the disk
        chunk.log.subList(known, chunk.log.size()).clear();
        List<ChunkIndex.Located> found = locate(chunk, 0);
        assertTrue(found.size() > 0 && found.size() <= known);
        for (int i = 0; i < found.size(); i++) {
            // Never a sample at the wrong place or of the wrong track
            assertEquals("sample " + i, (long) chunk.offsets.get(i), found.get(i).offset);
            assertEquals("sample " + i, (int) chunk.tracks.get(i), found.get(i).track);
        }
    }

    @Test
    public void videoThatDoesNotMatchTheLogIsNeverTakenAsVideo() throws Exception {
        Chunk chunk = build(8, false);
        List<SampleJournal.Sample> wrong = new ArrayList<>();
        for (SampleJournal.Sample s : chunk.log) {
            // As if the writer stored video differently: no logged size matches the file
            wrong.add(s.track == SampleJournal.VIDEO ? new SampleJournal.Sample(s.track, s.size + 1, s.ptsUs, s.flags) : s);
        }
        chunk.log.clear();
        chunk.log.addAll(wrong);
        for (ChunkIndex.Located located : locate(chunk, 0)) {
            assertEquals(SampleJournal.AUDIO, located.track); // ChunkRepair then refuses
        }
    }
}
