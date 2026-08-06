package com.drivingefficiency.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.google.android.gms.location.ActivityTransitionResult;
import com.google.android.gms.location.ActivityTransitionEvent;
import com.google.android.gms.location.DetectedActivity;
import com.google.android.gms.location.ActivityTransition;

/**
 * Genuine general-driving auto-start -- previously monitoring only ever
 * auto-started when Dasher was detected opening. This lets it start from
 * driving motion ALONE, without ever opening Dasher at all, using
 * Android's Activity Recognition API (already a dependency via
 * play-services-location, no new Gradle dependency needed).
 *
 * HONEST LIMIT, stated directly rather than implied: this cannot be
 * verified working end-to-end without a real device recognizing real
 * driving motion. Sandbox validation (javac compiling this correctly,
 * the logic reading sensibly) confirms the CODE is sound, not that
 * Android's actual motion classifier will reliably fire IN_VEHICLE
 * transitions the way this assumes -- that can only be confirmed by
 * someone actually driving with this installed and checking the log
 * for DRIVING_DETECTION entries afterward.
 */
public class DrivingDetectionReceiver extends BroadcastReceiver {

    public static final String ACTION_DRIVING_DETECTED = "com.drivingefficiency.app.DRIVING_DETECTED";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ActivityTransitionResult.hasResult(intent)) {
            return;
        }
        ActivityTransitionResult result = ActivityTransitionResult.extractResult(intent);
        if (result == null) {
            return;
        }
        try {
            for (ActivityTransitionEvent event : result.getTransitionEvents()) {
                boolean isEnteringVehicle = event.getActivityType() == DetectedActivity.IN_VEHICLE
                        && event.getTransitionType() == ActivityTransition.ACTIVITY_TRANSITION_ENTER;
                if (isEnteringVehicle) {
                    logToEngine(context, "DRIVING_DETECTION",
                            "Activity Recognition detected entering a vehicle -- attempting auto-start");
                    if (!TripForegroundService.isRunning) {
                        Intent startIntent = new Intent(context, TripForegroundService.class);
                        startIntent.setAction(TripForegroundService.ACTION_START_TRACKING);
                        context.startForegroundService(startIntent);
                        logToEngine(context, "DRIVING_DETECTION",
                                "Auto-started monitoring from detected driving motion (Dasher was never opened)");
                    }
                }
            }
        } catch (RuntimeException e) {
            logToEngine(context, "ERROR", "DrivingDetectionReceiver exception: "
                    + android.util.Log.getStackTraceString(e));
        }
    }

    /** Matches the same engine-connection + FallbackLogger safety-net pattern already established in other standalone receivers (e.g. MonitoringWatchdogReceiver). */
    private void logToEngine(Context context, String category, String message) {
        try {
            com.chaquo.python.PyObject engine = PythonBridge.getEngine(context);
            engine.callAttr("log_diagnostic", category, message);
        } catch (RuntimeException e) { // covers PyException too
            FallbackLogger.log(context, category, message + " (engine unavailable: "
                    + android.util.Log.getStackTraceString(e) + ")");
        }
    }
}
