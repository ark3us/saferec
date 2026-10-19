package net.ark3us.saferec.net;

import android.util.Log;

import com.google.api.client.http.HttpResponseException;

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

    /** Throws when Drive cannot be reached or refuses the token: the caller keeps the file. */
    private String getOrCacheSessionFolderId(String sessionId, String dataType) throws IOException {
        String key = cacheKey(sessionId, dataType);
        String cached = sessionFolderIdCache.get(key);
        if (cached != null) return cached;
        // One thread at a time: concurrent lookups would each create the missing folders
        synchronized (this) {
            cached = sessionFolderIdCache.get(key);
            if (cached != null) return cached;
            if (cachedBaseFolderId == null) {
                cachedBaseFolderId = driveClient.getOrCreateBaseFolderId();
            }
            String folderId = driveClient.getOrCreateNestedFolders(sessionId + "/" + dataType, cachedBaseFolderId);
            sessionFolderIdCache.put(key, folderId);
            return folderId;
        }
    }

    /** A cached folder was deleted on Drive: look the folders up again on the next upload. */
    private synchronized void forgetFolders() {
        sessionFolderIdCache.clear();
        cachedBaseFolderId = null;
    }

    private static String driveName(MediaFile mediaFile) {
        return String.format("%s.%s.%s", mediaFile.timestamp, mediaFile.sequenceNumber, mediaFile.getExtension());
    }

    /** Folders are only ever created by getOrCacheSessionFolderId, under its lock. */
    private com.google.api.services.drive.model.File tryUpload(File file, MediaFile mediaFile) throws IOException {
        String parentFolderId = getOrCacheSessionFolderId(mediaFile.sessionId, mediaFile.dataType);
        return driveClient.uploadFile(file, driveName(mediaFile), mediaFile.getMimeType(), parentFolderId, mediaFile.timestamp);
    }

    private static int httpStatus(IOException e) {
        return e instanceof HttpResponseException ? ((HttpResponseException) e).getStatusCode() : 0;
    }

    private static String describe(IOException e) {
        int status = httpStatus(e);
        return status != 0 ? "HTTP " + status : e.toString();
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
        try {
            com.google.api.services.drive.model.File res = tryUpload(file, mediaFile);
            Log.i(TAG, "File (seq=" + mediaFile.sequenceNumber + ") uploaded successfully: " + res.getName());
            deleteUploaded(file);
            return true;
        } catch (IOException e) {
            // Classify from the error itself: a second request to check the token runs
            // later, may find the network back, and says nothing about this failure
            int status = httpStatus(e);
            if (status == 401) {
                Log.e(TAG, "Access token rejected during upload. Attempting silent token refresh...");
                return refreshToken() && retryUpload(file, mediaFile);
            }
            if (status == 404) {
                forgetFolders();
            }
            // Network down or Drive failing: the token is not the problem,
            // so keep it and leave the file in the outbox for a later upload.
            Log.w(TAG, "Upload failed, keeping file: " + file.getName() + " (" + describe(e) + ")");
            return false;
        }
    }

    private boolean retryUpload(File file, MediaFile mediaFile) {
        try {
            com.google.api.services.drive.model.File res = tryUpload(file, mediaFile);
            Log.i(TAG, "Retry successful: " + res.getName());
            deleteUploaded(file);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Retry with a fresh token failed, keeping file: " + file.getName() + " (" + describe(e) + ")");
            return false;
        }
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

            // Report, but keep the stored token: Play services also ask for user interaction
            // when they cannot reach Google, and a cleared token stopped every later upload
            // (pending scans skip without one) until the user reopened the app.
            if (authResult.hasResolution()) {
                Log.e(TAG, "Silent refresh requires user interaction (resolution), keeping files");
                LiveData.getInstance().reportError(LiveData.ERROR_AUTH);
                return false;
            }
            String newToken = authResult.getAccessToken();
            if (newToken == null) {
                Log.e(TAG, "Silent refresh returned null token, keeping files");
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
