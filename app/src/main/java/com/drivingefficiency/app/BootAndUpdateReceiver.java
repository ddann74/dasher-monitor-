package com.drivingefficiency.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;

/**
 * Logs device reboots and app updates to the diagnostic log -- directly
 * relevant to OEM battery-killer investigation: some phones (including
 * some Oppo/OnePlus/Realme ColorOS/OxygenOS devices) silently reset
 * battery-optimization exemptions after a reboot or system update.
 * A logged "device just rebooted" or "app was just updated" entry lets
 * you correlate "did monitoring stop right after this" instead of
 * guessing from memory.
 *
 * docs/boot_resume_monitoring/PRD.md -- CONFIRMED REAL GAP, fixed here:
 * this used to only log the event. A reboot or app update kills the
 * running process -- the trip in progress, GPS callbacks, the
 * watchdog's own armed alarm, everything -- and was the ONE
 * interruption type with no recovery path at all (a killed-but-not-
 * rebooted process, or a stuck GPS callback, both already have one via
 * MonitoringWatchdogReceiver). Now checks whether monitoring was
 * actually supposed to be running (MonitoringWatchdogReceiver.
 * wasIntendedActive, a flag that already survives exactly this kind of
 * event) and resumes it -- the same startForegroundService call
 * DrivingDetectionReceiver already makes successfully elsewhere in
 * this codebase, not a new, unproven mechanism.
 *
 * PRD ss4's two open questions (treat MY_PACKAGE_REPLACED the same as a
 * reboot; notify visibly rather than log-only) are both built from the
 * PRD's own stated recommendation, since no driver override was given.
 */
public class BootAndUpdateReceiver extends BroadcastReceiver {

    private static final String RESUME_CHANNEL_ID = "monitoring_auto_resumed";
    // docs/boot_resume_circuit_breaker_and_false_notification/PRD.md --
    // same value and reasoning as DasherAccessibilityService's own
    // MONITORING_VERIFY_DELAY_MS: long enough for TripForegroundService's
    // onCreate() to either genuinely finish starting or hit and handle
    // its own SecurityException, short enough to stay well inside a
    // BroadcastReceiver's goAsync() window.
    private static final long MONITORING_VERIFY_DELAY_MS = 5 * 1000;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        boolean isBootOrUpdate = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        String message = Intent.ACTION_BOOT_COMPLETED.equals(action)
                ? "Device rebooted"
                : Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                    ? "App was updated/reinstalled"
                    : "Unknown broadcast: " + action;

        logToEngine(context, "SYSTEM", message);

        if (!isBootOrUpdate) {
            return;
        }
        if (!MonitoringWatchdogReceiver.wasIntendedActive(context)) {
            logToEngine(context, "SYSTEM", message + " -- monitoring was off before this, not resuming");
            return;
        }
        // docs/boot_watchdog_rearm/PRD.md -- CONFIRMED REAL GAP, fixed
        // here (round-10 scouting finding #1): AlarmManager alarms do
        // NOT survive a reboot -- the watchdog's own alarm from before
        // this reboot is already gone by the time this runs.
        // scheduleWatchdog() was previously only ever called from
        // INSIDE a successful TripForegroundService.startTracking() (or
        // its own re-arm runnable, itself only scheduled inside
        // startTracking() too) -- so this receiver only ever fired
        // ACTION_START_TRACKING and hoped startTracking() got far
        // enough to re-arm the watchdog itself. If that restart attempt
        // fails (e.g. the same Android 14 FGS-location eligibility
        // SecurityException this codebase already guards against
        // elsewhere -- boot-triggered starts are not obviously exempt
        // from that specific check), NO watchdog alarm would exist at
        // all after this reboot -- silently disarming every fail-safe
        // mechanism (staleness alert, circuit breaker, escalated alert,
        // GPS reacquire) for the rest of the shift, with nothing
        // anywhere telling the driver the safety net itself is gone.
        // Called directly here, unconditionally, independent of whether
        // the restart attempt below ends up succeeding -- the exact
        // "device just rebooted" moment is precisely when a fresh alarm
        // most needs (re-)arming. Safe to call even if startTracking()
        // ALSO successfully schedules it moments later --
        // AlarmManager.setExactAndAllowWhileIdle with FLAG_UPDATE_CURRENT
        // safely replaces any still-pending alarm (the same idempotent
        // re-arm pattern already established elsewhere in this
        // codebase, e.g. watchdogRearmRunnable).
        MonitoringWatchdogReceiver.scheduleWatchdog(context);

        // docs/boot_resume_circuit_breaker_and_false_notification/PRD.md --
        // CONFIRMED REAL GAP, fixed here (round-13 scouting finding #1):
        // every OTHER background auto-start path
        // (DrivingDetectionReceiver, DasherAccessibilityService's 3 call
        // sites) already checks MonitoringWatchdogReceiver's restart
        // circuit breaker before attempting a restart -- this one never
        // did, despite docs/circuit_breaker_other_autostart_paths/PRD.md
        // claiming the gap was closed for every auto-start path. A
        // device with a persistent restart failure (e.g. the Android 14
        // FGS-location SecurityException) would keep re-attempting the
        // identical doomed start on every single reboot/app-update,
        // exactly the repeated-doomed-retry behavior the breaker exists
        // to stop. A quiet log line, not a duplicate alert -- the
        // watchdog's own escalated alert already told the driver this
        // needs a manual app open.
        if (MonitoringWatchdogReceiver.isRestartCircuitBreakerTripped(context)) {
            logToEngine(context, "SYSTEM", message + " -- skipping auto-resume, the restart circuit "
                    + "breaker has already tripped from repeated failures; open the app manually to resume");
            return;
        }
        try {
            Intent startIntent = new Intent(context, TripForegroundService.class);
            startIntent.setAction(TripForegroundService.ACTION_START_TRACKING);
            context.startForegroundService(startIntent);
            logToEngine(context, "SYSTEM", message + " -- monitoring was active before this, auto-resume dispatched");
            // docs/boot_resume_circuit_breaker_and_false_notification/PRD.md
            // -- CONFIRMED REAL GAP, fixed here (round-13 scouting finding
            // #1): startForegroundService() dispatches ASYNCHRONOUSLY and
            // does not throw for a failure inside TripForegroundService.
            // onCreate() itself (e.g. that same SecurityException) -- it
            // only rolls the start back, records the failure, and raises
            // its own raiseMonitoringNotActiveAlert AFTER this call
            // already returned successfully. Calling notifyResumed()
            // unconditionally right here used to post a false "Dasher
            // Monitor resumed" notification alongside that genuine
            // failure alert -- a driver who trusts the reassuring one
            // over the technical one is left thinking monitoring is
            // running when it silently is not. goAsync() + a short
            // delayed re-check (the exact same pattern already used by
            // DasherAccessibilityService.attemptAutoStart's own
            // monitoringVerifyHandler) confirms genuine success before
            // posting the reassuring notification -- no duplicate alert
            // on failure, since TripForegroundService's own onCreate()
            // catch block already raises one.
            PendingResult pendingResult = goAsync();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    if (TripForegroundService.isRunning) {
                        notifyResumed(context);
                    } else {
                        logToEngine(context, "ERROR", "Auto-resume after " + message + " appeared to "
                                + "dispatch but monitoring still isn't running "
                                + (MONITORING_VERIFY_DELAY_MS / 1000) + "s later -- not showing the "
                                + "\"resumed\" notification");
                    }
                } finally {
                    pendingResult.finish();
                }
            }, MONITORING_VERIFY_DELAY_MS);
        } catch (RuntimeException e) {
            logToEngine(context, "ERROR", "Auto-resume after " + message + " failed: "
                    + android.util.Log.getStackTraceString(e));
        }
    }

    /**
     * A real notification, not a Toast (PRD ss4) -- a Toast is only seen
     * if the phone happens to be unlocked and awake at this exact moment,
     * which right after a reboot it usually isn't. This needs to be seen
     * whenever the driver next checks their phone, matching how every
     * other "something you need to know" surface in this app works.
     */
    private void notifyResumed(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannelHelper.ensureChannel(manager, RESUME_CHANNEL_ID, "Monitoring Auto-Resumed",
                NotificationManager.IMPORTANCE_DEFAULT,
                "Lets you know monitoring restarted itself after a reboot or update", false);
        Notification notification = new Notification.Builder(context, RESUME_CHANNEL_ID)
                .setContentTitle("Dasher Monitor resumed")
                .setContentText("Monitoring restarted automatically after your phone restarted.")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setAutoCancel(true)
                .build();
        manager.notify(9300, notification);
    }

    /** Matches the same engine-connection + FallbackLogger safety-net pattern already established in other standalone receivers (e.g. MonitoringWatchdogReceiver). */
    private void logToEngine(Context context, String category, String message) {
        try {
            PyObject engine = PythonBridge.getEngine(context);
            engine.callAttr("log_diagnostic", category, message);
        } catch (RuntimeException e) { // RuntimeException alone also catches PyException (Chaquopy PyException extends RuntimeException -- Java disallows both in one multi-catch since one is a subclass of the other)
            FallbackLogger.log(context, category, message + " (engine unavailable: "
                    + android.util.Log.getStackTraceString(e) + ")");
        }
    }
}
