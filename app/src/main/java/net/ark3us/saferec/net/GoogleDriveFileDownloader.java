package net.ark3us.saferec.net;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import androidx.core.content.FileProvider;

import com.google.api.services.drive.model.FileList;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GoogleDriveFileDownloader extends FileDownloader {

    private static final String TAG = GoogleDriveFileDownloader.class.getSimpleName();
    private final GoogleDriveClient driveClient;
    private final Context context;

    public GoogleDriveFileDownloader(Context context, String accessToken) {
        this.context = context.getApplicationContext();
        this.driveClient = new GoogleDriveClient(accessToken);
    }

    @Override
    public void listRecordings(Callback<List<RecordingItem>> callback) {
        executor.execute(() -> {
            try {
                String safeRecId = driveClient.findFolderId("SafeRec", null);
                if (safeRecId == null) {
                    callback.onSuccess(new ArrayList<>());
                    return;
                }

                List<RecordingItem> items = new ArrayList<>();

                // 1. Fetch Session Folders
                List<com.google.api.services.drive.model.File> sessionFolders = fetchFilesBatch(
                        Collections.singletonList(safeRecId), true);
                Map<String, String> folderIdToSessionId = new HashMap<>();
                List<String> sessionFolderIds = new ArrayList<>();
                for (com.google.api.services.drive.model.File f : sessionFolders) {
                    folderIdToSessionId.put(f.getId(), f.getName());
                    sessionFolderIds.add(f.getId());
                }

                if (sessionFolderIds.isEmpty()) {
                    callback.onSuccess(items);
                    return;
                }

                // 2. Fetch DataType Folders
                List<com.google.api.services.drive.model.File> dataTypeFolders = fetchFilesBatch(sessionFolderIds,
                        true);
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
                    callback.onSuccess(items);
                    return;
                }

                // 3. Fetch Media Files
                List<com.google.api.services.drive.model.File> mediaFiles = fetchFilesBatch(dataTypeFolderIds, false);
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

                // Sort by sessionId (descending) and then by timestamp
                items.sort((a, b) -> {
                    int sessionComp = b.mediaFile.sessionId.compareTo(a.mediaFile.sessionId);
                    if (sessionComp != 0)
                        return sessionComp;
                    return Long.compare(a.mediaFile.timestamp, b.mediaFile.timestamp);
                });

                callback.onSuccess(items);
            } catch (Exception e) {
                Log.e(TAG, "Failed to list recordings (optimized)", e);
                callback.onError(e);
            }
        });
    }

    private List<com.google.api.services.drive.model.File> fetchFilesBatch(List<String> parentIds, boolean isFolder)
            throws Exception {
        List<com.google.api.services.drive.model.File> allResults = new ArrayList<>();
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

            String fields = isFolder ? "files(id, name, parents)"
                    : "files(id, name, parents, size, createdTime, webViewLink, thumbnailLink)";
            FileList list = driveClient.getDriveService().files().list()
                    .setQ(q.toString())
                    .setFields(fields)
                    .execute();
            if (list.getFiles() != null)
                allResults.addAll(list.getFiles());
        }
        return allResults;
    }

    @Override
    public void deleteFiles(List<RecordingItem> items, Callback<Void> callback) {
        executor.execute(() -> {
            try {
                Set<String> folderIdsToCheck = new HashSet<>();
                for (RecordingItem item : items) {
                    // Get parents before deleting if not available
                    List<String> parents = item.driveFile.getParents();
                    if (parents != null && !parents.isEmpty()) {
                        folderIdsToCheck.addAll(parents);
                    }
                    driveClient.getDriveService().files().delete(item.driveFile.getId()).execute();
                }

                // Optional: Clean up empty folders (DataType and then Session)
                for (String folderId : folderIdsToCheck) {
                    try {
                        cleanEmptyFolderRecursively(folderId);
                    } catch (Exception folderEx) {
                        Log.e(TAG, "Failed to clean folders for: " + folderId, folderEx);
                    }
                }

                callback.onSuccess(null);
            } catch (Exception e) {
                callback.onError(e);
            }
        });
    }

    private void cleanEmptyFolderRecursively(String folderId) throws Exception {
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
                .setQ(String.format("trashed=false and '%s' in parents", folderId))
                .setFields("files(id)")
                .setPageSize(1)
                .execute();

        if (children.getFiles() == null || children.getFiles().isEmpty()) {
            Log.d(TAG, "Deleting empty folder: " + folder.getName() + " (" + folderId + ")");
            driveClient.getDriveService().files().delete(folderId).execute();

            // Recurse to parent
            if (folder.getParents() != null && !folder.getParents().isEmpty()) {
                cleanEmptyFolderRecursively(folder.getParents().get(0));
            }
        }
    }

    @Override
    public void shareFiles(List<RecordingItem> items, Callback<List<Uri>> callback) {
        executor.execute(() -> {
            try {
                List<Uri> uris = new ArrayList<>();
                File cacheDir = new File(context.getCacheDir(), "recordings");
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs();
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

    @Override
    public void downloadFiles(List<RecordingItem> items, Callback<List<File>> callback) {
        executor.execute(() -> {
            try {
                List<File> localFiles = new ArrayList<>();
                File cacheDir = new File(context.getCacheDir(), "merging");
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs();
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
