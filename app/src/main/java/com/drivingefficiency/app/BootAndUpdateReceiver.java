package com.drivingefficiency.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

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
        try {
            Intent startIntent = new Intent(context, TripForegroundService.class);
            startIntent.setAction(TripForegroundService.ACTION_START_TRACKING);
            context.startForegroundService(startIntent);
            logToEngine(context, "SYSTEM", message + " -- monitoring was active before this, auto-resumed");
            notifyResumed(context);
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    RESUME_CHANNEL_ID, "Monitoring Auto-Resumed", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Lets you know monitoring restarted itself after a reboot or update");
            manager.createNotificationChannel(channel);
        }
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
