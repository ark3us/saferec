package net.ark3us.saferec.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileUploaderTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File outbox;
    private FakeUploader uploader;

    /** Fails the files listed in "failing"; otherwise uploads a file by deleting it, like Drive. */
    static class FakeUploader extends FileUploader {
        final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
        final Set<String> failing = ConcurrentHashMap.newKeySet();
        volatile CountDownLatch gate; // when set, uploads wait for it

        FakeUploader(long retryMinMs, long retryMaxMs) {
            super(retryMinMs, retryMaxMs);
        }

        @Override
        public boolean uploadFile(File file) {
            attempts.computeIfAbsent(file.getName(), k -> new AtomicInteger()).incrementAndGet();
            CountDownLatch g = gate;
            if (g != null) {
                try {
                    g.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    return false;
                }
            }
            return !failing.contains(file.getName()) && file.delete();
        }

        int attemptsOf(String name) {
            AtomicInteger count = attempts.get(name);
            return count == null ? 0 : count.get();
        }
    }

    private static void waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("condition not met within " + timeoutMs + " ms");
            }
            Thread.sleep(10);
        }
    }

    private File chunk(String name) throws Exception {
        File file = new File(outbox, name);
        assertTrue(file.createNewFile());
        return file;
    }

    @Before
    public void setUp() throws Exception {
        outbox = tmp.newFolder("data_store");
    }

    @After
    public void tearDown() {
        if (uploader != null) {
            uploader.shutdown();
        }
    }

    private void start(long retryMinMs, long retryMaxMs) {
        uploader = new FakeUploader(retryMinMs, retryMaxMs);
        uploader.setOutbox(outbox);
    }

    @Test
    public void aFileIsQueuedOnceWhileItsUploadRuns() throws Exception {
        start(50, 400);
        File a = chunk("a.mp4");
        CountDownLatch gate = new CountDownLatch(1);
        uploader.gate = gate;
        uploader.upload(a);
        waitUntil(() -> uploader.attemptsOf("a.mp4") == 1, 2000);

        // The chunk callback, a pending-files scan and a network retry all find it again
        uploader.upload(a);
        uploader.upload(outbox);
        uploader.retryNow();
        Thread.sleep(200);
        assertEquals(1, uploader.attemptsOf("a.mp4"));

        gate.countDown();
        waitUntil(() -> !a.exists(), 2000);
        assertEquals(1, uploader.attemptsOf("a.mp4"));
    }

    @Test
    public void aFailedUploadIsRetriedFromTheOutbox() throws Exception {
        start(50, 400);
        File a = chunk("a.mp4");
        uploader.failing.add("a.mp4");
        uploader.upload(a);
        waitUntil(() -> uploader.attemptsOf("a.mp4") >= 3, 3000); // backoff 50, 100 ms ...
        assertTrue(a.exists()); // kept while failing

        uploader.failing.remove("a.mp4");
        waitUntil(() -> !a.exists(), 3000);
    }

    @Test
    public void aSuccessRetriesWaitingFilesAtOnce() throws Exception {
        start(60_000, 60_000); // the backoff alone would wait a minute
        File a = chunk("a.mp4");
        uploader.failing.add("a.mp4");
        uploader.upload(a);
        waitUntil(() -> uploader.attemptsOf("a.mp4") == 1, 2000);
        Thread.sleep(100); // let the failure schedule its rescan
        uploader.failing.remove("a.mp4");

        File b = chunk("b.mp4"); // Drive is reachable again: b goes through ...
        uploader.upload(b);
        waitUntil(() -> !b.exists(), 2000);
        waitUntil(() -> !a.exists(), 2000); // ... and a follows at once, not in a minute
        assertEquals(2, uploader.attemptsOf("a.mp4"));
    }

    @Test
    public void retryNowRescansTheOutbox() throws Exception {
        start(60_000, 60_000);
        File a = chunk("a.mp4"); // left by a crash, never submitted
        uploader.retryNow();
        waitUntil(() -> !a.exists(), 2000);
        assertFalse(a.exists());
    }
}
