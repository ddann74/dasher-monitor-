package com.drivingefficiency.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import com.chaquo.python.PyObject;

/**
 * Answers "alert me the instant monitoring stops working" as honestly as
 * Android actually allows. IMPORTANT HONEST LIMIT: if the whole app
 * process gets killed by the OS or an OEM battery manager, nothing
 * running INSIDE that process can alert about its own death -- the code
 * that would need to run to raise an alert is exactly the code that just
 * got killed. True instant detection from within the dying process is
 * not possible.

 * The real fix: this is a SEPARATE component, scheduled via AlarmManager,
 * which Android will invoke on its own even if the main app process was
 * killed (the OS briefly spins the app back up just to deliver the
 * alarm). It checks a heartbeat timestamp written to SharedPreferences
 * (a file on disk, durable across process death) rather than any
 * in-memory state, then raises a loud, high-priority alert notification
 * (sound + vibration, distinct from the normal quiet status notification)
 * if monitoring was supposed to be active but the heartbeat has gone
 * stale.

 * Realistic detection window: checked every WATCHDOG_INTERVAL_MS
 * (battery-friendly inexact repeating alarm, not a precise timer) with
 * ALERT_THRESHOLD_MS of staleness required before alerting -- so this is
 * "within several minutes," not truly instant. That's a real Android
 * power-management constraint, not a shortcut taken here.
 */
public class MonitoringWatchdogReceiver extends BroadcastReceiver {

    public static final String PREFS_NAME = "monitoring_watchdog_prefs";
    public static final String KEY_LAST_HEARTBEAT_MS = "last_heartbeat_ms";
    private static final String KEY_INTENDED_ACTIVE = "intended_active";
    private static final String KEY_SESSION_START_MS = "session_start_ms";

    // Mode-aware, per explicit request: faster detection specifically
    // while in DASHER mode (where losing untracked time actually costs a
    // real delivery), slower in GENERAL mode to keep the battery cost
    // proportional to when it matters. HONEST LIMIT: 45s is close to the
    // realistic floor for setExactAndAllowWhileIdle -- Android enforces
    // its own rate limit on how often this alarm type can fire, which
    // this code cannot override or guarantee around. This is the fastest
    // reasonable target, not a promise Android will always honor exactly.
    private static final long WATCHDOG_INTERVAL_DASHER_MS = 45 * 1000;
    // docs/watchdog_deep_park_margin/PRD.md -- CONFIRMED REAL GAP, fixed
    // here (round-8 scouting finding #4): this heartbeat can only update
    // as often as a real GPS fix actually arrives (maybeLogHeartbeat runs
    // from the location callback, not on its own independent timer -- see
    // its own doc), and TripForegroundService's deep-park GPS tier
    // (GPS_INTERVAL_DEEP_PARK_MS, 30s) is exactly the tier active during
    // the screen-off, long-idle condition most likely to also trigger
    // real Android Doze throttling on the device -- the same class of
    // delay this file's own class doc cites a real 17-minute incident for
    // (a nominal 5-minute interval, not even a battery-tier one). Was
    // 60s: only a 2x margin over the 30s nominal interval, uncomfortably
    // tight once real Doze delay on top of that nominal interval is
    // considered -- risking a spurious "monitoring may have stopped"
    // alert during an ordinary long restaurant wait, which trains a
    // driver to distrust/ignore the alert exactly when a REAL failure
    // happens. Widened to a 4x margin (matching the same margin
    // philosophy used for the accessibility liveness heartbeat's own
    // threshold, docs/accessibility_liveness_heartbeat/PRD.md) -- still
    // meaningfully faster than ALERT_THRESHOLD_GENERAL_MS below, so
    // DASHER mode's "detect faster, it costs a real delivery" intent is
    // preserved, just with a safer margin around the interval that
    // actually feeds this heartbeat.
    private static final long ALERT_THRESHOLD_DASHER_MS = 120 * 1000;
    private static final long WATCHDOG_INTERVAL_GENERAL_MS = 2 * 60 * 1000;
    private static final long ALERT_THRESHOLD_GENERAL_MS = 3 * 60 * 1000;
    private static final int ALERT_NOTIFICATION_ID = 9001;
    private static final String ALERT_CHANNEL_ID = "monitoring_watchdog_alert";
    private static final int WATCHDOG_REQUEST_CODE = 5001;

    // docs/watchdog_restart_circuit_breaker/PRD.md -- CONFIRMED REAL GAP,
    // fixed here (round-9 scouting finding #1): TripForegroundService.
    // onCreate()'s startForegroundLocationOnly() call can throw
    // SecurityException (a documented, real Android 14 FGS-location
    // eligibility rejection this codebase's own comment already says has
    // "no known way to make this specific auto-start path itself
    // Android-14-eligible without a user tap" -- i.e. genuinely
    // un-fixable from code). That failure path tears the service back
    // down WITHOUT ever setting isRunning=true or clearing
    // intendedActive, so the NEXT watchdog cycle sees the exact same
    // staleness and attempts the exact same doomed restart again --
    // forever, every WATCHDOG_INTERVAL_*_MS, with no counter, no
    // backoff, and identical alert text each time. This counter (durable
    // across process death via SharedPreferences, same file as the
    // heartbeat/intended-active state) tracks consecutive restart
    // failures so onReceive() below can recognize "this has already
    // failed repeatedly" and stop silently re-attempting a restart
    // that's proven futile, instead raising ONE clearly different,
    // escalated alert that tells the driver this needs a manual open of
    // the app (which will succeed, since a foreground user-initiated
    // start doesn't hit the same background-eligibility restriction) --
    // rather than an indistinguishable repeat of the same generic
    // message with a silent battery-draining spin-up/teardown loop
    // behind it.
    private static final String KEY_CONSECUTIVE_RESTART_FAILURES = "consecutive_restart_failures";
    private static final int RESTART_CIRCUIT_BREAKER_THRESHOLD = 3;
    private static final int ESCALATED_ALERT_NOTIFICATION_ID = 9002;

    /**
     * Called from TripForegroundService.onCreate()'s own SecurityException
     * catch block -- the exact point a background auto-start attempt is
     * confirmed to have failed. Returns the new consecutive-failure count
     * so the caller can log it.
     */
    public static int recordRestartFailure(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int count = prefs.getInt(KEY_CONSECUTIVE_RESTART_FAILURES, 0) + 1;
        prefs.edit().putInt(KEY_CONSECUTIVE_RESTART_FAILURES, count).apply();
        return count;
    }

    /**
     * Called from TripForegroundService.startTracking() -- reaching that
     * method at all means onCreate()'s own foreground-service start just
     * succeeded (a genuine recovery, not merely an attempt), so any prior
     * run of failures is no longer relevant.
     */
    public static void recordRestartSuccess(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_CONSECUTIVE_RESTART_FAILURES, 0)
                .apply();
    }

    private static int getConsecutiveRestartFailures(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_CONSECUTIVE_RESTART_FAILURES, 0);
    }

    public static void markIntendedActive(Context context, boolean active) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_INTENDED_ACTIVE, active)
                .putLong(KEY_LAST_HEARTBEAT_MS, System.currentTimeMillis())
                .apply();
    }

    /**
     * docs/boot_resume_monitoring/PRD.md ss3 -- was monitoring supposed to
     * be running the moment everything last died (a process kill, a
     * reboot, an app update)? This flag is written to SharedPreferences
     * (a file on disk, durable across process death AND a reboot), set
     * true in TripForegroundService.startTracking() and false in
     * stopTracking(), so it already means exactly the right thing -- this
     * is just the first reader of it outside the watchdog itself.
     * Defaults to false if never set (e.g. a fresh install).
     */
    public static boolean wasIntendedActive(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_INTENDED_ACTIVE, false);
    }

    /**
     * OEM-restart silent-data-loss fix (2026-09-14, docs/
     * session_start_ms_oem_restart/PRD.md): TripForegroundService.
     * sessionStartMs (a `public static volatile long`, in-memory only)
     * is what MainActivity's shift-end reviews (decline-reason,
     * delivery-rating batch) use to scope "this shift" -- but an in-
     * memory field doesn't survive a process death, so an OEM silently
     * killing and MonitoringWatchdogReceiver/boot_resume_monitoring
     * resurrecting the service mid-shift used to silently reset it to
     * "now," quietly shrinking "this shift" to just the time since the
     * resurrection -- any earlier offers/deliveries from the same real
     * shift would vanish from those reviews with no indication anything
     * was lost. Persisted here (same durable-across-process-death-and-
     * reboot SharedPreferences file as KEY_INTENDED_ACTIVE) so
     * startTracking() can tell a genuine new shift (wasIntendedActive()
     * was false) from a silent resurrection mid-shift (it was still
     * true) and resume the real value instead of resetting it.
     */
    public static void persistSessionStartMs(Context context, long sessionStartMs) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_SESSION_START_MS, sessionStartMs)
                .apply();
    }

    /** Returns 0 if never set (e.g. a fresh install, or before this fix shipped). */
    public static long getPersistedSessionStartMs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_SESSION_START_MS, 0);
    }

    /**
     * Queries the current mode via the same engine-connection pattern
     * already used for logging, so the watchdog can schedule itself
     * faster specifically while in DASHER mode. Defaults to GENERAL
     * (the slower, safer interval) if the engine isn't reachable --
     * never assumes DASHER when uncertain.
     */
    private static boolean isDasherModeActive(Context context) {
        try {
            PyObject engine = PythonBridge.getEngine(context);
            return "DASHER".equals(engine.callAttr("get_mode").toString());
        } catch (RuntimeException e) { // covers PyException too
            return false;
        }
    }

    public static void scheduleWatchdog(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        PendingIntent pendingIntent = buildPendingIntent(context);
        boolean dasherMode = isDasherModeActive(context);
        long intervalMs = dasherMode ? WATCHDOG_INTERVAL_DASHER_MS : WATCHDOG_INTERVAL_GENERAL_MS;
        // Confirmed via a real incident: the previous setInexactRepeating
        // approach let a real 17-minute gap occur despite a 5-minute
        // nominal interval -- Android is explicitly allowed to delay
        // inexact alarms significantly under Doze, and in this case that
        // delay corresponded to an entire real delivery going completely
        // untracked. setExactAndAllowWhileIdle is the strongest timing
        // guarantee available outside of Doze's own maintenance windows,
        // specifically designed to still fire promptly even while idle.
        // It's a one-shot alarm (not repeating), so onReceive below
        // re-schedules the next one each time it fires.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + intervalMs, pendingIntent);
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + intervalMs, pendingIntent);
        }
        // Requirement change (2026-08-30, docs/watchdog_reliability/PRD.md):
        // this call had no logging at all before -- a real uploaded field
        // log covering two full monitoring blackouts couldn't even confirm
        // whether the watchdog was ever armed to begin with, only that it
        // never fired. Logged here, not just in onReceive, so a future log
        // can tell scheduling itself apart from the alarm never firing.
        logToEngine(context, "WATCHDOG", "Scheduled next check in " + (intervalMs / 1000)
                + "s (" + (dasherMode ? "DASHER" : "GENERAL") + " mode interval)");
    }

    public static void cancelWatchdog(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.cancel(buildPendingIntent(context));
        }
    }

    private static PendingIntent buildPendingIntent(Context context) {
        Intent intent = new Intent(context, MonitoringWatchdogReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, WATCHDOG_REQUEST_CODE, intent, flags);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // Required now that this is a one-shot exact alarm (not a
        // repeating one) -- must reschedule the next check every time
        // this fires, regardless of what it finds below, or the
        // watchdog would silently stop checking after just one firing.
        scheduleWatchdog(context);

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean intendedActive = prefs.getBoolean(KEY_INTENDED_ACTIVE, false);
        if (!intendedActive) {
            return; // monitoring was deliberately stopped -- nothing to alert about
        }

        long lastHeartbeat = prefs.getLong(KEY_LAST_HEARTBEAT_MS, 0);
        long staleness = System.currentTimeMillis() - lastHeartbeat;
        long alertThreshold = isDasherModeActive(context) ? ALERT_THRESHOLD_DASHER_MS : ALERT_THRESHOLD_GENERAL_MS;
        if (staleness < alertThreshold) {
            return; // still healthy
        }

        // Previously this whole class never logged anything at all -- a
        // real, confirmed gap: even if the watchdog fired correctly,
        // there was zero record of it in the diagnostic log, making it
        // impossible to verify from the log whether it actually worked.
        // Uses the same engine-connection pattern already established in
        // BootAndUpdateReceiver, with the same FallbackLogger safety net
        // if the engine isn't reachable from this standalone receiver.
        logToEngine(context, "WATCHDOG", "Alert fired -- " + (staleness / (60 * 1000))
                + "+ min since last heartbeat, monitoring was intended to be active");
        raiseAlert(context, staleness);

        // Confirmed real gap: a real diagnostic log showed this alert
        // firing three separate times (7, 12, 17+ minutes stale) with no
        // evidence monitoring ever actually resumed afterward -- this
        // was previously only ever a notification, entirely dependent on
        // the user noticing and manually reopening the app. Attempts an
        // actual restart now, using the exact same proven
        // cross-component trigger already relied on for Dasher
        // auto-start and Dash-Paused auto-resume. TripForegroundService's
        // own startTracking() already safely no-ops if it's somehow
        // already running, so this can't cause a disruptive restart of
        // something that's actually fine.
        if (!TripForegroundService.isRunning) {
            int consecutiveFailures = getConsecutiveRestartFailures(context);
            if (consecutiveFailures >= RESTART_CIRCUIT_BREAKER_THRESHOLD) {
                // docs/watchdog_restart_circuit_breaker/PRD.md -- the last
                // several attempts through this exact code path all failed
                // the same way (see recordRestartFailure's own doc) --
                // Android's own platform restriction means retrying it
                // again right now is proven futile, not just unlucky.
                // Stop silently re-attempting (each one is a real,
                // wasted process spin-up/teardown) and tell the driver
                // plainly instead -- a foreground, user-initiated
                // "Start Monitoring" tap does not hit the same
                // background-eligibility restriction, so opening the app
                // is a real fix, not a shrug.
                logToEngine(context, "WATCHDOG", "Circuit breaker: " + consecutiveFailures
                        + " consecutive auto-restart failures -- skipping further automatic attempts, "
                        + "escalated alert raised instead");
                raiseEscalatedAlert(context, consecutiveFailures);
            } else {
                Intent restartIntent = new Intent(context, TripForegroundService.class);
                restartIntent.setAction(TripForegroundService.ACTION_START_TRACKING);
                context.startForegroundService(restartIntent);
                logToEngine(context, "WATCHDOG", "Attempted automatic restart of monitoring after staleness detected");
            }
        }
    }

    private static void logToEngine(Context context, String category, String message) {
        try {
            PyObject engine = PythonBridge.getEngine(context);
            engine.callAttr("log_diagnostic", category, message);
        } catch (RuntimeException e) { // covers PyException too
            FallbackLogger.log(context, category, message + " (engine unavailable: "
                    + android.util.Log.getStackTraceString(e) + ")");
        }
    }

    private void raiseAlert(Context context, long stalenessMs) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannelHelper.ensureChannel(manager, ALERT_CHANNEL_ID, "Monitoring Failure Alerts",
                NotificationManager.IMPORTANCE_HIGH,
                "Alerts if Dasher Monitor stops tracking unexpectedly", true);

        long minutesStale = stalenessMs / (60 * 1000);
        Notification notification = new Notification.Builder(context, ALERT_CHANNEL_ID)
                .setContentTitle("\u26A0 Monitoring may have stopped")
                .setContentText("No activity detected for " + minutesStale + "+ minutes. Open the app to check.")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE)
                .setAutoCancel(true)
                .build();
        manager.notify(ALERT_NOTIFICATION_ID, notification);
    }

    // docs/watchdog_restart_circuit_breaker/PRD.md -- distinct channel/id/
    // text from raiseAlert() above, deliberately: a driver seeing this
    // needs to understand "the app already tried and failed repeatedly to
    // fix this itself, on its own" -- a materially different, more urgent
    // situation than the first alert's "no activity detected yet," not
    // just a repeat of the same message. Deep-links to MainActivity (a
    // real, foreground, user-initiated open) since that's the one action
    // that actually resolves the underlying Android 14 background-start
    // restriction this circuit breaker exists for -- see
    // recordRestartFailure's own doc.
    private void raiseEscalatedAlert(Context context, int consecutiveFailures) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        String channelId = "monitoring_watchdog_escalated_alert";
        NotificationChannelHelper.ensureChannel(manager, channelId, "Monitoring Auto-Restart Failed Alerts",
                NotificationManager.IMPORTANCE_HIGH,
                "Alerts when Dasher Monitor has repeatedly failed to auto-restart and needs to be opened manually",
                true);

        Intent tapIntent = new Intent(context, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tapPendingIntent = PendingIntent.getActivity(context, ESCALATED_ALERT_NOTIFICATION_ID, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));

        Notification notification = new Notification.Builder(context, channelId)
                .setContentTitle("\u26A0 Monitoring couldn't restart itself")
                .setContentText("Auto-restart failed " + consecutiveFailures + "x in a row -- tap to open "
                        + "Dasher Monitor and start it manually.")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE)
                .setAutoCancel(true)
                .setContentIntent(tapPendingIntent)
                .build();
        manager.notify(ESCALATED_ALERT_NOTIFICATION_ID, notification);
    }
}
