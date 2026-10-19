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
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.Permission;

public class GoogleDriveClient {

    private static final String TAG = GoogleDriveClient.class.getSimpleName();
    private static final String BASE_FOLDER = "SafeRec";

    // Upload threads read these while a token refresh replaces them
    private volatile String accessToken;
    private volatile Drive driveService;

    public GoogleDriveClient(String accessToken) {
        setAccessToken(accessToken);
    }

    public void setAccessToken(String newAccessToken) {
        // To change the token, we just recreate the driveService with a new initializer
        HttpRequestInitializer initializer = (HttpRequest request) -> {
            request.getHeaders().setAuthorization("Bearer " + newAccessToken);
        };
        driveService = new Drive.Builder(
                new NetHttpTransport(),
                new GsonFactory(),
                initializer).setApplicationName("SafeRec").build();
        accessToken = newAccessToken;
    }

    @Nullable
    public String getAccessToken() {
        return accessToken;
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

    /**
     * Gives one Google account read access to every recording, future ones included, and has
     * Google email it. Never "anyone with the link": that exposed every recording to whoever
     * got the link, and the app could not take it back.
     */
    public void shareBaseFolderWith(String email) throws IOException {
        Permission permission = new Permission()
                .setType("user")
                .setRole("reader")
                .setEmailAddress(email);
        driveService.permissions().create(getOrCreateBaseFolderId(), permission)
                .setSendNotificationEmail(true)
                .execute();
        Log.i(TAG, "Shared base folder with one account");
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
     * The non-folder file with this name in the folder, with its size and MD5, or null when
     * there is none. Throws when Drive cannot be asked.
     */
    @Nullable
    public com.google.api.services.drive.model.File findFile(String name, String parentFolderId) throws IOException {
        FileList result = driveService.files().list()
                .setQ("name='" + name.replace("'", "\\'") + "' and '" + parentFolderId + "' in parents"
                        + " and trashed=false and mimeType!='application/vnd.google-apps.folder'")
                .setSpaces("drive")
                .setFields("files(id,name,size,md5Checksum)")
                .setPageSize(10)
                .execute();
        List<com.google.api.services.drive.model.File> files = result.getFiles();
        return files == null || files.isEmpty() ? null : files.get(0);
    }

    /**
     * Uploads a file into an existing folder. The folder is required: resolving it here, outside
     * the uploader's lock, let concurrent uploads create the same folders twice.
     */
    public com.google.api.services.drive.model.File uploadFile(File localFile, String name, String mimeType,
            String parentFolderId) throws IOException {
        com.google.api.services.drive.model.File meta = new com.google.api.services.drive.model.File();
        meta.setName(name);
        // Declared, or Drive guesses from the content: it took a cut chunk's original for video
        meta.setMimeType(mimeType);
        // No createdTime: Drive sets it to the upload time, a witness the phone's clock cannot
        // fake. The recording time is already in the name.
        meta.setParents(Collections.singletonList(parentFolderId));

        FileContent media = new FileContent(mimeType, localFile);

        return driveService.files()
                .create(meta, media)
                .setFields("id,name,parents,webViewLink")
                .execute();
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
