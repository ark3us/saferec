package net.ark3us.saferec.net;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Operation;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import net.ark3us.saferec.media.ChunkRepair;
import net.ark3us.saferec.media.WorkDir;
import net.ark3us.saferec.misc.Settings;

import java.io.File;
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
    private static final String NOW_WORK_NAME = "outbox-now";
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
     * Returns null when WorkManager refused it.
     */
    @Nullable
    public static Operation schedule(Context context) {
        return enqueue(context, ExistingWorkPolicy.KEEP);
    }

    @Nullable
    private static Operation enqueue(Context context, ExistingWorkPolicy policy) {
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(OutboxWorker.class)
                    .setConstraints(constraints)
                    .setInitialDelay(START_DELAY_MIN, TimeUnit.MINUTES)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MIN, TimeUnit.MINUTES)
                    .build();
            return WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request);
        } catch (RuntimeException e) {
            // The safety net must never break an upload
            Log.e(TAG, "Cannot schedule the outbox job", e);
            return null;
        }
    }

    /**
     * For when the service is known to be gone and files wait: it stopped, or the phone booted.
     * Android may hold a delayed job of an app in the background for over half an hour (seen on
     * a Galaxy S10+ with Android 16: ready, never started, 35 min). An expedited job is not held
     * back and gets the network. Out of quota it runs as a regular job. Below Android 12
     * WorkManager would run it as a foreground service with a notification: regular job there.
     */
    @Nullable
    public static Operation scheduleNow(Context context) {
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();
            OneTimeWorkRequest.Builder builder = new OneTimeWorkRequest.Builder(OutboxWorker.class)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MIN, TimeUnit.MINUTES);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST);
            }
            return WorkManager.getInstance(context)
                    .enqueueUniqueWork(NOW_WORK_NAME, ExistingWorkPolicy.KEEP, builder.build());
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot schedule the outbox job", e);
            return null;
        }
    }

    /** True when files wait in the outbox, or chunks in the work dir or for their repair. */
    public static boolean filesWaiting(Context context) {
        for (File dir : new File[]{GoogleDriveFileUploader.outboxDir(context), WorkDir.dir(context), WorkDir.repairDir(context)}) {
            String[] names = dir.list();
            if (names != null && names.length > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Like schedule(), but returns once the job is stored, for a caller about to lose the
     * process (the phone switching off). WorkManager stores requests one after the other, so
     * the ones made before are stored too. Returns false on timeout or failure.
     */
    public static boolean scheduleAndWait(Context context, long timeoutMs) {
        Operation operation = schedule(context);
        if (operation == null) {
            return false;
        }
        try {
            operation.getResult().get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "The outbox job may not be stored", e);
            return false;
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        int adopted = WorkDir.get().recover(WorkDir.dir(context), GoogleDriveFileUploader.outboxDir(context), new ChunkRepair());
        if (adopted > 0) {
            Log.w(TAG, "Adopted " + adopted + " chunks a dead process left in the work dir");
        }
        try {
            return upload(context);
        } finally {
            if (WorkDir.get().inUse()) {
                // A recording runs: a power cut now would leave its open chunk in the work dir.
                // A job queued after this one adopts it after the boot. Scheduling with KEEP
                // would do nothing while this one runs.
                enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE);
            }
        }
    }

    private Result upload(Context context) {
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
