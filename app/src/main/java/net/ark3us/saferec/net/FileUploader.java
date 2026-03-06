package net.ark3us.saferec.net;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class FileUploader {
    private static final String TAG = FileUploader.class.getSimpleName();
    private static final int MAX_CONCURRENT_UPLOADS = 4;
    protected final ExecutorService uploadExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_UPLOADS);
    protected final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger activeUploads = new AtomicInteger(0);

    public interface UploadListener {
        void onUploadStatusChanged(int activeUploads);
    }

    private UploadListener listener;

    public void setListener(UploadListener listener) {
        this.listener = listener;
    }

    public abstract void uploadFile(File file);

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

    private void wrappedUploadFile(File file) {
        onUploadStarted();
        try {
            uploadFile(file);
        } finally {
            onUploadFinished();
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
            uploadExecutor.submit(() -> wrappedUploadFile(fileOrDir));
        }
    }

    /** Single file: submit directly to upload executor. Directory: scan then submit each file. */
    public void upload(File fileOrDir) {
        if (fileOrDir != null && !fileOrDir.isDirectory()) {
            uploadExecutor.submit(() -> wrappedUploadFile(fileOrDir));
            return;
        }
        scanExecutor.submit(() -> uploadInternal(fileOrDir));
    }
}
