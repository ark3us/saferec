package net.ark3us.saferec.net;

import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public abstract class FileUploader {
    private static final String TAG = FileUploader.class.getSimpleName();
    private static final int MAX_CONCURRENT_UPLOADS = 4;
    // Backoff between outbox rescans after a failure: 10 s, 20 s, 40 s ... up to 5 min
    private final long retryMinMs;
    private final long retryMaxMs;
    protected final ExecutorService uploadExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_UPLOADS);
    protected final ScheduledExecutorService scanExecutor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger activeUploads = new AtomicInteger(0);
    private final Set<String> queuedFiles = ConcurrentHashMap.newKeySet();
    private volatile File outbox;
    // Own lock: subclasses may hold "this" during network calls (Drive folder lookups)
    private final Object retryLock = new Object();
    private long retryDelayMs; // guarded by retryLock
    private ScheduledFuture<?> pendingRetry; // guarded by retryLock
    // Rescans after a failure only make sense while something keeps the process alive
    private volatile boolean retryLoop = false;
    private static final long DRAIN_POLL_MS = 200;

    protected FileUploader() {
        this(10_000, 5 * 60_000);
    }

    /** Short delays let the unit tests run the retry logic in milliseconds. */
    FileUploader(long retryMinMs, long retryMaxMs) {
        this.retryMinMs = retryMinMs;
        this.retryMaxMs = retryMaxMs;
        this.retryDelayMs = retryMinMs;
    }

    public interface UploadListener {
        void onUploadStatusChanged(int activeUploads);

        /** A file reached Drive. Called once per successful upload, before the next scan. */
        default void onFileUploaded(File file) { }
    }

    private volatile UploadListener listener;

    public void setListener(@Nullable UploadListener listener) {
        this.listener = listener;
    }

    /**
     * Uploads the file and deletes it locally on success. Returns true on success.
     *
     * @param maybeSentBefore an earlier attempt may have reached the server without its answer
     *                        coming back (the process died, a timeout): check before sending
     *                        it again, or the server ends up with it twice
     */
    public abstract boolean uploadFile(File file, boolean maybeSentBefore);

    /**
     * A file waits in the outbox and may outlive this process: queued, or its upload failed.
     * Subclasses schedule a job that survives the process here.
     */
    protected void onFilePending(File file) {
    }

    /** Files left in this directory by a failed upload are retried until they get through. */
    public void setOutbox(File dir) {
        outbox = dir;
    }

    /**
     * On: a failure rescans the outbox later, 10 s doubling up to 5 min. Off: the file waits
     * for retryNow(), drain() or the next upload.
     */
    public void setRetryLoop(boolean on) {
        retryLoop = on;
        if (!on) {
            synchronized (retryLock) {
                if (pendingRetry != null) {
                    pendingRetry.cancel(false);
                    pendingRetry = null;
                }
                retryDelayMs = retryMinMs;
            }
        }
    }

    protected void notifyStatusChanged() {
        if (listener != null) {
            listener.onUploadStatusChanged(activeUploads.get());
        }
    }

    protected void onUploadStarted() {
        activeUploads.incrementAndGet();
        notifyStatusChanged();
    }

    protected void onUploadFinished() {
        activeUploads.decrementAndGet();
        notifyStatusChanged();
    }

    private boolean wrappedUploadFile(File file, boolean maybeSentBefore) {
        onUploadStarted();
        try {
            return uploadFile(file, maybeSentBefore);
        } finally {
            onUploadFinished();
        }
    }

    /**
     * A pending-files scan can find a chunk whose upload is already queued or running:
     * uploading it twice would duplicate it on Drive, so each file is queued at most once.
     */
    private void submit(File file) {
        submit(file, false);
    }

    /**
     * isNew: the file just entered the outbox, so this is its first upload. Only the caller that
     * queues it gets to say so: if a scan queued it first, that upload checks, at the cost of
     * one request. Everything else may be a repeat: a retry, or a file a dead process left in
     * the outbox, maybe in the middle of its upload.
     */
    private void submit(File file, boolean isNew) {
        String path = file.getAbsolutePath();
        // exists(): a scan may list a file that was uploaded and deleted meanwhile
        if (!file.exists() || !queuedFiles.add(path)) {
            return;
        }
        onFilePending(file);
        boolean maybeSentBefore = !isNew;
        uploadExecutor.submit(() -> {
            boolean uploaded = false;
            try {
                uploaded = wrappedUploadFile(file, maybeSentBefore);
            } catch (RuntimeException e) {
                Log.e(TAG, "Upload crashed: " + file.getName(), e);
            } finally {
                // Release before scheduling: a rescan must be able to pick the file up again
                queuedFiles.remove(path);
            }
            if (uploaded) {
                UploadListener l = listener;
                if (l != null) {
                    l.onFileUploaded(file);
                }
                onUploadSucceeded();
            } else if (file.exists()) {
                onFilePending(file);
                onUploadFailed();
            }
        });
    }

    /** Rescans the outbox now, e.g. when the network comes back. */
    public void retryNow() {
        File dir = outbox;
        if (dir == null) {
            return;
        }
        synchronized (retryLock) {
            if (pendingRetry != null) {
                pendingRetry.cancel(false);
                pendingRetry = null;
            }
            retryDelayMs = retryMinMs;
        }
        scanExecutor.execute(() -> uploadInternal(dir));
    }

    private void onUploadFailed() {
        File dir = outbox;
        if (dir == null || !retryLoop) {
            return;
        }
        synchronized (retryLock) {
            if (pendingRetry != null) {
                return; // one rescan at a time covers every file in the outbox
            }
            Log.i(TAG, "Upload failed, rescanning the outbox in " + retryDelayMs / 1000 + " s");
            pendingRetry = scanExecutor.schedule(() -> {
                synchronized (retryLock) {
                    pendingRetry = null;
                }
                uploadInternal(dir);
            }, retryDelayMs, TimeUnit.MILLISECONDS);
            retryDelayMs = Math.min(retryDelayMs * 2, retryMaxMs);
        }
    }

    /** A success means Drive is reachable again: files waiting for the backoff go now. */
    private void onUploadSucceeded() {
        boolean retryWaiting;
        synchronized (retryLock) {
            retryWaiting = pendingRetry != null;
            retryDelayMs = retryMinMs;
        }
        if (retryWaiting) {
            retryNow();
        }
    }

    private void uploadInternal(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] files = fileOrDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    uploadInternal(file);
                }
            }
        } else {
            submit(fileOrDir);
        }
    }

    /**
     * A file that just entered the outbox (a chunk closed, a timestamp received): nothing can
     * have sent it yet, so its first upload skips the check for an earlier copy.
     */
    public void uploadNew(File file) {
        if (file != null && file.isFile()) {
            submit(file, true);
        }
    }

    /** Single file: submit directly to upload executor. Directory: scan then submit each file. */
    public void upload(File fileOrDir) {
        if (fileOrDir == null) {
            return;
        }
        if (!fileOrDir.isDirectory()) {
            submit(fileOrDir);
            return;
        }
        scanExecutor.execute(() -> uploadInternal(fileOrDir));
    }

    /**
     * Uploads what waits in the outbox and blocks until it is empty (true), an upload failed
     * (false) or the caller stops (false). An upload already running, for the recording, is
     * waited for, never started twice. For the background job, which must know whether to
     * come back later.
     */
    public boolean drain(BooleanSupplier stopped) throws InterruptedException {
        File dir = outbox;
        if (dir == null) {
            return true;
        }
        while (!stopped.getAsBoolean()) {
            File[] files = dir.listFiles(File::isFile);
            if (files == null || files.length == 0) {
                return true;
            }
            for (File file : files) {
                submit(file);
            }
            while (anyQueued(files)) {
                if (stopped.getAsBoolean()) {
                    return false;
                }
                Thread.sleep(DRAIN_POLL_MS);
            }
            for (File file : files) {
                if (file.exists() && !queuedFiles.contains(file.getAbsolutePath())) {
                    return false; // its upload failed
                }
            }
            // Chunks recorded meanwhile: go round again
        }
        return false;
    }

    private boolean anyQueued(File[] files) {
        for (File file : files) {
            if (queuedFiles.contains(file.getAbsolutePath())) {
                return true;
            }
        }
        return false;
    }

    public void shutdown() {
        scanExecutor.shutdownNow();
        uploadExecutor.shutdownNow();
    }
}
