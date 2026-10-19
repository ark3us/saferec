package net.ark3us.saferec.net;

import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.api.client.http.FileContent;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.Permission;

public class GoogleDriveClient {

    private static final String TAG = GoogleDriveClient.class.getSimpleName();
    private static final String BASE_FOLDER = "SafeRec";

    private Drive driveService;

    public GoogleDriveClient(String accessToken) {
        HttpRequestInitializer initializer = (HttpRequest request) -> {
            request.getHeaders().setAuthorization("Bearer " + accessToken);
        };

        driveService = new Drive.Builder(
                new NetHttpTransport(),
                new GsonFactory(),
                initializer).setApplicationName("SafeRec").build();
    }

    public void setAccessToken(String newAccessToken) {
        // To change the token, we just recreate the driveService with a new initializer
        driveService = new Drive.Builder(
                new NetHttpTransport(),
                new GsonFactory(),
                (HttpRequest request) -> {
                    request.getHeaders().setAuthorization("Bearer " + newAccessToken);
                }).setApplicationName("SafeRec").build();
    }

    /**
     * IDs of the folders with this name, oldest first. Throws when Drive cannot be asked:
     * a failed lookup must never pass for "not found".
     */
    public List<String> findFolderIds(String folderName, @Nullable String parentFolderId) throws IOException {
        StringBuilder q = new StringBuilder();
        q.append("mimeType='application/vnd.google-apps.folder'");
        q.append(" and name='").append(folderName.replace("'", "\\'")).append("'");
        q.append(" and trashed=false");

        if (parentFolderId != null) {
            q.append(" and '").append(parentFolderId).append("' in parents");
        }

        FileList result = driveService.files().list()
                .setQ(q.toString())
                .setSpaces("drive")
                .setFields("files(id)")
                // Duplicates may exist (older builds made them): every client picks the oldest
                .setOrderBy("createdTime")
                .setPageSize(100)
                .execute();

        List<String> ids = new ArrayList<>();
        if (result.getFiles() != null) {
            for (com.google.api.services.drive.model.File f : result.getFiles()) {
                ids.add(f.getId());
            }
        }
        return ids;
    }

    /** Oldest folder with this name, or null if Drive says there is none. */
    @Nullable
    public String findFolderId(String folderName, @Nullable String parentFolderId) throws IOException {
        List<String> ids = findFolderIds(folderName, parentFolderId);
        if (ids.isEmpty()) {
            Log.i(TAG, "Folder not found: " + folderName);
            return null;
        }
        Log.i(TAG, "Found existing folder: " + folderName + " (ID: " + ids.get(0) + ")");
        return ids.get(0);
    }

    public String createFolder(String folderName, @Nullable String parentFolderId) throws IOException {
        com.google.api.services.drive.model.File metadata = new com.google.api.services.drive.model.File();
        metadata.setName(folderName);
        metadata.setMimeType("application/vnd.google-apps.folder");

        if (parentFolderId != null) {
            metadata.setParents(Collections.singletonList(parentFolderId));
        }

        com.google.api.services.drive.model.File folder = driveService.files()
                .create(metadata)
                .setFields("id")
                .execute();

        Log.i(TAG, "Created folder: " + folderName + " (ID: " + folder.getId() + ")");
        return folder.getId();
    }

    /**
     * Creates the folder only when Drive answered that it does not exist. Treating a timeout
     * as "not found" used to create duplicate folders and split recordings between them.
     */
    public String getOrCreateFolder(String folderName, @Nullable String parentFolderId) throws IOException {
        String folderId = findFolderId(folderName, parentFolderId);
        return folderId != null ? folderId : createFolder(folderName, parentFolderId);
    }

    /**
     * Returns the "SafeRec" base folder ID (create if needed). Cache this to avoid
     * repeated lookups.
     */
    public String getOrCreateBaseFolderId() throws IOException {
        return getOrCreateFolder(BASE_FOLDER, null);
    }

    /** Every "SafeRec" base folder, oldest first. */
    public List<String> findBaseFolderIds() throws IOException {
        return findFolderIds(BASE_FOLDER, null);
    }

    public String shareBaseFolder() {
        try {
            String baseId = getOrCreateBaseFolderId();
            if (baseId == null) {
                Log.e(TAG, "Base folder not found or could not be created");
                return null;
            }

            // Make it public
            Permission permission = new Permission()
                    .setType("anyone")
                    .setRole("reader");

            driveService.permissions().create(baseId, permission).execute();

            // Get the webViewLink
            com.google.api.services.drive.model.File folder = driveService.files().get(baseId).setFields("webViewLink")
                    .execute();
            Log.i(TAG, "Shared base folder: " + folder.getWebViewLink());
            return folder.getWebViewLink();
        } catch (Exception e) {
            Log.e(TAG, "Error sharing base folder", e);
            return null;
        }
    }

    public String getOrCreateNestedFolders(@Nullable String nestedPath, @Nullable String parentFolderId)
            throws IOException {
        // parentFolderId is where the path should be created under (can be null = My
        // Drive root)
        if (nestedPath == null || nestedPath.trim().isEmpty()) {
            return parentFolderId; // no extra folders requested
        }

        // Normalize: support "a/b/c", "/a/b/c/", "a\\b\\c"
        String normalized = nestedPath.trim()
                .replace("\\", "/");

        String[] parts = normalized.split("/+");

        String currentParent = parentFolderId;
        for (String part : parts) {
            if (part == null)
                continue;
            String name = part.trim();
            if (name.isEmpty())
                continue;

            currentParent = getOrCreateFolder(name, currentParent);
        }
        return currentParent;
    }

    /**
     * Uploads a file into an existing folder. The folder is required: resolving it here, outside
     * the uploader's lock, let concurrent uploads create the same folders twice.
     */
    public com.google.api.services.drive.model.File uploadFile(File localFile, String name, String mimeType,
            String parentFolderId, long timestampMillis) {
        try {
            com.google.api.services.drive.model.File meta = new com.google.api.services.drive.model.File();
            meta.setName(name);

            if (timestampMillis > 0) {
                DateTime dt = new DateTime(timestampMillis);
                meta.setCreatedTime(dt);
                meta.setModifiedTime(dt);
            }

            meta.setParents(Collections.singletonList(parentFolderId));

            FileContent media = new FileContent(mimeType, localFile);

            return driveService.files()
                    .create(meta, media)
                    .setFields("id,name,parents,webViewLink")
                    .execute();

        } catch (Exception e) {
            Log.e(TAG, "Failed to upload file to Google Drive", e);
            return null;
        }
    }

    public void downloadFile(String fileId, OutputStream outputStream) throws IOException {
        driveService.files().get(fileId)
                .executeMediaAndDownloadTo(outputStream);
    }

    public enum AuthStatus {
        VALID,
        /** Drive rejected the token (HTTP 401): a new token is needed. */
        INVALID,
        /** Network down or Drive failing: says nothing about the token, keep it. */
        UNKNOWN
    }

    public AuthStatus checkAuthentication() {
        try {
            // A simple lightweight call to check if the token is valid
            driveService.files().list().setPageSize(1).setFields("files(id)").execute();
            return AuthStatus.VALID;
        } catch (HttpResponseException e) {
            Log.e(TAG, "Authentication check failed with HTTP " + e.getStatusCode(), e);
            return e.getStatusCode() == 401 ? AuthStatus.INVALID : AuthStatus.UNKNOWN;
        } catch (Exception e) {
            Log.e(TAG, "Authentication check failed: Drive unreachable", e);
            return AuthStatus.UNKNOWN;
        }
    }

    public Drive getDriveService() {
        return driveService;
    }
}
