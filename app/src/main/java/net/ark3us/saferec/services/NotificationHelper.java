package net.ark3us.saferec.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

import net.ark3us.saferec.MainActivity;
import net.ark3us.saferec.R;

public class NotificationHelper {
    private NotificationHelper() {}
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

    public static Notification buildNotification(Context context, boolean isRecording, int activeUploads, int activeDeletions, int activeTimestamping) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(SafeRecService.EXTRA_FROM_NOTIFICATION, true);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = isRecording ? "SafeRec Recording" : "SafeRec Service";
        StringBuilder contentText = new StringBuilder();
        if (isRecording) {
            contentText.append("Recording in progress...");
        } else if (activeTimestamping > 0) {
            contentText.append("Timestamping...");
        } else if (activeDeletions > 0) {
            contentText.append("Deleting recordings...");
        } else if (activeUploads > 0) {
            contentText.append("Uploading...");
        } else {
            contentText.append("Service is idle");
        }

        List<String> statusParts = new ArrayList<>();
        if (activeTimestamping > 0) {
            statusParts.add("Timestamping " + activeTimestamping + " file" + (activeTimestamping > 1 ? "s" : ""));
        }
        if (activeUploads > 0) {
            statusParts.add("Uploading " + activeUploads + " file" + (activeUploads > 1 ? "s" : ""));
        }
        if (activeDeletions > 0) {
            statusParts.add("Deleting " + activeDeletions + " file" + (activeDeletions > 1 ? "s" : ""));
        }

        if (!statusParts.isEmpty()) {
            if (isRecording) {
                contentText.append(" (").append(String.join(", ", statusParts)).append(")");
            } else {
                StringBuilder details = new StringBuilder();
                for (int i = 0; i < statusParts.size(); i++) {
                    if (i > 0) details.append(", ");
                    details.append(statusParts.get(i));
                }
                if (details.length() > 0) {
                   contentText.setLength(0);
                   contentText.append(details.toString());
                }
            }
        }

        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(contentText.toString())
                .setSmallIcon(R.drawable.ic_stat_shield_record)
                .setLargeIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                .setColor(context.getColor(R.color.primary))
                .setContentIntent(pendingIntent)
                .setOngoing(isRecording || activeUploads > 0 || activeDeletions > 0 || activeTimestamping > 0)
                .setOnlyAlertOnce(true);

        if (activeUploads > 0 || activeDeletions > 0 || activeTimestamping > 0) {
            builder.setProgress(0, 0, true);
        }

        return builder.build();
    }
}
