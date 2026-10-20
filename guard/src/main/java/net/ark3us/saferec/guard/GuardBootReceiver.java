package net.ark3us.saferec.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a boot or an app update the guard is gone: bring it back if it is enabled. */
public class GuardBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Guard.ensureRunning(context);
    }
}
