package net.ark3us.saferec.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;

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
}
