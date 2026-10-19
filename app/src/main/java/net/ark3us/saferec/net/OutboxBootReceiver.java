package net.ark3us.saferec.net;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * After a boot, uploads what a power cut, a shutdown or a dead service left behind, without the
 * app being opened. WorkManager reschedules the delayed outbox job at boot by itself, but
 * Android may then hold it for over half an hour: the expedited job goes as soon as there is a
 * network. Sent after the first unlock, when the outbox (credential-encrypted) is readable.
 */
public class OutboxBootReceiver extends BroadcastReceiver {
    private static final String TAG = OutboxBootReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        if (OutboxWorker.filesWaiting(context)) {
            Log.i(TAG, "Files left from before the boot: uploading them as soon as there is a network");
            OutboxWorker.scheduleNow(context);
        }
    }
}
