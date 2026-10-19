package net.ark3us.saferec.net;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.api.client.http.HttpResponseException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import android.content.Context;

import net.ark3us.saferec.data.ServiceState;
import net.ark3us.saferec.misc.Settings;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
import com.google.api.services.drive.DriveScopes;
import com.google.android.gms.tasks.Tasks;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class GoogleDriveFileUploader extends FileUploader {

    private static final String TAG = GoogleDriveFileUploader.class.getSimpleName();
    private final GoogleDriveClient driveClient;
    private final Context context;
    /** Cache parent folder ID per session+dataType to avoid repeated Drive API folder lookups. */
    private final ConcurrentHashMap<String, String> sessionFolderIdCache = new ConcurrentHashMap<>();
    private volatile String cachedBaseFolderId;
    // Token refreshes are shared: one at a time, at most one Play services request per
    // minute. Each failing chunk used to ask on its own (8 requests in 30 s), which can get
    // the app throttled right when it needs a token.
    private static final long REFRESH_MIN_INTERVAL_MS = 60_000;
    private final Object refreshLock = new Object();
    private long lastRefreshMs; // guarded by refreshLock, elapsedRealtime
    private volatile String rejectedToken; // last token Drive answered 401 to
    // Complete chunks and .tsa replies wait here until uploaded
    private static final String OUTBOX_DIR = "data_store";
    private static GoogleDriveFileUploader instance;

    private GoogleDriveFileUploader(Context context) {
        this.context = context.getApplicationContext();
        this.driveClient = new GoogleDriveClient(Settings.getAccessToken(context));
        setOutbox(outboxDir(context));
    }

    /**
     * One uploader per process, shared by the service and the background job: one folder
     * lock, one in-flight set and one token refresh, so neither duplicate folders nor
     * duplicate uploads can happen between them.
     */
    public static synchronized GoogleDriveFileUploader getInstance(Context context) {
        if (instance == null) {
            instance = new GoogleDriveFileUploader(context);
        }
        return instance;
    }

    public static File outboxDir(Context context) {
        return new File(context.getFilesDir(), OUTBOX_DIR);
    }

    @Override
    protected void onFilePending(File file) {
        OutboxWorker.schedule(context);
    }

    /** The app stores a new token whenever the user opens it: the stored one wins, unless Drive rejected it. */
    @Nullable
    private String currentToken() {
        String current = driveClient.getAccessToken();
        String stored = Settings.getAccessToken(context);
        if (stored != null && !stored.equals(current) && !stored.equals(rejectedToken)) {
            driveClient.setAccessToken(stored);
            return stored;
        }
        return current;
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

    /**
     * Folders are only ever created by getOrCacheSessionFolderId, under its lock. When an
     * earlier attempt may have reached Drive without its answer coming back, Drive is asked
     * first: the same name with the same MD5 means that upload already happened. Sending it
     * again put chunks on Drive twice, and merging the session repeated them. The same name
     * with another MD5 is uploaded anyway: evidence is never dropped on a guess.
     */
    private com.google.api.services.drive.model.File tryUpload(File file, MediaFile mediaFile, boolean maybeSentBefore)
            throws IOException {
        String parentFolderId = getOrCacheSessionFolderId(mediaFile.sessionId, mediaFile.dataType);
        String name = driveName(mediaFile);
        if (maybeSentBefore) {
            com.google.api.services.drive.model.File existing = driveClient.findFile(name, parentFolderId);
            if (existing != null) {
                if (md5(file).equalsIgnoreCase(String.valueOf(existing.getMd5Checksum()))) {
                    Log.i(TAG, "Already on Drive, an earlier upload got there: " + name);
                    return existing;
                }
                Log.w(TAG, "Drive has another " + name + " (different content): uploading this one too");
            }
        }
        return driveClient.uploadFile(file, name, mediaFile.getMimeType(), parentFolderId);
    }

    private static String md5(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
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
    public boolean uploadFile(File file, boolean maybeSentBefore) {
        MediaFile mediaFile = MediaFile.fromFile(file);
        if (mediaFile == null) {
            Log.e(TAG, "Cannot parse media file from: " + file.getAbsolutePath());
            return false;
        }
        Log.i(TAG, "Uploading file (seq=" + mediaFile.sequenceNumber + ") to Google Drive: " + driveName(mediaFile));
        String token = currentToken();
        if (token == null || token.equals(rejectedToken)) {
            // Drive would only answer 401 again: get a token before sending the chunk
            return refreshToken(token) && retryUpload(file, mediaFile, maybeSentBefore);
        }
        try {
            com.google.api.services.drive.model.File res = tryUpload(file, mediaFile, maybeSentBefore);
            Log.i(TAG, "File (seq=" + mediaFile.sequenceNumber + ") uploaded successfully: " + res.getName());
            deleteUploaded(file);
            return true;
        } catch (IOException e) {
            // Classify from the error itself: a second request to check the token runs
            // later, may find the network back, and says nothing about this failure
            int status = httpStatus(e);
            if (status == 401) {
                Log.e(TAG, "Access token rejected during upload. Attempting silent token refresh...");
                return refreshToken(token) && retryUpload(file, mediaFile, maybeSentBefore);
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

    private boolean retryUpload(File file, MediaFile mediaFile, boolean maybeSentBefore) {
        try {
            com.google.api.services.drive.model.File res = tryUpload(file, mediaFile, maybeSentBefore);
            Log.i(TAG, "Retry successful: " + res.getName());
            deleteUploaded(file);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Retry with a fresh token failed, keeping file: " + file.getName() + " (" + describe(e) + ")");
            return false;
        }
    }

    /** Returns true when a token other than the rejected one is ready to use. */
    private boolean refreshToken(@Nullable String rejected) {
        synchronized (refreshLock) {
            rejectedToken = rejected;
            if (!Objects.equals(rejected, driveClient.getAccessToken())) {
                return true; // another upload refreshed it while this one waited
            }
            // The app stores a new token when the user opens it: use it without asking
            String stored = Settings.getAccessToken(context);
            if (stored != null && !stored.equals(rejected)) {
                Log.i(TAG, "Using the token stored by the app");
                driveClient.setAccessToken(stored);
                return true;
            }
            long now = SystemClock.elapsedRealtime();
            if (lastRefreshMs != 0 && now - lastRefreshMs < REFRESH_MIN_INTERVAL_MS) {
                Log.w(TAG, "Token refresh tried " + (now - lastRefreshMs) / 1000 + " s ago, the file waits for a later retry");
                return false;
            }
            lastRefreshMs = now;
            return requestNewToken();
        }
    }

    /** Returns true when a new token was obtained without user interaction. */
    private boolean requestNewToken() {
        Log.i(TAG, "Requesting a new token from Play services");
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
                ServiceState.getInstance().reportError(ServiceState.ERROR_AUTH);
                return false;
            }
            String newToken = authResult.getAccessToken();
            if (newToken == null) {
                Log.e(TAG, "Silent refresh returned null token, keeping files");
                ServiceState.getInstance().reportError(ServiceState.ERROR_AUTH);
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
