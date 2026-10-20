package net.ark3us.saferec.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
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
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        manager.createNotificationChannel(serviceChannel);
    }

    public static Notification buildNotification(Context context, boolean isRecording, long recordingSinceMillis, int safeChunks,
                                                 int activeUploads, int activeDeletions,
                                                 int activeTimestamping, int pendingFiles) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = isRecording ? context.getString(R.string.notif_title_recording) : context.getString(R.string.notif_title_service);
        StringBuilder contentText = new StringBuilder();
        if (isRecording) {
            if (safeChunks > 0) {
                contentText.append(context.getResources().getQuantityString(
                        R.plurals.notif_safe_chunks, safeChunks, safeChunks));
            } else {
                contentText.append(context.getString(R.string.notif_content_recording));
            }
        } else if (activeTimestamping > 0) {
            contentText.append(context.getString(R.string.notif_content_timestamping));
        } else if (activeDeletions > 0) {
            contentText.append(context.getString(R.string.notif_content_deleting));
        } else if (activeUploads > 0) {
            contentText.append(context.getString(R.string.notif_content_uploading));
        } else if (pendingFiles > 0) {
            // Between upload retries (no network): the evidence is not on Drive yet
            contentText.append(context.getResources().getQuantityString(
                    R.plurals.notif_status_waiting, pendingFiles, pendingFiles));
        } else {
            contentText.append(context.getString(R.string.notif_content_idle));
        }

        List<String> statusParts = new ArrayList<>();
        if (activeTimestamping > 0) {
            statusParts.add(context.getResources().getQuantityString(
                    R.plurals.notif_status_timestamping, activeTimestamping, activeTimestamping));
        }
        if (activeUploads > 0) {
            statusParts.add(context.getResources().getQuantityString(
                    R.plurals.notif_status_uploading, activeUploads, activeUploads));
        }
        if (activeDeletions > 0) {
            statusParts.add(context.getResources().getQuantityString(
                    R.plurals.notif_status_deleting, activeDeletions, activeDeletions));
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
                .setOngoing(isRecording || activeUploads > 0 || activeDeletions > 0 || activeTimestamping > 0 || pendingFiles > 0)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(new Notification.Builder(context, CHANNEL_ID)
                        .setContentTitle(context.getString(R.string.app_name))
                        .setContentText(context.getString(R.string.notif_public_active))
                        .setSmallIcon(R.drawable.ic_stat_shield_record)
                        .build());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }

        if (isRecording) {
            builder.setUsesChronometer(true)
                    .setWhen(recordingSinceMillis)
                    .setShowWhen(true);
            builder.addAction(buildStopAction(context));
        }

        if (activeUploads > 0 || activeDeletions > 0 || activeTimestamping > 0) {
            builder.setProgress(0, 0, true);
        }

        return builder.build();
    }

    private static Notification.Action buildStopAction(Context context) {
        PendingIntent stopIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            stopIntent = PendingIntent.getService(context, 1,
                    SafeRecService.createCommandIntent(context, SafeRecService.CMD_STOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } else {
            // Before Android 12 the action cannot require the unlock: open the app instead
            Intent open = new Intent(context, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            stopIntent = PendingIntent.getActivity(context, 1, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        }
        Notification.Action.Builder action = new Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.outline_stop_circle_24),
                context.getString(R.string.notif_action_stop), stopIntent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The phone must be unlocked: whoever holds a locked phone cannot stop the recording
            action.setAuthenticationRequired(true);
        }
        return action.build();
    }
}
