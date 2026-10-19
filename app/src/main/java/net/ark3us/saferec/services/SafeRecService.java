package net.ark3us.saferec.services;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.ark3us.saferec.data.LiveData;
import net.ark3us.saferec.media.AudioStreamRecorder;
import net.ark3us.saferec.media.MuxerSink;
import net.ark3us.saferec.media.VideoStreamRecorder;
import net.ark3us.saferec.misc.Settings;
import net.ark3us.saferec.net.FileDownloader;
import net.ark3us.saferec.net.FreeTSAClient;
import net.ark3us.saferec.net.GoogleDriveFileDownloader;
import net.ark3us.saferec.net.GoogleDriveFileUploader;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SafeRecService extends Service {
    public static final String CMD_START = "START";
    public static final String CMD_STOP = "STOP";
    public static final String CMD_DELETE = "DELETE";
    public static final String CMD_UPLOAD_PENDING = "UPLOAD_PENDING";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_FROM_TILE = "from_tile";
    public static final String EXTRA_FROM_NOTIFICATION = "from_notification";
    public static final String EXTRA_FILE_IDS = "file_ids";
    public static final String EXTRA_FOLDER_IDS = "folder_ids";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_STARTED = "STARTED";
    public static final String STATUS_STOPPED = "STOPPED";
    private static final String TAG = "SafeRecService";
    // Chunks being written live in WORK_DIR; complete ones wait in OUTBOX_DIR until uploaded
    private static final String WORK_DIR = "recording";
    private static final String OUTBOX_DIR = "data_store";
    private VideoStreamRecorder videoRecorder;
    private AudioStreamRecorder audioRecorder;
    private MuxerSink sink;

    private boolean isRecording = false;
    private boolean isForeground = false;
    private int activeUploads = 0;
    private int activeDeletions = 0;
    private int activeTimestamping = 0;
    private String currentSessionId;
    private String errorMessage;
    private volatile GoogleDriveFileUploader sharedUploader; // read by the network callback thread
    private ConnectivityManager.NetworkCallback networkCallback;
    private int currentSequenceNumber = 0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService postProcessExecutor = Executors.newSingleThreadExecutor();
    // Separate from postProcessExecutor so a slow TSA never delays chunk uploads
    private final ExecutorService tsaExecutor = Executors.newSingleThreadExecutor();

    public static Intent createCommandIntent(Context context, @Nullable String command) {
        Intent intent = new Intent(context, SafeRecService.class);
        intent.putExtra(EXTRA_COMMAND, command);
        return intent;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationHelper.createNotificationChannel(this);
        recoverInterruptedChunks();
        registerNetworkCallback();
    }

    private File workDir() {
        return new File(getFilesDir(), WORK_DIR);
    }

    private File outboxDir() {
        return new File(getFilesDir(), OUTBOX_DIR);
    }

    /**
     * A crash leaves the open chunk in the work dir. It has no MP4 index, but it is
     * still evidence (recoverable with repair tools), so it goes to the outbox too.
     * Safe here: no recording can be running before the service is created.
     */
    private void recoverInterruptedChunks() {
        File[] orphans = workDir().listFiles();
        if (orphans == null || orphans.length == 0) {
            return;
        }
        File outbox = outboxDir();
        if (!outbox.exists() && !outbox.mkdirs()) {
            Log.e(TAG, "Cannot create outbox: " + outbox.getAbsolutePath());
            return;
        }
        for (File orphan : orphans) {
            if (orphan.length() == 0) {
                orphan.delete();
            } else if (orphan.renameTo(new File(outbox, orphan.getName()))) {
                Log.w(TAG, "Recovered interrupted chunk: " + orphan.getName());
            } else {
                Log.e(TAG, "Failed to recover interrupted chunk: " + orphan.getName());
            }
        }
    }

    private Notification buildNotification() {
        return NotificationHelper.buildNotification(this, isRecording, activeUploads, activeDeletions, activeTimestamping);
    }

    private void updateNotification() {
        Notification notification = buildNotification();
        if (!isForeground) {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification);
            isForeground = true;
            Log.i(TAG, "Started as foreground service");
        } else {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(NotificationHelper.NOTIFICATION_ID, notification);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "SafeRecService started");
        updateNotification();
        String command = intent != null ? intent.getStringExtra(EXTRA_COMMAND) : null;
        if (command == null) {
            if (!isRecording) {
                LiveData.getInstance().updateStatus(STATUS_READY);
            }
            // Also the sticky restart after the process was killed: resume the outbox
            uploadPendingFiles();
            return START_STICKY;
        }
        Log.i(TAG, "Received command: " + command);
        switch (command) {
            case CMD_START:
                if (isRecording) {
                    Log.i(TAG, "Stopping ongoing recording before restart (camera switch)");
                    stopRecording();
                }
                startRecording();
                break;
            case CMD_STOP:
                stopRecording();
                currentSessionId = null;
                currentSequenceNumber = 0;
                break;
            case CMD_UPLOAD_PENDING:
                // Safe while recording: the outbox only ever holds complete chunks
                uploadPendingFiles();
                break;
            case CMD_DELETE:
                deleteDriveFiles(intent);
                break;
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.i(TAG, "Task removed from recents, service continues running (recording=" + isRecording + ")");
    }

    private void startRecording() {
        if (isRecording) {
            Log.w(TAG, "Recording is already in progress");
            return;
        }
        // No token gate: recording never depends on Drive. Without a valid token
        // the uploader tries a silent refresh, otherwise chunks wait on disk.
        String accessToken = Settings.getAccessToken(this);
        if (accessToken == null || accessToken.isEmpty()) {
            Log.w(TAG, "Recording without access token: chunks are kept until Drive is reachable");
        }
        isRecording = true;
        LiveData.getInstance().updateStatus(STATUS_STARTED);
        if (currentSessionId == null) {
            currentSessionId = String.valueOf(System.currentTimeMillis());
            currentSequenceNumber = 0;
        }
        String sessionId = currentSessionId;
        Log.i(TAG, "Starting recording session id=" + sessionId + " sequence=" + currentSequenceNumber);
        
        GoogleDriveFileUploader uploader = ensureUploader();

        boolean includeVideo = !Settings.onlyAudio(this);
        String dataType = includeVideo ? MuxerSink.DATA_TYPE_VIDEO : MuxerSink.DATA_TYPE_AUDIO;
        long chunkSize = 0;
        long chunkDurationMs = 0;

        int prefChunkSizeMB = Settings.getChunkSizeMB(this);
        if (prefChunkSizeMB > 0) {
            chunkSize = prefChunkSizeMB * 1024L * 1024L;
            Log.i(TAG, "Using manual chunk size: " + prefChunkSizeMB + " MB (" + chunkSize + " bytes)");
        } else {
            // Split by time, not bytes: a silent or static scene must not stretch a chunk
            chunkDurationMs = MuxerSink.DEFAULT_CHUNK_DURATION_MS;
            Log.i(TAG, "Using chunk duration: " + chunkDurationMs + " ms");
        }

        MuxerSink.FileSavedCallback callback = file -> {
            postProcessExecutor.execute(() -> {
                Log.i(TAG, "Processing saved chunk: " + file.getName());
                // Hash before uploading: the uploader deletes the local file once it is on Drive
                byte[] hash = Settings.isTimestampingEnabled(this) ? FreeTSAClient.sha256(file) : null;
                // The chunk is the evidence: it never waits for the TSA, the timestamp can come later
                uploader.upload(file);
                if (hash != null) {
                    tsaExecutor.execute(() -> timestampChunk(uploader, file, hash));
                }
            });
        };

        sink = new MuxerSink(workDir(), outboxDir(), callback, dataType, sessionId, chunkSize, chunkDurationMs);
        sink.setSequenceNumber(currentSequenceNumber);
        audioRecorder = new AudioStreamRecorder(this);
        if (includeVideo) {
            videoRecorder = VideoStreamRecorder.getInstance();
            boolean useFront = Settings.getUseFrontCamera(this);
            MuxerSink sessionSink = sink;
            // Camera callbacks arrive on other threads: handle them on the main thread, like commands
            boolean started = videoRecorder.start(this, sink, useFront, null,
                    success -> mainHandler.post(() -> onVideoResult(sessionSink, success)));
            if (!started) {
                mainHandler.post(() -> onVideoResult(sessionSink, false));
            }
        }
        if (audioRecorder.start(sink)) {
            Log.i(TAG, "Audio recorder started successfully");
        } else {
            Log.e(TAG, "Audio recorder failed to start");
            if (includeVideo) {
                Log.w(TAG, "Proceeding with Video only (Audio error)");
                sink.setExpectedAudio(false);
            } else {
                Log.e(TAG, "Audio-only mode failed, stopping");
                LiveData.getInstance().reportError(LiveData.ERROR_RECORDING);
                stopRecording();
            }
        }

        updateNotification();
    }

    /** Losing the camera must not lose the evidence: keep recording audio when possible. */
    private void onVideoResult(MuxerSink sessionSink, boolean success) {
        if (sink != sessionSink || videoRecorder == null) {
            return; // stale callback from a previous session, or video failure already handled
        }
        if (success) {
            Log.i(TAG, "Video recorder started successfully");
            return;
        }
        if (audioRecorder != null && audioRecorder.isRecording()) {
            Log.w(TAG, "Video recorder failed, continuing with audio only");
            // stop(), not stopRecording(): with a preview attached the latter keeps the camera open
            videoRecorder.stop();
            videoRecorder = null;
            sink.setExpectedVideo(false);
            LiveData.getInstance().reportError(LiveData.ERROR_CAMERA);
        } else {
            Log.e(TAG, "Video recorder failed and no audio is recording, stopping");
            LiveData.getInstance().reportError(LiveData.ERROR_RECORDING);
            stopRecording();
        }
    }

    private void timestampChunk(GoogleDriveFileUploader uploader, File chunkFile, byte[] hash) {
        synchronized (this) {
            activeTimestamping++;
            updateNotification();
        }
        String path = chunkFile.getAbsolutePath();
        int dotIndex = path.lastIndexOf('.');
        File tsaFile = new File((dotIndex > 0 ? path.substring(0, dotIndex) : path) + ".tsa");
        boolean success = FreeTSAClient.timestampHash(this, hash, tsaFile);
        synchronized (this) {
            activeTimestamping--;
            updateNotification();
        }
        if (success) {
            uploader.upload(tsaFile);
        }
    }

    private void stopRecording() {
        if (!isRecording) {
            Log.w(TAG, "No recording in progress to stop");
            return;
        }
        isRecording = false;
        LiveData.getInstance().updateStatus(STATUS_STOPPED);
        if (videoRecorder != null) {
            videoRecorder.stopRecording();
            videoRecorder = null;
        }
        if (audioRecorder != null) {
            audioRecorder.stop();
            audioRecorder = null;
        }
        if (sink != null) {
            sink.stop();
            // Sync currentSequenceNumber from sink before nulling it out
            currentSequenceNumber = sink.getSequenceNumber();
            sink = null;
        }
        updateNotification();
    }

    /** One uploader per service: it owns the outbox retry loop and the duplicate guard. */
    private synchronized GoogleDriveFileUploader ensureUploader() {
        if (sharedUploader == null) {
            GoogleDriveFileUploader uploader = new GoogleDriveFileUploader(this, Settings.getAccessToken(this));
            uploader.setOutbox(outboxDir());
            uploader.setListener(num -> {
                activeUploads = num;
                updateNotification();
            });
            sharedUploader = uploader;
        }
        return sharedUploader;
    }

    private void uploadPendingFiles() {
        executor.execute(() -> {
            File baseDir = outboxDir();
            String accessToken = Settings.getAccessToken(this);
            if (accessToken == null || accessToken.isEmpty()) {
                Log.w(TAG, "Skipping pending upload: missing access token");
                return;
            }
            Log.i(TAG, "Uploading pending files from " + baseDir.getAbsolutePath());
            ensureUploader().upload(baseDir);
        });
    }

    private static int transportMask(NetworkCapabilities caps) {
        int mask = 0;
        for (int transport = 0; transport < Integer.SIZE; transport++) {
            if (caps.hasTransport(transport)) {
                mask |= 1 << transport;
            }
        }
        return mask;
    }

    /**
     * Network back → empty the outbox now instead of waiting for the retry backoff.
     * A VPN stays the default network, and stays VALIDATED, while the phone has no network
     * under it, so onAvailable never fires when the network returns. Its transports do
     * change (VPN → WIFI|VPN): a transport appearing counts as the network coming back.
     * A new Wi-Fi network shows up before its DNS works, so turning VALIDATED counts too.
     */
    private void registerNetworkCallback() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) {
            return;
        }
        networkCallback = new ConnectivityManager.NetworkCallback() {
            // Callbacks arrive one at a time, and onCapabilitiesChanged always follows onAvailable
            private Network current;
            private int transports;
            private boolean validated;

            @Override
            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) {
                int now = transportMask(caps);
                boolean nowValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                boolean back = !network.equals(current) || (now & ~transports) != 0
                        || (nowValidated && !validated);
                current = network;
                transports = now;
                validated = nowValidated;
                GoogleDriveFileUploader uploader = sharedUploader;
                if (back && uploader != null) {
                    Log.i(TAG, "Network changed (transports " + Integer.toBinaryString(now) + ", validated " + nowValidated + "), retrying pending uploads");
                    uploader.retryNow();
                }
            }

            @Override
            public void onLost(@NonNull Network network) {
                current = null;
                transports = 0;
                validated = false;
            }
        };
        try {
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot watch the network, relying on the retry backoff", e);
            networkCallback = null;
        }
    }

    private void deleteDriveFiles(Intent intent) {
        String[] fileIds = intent.getStringArrayExtra(EXTRA_FILE_IDS);
        String[] folderIds = intent.getStringArrayExtra(EXTRA_FOLDER_IDS);

        if (fileIds == null || fileIds.length == 0) {
            Log.w(TAG, "Delete requested with empty file list");
            return;
        }

        executor.execute(() -> {
            String accessToken = Settings.getAccessToken(this);
            if (accessToken == null) {
                Log.e(TAG, "Cannot delete: No access token");
                return;
            }

            Log.i(TAG, "Starting background deletion of " + fileIds.length + " files");
            synchronized (this) {
                activeDeletions += fileIds.length;
                updateNotification();
            }

            GoogleDriveFileDownloader downloader = new GoogleDriveFileDownloader(this, accessToken);
            
            List<String> fileIdList = Arrays.asList(fileIds);
            Set<String> folderIdSet = new HashSet<>();
            if (folderIds != null) {
                Collections.addAll(folderIdSet, folderIds);
            }

            downloader.deleteFilesById(fileIdList, folderIdSet, new FileDownloader.Callback<Void>() {
                @Override
                public void onSuccess(Void result) {
                    Log.i(TAG, "Background deletion completed successfully");
                    synchronized (SafeRecService.this) {
                        activeDeletions -= fileIds.length;
                        updateNotification();
                    }
                }

                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "Background deletion failed", e);
                    synchronized (SafeRecService.this) {
                        activeDeletions -= fileIds.length;
                        updateNotification();
                    }
                }
            });
        });
    }

    @Override
    public void onDestroy() {
        stopRecording();
        if (networkCallback != null) {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm != null) {
                cm.unregisterNetworkCallback(networkCallback);
            }
            networkCallback = null;
        }
        if (sharedUploader != null) {
            sharedUploader.shutdown();
            sharedUploader = null;
        }
        executor.shutdown();
        postProcessExecutor.shutdown();
        tsaExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
