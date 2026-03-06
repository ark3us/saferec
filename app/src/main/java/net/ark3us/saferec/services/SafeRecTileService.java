package net.ark3us.saferec.services;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;
import android.util.Log;

import net.ark3us.saferec.MainActivity;

public class SafeRecTileService extends TileService {
    private static final String TAG = SafeRecTileService.class.getSimpleName();

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Override
    public void onClick() {
        super.onClick();
        unlockAndRun(() -> {
            try {
                Intent intent = new Intent(this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra("command", SafeRecService.CMD_START);
                intent.putExtra("from_tile", true);
                Log.i(TAG, "Tile clicked, launching SafeRecActivity");

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    PendingIntent pendingIntent = PendingIntent.getActivity(
                            this,
                            0,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    startActivityAndCollapse(pendingIntent);
                } else {
                    startActivityAndCollapse(intent);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch MainActivity from tile", e);
            }
        });
    }
}
