package net.ark3us.saferec.guard;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/** Restarts the guard if the low-memory killer took it: it cannot return to the foreground alone. */
public class GuardWatchdog extends Worker {
    private static final String UNIQUE_NAME = "guard-watchdog";

    public GuardWatchdog(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Guard.ensureRunning(getApplicationContext());
        return Result.success();
    }

    static void schedule(Context context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                new PeriodicWorkRequest.Builder(GuardWatchdog.class, 15, TimeUnit.MINUTES).build());
    }

    static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME);
    }
}
