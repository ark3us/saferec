package net.ark3us.saferec;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import androidx.core.content.ContextCompat;

import net.ark3us.saferec.services.Haptics;
import net.ark3us.saferec.services.SafeRecService;

/**
 * The one entry point of every quick start (tile, shortcut, power-key guard, camera press).
 * It is the only screen allowed over the lock screen: it shows black, never the viewfinder,
 * so whoever holds a locked phone sees nothing of the camera.
 */
public class QuickStartActivity extends Activity {
    private static final String TAG = QuickStartActivity.class.getSimpleName();

    public static final String EXTRA_SOURCE = "net.ark3us.saferec.extra.SOURCE";
    public static final String SOURCE_TILE = "tile";
    public static final String SOURCE_SHORTCUT = "shortcut";
    public static final String SOURCE_POWER = "power";
    public static final String SOURCE_CAMERA = "camera";

    private static final long LOCKED_FINISH_DELAY_MS = 1500;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The manifest declares both too: the tile's PendingIntent reads them there (Android 14+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        View black = new View(this);
        black.setBackgroundColor(0xFF000000);
        black.setContentDescription(getString(R.string.quick_start_recording));
        setContentView(black);

        // Only a fresh activity starts the recording: turning the screen back on resumes it,
        // and a recreated one must not send a second start. A recreated one still finishes
        // below, or its black screen would stay up.
        if (savedInstanceState == null) {
            Log.i(TAG, "Quick start from " + source());
            startRecording();
        } else {
            Log.i(TAG, "Quick start screen recreated");
        }

        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isKeyguardLocked()) {
            handler.postDelayed(this::finish, LOCKED_FINISH_DELAY_MS);
        } else {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void startRecording() {
        Intent start = SafeRecService.createCommandIntent(this, SafeRecService.CMD_START);
        start.putExtra(SafeRecService.EXTRA_QUICK_START, true);
        try {
            ContextCompat.startForegroundService(this, start);
        } catch (RuntimeException e) {
            // This runs in the recording's own process: a refusal must never crash a recording
            // already in progress
            Log.e(TAG, "Cannot start the recording service", e);
            try {
                Haptics.failed(this);
            } catch (RuntimeException vibrationError) {
                // Nothing in this catch may throw: it protects a recording in progress
                Log.w(TAG, "Cannot vibrate the failure", vibrationError);
            }
        }
    }

    private String source() {
        Intent intent = getIntent();
        String action = intent.getAction();
        if (MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA.equals(action)
                || MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE.equals(action)) {
            return SOURCE_CAMERA;
        }
        String source = intent.getStringExtra(EXTRA_SOURCE);
        return source != null ? source : "unknown";
    }
}
