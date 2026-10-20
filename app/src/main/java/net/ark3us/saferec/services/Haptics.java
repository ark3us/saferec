package net.ark3us.saferec.services;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;

import net.ark3us.saferec.misc.Settings;

/** Vibrations that confirm a start or a stop when the screen is not looked at. */
public final class Haptics {
    private static final long[] STARTED = {0, 80, 120, 80};
    private static final long[] FAILED = {0, 80, 100, 80, 100, 80};
    private static final long STOPPED_MS = 400;

    private Haptics() {}

    /** Two short pulses. */
    public static void started(Context context) {
        vibrate(context, VibrationEffect.createWaveform(STARTED, -1));
    }

    /** Three short pulses: a start that records nothing (no camera, no microphone). */
    public static void failed(Context context) {
        vibrate(context, VibrationEffect.createWaveform(FAILED, -1));
    }

    /** One long pulse. */
    public static void stopped(Context context) {
        vibrate(context, VibrationEffect.createOneShot(STOPPED_MS, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    private static void vibrate(Context context, VibrationEffect effect) {
        if (!Settings.isVibrationsEnabled(context)) {
            return;
        }
        Vibrator vibrator = context.getSystemService(Vibrator.class);
        if (vibrator != null) {
            vibrator.vibrate(effect);
        }
    }
}
