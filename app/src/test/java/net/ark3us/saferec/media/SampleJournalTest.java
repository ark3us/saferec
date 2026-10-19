package net.ark3us.saferec.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SampleJournalTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File writeLog(int samples) throws Exception {
        File file = tmp.newFile("x_video_1.0.mp4.idx");
        try (SampleJournal.Writer writer = new SampleJournal.Writer(file)) {
            Map<String, Object> video = new LinkedHashMap<>();
            video.put("mime", "video/avc");
            video.put("width", 640);
            video.put("durationUs", 10_000_000L);
            video.put("csd-0", new byte[]{0, 0, 0, 1, 0x67, 0x42});
            writer.format(SampleJournal.VIDEO, video);
            writer.rotation(90);
            for (int i = 0; i < samples; i++) {
                writer.sample(i % 2, 100 + i, 1000L * i, i == 0 ? 1 : 0);
            }
        }
        return file;
    }

    @Test
    public void readsBackWhatWasWritten() throws Exception {
        SampleJournal journal = SampleJournal.read(writeLog(5));
        Map<String, Object> video = journal.formats.get(SampleJournal.VIDEO);
        assertEquals("video/avc", video.get("mime"));
        assertEquals(640, video.get("width"));
        assertEquals(10_000_000L, video.get("durationUs"));
        assertArrayEquals(new byte[]{0, 0, 0, 1, 0x67, 0x42}, (byte[]) video.get("csd-0"));
        assertEquals(90, journal.rotation);
        assertEquals(5, journal.samples.size());
        SampleJournal.Sample last = journal.samples.get(4);
        assertEquals(SampleJournal.VIDEO, last.track);
        assertEquals(104, last.size);
        assertEquals(4000L, last.ptsUs);
    }

    @Test
    public void aRecordCutShortEndsTheLog() throws Exception {
        File file = writeLog(5);
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(raf.length() - 3); // the power went in the middle of the last sample
        }
        SampleJournal journal = SampleJournal.read(file);
        assertEquals(4, journal.samples.size());
        assertEquals(90, journal.rotation);
    }

    @Test
    public void aForeignFileIsNotALog() throws Exception {
        File file = tmp.newFile("foreign.idx");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[]{1, 2, 3, 4, 5, 6});
        }
        assertNull(SampleJournal.read(file));
        assertNull(SampleJournal.read(tmp.newFile("empty.idx")));
    }
}
