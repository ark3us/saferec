package net.ark3us.saferec.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class WorkDirTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File work;
    private File outbox;
    private WorkDir workDir;

    @Before
    public void setUp() throws Exception {
        work = tmp.newFolder("recording");
        outbox = new File(tmp.getRoot(), "data_store"); // created on demand
        workDir = new WorkDir();
    }

    private File chunk(String name, int bytes) throws Exception {
        File file = new File(work, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[bytes]);
        }
        return file;
    }

    /** Writes "repaired" as the playable copy, or fails. */
    private static WorkDir.Repairer repairer(boolean succeeds) {
        return (cut, journal, out) -> {
            if (!succeeds) {
                return false;
            }
            try (FileOutputStream stream = new FileOutputStream(out)) {
                stream.write("repaired".getBytes());
            } catch (Exception e) {
                return false;
            }
            return true;
        };
    }

    private static String content(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()));
    }

    @Test
    public void leftoversGoToTheOutboxAndEmptyOnesAreDropped() throws Exception {
        File cut = chunk("a.mp4", 100);
        File empty = chunk("b.mp4", 0);
        assertEquals(1, workDir.recover(work, outbox));
        assertFalse(cut.exists());
        assertTrue(new File(outbox, "a.mp4").exists());
        assertFalse(empty.exists());
        assertFalse(new File(outbox, "b.mp4").exists());
    }

    @Test
    public void nothingMovesWhileARecordingUsesTheDirectory() throws Exception {
        File open = chunk("a.mp4", 100); // being written
        workDir.acquire();
        assertEquals(-1, workDir.recover(work, outbox));
        assertTrue(open.exists());

        workDir.release(); // its last chunk left: what is still there is a leftover
        assertEquals(1, workDir.recover(work, outbox));
        assertFalse(open.exists());
    }

    @Test
    public void aCameraSwitchKeepsTheDirectoryInUse() throws Exception {
        chunk("a.mp4", 100);
        workDir.acquire();  // first recording
        workDir.acquire();  // the restart after the switch, before the first one is post-processed
        workDir.release();  // the first one's last chunk left
        assertTrue(workDir.inUse());
        assertEquals(-1, workDir.recover(work, outbox));
        workDir.release();
        assertFalse(workDir.inUse());
    }

    @Test
    public void anExtraReleaseDoesNotHideALaterRecording() throws Exception {
        workDir.release();
        workDir.acquire();
        assertTrue(workDir.inUse());
    }

    @Test
    public void aChunkWithItsLogIsRepairedAndItsOriginalKept() throws Exception {
        chunk("x_video_1.0.mp4", 100);
        chunk("x_video_1.0.mp4.idx", 40);
        assertEquals(1, workDir.recover(work, outbox, repairer(true)));
        assertEquals("repaired", content(new File(outbox, "x_video_1.0.mp4")));
        assertEquals(100, new File(outbox, "x_video_1.0.cut").length()); // untouched original
        assertFalse(new File(outbox, "x_video_1.0.mp4.idx").exists()); // a log never reaches the outbox
        assertEquals(0, new File(tmp.getRoot(), "repair").list().length);
    }

    @Test
    public void aFailedRepairSendsTheOriginalAsItIs() throws Exception {
        chunk("x_video_1.0.mp4", 100);
        chunk("x_video_1.0.mp4.idx", 40);
        assertEquals(1, workDir.recover(work, outbox, repairer(false)));
        assertEquals(100, new File(outbox, "x_video_1.0.mp4").length());
        assertFalse(new File(outbox, "x_video_1.0.cut").exists());
        assertEquals(1, outbox.list().length);
    }

    @Test
    public void aRepairCutShortByADeadProcessStartsOver() throws Exception {
        File repairDir = new File(tmp.getRoot(), "repair");
        assertTrue(repairDir.mkdirs());
        // Left by a process that died during the repair
        Files.write(new File(repairDir, "x_video_1.0.mp4").toPath(), new byte[100]);
        Files.write(new File(repairDir, "x_video_1.0.mp4.idx").toPath(), new byte[40]);
        Files.write(new File(repairDir, "x_video_1.0.mp4.repairing").toPath(), new byte[7]);
        assertEquals(1, workDir.recover(work, outbox, repairer(true)));
        assertEquals("repaired", content(new File(outbox, "x_video_1.0.mp4")));
        assertTrue(new File(outbox, "x_video_1.0.cut").exists());
        assertEquals(0, repairDir.list().length);
    }

    @Test
    public void aRepairLeftWithoutItsChunkIsDeleted() throws Exception {
        File repairDir = new File(tmp.getRoot(), "repair");
        assertTrue(repairDir.mkdirs());
        // Its chunk already reached the outbox: kept, it would make every boot schedule a job
        Files.write(new File(repairDir, "y_video_1.0.mp4.repairing").toPath(), new byte[7]);
        assertEquals(0, workDir.recover(work, outbox, repairer(true)));
        assertEquals(0, repairDir.list().length);
    }

    @Test
    public void aLogWithoutItsChunkIsDropped() throws Exception {
        chunk("x_video_1.0.mp4.idx", 40); // the chunk was closed and handed over
        assertEquals(0, workDir.recover(work, outbox, repairer(true)));
        assertEquals(0, work.list().length);
        assertFalse(outbox.exists() && outbox.list().length > 0);
    }

    @Test
    public void nothingIsRepairedWhileARecordingUsesTheDirectory() throws Exception {
        File open = chunk("x_video_1.0.mp4", 100);
        File log = chunk("x_video_1.0.mp4.idx", 40);
        workDir.acquire();
        assertEquals(-1, workDir.recover(work, outbox, repairer(true)));
        assertTrue(open.exists());
        assertTrue(log.exists());
    }

    @Test
    public void theOriginalIsNamedAfterItsChunk() {
        assertEquals("x_video_1792394620112.3.cut", WorkDir.cutName("x_video_1792394620112.3.mp4"));
    }
}
