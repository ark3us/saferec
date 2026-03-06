package net.ark3us.saferec.net;

import android.net.Uri;
import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class FileDownloader {
    public interface Callback<T> {
        void onSuccess(T result);

        void onError(Exception e);
    }

    protected final ExecutorService executor = Executors.newSingleThreadExecutor();

    public abstract void listRecordings(Callback<List<RecordingItem>> callback);

    public abstract void deleteFiles(List<RecordingItem> items, Callback<Void> callback);

    public abstract void shareFiles(List<RecordingItem> items, Callback<List<Uri>> callback);

    public abstract void downloadFiles(List<RecordingItem> items, Callback<List<File>> callback);

    public static class RecordingItem {
        public final MediaFile mediaFile;
        public final com.google.api.services.drive.model.File driveFile;

        public RecordingItem(MediaFile mediaFile, com.google.api.services.drive.model.File driveFile) {
            this.mediaFile = mediaFile;
            this.driveFile = driveFile;
        }
    }
}
