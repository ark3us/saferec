package net.ark3us.saferec.misc;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

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

    public static boolean isRecordingsTutorialShown(Context context) {
        return getPrefs(context).getBoolean("recordingsTutorialShown", false);
    }

    public static void setRecordingsTutorialShown(Context context, boolean shown) {
        getPrefs(context).edit().putBoolean("recordingsTutorialShown", shown).apply();
    }

    public static boolean isAutoStartOnLaunch(Context context) {
        return getPrefs(context).getBoolean("autoStartOnLaunch", false);
    }

    public static void setAutoStartOnLaunch(Context context, boolean enabled) {
        Log.i(TAG, "Setting autoStartOnLaunch to: " + enabled);
        getPrefs(context).edit().putBoolean("autoStartOnLaunch", enabled).apply();
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
