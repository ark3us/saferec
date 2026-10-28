package net.ark3us.saferec.guard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/**
 * Counts screen on/off changes, the trace of power-key presses (no public API reports the key
 * itself). As many as the user picked, within {@link TriggerRule#spanMs(int)}, open the app's
 * quick-start screen, which shows over the lock screen and starts the recording.
 */
public class GuardService extends Service {
    private static final String TAG = GuardService.class.getSimpleName();
    private static final String CHANNEL_ID = "guard";
    private static final int NOTIFICATION_ID = 4242;

    // The guard has no compile dependency on the app: these match QuickStartActivity in the app
    // (QuickStartActivity.class, EXTRA_SOURCE and SOURCE_POWER).
    private static final String QUICK_START_CLASS = "net.ark3us.saferec.QuickStartActivity";
    private static final String EXTRA_SOURCE = "net.ark3us.saferec.extra.SOURCE";
    private static final String SOURCE_POWER = "power";

    /** True once the service is in the foreground and listens to the screen. */
    static volatile boolean running;

    private final TriggerRule rule = new TriggerRule();
    private boolean receiverRegistered;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Read on every change, so a new count in Settings applies at once
            if (rule.onScreenChange(SystemClock.elapsedRealtime(), Guard.presses(context))) {
                trigger(context);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                getString(R.string.guard_channel_name), NotificationManager.IMPORTANCE_MIN));
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_guard)
                .setContentTitle(getString(R.string.guard_ready_title))
                .setContentText(getString(R.string.guard_ready_text))
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (IllegalStateException e) {
            // ForegroundServiceStartNotAllowedException (API 31+) is one of these
            Log.w(TAG, "Guard cannot enter the foreground", e);
            stopSelf();
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
        receiverRegistered = true;
        running = true;
        Log.i(TAG, "Guard running");
    }

    private void trigger(Context context) {
        if (!Settings.canDrawOverlays(context)) {
            // Android does not throw for a blocked background start: only this line shows it.
            // Still try, in case the check lies or another exemption applies.
            Log.w(TAG, "trigger refused: no overlay permission");
        }
        Intent intent = new Intent()
                .setComponent(new ComponentName(context.getPackageName(), QUICK_START_CLASS))
                .putExtra(EXTRA_SOURCE, SOURCE_POWER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            context.startActivity(intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "trigger refused", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        Log.i(TAG, "Guard stopped");
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver);
            receiverRegistered = false;
        }
        super.onDestroy();
    }
}
