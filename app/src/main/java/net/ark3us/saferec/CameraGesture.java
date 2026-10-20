package net.ark3us.saferec;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

/**
 * The optional camera double press. Android resolves the double press of the power key to the
 * default camera app. A disabled activity alias declares the camera intent filters, and is enabled
 * only when the owner turns the option on: a permanent declaration would show a chooser on phones
 * with two camera apps, which on the lock screen needs an unlock.
 */
public final class CameraGesture {
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

    /** The intent the double press sends, to try it without the key. */
    public static Intent tryIntent() {
        return new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
    }

    private static ComponentName alias(Context context) {
        return new ComponentName(context.getPackageName(), ALIAS);
    }
}
