package net.ark3us.saferec;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;

/**
 * The optional camera double press. Android resolves the double press of the power key to the
 * default camera app. A disabled activity alias declares the camera intent filters, and is enabled
 * only when the owner turns the option on: a permanent declaration would show a chooser on phones
 * with two camera apps, which on the lock screen needs an unlock.
 */
public final class CameraGesture {
    private static final String TAG = CameraGesture.class.getSimpleName();
    private static final String ALIAS = "net.ark3us.saferec.CameraGestureAlias";
    private static final String SYSTEM_UI = "com.android.systemui";

    private CameraGesture() {}

    /** True when the gesture is not tied to one other camera app. */
    public static boolean allowsOtherApps(@Nullable String gesturePackage, String ownPackage) {
        return gesturePackage == null || gesturePackage.isEmpty() || gesturePackage.equals(ownPackage);
    }

    /** Whether the phone lets the owner pick the app. If SystemUI cannot be read, say yes: "try it now" will tell. */
    @SuppressLint("DiscouragedApi") // the resource belongs to another app: no R id exists
    public static boolean isAvailable(Context context) {
        try {
            Resources res = context.getPackageManager().getResourcesForApplication(SYSTEM_UI);
            int id = res.getIdentifier("config_cameraGesturePackage", "string", SYSTEM_UI);
            if (id == 0) return true;
            return allowsOtherApps(res.getString(id), context.getPackageName());
        } catch (Exception e) {
            Log.w(TAG, "Cannot read SystemUI's camera gesture setting, offering the option", e);
            return true;
        }
    }

    public static boolean isEnabled(Context context) {
        return context.getPackageManager().getComponentEnabledSetting(alias(context))
            == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    public static void setEnabled(Context context, boolean enabled) {
        context.getPackageManager().setComponentEnabledSetting(alias(context),
            enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP);
    }

    /**
     * The intents the double press sends, to try them without the key: unlocked, then locked.
     * Android stores the "Always" choice per action, so each one needs its own try: with only
     * the first, a locked phone still asked which app to use, behind the unlock.
     */
    public static Intent[] tryIntents() {
        return new Intent[] {
            new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE),
        };
    }

    /** Whether Android now opens the alias, without asking, for this intent ("Always" was stored). */
    public static boolean isDefaultFor(Context context, Intent intent) {
        ResolveInfo info = context.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        return info != null && info.activityInfo != null
                && context.getPackageName().equals(info.activityInfo.packageName)
                && ALIAS.equals(info.activityInfo.name);
    }

    /** {@link #isDefaultFor} for each of {@link #tryIntents()}. */
    public static boolean[] storedTries(Context context) {
        Intent[] tries = tryIntents();
        boolean[] stored = new boolean[tries.length];
        for (int i = 0; i < tries.length; i++) stored[i] = isDefaultFor(context, tries[i]);
        return stored;
    }

    private static ComponentName alias(Context context) {
        return new ComponentName(context.getPackageName(), ALIAS);
    }
}
