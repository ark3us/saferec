package net.ark3us.saferec.net;

import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import android.content.Context;

import net.ark3us.saferec.data.LiveData;
import net.ark3us.saferec.misc.Settings;
import net.ark3us.saferec.services.SafeRecService;

public class GoogleDriveFileUploader extends FileUploader {

    private static final String TAG = GoogleDriveFileUploader.class.getSimpleName();
    private final GoogleDriveClient driveClient;
    private final Context context;
    /** Cache parent folder ID per session+dataType to avoid repeated Drive API folder lookups. */
    private final ConcurrentHashMap<String, String> sessionFolderIdCache = new ConcurrentHashMap<>();
    private volatile String cachedBaseFolderId;

    public GoogleDriveFileUploader(Context context, String accessToken) {
        this.context = context.getApplicationContext();
        this.driveClient = new GoogleDriveClient(accessToken);
    }

    private String cacheKey(String sessionId, String dataType) {
        return sessionId + "\0" + dataType;
    }

    private String getOrCacheSessionFolderId(String sessionId, String dataType) {
        String key = cacheKey(sessionId, dataType);
        String cached = sessionFolderIdCache.get(key);
        if (cached != null) return cached;
        synchronized (this) {
            cached = sessionFolderIdCache.get(key);
            if (cached != null) return cached;
            if (cachedBaseFolderId == null) {
                cachedBaseFolderId = driveClient.getOrCreateBaseFolderId();
            }
            if (cachedBaseFolderId == null) return null;
            String destPath = String.format("%s/%s", sessionId, dataType);
            String folderId = driveClient.getOrCreateNestedFolders(destPath, cachedBaseFolderId);
            if (folderId != null) sessionFolderIdCache.put(key, folderId);
            return folderId;
        }
    }

    @Override
    public void uploadFile(File file) {
        MediaFile mediaFile = MediaFile.fromFile(file);
        if (mediaFile == null) {
            Log.e(TAG, "Cannot parse media file from: " + file.getAbsolutePath());
            return;
        }
        String destPath = String.format("%s/%s", mediaFile.sessionId, mediaFile.dataType);
        String destName = String.format("%s.%s.%s", mediaFile.timestamp, mediaFile.sequenceNumber, mediaFile.getExtension());
        File destFile = new File(destPath, destName);
        @Nullable String parentFolderId = getOrCacheSessionFolderId(mediaFile.sessionId, mediaFile.dataType);
        Log.i(TAG, "Uploading file (seq=" + mediaFile.sequenceNumber + ") to Google Drive: " + destFile.getName());
        com.google.api.services.drive.model.File res = driveClient.uploadFile(file, destFile, mediaFile.getMimeType(), parentFolderId, mediaFile.timestamp);
        if (res != null) {
            Log.i(TAG, "File (seq=" + mediaFile.sequenceNumber + ") uploaded successfully: " + res.getName());
            if (!file.delete()) {
                Log.w(TAG, "Failed to delete file after upload: " + file.getAbsolutePath());
            } else {
                Log.d(TAG, "File deleted after upload: " + file.getAbsolutePath());
            }
        } else {
            Log.e(TAG, "File upload failed: " + file.getAbsolutePath());
            if (!driveClient.checkAuthentication()) {
                Log.e(TAG, "Authentication check failed during upload. Clearing token and reporting error.");
                Settings.setAccessToken(context, null);
                LiveData.getInstance().updateStatus(SafeRecService.STATUS_ERROR);
            }
        }
    }
}
