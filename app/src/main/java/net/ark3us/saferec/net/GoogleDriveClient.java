package net.ark3us.saferec.net;

import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;

import com.google.api.client.http.FileContent;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.Permission;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;

public class GoogleDriveClient {

    private static final String TAG = GoogleDriveClient.class.getSimpleName();
    private static final String BASE_FOLDER = "SafeRec";

    private final Drive driveService;

    public GoogleDriveClient(String accessToken) {
        HttpRequestInitializer initializer = (HttpRequest request) -> {
            request.getHeaders().setAuthorization("Bearer " + accessToken);
        };

        driveService = new Drive.Builder(
                new NetHttpTransport(),
                new GsonFactory(),
                initializer).setApplicationName("SafeRec").build();
    }

    public String findFolderId(String folderName, @Nullable String parentFolderId) {
        try {
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
                    .setFields("files(id,name)")
                    .setPageSize(1)
                    .execute();

            if (result.getFiles() != null && !result.getFiles().isEmpty()) {
                Log.i(TAG, "Found existing folder: " + folderName + " (ID: " + result.getFiles().get(0).getId() + ")");
                return result.getFiles().get(0).getId();
            }

            Log.i(TAG, "Folder not found: " + folderName);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error finding folder: " + folderName, e);
            return null;
        }
    }

    public String createFolder(String folderName, @Nullable String parentFolderId) {
        try {
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
        } catch (Exception e) {
            Log.e(TAG, "Error creating folder: " + folderName, e);
            return null;
        }
    }

    public String getOrCreateFolder(String folderName, @Nullable String parentFolderId) {
        String folderId = findFolderId(folderName, parentFolderId);
        if (folderId != null) {
            return folderId;
        }
        return createFolder(folderName, parentFolderId);
    }

    /**
     * Returns the "SafeRec" base folder ID (create if needed). Cache this to avoid
     * repeated lookups.
     */
    public String getOrCreateBaseFolderId() {
        return getOrCreateFolder(BASE_FOLDER, null);
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

    public String getOrCreateNestedFolders(@Nullable String nestedPath, @Nullable String parentFolderId) {
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

            String id = getOrCreateFolder(name, currentParent);
            if (id == null)
                return null;

            currentParent = id;
        }
        return currentParent;
    }

    /**
     * Upload a file. If parentFolderId is non-null, uses it as the Drive parent
     * (avoids folder
     * lookups). Otherwise resolves SafeRec + destFile.getParent() as before.
     */
    public com.google.api.services.drive.model.File uploadFile(File localFile, File destFile, String mimeType,
            @Nullable String parentFolderId, long timestampMillis) {
        try {
            com.google.api.services.drive.model.File meta = new com.google.api.services.drive.model.File();
            meta.setName(destFile.getName());
            
            if (timestampMillis > 0) {
                DateTime dt = new DateTime(timestampMillis);
                meta.setCreatedTime(dt);
                meta.setModifiedTime(dt);
            }

            String destinationFolderId;
            if (parentFolderId != null) {
                destinationFolderId = parentFolderId;
            } else {
                String baseId = getOrCreateFolder(BASE_FOLDER, null);
                if (baseId == null) {
                    Log.e(TAG, "Failed to get or create base folder: " + BASE_FOLDER);
                    return null;
                }
                String destPath = destFile.getParent();
                destinationFolderId = getOrCreateNestedFolders(destPath, baseId);
                if (destinationFolderId == null) {
                    Log.e(TAG, "Failed to get or create nested folders: " + destPath);
                    return null;
                }
            }

            meta.setParents(Collections.singletonList(destinationFolderId));

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

    public com.google.api.services.drive.model.File uploadFile(File localFile, File destFile, String mimeType) {
        return uploadFile(localFile, destFile, mimeType, null, 0);
    }

    public void downloadFile(String fileId, OutputStream outputStream) throws IOException {
        driveService.files().get(fileId)
                .executeMediaAndDownloadTo(outputStream);
    }

    public boolean checkAuthentication() {
        try {
            // A simple lightweight call to check if the token is valid
            driveService.files().list().setPageSize(1).setFields("files(id)").execute();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Authentication check failed", e);
            return false;
        }
    }

    public Drive getDriveService() {
        return driveService;
    }
}
