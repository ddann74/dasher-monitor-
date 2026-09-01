package com.drivingefficiency.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
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

    // Mode-aware, per explicit request: faster detection specifically
    // while in DASHER mode (where losing untracked time actually costs a
    // real delivery), slower in GENERAL mode to keep the battery cost
    // proportional to when it matters. HONEST LIMIT: 45s is close to the
    // realistic floor for setExactAndAllowWhileIdle -- Android enforces
    // its own rate limit on how often this alarm type can fire, which
    // this code cannot override or guarantee around. This is the fastest
    // reasonable target, not a promise Android will always honor exactly.
    private static final long WATCHDOG_INTERVAL_DASHER_MS = 45 * 1000;
    private static final long ALERT_THRESHOLD_DASHER_MS = 60 * 1000;
    private static final long WATCHDOG_INTERVAL_GENERAL_MS = 2 * 60 * 1000;
    private static final long ALERT_THRESHOLD_GENERAL_MS = 3 * 60 * 1000;
    private static final int ALERT_NOTIFICATION_ID = 9001;
    private static final String ALERT_CHANNEL_ID = "monitoring_watchdog_alert";
    private static final int WATCHDOG_REQUEST_CODE = 5001;

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
            Intent restartIntent = new Intent(context, TripForegroundService.class);
            restartIntent.setAction(TripForegroundService.ACTION_START_TRACKING);
            context.startForegroundService(restartIntent);
            logToEngine(context, "WATCHDOG", "Attempted automatic restart of monitoring after staleness detected");
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    ALERT_CHANNEL_ID, "Monitoring Failure Alerts", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Alerts if Dasher Monitor stops tracking unexpectedly");
            channel.enableVibration(true);
            manager.createNotificationChannel(channel);
        }

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
}
