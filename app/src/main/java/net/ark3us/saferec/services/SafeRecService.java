package net.ark3us.saferec.services;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import net.ark3us.saferec.data.LiveData;
import net.ark3us.saferec.media.AudioStreamRecorder;
import net.ark3us.saferec.media.MuxerSink;
import net.ark3us.saferec.media.VideoStreamRecorder;
import net.ark3us.saferec.misc.Settings;
import net.ark3us.saferec.net.GoogleDriveFileUploader;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SafeRecService extends Service {
    private static final String TAG = "SafeRecService";

    public static final String CMD_START = "START";
    public static final String CMD_STOP = "STOP";
    public static final String CMD_UPLOAD_PENDING = "UPLOAD_PENDING";

    public static final String STATUS_READY = "READY";
    public static final String STATUS_STARTED = "STARTED";
    public static final String STATUS_STOPPED = "STOPPED";
    public static final String STATUS_ERROR = "ERROR";

    private VideoStreamRecorder videoRecorder;
    private AudioStreamRecorder audioRecorder;
    private MuxerSink sink;

    private boolean isRecording = false;
    private boolean isForeground = false;
    private int activeUploads = 0;
    private String currentSessionId;
    private int currentSequenceNumber = 0;

    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationHelper.createNotificationChannel(this);
        executor = Executors.newSingleThreadExecutor();
    }

    private Notification buildNotification() {
        return NotificationHelper.buildNotification(this, isRecording, activeUploads);
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
        String command = intent != null ? intent.getStringExtra("command") : null;
        if (command == null) {
            if (!isRecording) {
                LiveData.getInstance().updateStatus(STATUS_READY);
            }
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
                if (isRecording) {
                    Log.i(TAG, "Skipping pending upload — recording in progress");
                } else {
                    uploadPendingFiles();
                }
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
        isRecording = true;
        LiveData.getInstance().updateStatus(STATUS_STARTED);
        if (currentSessionId == null) {
            currentSessionId = String.valueOf(System.currentTimeMillis());
            currentSequenceNumber = 0;
        }
        String sessionId = currentSessionId;
        String accessToken = Settings.getAccessToken(this);
        GoogleDriveFileUploader fileUploader = new GoogleDriveFileUploader(accessToken);
        fileUploader.setListener(num -> {
            activeUploads = num;
            updateNotification();
        });
        File baseDir = new File(getFilesDir(), "data_store");
        boolean includeVideo = !Settings.onlyAudio(this);
        String dataType = includeVideo ? MuxerSink.DATA_TYPE_VIDEO : MuxerSink.DATA_TYPE_AUDIO;
        int chunkSize;

        int prefChunkSizeMB = Settings.getChunkSizeMB(this);
        if (prefChunkSizeMB > 0) {
            chunkSize = prefChunkSizeMB * 1024 * 1024;
            Log.i(TAG, "Using manual chunk size: " + prefChunkSizeMB + " MB (" + chunkSize + " bytes)");
        } else if (includeVideo) {
            VideoStreamRecorder vr = VideoStreamRecorder.getInstance();
            vr.syncQuality(this);
            chunkSize = vr.getQuality().getRecommendedChunkSize();
            Log.i(TAG, "Using dynamic chunk size for video: " + chunkSize + " bytes");
        } else {
            chunkSize = MuxerSink.DEFAULT_AUDIO_CHUNK;
            Log.i(TAG, "Using default audio chunk size: " + chunkSize + " bytes");
        }

        MuxerSink.FileSavedCallback callback = file -> {
            new Thread(() -> {
                if (Settings.isTimestampingEnabled(this)) {
                    String path = file.getAbsolutePath();
                    int dotIndex = path.lastIndexOf('.');
                    File tsaFile = new File((dotIndex > 0 ? path.substring(0, dotIndex) : path) + ".tsa");
                    boolean success = net.ark3us.saferec.net.FreeTSAClient.timestampFile(this, file, tsaFile);
                    if (success) {
                        fileUploader.upload(tsaFile);
                    }
                }
                fileUploader.upload(file);
            }, "TimestampUploadThread").start();
        };

        sink = new MuxerSink(baseDir, callback, dataType, sessionId, chunkSize);
        sink.setSequenceNumber(currentSequenceNumber);
        audioRecorder = new AudioStreamRecorder(this);
        if (includeVideo) {
            videoRecorder = VideoStreamRecorder.getInstance();
            boolean useFront = Settings.getUseFrontCamera(this);
            videoRecorder.start(this, sink, useFront, null, success -> {
                if (success) {
                    Log.i(TAG, "Video recorder started successfully");
                } else {
                    Log.e(TAG, "Video recorder failed to start, reporting error");
                    LiveData.getInstance().updateStatus(STATUS_ERROR);
                    stopRecording();
                }
            });
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
                LiveData.getInstance().updateStatus(STATUS_ERROR);
                stopRecording();
            }
        }

        updateNotification();
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
            currentSequenceNumber = sink.getSequenceNumber();
            sink = null;
        }
        updateNotification();
    }

    private void uploadPendingFiles() {
        executor.execute(() -> {
            File baseDir = new File(getFilesDir(), "data_store");
            String accessToken = Settings.getAccessToken(this);
            if (accessToken != null) {
                new GoogleDriveFileUploader(accessToken).upload(baseDir);
            }
        });
    }

    @Override
    public void onDestroy() {
        stopRecording();
        executor.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
