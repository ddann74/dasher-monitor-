package com.drivingefficiency.app;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Vibration cues for the Smart Score label -- lets you know an offer's
 * quality without looking at the screen, same safety rationale as the
 * existing TTS announcements. The VIBRATE permission was already
 * declared in the manifest but never actually used anywhere until now.
 *
 * Pattern: two short pulses for a good offer, one short pulse for a
 * borderline one, one long buzz to avoid.
 */
public final class HapticFeedback {

    private HapticFeedback() {}

    public static void vibrateForLabel(Context context, String label) {
        Vibrator vibrator = getVibrator(context);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }

        long[] pattern;
        switch (label) {
            case "Excellent":
            case "Good":
                // Two quick short bursts.
                pattern = new long[]{0, 120, 100, 120};
                break;
            case "Fair":
                // One short burst.
                pattern = new long[]{0, 150};
                break;
            default:
                // One long, continuous buzz.
                pattern = new long[]{0, 500};
                break;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            vibrator.vibrate(pattern, -1);
        }
    }

    private static Vibrator getVibrator(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vibratorManager =
                    (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return vibratorManager != null ? vibratorManager.getDefaultVibrator() : null;
        }
        return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }
}
