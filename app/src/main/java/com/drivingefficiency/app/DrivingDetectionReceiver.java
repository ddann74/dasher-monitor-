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
                    // docs/circuit_breaker_other_autostart_paths/PRD.md --
                    // CONFIRMED REAL GAP, fixed here: this auto-start path
                    // used to have no awareness of MonitoringWatchdogReceiver's
                    // own restart circuit breaker -- every vehicle entry
                    // re-triggered the identical doomed startForegroundService
                    // call (and its own separate raiseMonitoringNotActiveAlert)
                    // even after the breaker had already tripped and gone
                    // quiet elsewhere, defeating the whole point of tripping
                    // it. A quiet log line, not a duplicate alert -- the
                    // watchdog's own escalated alert already told the driver
                    // this needs a manual app open.
                    if (MonitoringWatchdogReceiver.isRestartCircuitBreakerTripped(context)) {
                        logToEngine(context, "DRIVING_DETECTION",
                                "Skipping auto-start -- the restart circuit breaker has already tripped "
                                        + "from repeated failures; open the app manually to resume");
                    } else if (!TripForegroundService.isRunning) {
                        // docs/dash_monitoring_awareness/PRD.md -- own
                        // try/catch (not just the outer one below) so a
                        // real startForegroundService rejection (a
                        // documented Android 12+ background-start
                        // restriction) raises the loud alert specifically,
                        // not just a generic exception log line. No
                        // delayed re-verification here (unlike
                        // DasherAccessibilityService's equivalent) --
                        // a BroadcastReceiver is meant to be short-lived,
                        // and the synchronous throw is the primary real
                        // failure mode either way.
                        try {
                            Intent startIntent = new Intent(context, TripForegroundService.class);
                            startIntent.setAction(TripForegroundService.ACTION_START_TRACKING);
                            context.startForegroundService(startIntent);
                            logToEngine(context, "DRIVING_DETECTION",
                                    "Auto-started monitoring from detected driving motion (Dasher was never opened)");
                        } catch (RuntimeException e) {
                            logToEngine(context, "ERROR", "Auto-start from driving detection threw: "
                                    + android.util.Log.getStackTraceString(e));
                            TripForegroundService.raiseMonitoringNotActiveAlert(context,
                                    "driving detected, Dasher never opened");
                        }
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
