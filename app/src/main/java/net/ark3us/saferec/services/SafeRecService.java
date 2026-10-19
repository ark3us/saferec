package net.ark3us.saferec.services;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.ark3us.saferec.data.ServiceState;
import net.ark3us.saferec.media.AudioStreamRecorder;
import net.ark3us.saferec.media.ChunkRepair;
import net.ark3us.saferec.media.ChunkPolicy;
import net.ark3us.saferec.media.MuxerSink;
import net.ark3us.saferec.media.VideoStreamRecorder;
import net.ark3us.saferec.media.WorkDir;
import net.ark3us.saferec.misc.Settings;
import net.ark3us.saferec.net.DriveRecordings;
import net.ark3us.saferec.net.TsaClient;
import net.ark3us.saferec.net.GoogleDriveFileUploader;
import net.ark3us.saferec.net.OutboxWorker;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

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
    private VideoStreamRecorder videoRecorder;
    private AudioStreamRecorder audioRecorder;
    private MuxerSink sink;

    private boolean isRecording = false;
    private static final int NOT_FOREGROUND = -1;
    private int foregroundType = NOT_FOREGROUND; // main thread only
    // Entering the foreground needs the app in front: true once a command came from the UI,
    // false after a sticky restart of a killed process
    private boolean mayEnterForeground = false;
    private int lastStartId;
    private boolean destroyed = false; // main thread only
    private int activeUploads = 0;
    private int activeDeletions = 0;
    private int activeTimestamping = 0;
    private String currentSessionId;
    private String errorMessage;
    private GoogleDriveFileUploader uploader; // set in onCreate, before any other thread uses it
    private ConnectivityManager.NetworkCallback networkCallback;
    private int currentSequenceNumber = 0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService postProcessExecutor = Executors.newSingleThreadExecutor();
    // Separate from postProcessExecutor so a slow TSA never delays chunk uploads
    private final ExecutorService tsaExecutor = Executors.newSingleThreadExecutor();
    // Android waits up to 10 s in all for the receivers of ACTION_SHUTDOWN
    private static final long SHUTDOWN_QUEUE_WAIT_MS = 3000;
    private static final long SHUTDOWN_JOB_WAIT_MS = 2000;
    private final BroadcastReceiver shutdownReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onShutdown();
        }
    };

    public static Intent createCommandIntent(Context context, @Nullable String command) {
        Intent intent = new Intent(context, SafeRecService.class);
        intent.putExtra(EXTRA_COMMAND, command);
        return intent;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationHelper.createNotificationChannel(this);
        // Shared with the outbox job. While the service lives it retries on its own; once
        // it is gone, the job takes over.
        uploader = GoogleDriveFileUploader.getInstance(this);
        uploader.setListener(num -> {
            activeUploads = num;
            updateNotification();
        });
        uploader.setRetryLoop(true);
        if (pendingFiles() > 0) {
            OutboxWorker.schedule(this);
        }
        // Chunks left by a crash, repaired when they have their sample log. Not on the main
        // thread: a repair can take seconds. A recording that starts first makes it skip
        // (WorkDir), and the outbox job recovers them later.
        postProcessExecutor.execute(() -> {
            if (WorkDir.get().recover(workDir(), outboxDir(), new ChunkRepair(this)) > 0) {
                uploader.upload(outboxDir());
            }
        });
        registerNetworkCallback();
        // Sent to receivers registered at runtime only: none in the manifest would get it
        ContextCompat.registerReceiver(this, shutdownReceiver, new IntentFilter(Intent.ACTION_SHUTDOWN),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    /**
     * The phone is switching off: power key, reboot, or a battery about to die (Android shuts
     * down cleanly at ~1%). The power cut left the open chunk without its MP4 index, so it
     * needed repair tools to play, and it stayed outside the outbox until the app was opened
     * again. Close it now, and wait until it is in the outbox with the job stored: the job
     * uploads it after the next boot.
     */
    private void onShutdown() {
        if (!isRecording) {
            return; // waiting files already have their job
        }
        Log.w(TAG, "Phone switching off: closing the recording");
        stopRecording();
        try {
            // The closed chunk is hashed and moved to the outbox there
            postProcessExecutor.submit(() -> { }).get(SHUTDOWN_QUEUE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Log.w(TAG, "The last chunk may not reach the outbox before the shutdown", e);
        }
        boolean stored = OutboxWorker.scheduleAndWait(this, SHUTDOWN_JOB_WAIT_MS);
        Log.i(TAG, "Recording closed before the shutdown: " + pendingFiles() + " files in the outbox, job stored " + stored);
    }

    private File workDir() {
        return WorkDir.dir(this);
    }

    private File outboxDir() {
        return GoogleDriveFileUploader.outboxDir(this);
    }


    private int pendingFiles() {
        String[] names = outboxDir().list();
        return names == null ? 0 : names.length;
    }

    private Notification buildNotification(boolean recording) {
        return NotificationHelper.buildNotification(this, recording, activeUploads, activeDeletions,
                activeTimestamping, pendingFiles());
    }

    private int recordingForegroundType() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return 0;
        }
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        return Settings.onlyAudio(this) ? type : type | ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
    }

    private static int dataSyncForegroundType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0;
    }

    /** False when Android refuses (app not in front, missing permission): better than a crash. */
    private boolean enterForeground(int type, Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NotificationHelper.NOTIFICATION_ID, notification, type);
            } else {
                startForeground(NotificationHelper.NOTIFICATION_ID, notification);
            }
            foregroundType = type;
            Log.i(TAG, "Foreground with type " + type);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot enter the foreground with type " + type, e);
            return false;
        }
    }

    /** Safe from any thread: the decision runs on the main thread, once the state has settled. */
    private void updateNotification() {
        mainHandler.post(this::refreshForeground);
    }

    /**
     * In the foreground only while there is something to do, with the type of what it does:
     * camera and microphone only while recording, dataSync while chunks wait to be uploaded.
     * With nothing left the notification goes and the service stops. It used to stay in the
     * foreground as camera|microphone forever, which Android 14+ and Play policies reject.
     */
    private void refreshForeground() {
        if (destroyed) {
            return; // posted before onDestroy()
        }
        int pending = pendingFiles();
        boolean busy = isRecording || pending > 0 || activeUploads > 0 || activeDeletions > 0 || activeTimestamping > 0;
        if (!busy) {
            if (foregroundType != NOT_FOREGROUND) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                foregroundType = NOT_FOREGROUND;
            }
            // Not if a newer command arrived meanwhile
            if (stopSelfResult(lastStartId)) {
                Log.i(TAG, "Nothing to record or upload: service stopped");
            }
            return;
        }
        Notification notification = buildNotification(isRecording);
        int type = isRecording ? recordingForegroundType() : dataSyncForegroundType();
        if (type == foregroundType) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(NotificationHelper.NOTIFICATION_ID, notification);
            }
        } else if (foregroundType != NOT_FOREGROUND || mayEnterForeground) {
            enterForeground(type, notification);
        }
        // else: a sticky restart in the background, it runs as a plain service
    }

    /** Android 15+ caps dataSync at 6 h a day. The outbox job uploads what is left. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        Log.w(TAG, "Foreground time limit reached (type " + fgsType + "), stopping; the outbox job takes over");
        stopForeground(STOP_FOREGROUND_REMOVE);
        foregroundType = NOT_FOREGROUND;
        stopSelf();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "SafeRecService started");
        lastStartId = startId;
        String command = intent != null ? intent.getStringExtra(EXTRA_COMMAND) : null;
        if (command == null) {
            if (!isRecording) {
                ServiceState.getInstance().updateStatus(STATUS_READY);
            }
            // Sticky restart after the process was killed. The app is not in front, so Android
            // forbids the foreground (and the camera and microphone): only resume the outbox,
            // as a plain background service, for as long as Android lets it run.
            uploadPendingFiles();
            updateNotification();
            return START_STICKY;
        }
        mayEnterForeground = true;
        Log.i(TAG, "Received command: " + command);
        switch (command) {
            case CMD_START:
                // Sent with startForegroundService(): the service must enter the foreground
                // whatever happens next, or Android kills the app
                Notification notification = buildNotification(true);
                if (!enterForeground(recordingForegroundType(), notification)) {
                    enterForeground(dataSyncForegroundType(), notification);
                }
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
        updateNotification();
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
        // The outbox job adopts chunks a power cut leaves in the work dir, but only when no
        // recording uses it. Queued from the start, so one is there after a cut.
        WorkDir.get().acquire();
        OutboxWorker.schedule(this);
        ServiceState.getInstance().updateStatus(STATUS_STARTED);
        if (currentSessionId == null) {
            currentSessionId = String.valueOf(System.currentTimeMillis());
            currentSequenceNumber = 0;
        }
        String sessionId = currentSessionId;
        Log.i(TAG, "Starting recording session id=" + sessionId + " sequence=" + currentSequenceNumber);

        boolean includeVideo = !Settings.onlyAudio(this);
        String dataType = includeVideo ? MuxerSink.DATA_TYPE_VIDEO : MuxerSink.DATA_TYPE_AUDIO;
        ChunkPolicy chunkPolicy = ChunkPolicy.fromSetting(Settings.getChunkSizeMB(this));
        Log.i(TAG, "Chunk policy: " + chunkPolicy);

        MuxerSink.FileSavedCallback callback = closed -> {
            postProcessExecutor.execute(() -> {
                Log.i(TAG, "Processing saved chunk: " + closed.getName());
                // Hash before the chunk enters the outbox: from there a rescan or the outbox
                // job may upload and delete it at any time
                byte[] hash = Settings.isTimestampingEnabled(this) ? TsaClient.sha256(closed) : null;
                File file = moveToOutbox(closed);
                // The chunk is the evidence: it never waits for the TSA, the timestamp can come later
                uploader.uploadNew(file);
                if (hash != null) {
                    // Counted now, not when the task starts: the service must not stop meanwhile
                    synchronized (this) {
                        activeTimestamping++;
                    }
                    updateNotification();
                    tsaExecutor.execute(() -> timestampChunk(file, hash));
                }
            });
        };

        sink = new MuxerSink(workDir(), callback, dataType, sessionId, chunkPolicy);
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
                ServiceState.getInstance().reportError(ServiceState.ERROR_RECORDING);
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
            ServiceState.getInstance().reportError(ServiceState.ERROR_CAMERA);
        } else {
            Log.e(TAG, "Video recorder failed and no audio is recording, stopping");
            ServiceState.getInstance().reportError(ServiceState.ERROR_RECORDING);
            stopRecording();
        }
    }

    /** Atomic rename (same filesystem). On failure the chunk is uploaded from the work dir. */
    private File moveToOutbox(File chunk) {
        File outbox = outboxDir();
        File target = new File(outbox, chunk.getName());
        if ((outbox.isDirectory() || outbox.mkdirs()) && chunk.renameTo(target)) {
            return target;
        }
        Log.e(TAG, "Failed to move chunk to outbox: " + chunk.getName());
        return chunk;
    }

    private void timestampChunk(File chunkFile, byte[] hash) {
        try {
            String path = chunkFile.getAbsolutePath();
            int dotIndex = path.lastIndexOf('.');
            File tsaFile = new File((dotIndex > 0 ? path.substring(0, dotIndex) : path) + ".tsa");
            if (TsaClient.timestampHash(this, hash, tsaFile)) {
                uploader.uploadNew(tsaFile);
            }
        } finally {
            synchronized (this) {
                activeTimestamping--;
            }
            updateNotification();
        }
    }

    private void stopRecording() {
        if (!isRecording) {
            Log.w(TAG, "No recording in progress to stop");
            return;
        }
        isRecording = false;
        ServiceState.getInstance().updateStatus(STATUS_STOPPED);
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
        // After the last chunk's post-processing, queued by sink.stop(), has moved it
        try {
            postProcessExecutor.execute(WorkDir.get()::release);
        } catch (RejectedExecutionException e) {
            WorkDir.get().release();
        }
        updateNotification();
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
            uploader.upload(baseDir);
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
                if (back) {
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

    private void finishDeletion(int count) {
        synchronized (this) {
            activeDeletions -= count;
        }
        updateNotification();
    }

    private void deleteDriveFiles(Intent intent) {
        String[] fileIds = intent.getStringArrayExtra(EXTRA_FILE_IDS);
        String[] folderIds = intent.getStringArrayExtra(EXTRA_FOLDER_IDS);

        if (fileIds == null || fileIds.length == 0) {
            Log.w(TAG, "Delete requested with empty file list");
            return;
        }
        // Counted now, not when the task starts: the service must not stop meanwhile
        synchronized (this) {
            activeDeletions += fileIds.length;
        }
        updateNotification();

        executor.execute(() -> {
            String accessToken = Settings.getAccessToken(this);
            if (accessToken == null) {
                Log.e(TAG, "Cannot delete: No access token");
                finishDeletion(fileIds.length);
                return;
            }

            Log.i(TAG, "Starting background deletion of " + fileIds.length + " files");
            DriveRecordings drive = new DriveRecordings(this, accessToken);

            List<String> fileIdList = Arrays.asList(fileIds);
            Set<String> folderIdSet = new HashSet<>();
            if (folderIds != null) {
                Collections.addAll(folderIdSet, folderIds);
            }

            drive.deleteFilesById(fileIdList, folderIdSet, new DriveRecordings.Callback<Void>() {
                @Override
                public void onSuccess(Void result) {
                    Log.i(TAG, "Background deletion completed successfully");
                    finishDeletion(fileIds.length);
                    drive.shutdown(); // its thread is done: do not leak one per deletion
                }

                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "Background deletion failed", e);
                    finishDeletion(fileIds.length);
                    drive.shutdown();
                }
            });
        });
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        unregisterReceiver(shutdownReceiver);
        stopRecording();
        if (networkCallback != null) {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm != null) {
                cm.unregisterNetworkCallback(networkCallback);
            }
            networkCallback = null;
        }
        // Not shut down: uploads still running finish, and the outbox job carries on
        uploader.setListener(null);
        uploader.setRetryLoop(false);
        if (OutboxWorker.filesWaiting(this)) {
            // Stopped with files waiting (time limit, or Android stopping the service): the
            // delayed job could be held back for a long time, the expedited one is not
            OutboxWorker.scheduleNow(this);
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
