package net.ark3us.saferec.net;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import com.google.api.services.drive.model.FileList;

import net.ark3us.saferec.media.WorkDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** The recordings on Drive: list, download, share and move to the trash. */
public class DriveRecordings {
    public interface Callback<T> {
        void onSuccess(T result);

        void onError(Exception e);
    }

    public static class RecordingItem {
        public final MediaFile mediaFile;
        public final com.google.api.services.drive.model.File driveFile;
        /** On a session manifest: the chunks it lists that Drive lacks; null when unread or empty. */
        public SessionManifest.Gaps gaps;

        public RecordingItem(MediaFile mediaFile, com.google.api.services.drive.model.File driveFile) {
            this.mediaFile = mediaFile;
            this.driveFile = driveFile;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static final String TAG = DriveRecordings.class.getSimpleName();
    // Session folders the gallery looks in, newest first, past ones whose files were trashed
    private static final int LATEST_CANDIDATES = 5;
    private static final int MANIFEST_THREADS = 4;
    private final GoogleDriveClient driveClient;
    private final Context context;

    public DriveRecordings(Context context, String accessToken) {
        this.context = context.getApplicationContext();
        this.driveClient = new GoogleDriveClient(accessToken);
    }

    /** A new token for the requests from now on (after a refresh); one in flight keeps the old one. */
    public void setAccessToken(String accessToken) {
        driveClient.setAccessToken(accessToken);
    }

    private static String groupKey(MediaFile file) {
        return groupKey(file.sessionId, file.dataType);
    }

    private static String groupKey(String sessionId, String dataType) {
        return sessionId + "|" + dataType;
    }

    /**
     * Reads each session's manifest and records, on its item, which chunks it lists that
     * Drive lacks: still on this phone (waiting to upload, or to be repaired), or lost. A
     * manifest that cannot be read leaves the session unchecked.
     */
    private void markGaps(List<RecordingItem> items) {
        Map<String, Set<Integer>> onDrive = new HashMap<>();
        List<RecordingItem> manifests = new ArrayList<>();
        for (RecordingItem item : items) {
            String extension = item.mediaFile.getExtension();
            if ("mp4".equalsIgnoreCase(extension)) {
                onDrive.computeIfAbsent(groupKey(item.mediaFile), k -> new HashSet<>()).add(item.mediaFile.sequenceNumber);
            } else if (SessionManifest.EXTENSION.equalsIgnoreCase(extension)) {
                manifests.add(item);
            }
        }
        if (manifests.isEmpty()) {
            return;
        }
        Map<String, Set<Integer>> onPhone = chunksOnThisPhone();
        // Each manifest is a Drive round trip: a few at once, or 30 sessions take 15 s. Each task
        // sets only its own item, and get() below makes the results visible here.
        ExecutorService pool = Executors.newFixedThreadPool(MANIFEST_THREADS);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (RecordingItem manifest : manifests) {
                tasks.add(pool.submit(() -> markManifestGaps(manifest, onDrive, onPhone)));
            }
            for (Future<?> task : tasks) {
                try {
                    task.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException e) {
                    Log.w(TAG, "Manifest check failed", e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void markManifestGaps(RecordingItem manifest, Map<String, Set<Integer>> onDrive,
                                  Map<String, Set<Integer>> onPhone) {
        try {
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            driveClient.downloadFile(manifest.driveFile.getId(), content);
            String key = groupKey(manifest.mediaFile);
            manifest.gaps = SessionManifest.gaps(content.toString("UTF-8"),
                    onDrive.getOrDefault(key, Collections.emptySet()),
                    onPhone.getOrDefault(key, Collections.emptySet()));
            if (manifest.gaps == null) {
                Log.w(TAG, "Session " + key + ": the manifest lists no chunk");
            } else if (!manifest.gaps.isEmpty()) {
                Log.w(TAG, "Session " + key + ": on this phone " + manifest.gaps.onPhone + ", lost " + manifest.gaps.lost);
            }
        } catch (IOException e) {
            Log.w(TAG, "Cannot read the manifest of session " + manifest.mediaFile.sessionId, e);
        }
    }

    /** Sequence numbers of the chunks waiting on this phone, by session and type. */
    /** The chunks of one session still on this phone (outbox, recording or repair): not on Drive yet. */
    public Set<Integer> chunksOnThisPhone(String sessionId, String dataType) {
        Set<Integer> chunks = chunksOnThisPhone().get(groupKey(sessionId, dataType));
        return chunks != null ? chunks : new HashSet<>();
    }

    private Map<String, Set<Integer>> chunksOnThisPhone() {
        Map<String, Set<Integer>> chunks = new HashMap<>();
        for (File dir : new File[]{GoogleDriveFileUploader.outboxDir(context), WorkDir.dir(context), WorkDir.repairDir(context)}) {
            File[] files = dir.listFiles(f -> f.getName().endsWith(".mp4"));
            if (files == null) {
                continue;
            }
            for (File file : files) {
                MediaFile chunk = MediaFile.fromFile(file);
                if (chunk != null) {
                    chunks.computeIfAbsent(groupKey(chunk), k -> new HashSet<>()).add(chunk.sequenceNumber);
                }
            }
        }
        return chunks;
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    public void listRecordings(Callback<List<RecordingItem>> callback) {
        executor.execute(() -> {
            try {
                callback.onSuccess(collectItems(null, null));
            } catch (Exception e) {
                Log.e(TAG, "Failed to list recordings (optimized)", e);
                callback.onError(e);
            }
        });
    }

    /** Every session on Drive, newest first, each with its status, pieces and files. */
    public void listSessions(Callback<List<SessionSummary>> callback) {
        listRecordings(new Callback<List<RecordingItem>>() {
            @Override
            public void onSuccess(List<RecordingItem> items) {
                callback.onSuccess(SessionSummary.group(items));
            }

            @Override
            public void onError(Exception e) {
                callback.onError(e);
            }
        });
    }

    /**
     * One session only: its folders and files are asked for by name, so a screen about one
     * session never lists every recording. Calls back with null when Drive has no such session.
     */
    public void session(String sessionId, String dataType, Callback<SessionSummary> callback) {
        executor.execute(() -> {
            try {
                callback.onSuccess(loadSession(sessionId, dataType));
            } catch (Exception e) {
                Log.e(TAG, "Failed to read session " + sessionId, e);
                callback.onError(e);
            }
        });
    }

    private SessionSummary loadSession(String sessionId, String dataType) throws Exception {
        List<SessionSummary> found = SessionSummary.group(collectItems(sessionId, dataType));
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * The newest session only, for the gallery button: Drive sorts the session folders (their
     * names are epoch millis of equal length) and sends the newest few, so the whole list is
     * never read. Prefers the video folder, else audio. A trashed session leaves its folders
     * behind (folders holding trashed files stay), so a folder with no session in it is passed
     * over for the next one. Null when none of them holds a session.
     */
    public void latestSession(Callback<SessionSummary> callback) {
        executor.execute(() -> {
            try {
                callback.onSuccess(findLatestSession());
            } catch (Exception e) {
                Log.e(TAG, "Failed to read the latest session", e);
                callback.onError(e);
            }
        });
    }

    private SessionSummary findLatestSession() throws Exception {
        List<String> baseFolderIds = driveClient.findBaseFolderIds();
        if (baseFolderIds.isEmpty()) {
            return null;
        }
        List<String> bases = baseFolderIds.subList(0, Math.min(50, baseFolderIds.size()));
        StringBuilder q = new StringBuilder(
                "trashed=false and mimeType='application/vnd.google-apps.folder' and (");
        for (int i = 0; i < bases.size(); i++) {
            if (i > 0)
                q.append(" or ");
            q.append("'").append(bases.get(i)).append("' in parents");
        }
        q.append(")");
        FileList newest = driveClient.getDriveService().files().list()
                .setQ(q.toString())
                .setFields("files(id, name)")
                .setOrderBy("name desc")
                .setPageSize(LATEST_CANDIDATES)
                .execute();
        if (newest.getFiles() == null) {
            return null;
        }
        Set<String> tried = new HashSet<>();
        for (com.google.api.services.drive.model.File candidate : newest.getFiles()) {
            String sessionId = candidate.getName();
            // Not a session's folder (a session's name is its start time), or one already looked in
            if (sessionId == null || !sessionId.matches("\\d+") || !tried.add(sessionId)) {
                continue;
            }
            SessionSummary latest = sessionIn(bases, sessionId);
            if (latest != null) {
                return latest;
            }
        }
        return null;
    }

    /** The session of that name, its video if any, else its audio; null when it holds neither. */
    private SessionSummary sessionIn(List<String> bases, String sessionId) throws Exception {
        // The session may be split between duplicate base folders: look in every folder of that name
        List<String> sessionFolderIds = new ArrayList<>();
        for (com.google.api.services.drive.model.File f : fetchFilesBatch(bases, true, sessionId)) {
            sessionFolderIds.add(f.getId());
        }
        for (String type : new String[] { "video", "audio" }) {
            if (!fetchFilesBatch(sessionFolderIds, true, type).isEmpty()) {
                SessionSummary session = loadSession(sessionId, type);
                if (session != null) {
                    return session;
                }
            }
        }
        return null;
    }

    /**
     * The files of every session, or of one when {@code onlySession} and {@code onlyType} are
     * set (the folders are then matched by name), with the gaps read from the manifests.
     * Newest session first, then by time.
     */
    private List<RecordingItem> collectItems(String onlySession, String onlyType) throws Exception {
        List<String> baseFolderIds = driveClient.findBaseFolderIds();
        if (baseFolderIds.isEmpty()) {
            return new ArrayList<>();
        }

        List<RecordingItem> items = new ArrayList<>();

        // 1. Fetch Session Folders, from every base folder: older builds could
        // create duplicates and split a session between them
        List<com.google.api.services.drive.model.File> sessionFolders = fetchFilesBatch(baseFolderIds, true, onlySession);
        Map<String, String> folderIdToSessionId = new HashMap<>();
        List<String> sessionFolderIds = new ArrayList<>();
        for (com.google.api.services.drive.model.File f : sessionFolders) {
            folderIdToSessionId.put(f.getId(), f.getName());
            sessionFolderIds.add(f.getId());
        }

        if (sessionFolderIds.isEmpty()) {
            return items;
        }

        // 2. Fetch DataType Folders
        List<com.google.api.services.drive.model.File> dataTypeFolders = fetchFilesBatch(sessionFolderIds,
                true, onlyType);
        Map<String, String> folderIdToDataType = new HashMap<>();
        Map<String, String> dataTypeToSessionId = new HashMap<>();
        List<String> dataTypeFolderIds = new ArrayList<>();
        for (com.google.api.services.drive.model.File f : dataTypeFolders) {
            folderIdToDataType.put(f.getId(), f.getName());
            if (f.getParents() != null && !f.getParents().isEmpty()) {
                String parentId = f.getParents().get(0);
                dataTypeToSessionId.put(f.getId(), folderIdToSessionId.get(parentId));
            }
            dataTypeFolderIds.add(f.getId());
        }

        if (dataTypeFolderIds.isEmpty()) {
            return items;
        }

        // 3. Fetch Media Files
        List<com.google.api.services.drive.model.File> mediaFiles = fetchFilesBatch(dataTypeFolderIds, false, null);
        for (com.google.api.services.drive.model.File file : mediaFiles) {
            if (file.getParents() != null && !file.getParents().isEmpty()) {
                String parentId = file.getParents().get(0);
                String dataType = folderIdToDataType.get(parentId);
                String sessionId = dataTypeToSessionId.get(parentId);

                if (sessionId != null && dataType != null) {
                    MediaFile mediaFile = MediaFile.fromDriveData(sessionId, dataType, file.getName());
                    if (mediaFile != null) {
                        items.add(new RecordingItem(mediaFile, file));
                    }
                }
            }
        }

        markGaps(items);

        // Sort by sessionId (descending) and then by timestamp
        items.sort((a, b) -> {
            int sessionComp = b.mediaFile.sessionId.compareTo(a.mediaFile.sessionId);
            if (sessionComp != 0)
                return sessionComp;
            return Long.compare(a.mediaFile.timestamp, b.mediaFile.timestamp);
        });

        return items;
    }

    private List<com.google.api.services.drive.model.File> fetchFilesBatch(List<String> parentIds, boolean isFolder, String name)
            throws Exception {
        List<com.google.api.services.drive.model.File> allResults = new ArrayList<>();
        Log.d(TAG, "Fetching " + (isFolder ? "folders" : "files") + " for parent count=" + parentIds.size());
        for (int i = 0; i < parentIds.size(); i += 50) {
            List<String> batch = parentIds.subList(i, Math.min(i + 50, parentIds.size()));
            StringBuilder q = new StringBuilder("trashed=false and ");
            if (isFolder) {
                q.append("mimeType='application/vnd.google-apps.folder' and (");
            } else {
                q.append("mimeType!='application/vnd.google-apps.folder' and (");
            }

            for (int k = 0; k < batch.size(); k++) {
                if (k > 0)
                    q.append(" or ");
                q.append("'").append(batch.get(k)).append("' in parents");
            }
            q.append(")");
            if (name != null) {
                q.append(" and name='").append(name.replace("\\", "\\\\").replace("'", "\\'")).append("'");
            }

            String fields = isFolder ? "files(id, name, parents)"
                    : "files(id, name, parents, size, createdTime, webViewLink, thumbnailLink, videoMediaMetadata(durationMillis))";
            String pageToken = null;
            do {
                FileList list = driveClient.getDriveService().files().list()
                        .setQ(q.toString())
                        .setFields(fields + ",nextPageToken")
                        .setPageToken(pageToken)
                        .setPageSize(1000)
                        .execute();
                if (list.getFiles() != null) {
                    allResults.addAll(list.getFiles());
                }
                pageToken = list.getNextPageToken();
            } while (pageToken != null && !pageToken.isEmpty());
        }
        Log.d(TAG, "Fetched " + allResults.size() + " " + (isFolder ? "folders" : "files"));
        return allResults;
    }

    /**
     * Never deletes for good: whoever forces the phone open (or a slip of the finger) could
     * otherwise destroy the evidence. Drive keeps trashed files for 30 days.
     */
    private void trash(String fileId) throws Exception {
        com.google.api.services.drive.model.File trashed = new com.google.api.services.drive.model.File();
        trashed.setTrashed(true);
        driveClient.getDriveService().files().update(fileId, trashed).execute();
    }

    public void deleteFilesById(List<String> fileIds, Set<String> folderIdsToCheck, Callback<Void> callback) {
        executor.execute(() -> {
            try {
                for (String fileId : fileIds) {
                    trash(fileId);
                }

                // Optional: Clean up empty folders (DataType and then Session)
                if (folderIdsToCheck != null) {
                    for (String folderId : folderIdsToCheck) {
                        try {
                            trashEmptyFolderRecursively(folderId);
                        } catch (Exception folderEx) {
                            Log.e(TAG, "Failed to clean folders for: " + folderId, folderEx);
                        }
                    }
                }

                if (callback != null) callback.onSuccess(null);
            } catch (Exception e) {
                if (callback != null) callback.onError(e);
            }
        });
    }

    /**
     * Folders holding trashed files stay: "Restore" in Drive then puts the files back where
     * they were. Deleting such a folder for good would also delete the trashed files in it.
     */
    public void trashEmptyFolderRecursively(String folderId) throws Exception {
        if (folderId == null)
            return;

        // Fetch folder metadata to get parent and name
        com.google.api.services.drive.model.File folder = driveClient.getDriveService().files().get(folderId)
                .setFields("id, name, parents")
                .execute();

        // Don't delete the SafeRec folder itself!
        if ("SafeRec".equals(folder.getName())) {
            return;
        }

        FileList children = driveClient.getDriveService().files().list()
                .setQ(String.format("'%s' in parents", folderId)) // trashed children count too
                .setFields("files(id)")
                .setPageSize(1)
                .execute();

        if (children.getFiles() == null || children.getFiles().isEmpty()) {
            Log.d(TAG, "Trashing empty folder: " + folder.getName() + " (" + folderId + ")");
            trash(folderId);

            // Recurse to parent
            if (folder.getParents() != null && !folder.getParents().isEmpty()) {
                trashEmptyFolderRecursively(folder.getParents().get(0));
            }
        }
    }

    public void shareFiles(List<RecordingItem> items, Callback<List<Uri>> callback) {
        executor.execute(() -> {
            try {
                List<Uri> uris = new ArrayList<>();
                File cacheDir = new File(context.getCacheDir(), "recordings");
                if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                    callback.onError(new IllegalStateException("Failed to create cache dir: " + cacheDir.getAbsolutePath()));
                    return;
                }

                for (RecordingItem item : items) {
                    String fileName = item.mediaFile.sessionId + "_" + item.mediaFile.dataType + "_"
                            + item.driveFile.getName();
                    File localFile = new File(cacheDir, fileName);

                    try (FileOutputStream fos = new FileOutputStream(localFile)) {
                        driveClient.downloadFile(item.driveFile.getId(), fos);
                    }

                    Uri contentUri = FileProvider.getUriForFile(context,
                            context.getPackageName() + ".fileprovider", localFile);
                    uris.add(contentUri);
                }
                callback.onSuccess(uris);
            } catch (Exception e) {
                Log.e(TAG, "Share prepare failed", e);
                callback.onError(e);
            }
        });
    }

    public void downloadFiles(List<RecordingItem> items, Callback<List<File>> callback) {
        executor.execute(() -> {
            try {
                List<File> localFiles = new ArrayList<>();
                File cacheDir = new File(context.getCacheDir(), "merging");
                if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                    callback.onError(new IllegalStateException("Failed to create merge cache dir: " + cacheDir.getAbsolutePath()));
                    return;
                }

                for (RecordingItem item : items) {
                    String fileName = item.mediaFile.sessionId + "_" + item.mediaFile.dataType + "_"
                            + item.driveFile.getName();
                    File localFile = new File(cacheDir, fileName);

                    try (FileOutputStream fos = new FileOutputStream(localFile)) {
                        driveClient.downloadFile(item.driveFile.getId(), fos);
                    }
                    localFiles.add(localFile);
                }
                callback.onSuccess(localFiles);
            } catch (Exception e) {
                Log.e(TAG, "Download for merge failed", e);
                callback.onError(e);
            }
        });
    }
}
