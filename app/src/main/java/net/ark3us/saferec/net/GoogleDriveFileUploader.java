package net.ark3us.saferec.net;

import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import android.content.Context;

import net.ark3us.saferec.data.LiveData;
import net.ark3us.saferec.misc.Settings;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
import com.google.api.services.drive.DriveScopes;
import com.google.android.gms.tasks.Tasks;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

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

    /** Null when Drive cannot be reached or refuses the token: the caller keeps the file. */
    @Nullable
    private String getOrCacheSessionFolderId(String sessionId, String dataType) {
        String key = cacheKey(sessionId, dataType);
        String cached = sessionFolderIdCache.get(key);
        if (cached != null) return cached;
        // One thread at a time: concurrent lookups would each create the missing folders
        synchronized (this) {
            cached = sessionFolderIdCache.get(key);
            if (cached != null) return cached;
            try {
                if (cachedBaseFolderId == null) {
                    cachedBaseFolderId = driveClient.getOrCreateBaseFolderId();
                }
                String folderId = driveClient.getOrCreateNestedFolders(sessionId + "/" + dataType, cachedBaseFolderId);
                sessionFolderIdCache.put(key, folderId);
                return folderId;
            } catch (IOException e) {
                Log.w(TAG, "Cannot resolve Drive folder " + sessionId + "/" + dataType + ": " + e);
                return null;
            }
        }
    }

    private static String driveName(MediaFile mediaFile) {
        return String.format("%s.%s.%s", mediaFile.timestamp, mediaFile.sequenceNumber, mediaFile.getExtension());
    }

    /** Folders are only ever created by getOrCacheSessionFolderId, under its lock. */
    @Nullable
    private com.google.api.services.drive.model.File tryUpload(File file, MediaFile mediaFile) {
        String parentFolderId = getOrCacheSessionFolderId(mediaFile.sessionId, mediaFile.dataType);
        if (parentFolderId == null) {
            return null;
        }
        return driveClient.uploadFile(file, driveName(mediaFile), mediaFile.getMimeType(), parentFolderId, mediaFile.timestamp);
    }

    private void deleteUploaded(File file) {
        if (!file.delete()) {
            Log.w(TAG, "Failed to delete file after upload: " + file.getAbsolutePath());
        } else {
            Log.d(TAG, "File deleted after upload: " + file.getAbsolutePath());
        }
    }

    @Override
    public boolean uploadFile(File file) {
        MediaFile mediaFile = MediaFile.fromFile(file);
        if (mediaFile == null) {
            Log.e(TAG, "Cannot parse media file from: " + file.getAbsolutePath());
            return false;
        }
        Log.i(TAG, "Uploading file (seq=" + mediaFile.sequenceNumber + ") to Google Drive: " + driveName(mediaFile));
        com.google.api.services.drive.model.File res = tryUpload(file, mediaFile);
        if (res != null) {
            Log.i(TAG, "File (seq=" + mediaFile.sequenceNumber + ") uploaded successfully: " + res.getName());
            deleteUploaded(file);
            return true;
        }
        Log.e(TAG, "File upload failed: " + file.getAbsolutePath());
        GoogleDriveClient.AuthStatus authStatus = driveClient.checkAuthentication();
        if (authStatus == GoogleDriveClient.AuthStatus.INVALID) {
            Log.e(TAG, "Access token rejected during upload. Attempting silent token refresh...");
            if (!refreshToken()) {
                return false;
            }
            res = tryUpload(file, mediaFile);
            if (res != null) {
                Log.i(TAG, "Retry successful: " + res.getName());
                deleteUploaded(file);
                return true;
            }
            Log.e(TAG, "Retry upload failed despite fresh token.");
            return false;
        }
        // Network down or Drive failing: the token is not the problem,
        // so keep it and leave the file in the outbox for a later upload.
        Log.w(TAG, "Upload failed (auth " + authStatus + "), keeping file: " + file.getName());
        return false;
    }

    /** Returns true when a new token was obtained without user interaction. */
    private boolean refreshToken() {
        try {
            AuthorizationRequest req = AuthorizationRequest.builder()
                    .setRequestedScopes(Collections.singletonList(new Scope(DriveScopes.DRIVE_FILE)))
                    .build();

            AuthorizationResult authResult = Tasks.await(
                    Identity.getAuthorizationClient(context).authorize(req),
                    30, TimeUnit.SECONDS);

            if (authResult.hasResolution()) {
                Log.e(TAG, "Silent refresh requires user interaction (resolution). Failing.");
                Settings.setAccessToken(context, null);
                LiveData.getInstance().reportError(LiveData.ERROR_AUTH);
                return false;
            }
            String newToken = authResult.getAccessToken();
            if (newToken == null) {
                Log.e(TAG, "Silent refresh returned null token. Failing.");
                Settings.setAccessToken(context, null);
                LiveData.getInstance().reportError(LiveData.ERROR_AUTH);
                return false;
            }
            Log.i(TAG, "Silent refresh succeeded! Updating token and retrying upload.");
            Settings.setAccessToken(context, newToken);
            driveClient.setAccessToken(newToken);
            return true;
        } catch (Exception e) {
            // Usually a network failure: the stored token may still be valid, so keep it
            Log.e(TAG, "Exception during silent token refresh", e);
            return false;
        }
    }
}
