package net.ark3us.saferec.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.util.Log;

import net.ark3us.saferec.MainActivity;
import net.ark3us.saferec.R;

public class NotificationHelper {
    private static final String TAG = "NotificationHelper";
    public static final String CHANNEL_ID = "SafeRecServiceChannel";
    public static final int NOTIFICATION_ID = 1;

    public static void createNotificationChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            Log.e(TAG, "Failed to get NotificationManager");
            return;
        }
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                "SafeRec Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT);
        manager.createNotificationChannel(serviceChannel);
    }

    public static Notification buildNotification(Context context, boolean isRecording, int activeUploads) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("from_notification", true);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = isRecording ? "SafeRec Recording" : "SafeRec Service";
        StringBuilder contentText = new StringBuilder();
        if (isRecording) {
            contentText.append("Recording in progress...");
        } else {
            contentText.append("Service is idle");
        }

        if (activeUploads > 0) {
            contentText.append(" (Uploading ").append(activeUploads).append(" file")
                    .append(activeUploads > 1 ? "s" : "").append(")");
        }

        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(contentText.toString())
                .setSmallIcon(R.drawable.ic_stat_shield_record)
                .setLargeIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                .setColor(context.getColor(R.color.primary))
                .setContentIntent(pendingIntent)
                .setOngoing(isRecording)
                .setOnlyAlertOnce(true);

        if (activeUploads > 0) {
            builder.setProgress(0, 0, true);
        }

        return builder.build();
    }
}
