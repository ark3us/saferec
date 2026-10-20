package net.ark3us.saferec.misc;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.Nullable;

public class Settings {
    private static final String TAG = Settings.class.getSimpleName();

    private static final String PREFS_NAME = "SafeRecPrefs";

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static void setAccessToken(Context context, String token) {
        Log.i(TAG, "Saving access token: " + (token != null ? "****" : "null"));
        getPrefs(context).edit().putString("accessToken", token).apply();
    }

    public static String getAccessToken(Context context) {
        return getPrefs(context).getString("accessToken", null);
    }

    /** Google account the stored token belongs to. Stays on the phone. */
    public static String getDriveAccount(Context context) {
        return getPrefs(context).getString("driveAccount", null);
    }

    public static void setDriveAccount(Context context, @Nullable String email) {
        getPrefs(context).edit().putString("driveAccount", email).apply();
    }

    /** Last address recordings were shared with, offered again next time. */
    public static String getShareEmail(Context context) {
        return getPrefs(context).getString("shareEmail", "");
    }

    public static void setShareEmail(Context context, String email) {
        getPrefs(context).edit().putString("shareEmail", email).apply();
    }

    public static boolean onlyAudio(Context context) {
        return getPrefs(context).getBoolean("onlyAudio", false);
    }

    public static void setOnlyAudio(Context context, boolean onlyAudio) {
        Log.i(TAG, "Setting onlyAudio to: " + onlyAudio);
        getPrefs(context).edit().putBoolean("onlyAudio", onlyAudio).apply();
    }

    public static void setUseFrontCamera(Context context, boolean useFront) {
        Log.i(TAG, "Setting useFrontCamera to: " + useFront);
        getPrefs(context).edit().putBoolean("useFrontCamera", useFront).apply();
    }

    public static boolean getUseFrontCamera(Context context) {
        return getPrefs(context).getBoolean("useFrontCamera", false);
    }

    /** Quick starts (locked phone) record with their own defaults, not the viewfinder's last choice. */
    public static boolean isQuickStartAudioOnly(Context context) {
        return getPrefs(context).getBoolean("quickStartAudioOnly", false);
    }

    public static void setQuickStartAudioOnly(Context context, boolean audioOnly) {
        Log.i(TAG, "Setting quickStartAudioOnly to: " + audioOnly);
        getPrefs(context).edit().putBoolean("quickStartAudioOnly", audioOnly).apply();
    }

    public static boolean isQuickStartFrontCamera(Context context) {
        return getPrefs(context).getBoolean("quickStartFrontCamera", false);
    }

    public static void setQuickStartFrontCamera(Context context, boolean front) {
        Log.i(TAG, "Setting quickStartFrontCamera to: " + front);
        getPrefs(context).edit().putBoolean("quickStartFrontCamera", front).apply();
    }

    public static boolean isVibrationsEnabled(Context context) {
        return getPrefs(context).getBoolean("vibrations", true);
    }

    public static void setVibrationsEnabled(Context context, boolean enabled) {
        Log.i(TAG, "Setting vibrations to: " + enabled);
        getPrefs(context).edit().putBoolean("vibrations", enabled).apply();
    }

    public static String getVideoQuality(Context context) {
        return getPrefs(context).getString("videoQuality", "LOW");
    }

    public static void setVideoQuality(Context context, String quality) {
        Log.i(TAG, "Setting videoQuality to: " + quality);
        getPrefs(context).edit().putString("videoQuality", quality).apply();
    }

    public static boolean isTutorialShown(Context context) {
        return getPrefs(context).getBoolean("tutorialShown", false);
    }

    public static void setTutorialShown(Context context, boolean shown) {
        getPrefs(context).edit().putBoolean("tutorialShown", shown).apply();
    }

    /** Defaults to the old tutorial's flag: users who saw it never get the cards. */
    public static boolean isFirstRunDone(Context context) {
        return getPrefs(context).getBoolean("firstRunDone", isTutorialShown(context));
    }

    public static void setFirstRunDone(Context context, boolean done) {
        getPrefs(context).edit().putBoolean("firstRunDone", done).apply();
    }

    public static int getChunkSizeMB(Context context) {
        return getPrefs(context).getInt("chunkSizeMB", 0);
    }

    public static void setChunkSizeMB(Context context, int sizeMB) {
        Log.i(TAG, "Setting chunkSizeMB to: " + sizeMB);
        getPrefs(context).edit().putInt("chunkSizeMB", sizeMB).apply();
    }

    public static boolean isTimestampingEnabled(Context context) {
        return getPrefs(context).getBoolean("timestampingEnabled", false);
    }

    public static void setTimestampingEnabled(Context context, boolean enabled) {
        Log.i(TAG, "Setting timestampingEnabled to: " + enabled);
        getPrefs(context).edit().putBoolean("timestampingEnabled", enabled).apply();
    }

}
