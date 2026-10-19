package net.ark3us.saferec.net;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import net.ark3us.saferec.misc.Settings;

import java.util.concurrent.TimeUnit;

/**
 * Uploads the outbox when the service is gone: killed, stopped by the Android 15 time limit
 * for dataSync, or the phone rebooted. Without it, chunks recorded offline waited until the
 * user opened the app again. While the service lives it uploads and retries on its own; this
 * job shares its uploader, so a file is never uploaded twice.
 */
public class OutboxWorker extends Worker {
    private static final String TAG = OutboxWorker.class.getSimpleName();
    private static final String WORK_NAME = "outbox";
    // Delay before the job runs: while the service lives, it does the work. Backoff after a
    // failed run: 5 min, doubling up to WorkManager's 5 h cap.
    private static final long START_DELAY_MIN = 5;
    private static final long BACKOFF_MIN = 5;

    public OutboxWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /**
     * Makes sure a job will upload the outbox once there is a network, even after a reboot.
     * Cheap to call often: while a job is waiting or running, this does nothing.
     */
    public static void schedule(Context context) {
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(OutboxWorker.class)
                    .setConstraints(constraints)
                    .setInitialDelay(START_DELAY_MIN, TimeUnit.MINUTES)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MIN, TimeUnit.MINUTES)
                    .build();
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request);
        } catch (RuntimeException e) {
            // The safety net must never break an upload
            Log.e(TAG, "Cannot schedule the outbox job", e);
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        String token = Settings.getAccessToken(context);
        if (token == null || token.isEmpty()) {
            Log.w(TAG, "No Drive access: files wait until the user connects Drive in the app");
            return Result.success();
        }
        String[] waiting = GoogleDriveFileUploader.outboxDir(context).list();
        Log.i(TAG, "Outbox job started (attempt " + getRunAttemptCount() + "), " + (waiting == null ? 0 : waiting.length) + " files waiting");
        try {
            if (GoogleDriveFileUploader.getInstance(context).drain(this::isStopped)) {
                Log.i(TAG, "Outbox job done: outbox empty");
                return Result.success();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Log.w(TAG, "Outbox job: files left, it will run again later");
        return Result.retry();
    }
}
