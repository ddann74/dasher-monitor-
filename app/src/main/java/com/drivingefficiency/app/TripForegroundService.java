package com.drivingefficiency.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Always-running foreground service (starts when the app opens, or the
 * first time "Start Monitoring" is tapped, and keeps running afterward) so
 * the status dot can be visible at all times, not just while GPS tracking
 * is active. "Start Monitoring" / "Stop Monitoring" now toggle an internal
 * monitoringActive flag via Intent actions rather than starting/stopping
 * the service itself -- this means Android requires a permanent
 * notification the whole time this service exists (unavoidable: a
 * foreground service cannot run invisibly), not just while tracking.
 *
 * on_gps_update() returns {"state": ..., "mode": "DASHER"|"GENERAL",
 * "arrival": {...} | null}. "mode" reflects whether the Dasher app is
 * currently active (delivery tracking, with offers/stops/instructions) or
 * not (plain driving-efficiency monitoring). The persistent notification's
 * title/icon updates whenever the mode changes, and a short TTS cue
 * announces the switch, so which mode is active is always clear without
 * touching the phone.
 *
 * When "arrival" is present (you've reached a stop with pending customer
 * instructions attached), this announces it both aloud (VoiceAnnouncer) and
 * via a floating overlay (OverlayHelper) so nothing requires touching the
 * phone while driving.
 */
public class TripForegroundService extends Service {

    public static final String ACTION_START_TRACKING = "com.drivingefficiency.app.START_TRACKING";
    public static final String ACTION_STOP_TRACKING = "com.drivingefficiency.app.STOP_TRACKING";
    public static final String ACTION_QUIT_COMPLETELY = "com.drivingefficiency.app.QUIT_COMPLETELY";

    private static final String CHANNEL_ID = "trip_tracking_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final long GPS_INTERVAL_MOVING_MS = 1000;   // 1 point/sec while driving
    private static final long GPS_INTERVAL_STATIONARY_MS = 5000; // battery optimised when parked

    /**
     * True only while GPS tracking is actually active (not merely while
     * this always-on service exists). Checked by MainActivity's developer
     * test/simulate buttons -- they share the same Python engine singleton
     * (via PythonBridge) as this service, so running both at once while
     * actually tracking would interleave real and fake GPS timestamps into
     * the same TripManager and corrupt its state. Simulate buttons refuse
     * to run while this is true; they're fine to run while the service
     * exists but isn't actively tracking.
     */
    public static volatile boolean isRunning = false;

    /**
     * True whenever this service object exists at all -- idle or actively
     * tracking, doesn't matter. False only once quitCompletely() (or the
     * OS) has actually torn it down. Distinguishing this from isRunning is
     * what makes "fully off" a real, checkable state instead of looking
     * identical to "idle but still running in the background."
     */
    public static volatile boolean serviceExists = false;

    /**
     * Last known real GPS position, exposed so other components (like
     * DasherAccessibilityService, for live traffic queries) can access
     * the current location without needing their own separate location
     * subscription. 0.0/0.0 means no real fix has arrived yet this
     * session -- check hasValidLocation before using these.
     */
    public static volatile double lastKnownLat = 0.0;
    public static volatile double lastKnownLon = 0.0;
    public static volatile boolean hasValidLocation = false;
    // Real proof of recency, not just a category state (green/yellow/etc.
    // only ever shows WHAT mode you're in, never WHEN it last actually
    // updated) -- 0 means no real GPS tick has landed yet this session.
    public static volatile long lastGpsUpdateMs = 0;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private PyObject engine;
    private String lastKnownMode = null;
    private volatile boolean monitoringActive = false;

    @Override
    public void onCreate() {
        super.onCreate();
        serviceExists = true;
        createNotificationChannel();
        engine = PythonBridge.getEngine(this);
        VoiceAnnouncer.init(this);
        // Real, direct evidence for whether a process restart correlates
        // with a fresh install/update, rather than relying on memory --
        // see DasherAccessibilityService's identical-logic twin for why
        // this is duplicated rather than shared.
        logDiagnostic("SERVICE", "onCreate() -- service process started. " + buildInstallTimingNote());
        // Requirement change (2026-08-30, docs/watchdog_reliability/PRD.md):
        // a real uploaded field log covering two full monitoring blackouts
        // couldn't answer "which phone was this" at all -- OemBackgroundHelper
        // already has isKnownAggressiveOem() logic gating the autostart
        // -settings guidance button, but nothing ever logged the value it
        // depends on. Logged once per session, not per-heartbeat -- this
        // never changes while the process is alive.
        logDiagnostic("DEVICE", "manufacturer=" + Build.MANUFACTURER + " model=" + Build.MODEL
                + " sdk=" + Build.VERSION.SDK_INT + " knownAggressiveOem=" + OemBackgroundHelper.isKnownAggressiveOem());
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                for (Location location : result.getLocations()) {
                    double speedKmh = location.getSpeed() * 3.6; // m/s -> km/h
                    long tsMs = System.currentTimeMillis();
                    lastKnownLat = location.getLatitude();
                    lastKnownLon = location.getLongitude();
                    hasValidLocation = true;
                    lastGpsUpdateMs = tsMs;
                    updateGpsIntervalForSpeed(speedKmh, tsMs);
                    maybeLogHeartbeat(tsMs);
                    try {
                        String resultJson = engine.callAttr("on_gps_update",
                                location.getLatitude(), location.getLongitude(),
                                speedKmh, tsMs).toString();
                        handleGpsResult(resultJson);
                    } catch (RuntimeException e) { // covers PyException too -- handleGpsResult does
                        // real Java-side work (overlays, mode changes, the navigation icon), not just
                        // Python calls, so this needs to catch more than PyException alone to avoid
                        // silently crashing the always-on foreground service on one bad GPS tick.
                        logDiagnostic("ERROR", "GPS tick exception: " + android.util.Log.getStackTraceString(e));
                    }
                }
            }
        };

        // Baseline state as soon as the service exists -- refreshStatusDot
        // correctly shows nothing (idle, Dasher closed) or flashing red
        // (idle, but Dasher happens to already be open) depending on
        // DasherAccessibilityService's independently-tracked state.
        startForeground(NOTIFICATION_ID, buildIdleNotification());
        refreshStatusDot();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START_TRACKING.equals(action)) {
            startTracking();
        } else if (ACTION_STOP_TRACKING.equals(action)) {
            stopTracking();
        } else if (ACTION_QUIT_COMPLETELY.equals(action)) {
            quitCompletely();
            return START_NOT_STICKY; // don't restart -- this is a deliberate full shutdown
        } else {
            // No action (e.g. app-launch bootstrap, or a re-check after
            // overlay permission was just granted) -- don't change
            // monitoring state, but do refresh the dot in case it silently
            // failed to show earlier due to missing permission.
            refreshStatusDot();
        }
        return START_STICKY;
    }

    /**
     * Recomputes and shows the correct status dot state (see
     * DasherAccessibilityService's identical-logic twin for why this is
     * duplicated rather than shared -- each component needs to trigger
     * this from its own state-change events). GREEN/YELLOW while
     * monitoring (by mode); RED_FLASHING or nothing while not monitoring,
     * depending on whether Dasher happens to be open right now.
     */
    private void refreshStatusDot() {
        if (!monitoringActive) {
            if (DasherAccessibilityService.isDasherForeground) {
                OverlayHelper.showStatusDot(this, OverlayHelper.DotState.RED_FLASHING);
            } else {
                OverlayHelper.clearStatusDot(this);
            }
            return;
        }
        // Takes priority over walking/mode states below -- accessibility
        // being off means offer detection and Accept/Decline tracking
        // aren't working AT ALL right now, which matters more than which
        // mode the app currently thinks it's in. Uses lastLoggedAccessibility
        // (kept fresh by both the regular heartbeat and the new dedicated
        // 15-second accessibility heartbeat) rather than re-querying here.
        if (Boolean.FALSE.equals(lastLoggedAccessibility)) {
            OverlayHelper.showStatusDot(this, OverlayHelper.DotState.BLUE_FLASHING);
            return;
        }
        if (lastKnownIsWalking) {
            OverlayHelper.showStatusDot(this, OverlayHelper.DotState.WALKING);
            return;
        }
        try {
            String mode = engine.callAttr("get_mode").toString();
            OverlayHelper.showStatusDot(this,
                    "DASHER".equals(mode) ? OverlayHelper.DotState.GREEN : OverlayHelper.DotState.YELLOW);
        } catch (RuntimeException e) { // covers PyException too -- OverlayHelper.showStatusDot
            // is real Java-side work, not just a Python call, so this needs to catch more than
            // PyException alone.
            // Leave the dot as whatever it currently shows on error.
        }
    }

    /**
     * The genuine "fully off" action -- distinct from stopTracking(),
     * which pauses GPS but deliberately keeps the service (and its idle
     * red badge/notification) alive so status stays visible. This
     * actually terminates the service: no notification, no badge,
     * nothing. Added because "Not Monitoring" while something still
     * silently runs forever was confusing -- there was no way to reach a
     * state you could be certain was completely off.
     */
    private void quitCompletely() {
        logDiagnostic("SERVICE", "quitCompletely() called -- fully shutting down");
        if (monitoringActive) {
            stopTracking();
        }
        OverlayHelper.clearStatusDot(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    private void startTracking() {
        if (monitoringActive) {
            return; // already tracking
        }
        logDiagnostic("SERVICE", "startTracking() -- monitoring turned on");
        checkAndLogPermissions(true);
        monitoringActive = true;
        isRunning = true;
        lastKnownMode = null; // force onModeChanged to fire on the next GPS fix
        startForeground(NOTIFICATION_ID, buildNotificationForMode("GENERAL"));
        refreshStatusDot();
        startLocationUpdates();
        MonitoringWatchdogReceiver.markIntendedActive(this, true);
        MonitoringWatchdogReceiver.scheduleWatchdog(this);
        // Dedicated, independent check specifically for accessibility --
        // the regular heartbeat is tied to GPS ticks, which slow down
        // significantly when parked (up to 30+ seconds at the deep-park
        // tier), creating gaps that could hide exactly when and under
        // what conditions accessibility actually drops. This runs on a
        // fixed schedule regardless of GPS tier, specifically to help
        // pin down the real pattern.
        accessibilityHeartbeatHandler.postDelayed(accessibilityHeartbeatRunnable, ACCESSIBILITY_HEARTBEAT_INTERVAL_MS);
        // Requirement change (2026-08-31, docs/watchdog_gps_independent_rearm/PRD.md):
        // the watchdog's redundant re-arm used to live inside maybeLogHeartbeat,
        // which only runs when a GPS location callback actually arrives -- sharing
        // a failure dependency with the exact self-perpetuating-alarm-chain risk it
        // was built to backstop (if GPS updates and the alarm chain both stall
        // together, plausible under the same aggressive-OEM class of kill already
        // evidenced in docs/watchdog_reliability/PRD.md, neither layer helps).
        // Mirrors accessibilityHeartbeatHandler above, which already solved this
        // identical GPS-tied-gap shape for a different reason.
        watchdogRearmHandler.postDelayed(watchdogRearmRunnable, WATCHDOG_REARM_INTERVAL_MS);
    }

    private final android.os.Handler watchdogRearmHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable watchdogRearmRunnable = new Runnable() {
        @Override
        public void run() {
            MonitoringWatchdogReceiver.scheduleWatchdog(TripForegroundService.this);
            if (monitoringActive) {
                watchdogRearmHandler.postDelayed(this, WATCHDOG_REARM_INTERVAL_MS);
            }
        }
    };

    private static final long ACCESSIBILITY_HEARTBEAT_INTERVAL_MS = 15 * 1000;
    private final android.os.Handler accessibilityHeartbeatHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable accessibilityHeartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            String enabledServices = android.provider.Settings.Secure.getString(getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean hasAccessibility = enabledServices != null
                    && enabledServices.contains(getPackageName() + "/" + getPackageName() + ".DasherAccessibilityService");
            boolean screenOn = false;
            android.os.PowerManager powerManager = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager != null) {
                screenOn = powerManager.isInteractive();
            }
            boolean isDozing = powerManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && powerManager.isDeviceIdleMode();
            // "lastKnownDasherForeground" rather than a live re-query --
            // there's no way to independently ask "what's in the
            // foreground right now" without accessibility itself, so if
            // accessibility has already dropped, this can only reflect
            // whatever was last confirmed before that happened, not the
            // current live truth. Still useful context for the moment a
            // drop is detected: was Dasher open right before it happened?
            logDiagnostic("ACCESSIBILITY_HEARTBEAT", "accessibility=" + hasAccessibility
                    + " lastKnownDasherForeground=" + DasherAccessibilityService.isDasherForeground
                    + " screenOn=" + screenOn + " doze=" + isDozing + " " + getBatteryAndDozeInfo());
            // Shares lastLoggedAccessibility with checkAndLogPermissions --
            // whichever check (this one, every 15s, or the GPS-tied
            // heartbeat) catches a real change first fires the alert and
            // updates the dot; the other then sees no change and correctly
            // stays silent, avoiding a duplicate alert.
            if (!java.util.Objects.equals(lastLoggedAccessibility, hasAccessibility)) {
                if (lastLoggedAccessibility != null && lastLoggedAccessibility && !hasAccessibility) {
                    raisePermissionRevokedAlert("Accessibility",
                            "Offer detection and Accept/Decline tracking won't work");
                }
                lastLoggedAccessibility = hasAccessibility;
                refreshStatusDot();
            }
            if (monitoringActive) {
                accessibilityHeartbeatHandler.postDelayed(this, ACCESSIBILITY_HEARTBEAT_INTERVAL_MS);
            }
        }
    };

    // Last known values, so periodic re-checks only log when something
    // actually CHANGES -- not a repeat of the same state every heartbeat.
    private Boolean lastLoggedLocation = null;
    private Boolean lastLoggedOverlay = null;
    private Boolean lastLoggedNotificationAccess = null;
    private Boolean lastLoggedBatteryExempt = null;
    private Boolean lastLoggedAccessibility = null;
    private android.os.PowerManager.WakeLock tripWakeLock = null;

    /**
     * Checks whether each permission this app relies on is currently
     * granted. Called once (forceLog=true) when tracking starts, and
     * again on every heartbeat (forceLog=false) -- the periodic re-check
     * is what catches a permission getting silently revoked mid-shift
     * (some OEM skins do this after certain triggers), which a one-time
     * snapshot at start could never detect.
     */
    private void checkAndLogPermissions(boolean forceLog) {
        boolean hasLocation = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasOverlay = OverlayHelper.hasPermission(this);
        String enabledListeners = android.provider.Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        boolean hasNotificationAccess = enabledListeners != null && enabledListeners.contains(getPackageName());
        boolean hasBatteryExemption = false;
        android.os.PowerManager powerManager = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            hasBatteryExemption = powerManager.isIgnoringBatteryOptimizations(getPackageName());
        }
        // Previously omitted entirely -- confirmed as a real gap: the
        // PERMISSIONS log line couldn't answer "is accessibility access
        // actually on" at all, despite that being exactly what offer/
        // dropoff screen reading and Accept/Decline detection all
        // depend on.
        String enabledServices = android.provider.Settings.Secure.getString(getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean hasAccessibility = enabledServices != null
                && enabledServices.contains(getPackageName() + "/" + getPackageName() + ".DasherAccessibilityService");

        boolean changed = !Boolean.valueOf(hasLocation).equals(lastLoggedLocation)
                || !Boolean.valueOf(hasOverlay).equals(lastLoggedOverlay)
                || !Boolean.valueOf(hasNotificationAccess).equals(lastLoggedNotificationAccess)
                || !Boolean.valueOf(hasBatteryExemption).equals(lastLoggedBatteryExempt)
                || !Boolean.valueOf(hasAccessibility).equals(lastLoggedAccessibility);

        // Confirmed via a real diagnostic log: accessibility can genuinely
        // turn itself off mid-session (most likely Android's "Restricted
        // Settings" security feature for sideloaded apps, often triggered
        // by reinstalling/updating the APK) -- and the log only helps if
        // someone thinks to go check it. This fires the instant a real
        // true-to-false transition is detected while monitoring is
        // active, rather than waiting to be discovered after the fact --
        // the log is for diagnosis after something happened; this is for
        // finding out WHILE it's still happening and fixable.
        //
        // Extended to all 4 critical permissions, not just accessibility
        // -- a real, confirmed gap: location dropping is arguably the
        // MOST catastrophic of all four (GPS tracking stops completely),
        // yet it previously got the exact same quiet, passive treatment
        // as overlay or notification access.
        if (monitoringActive) {
            if (lastLoggedLocation != null && lastLoggedLocation && !hasLocation) {
                raisePermissionRevokedAlert("Location", "GPS tracking has stopped completely");
            }
            if (lastLoggedOverlay != null && lastLoggedOverlay && !hasOverlay) {
                // Premortem finding, fixed here (docs/road_warrior_icon/PRD.md
                // ss4a, P4): this alert already fired on overlay revocation,
                // but the text only mentioned the Smart Score badge/status
                // dot -- OverlayHelper.showNavigationIcon and
                // showReturnToSweetSpotIcon gate on this exact same
                // permission and silently stop appearing too, with nothing
                // connecting that silence back to this alert.
                raisePermissionRevokedAlert("Overlay",
                        "The Smart Score badge, status dot, and RoadWarrior navigation icon won't show");
            }
            if (lastLoggedNotificationAccess != null && lastLoggedNotificationAccess && !hasNotificationAccess) {
                raisePermissionRevokedAlert("Notification Access",
                        "Offer detection via notification and message reading won't work");
            }
            if (lastLoggedAccessibility != null && lastLoggedAccessibility && !hasAccessibility) {
                raisePermissionRevokedAlert("Accessibility",
                        "Offer detection and Accept/Decline tracking won't work");
            }
        }

        if (forceLog || changed) {
            logDiagnostic("PERMISSIONS", "location=" + hasLocation + " overlay=" + hasOverlay
                    + " notificationAccess=" + hasNotificationAccess + " batteryExempt=" + hasBatteryExemption
                    + " accessibility=" + hasAccessibility
                    + (changed && !forceLog ? " (CHANGED since last check)" : ""));
            // Immediate visual update the moment accessibility actually
            // changes (drops OR recovers) -- previously the blue-flashing
            // dot (and its return to normal) would only show up
            // incidentally, whenever refreshStatusDot happened to be
            // called for some unrelated reason.
            if (!java.util.Objects.equals(lastLoggedAccessibility, hasAccessibility)) {
                refreshStatusDot();
            }
            lastLoggedLocation = hasLocation;
            lastLoggedOverlay = hasOverlay;
            lastLoggedNotificationAccess = hasNotificationAccess;
            lastLoggedBatteryExempt = hasBatteryExemption;
            lastLoggedAccessibility = hasAccessibility;
        }
    }

    /**
     * High-priority alert, same urgency as the existing GPS-silence
     * watchdog -- fires immediately, not on a delay, since accessibility
     * being off means offer detection, dropoff parsing, and Accept/
     * Decline tracking are all silently not working right now.
     */
    /**
     * Generalized alert for ANY of the 4 critical permissions dropping
     * while monitoring is active -- previously this only existed for
     * accessibility specifically, even though location dropping is
     * arguably the most catastrophic of all four. Each permission gets
     * its own notification ID (derived from its name) so multiple alerts
     * firing close together don't overwrite each other.
     */
    private void raisePermissionRevokedAlert(String permissionName, String consequenceText) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        String channelId = "permission_revoked_alert_" + permissionName.toLowerCase().replace(" ", "_");
        int notificationId = 9100 + Math.abs(permissionName.hashCode() % 100);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId, permissionName + " Revoked Alerts", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Alerts immediately if " + permissionName + " turns off while monitoring");
            channel.enableVibration(true);
            manager.createNotificationChannel(channel);
        }
        Notification notification = new Notification.Builder(this, channelId)
                .setContentTitle("\u26A0 " + permissionName + " turned off")
                .setContentText(consequenceText + " until this is re-enabled.")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE)
                .setAutoCancel(true)
                .build();
        manager.notify(notificationId, notification);
        // Real, direct evidence for "was this caused by a reinstall" --
        // rather than relying on memory of when the APK was last
        // installed/updated, this queries Android's own record of it
        // directly, every time this alert fires.
        logDiagnostic("ALERT", permissionName + " revoked while monitoring active -- immediate notification raised. "
                + buildInstallTimingNote());
    }

    /**
     * Fixes a real, confirmed bug: the feedback dialog had zero automatic
     * trigger anywhere -- it only appeared if manually navigated to via
     * Last Trip Summary. A background service can't show a dialog
     * directly, so this fires a notification the instant a trip ends
     * instead; tapping it opens TripHistoryActivity with an extra telling
     * it to immediately show the actual feedback dialog for that trip,
     * reusing the existing, already-working dialog rather than
     * duplicating it.
     */
    /**
     * Acquired only while a real delivery is actively in progress --
     * confirmed real motivation: an entire delivery went completely
     * untracked because the process was dead the whole time, and
     * Doze-related CPU sleep is a real, plausible contributor during
     * exactly this kind of extended background window. Deliberately
     * scoped to this highest-value period only, not held constantly,
     * to keep the real battery cost bounded and justified rather than
     * an open-ended drain for marginal benefit.
     */
    private void acquireTripWakeLock() {
        if (tripWakeLock != null && tripWakeLock.isHeld()) {
            return; // already held
        }
        android.os.PowerManager powerManager = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null) {
            return;
        }
        tripWakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "com.drivingefficiency.app:trip_active_wakelock");
        // 90-minute safety timeout -- covers any realistically long real
        // delivery, while guaranteeing this can never be held forever if
        // the normal release path is somehow missed entirely.
        tripWakeLock.acquire(90 * 60 * 1000L);
        logDiagnostic("WAKELOCK", "Acquired for active trip");
    }

    private void releaseTripWakeLock() {
        if (tripWakeLock != null && tripWakeLock.isHeld()) {
            tripWakeLock.release();
            logDiagnostic("WAKELOCK", "Released");
        }
        tripWakeLock = null;
    }

    private static final long FEEDBACK_OVERLAY_AUTO_DISMISS_MS = 20 * 1000;

    /**
     * Requirement change (2026-08-30, docs/feedback_page_direct/PRD.md):
     * per explicit request, shows the feedback page directly instead of
     * only ever posting a notification the driver has to tap. Mirrors
     * AppNotificationListenerService.launchDasherApp()'s proven
     * Background Activity Launch (BAL) workaround -- a background Service
     * genuinely can't show the feedback AlertDialog itself, but it CAN
     * reliably bring MainActivity to the foreground (which already shows
     * that dialog automatically via auto_show_feedback_trip_id, see
     * MainActivity.onCreate), the same way launchDasherApp already does
     * for a new offer. Reasonable to auto-launch here, unlike an offer
     * arriving mid-drive: this only fires once a delivery is actually
     * marked complete, which requires the driver to already be
     * interacting with their phone.
     */
    private void notifyRateThisDelivery() {
        try {
            JSONObject summary = new JSONObject(engine.callAttr("get_last_trip_summary").toString());
            if (!"DASHER".equals(summary.optString("mode", ""))) {
                return; // only real Dasher trips get a feedback prompt, matching the existing gating
            }
            int tripId = summary.optInt("trip_id", -1);
            if (tripId < 0) {
                return;
            }

            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("auto_show_feedback_trip_id", tripId);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            // Same BAL exemption launchDasherApp relies on: an app
            // currently showing a visible overlay window is one of
            // Android's real Background Activity Launch exemptions, unlike
            // the plain background-service context startActivity() would
            // otherwise run from. Shown FIRST so the overlay window is
            // genuinely on screen by the time startActivity() runs below.
            OverlayHelper.showMessage(this, "Delivery complete -- tap to rate it.",
                    FEEDBACK_OVERLAY_AUTO_DISMISS_MS, android.graphics.Color.parseColor("#CC2E7D32"),
                    () -> {
                        try {
                            startActivity(intent);
                        } catch (RuntimeException e) {
                            logDiagnostic("ERROR", "Rate-delivery overlay tap-to-launch exception: "
                                    + android.util.Log.getStackTraceString(e));
                        }
                    });
            try {
                startActivity(intent);
                // HONESTY NOTE, same as launchDasherApp: a blocked BAL
                // launch fails SILENTLY -- no exception -- so this can't
                // actually confirm the direct switch worked, only that it
                // was attempted under a condition where it plausibly can.
                logDiagnostic("BUTTON", "Attempted direct feedback-page launch for trip " + tripId
                        + " while an overlay window was active -- not confirmable whether it actually "
                        + "switched apps, see AppNotificationListenerService.launchDasherApp's class docs");
            } catch (RuntimeException e) {
                logDiagnostic("BUTTON", "Direct feedback-page launch attempt failed/blocked for trip "
                        + tripId + ": " + e.getClass().getSimpleName()
                        + " -- falling back to full-screen-intent notification + overlay tap");
            }

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) {
                return;
            }
            String channelId = "rate_delivery_prompt";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        channelId, "Rate This Delivery", NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("Shows the feedback page right after a delivery completes");
                manager.createNotificationChannel(channel);
            }
            // Posted unconditionally, not only if the direct attempt above
            // threw -- a blocked BAL launch fails silently (no exception),
            // so there's no reliable way to know whether it's needed. Same
            // full-screen-intent mechanism as launchDasherApp, same
            // already-granted USE_FULL_SCREEN_INTENT permission -- reliable
            // even from the lock screen, with ordinary tap-to-open as the
            // fallback if that permission is ever revoked (Android 14+).
            PendingIntent pendingIntent = PendingIntent.getActivity(this, tripId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));
            Notification notification = new Notification.Builder(this, channelId)
                    .setContentTitle("Rate this delivery")
                    .setContentText("Opening the feedback page...")
                    .setSmallIcon(android.R.drawable.ic_menu_edit)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setFullScreenIntent(pendingIntent, true)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build();
            manager.notify(9200 + tripId, notification);
            logDiagnostic("BUTTON", "Requested feedback-page foreground via full-screen-intent notification for trip " + tripId);
        } catch (JSONException | RuntimeException e) {
            logDiagnostic("ERROR", "notifyRateThisDelivery exception: " + android.util.Log.getStackTraceString(e));
        }
    }

    /**
     * Real, direct evidence for the "does this correlate with reinstalling
     * the APK" question -- queries Android's own record of when this app
     * was first installed and last updated, rather than relying on
     * memory. Used both here and can be checked against any future
     * accessibility-revoked alert's timestamp directly.
     */
    private String buildInstallTimingNote() {
        try {
            android.content.pm.PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            long nowMs = System.currentTimeMillis();
            long sinceInstallMin = (nowMs - info.firstInstallTime) / (60 * 1000);
            long sinceUpdateMin = (nowMs - info.lastUpdateTime) / (60 * 1000);
            return "App installed " + sinceInstallMin + " min ago, last updated " + sinceUpdateMin + " min ago.";
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException e) {
            // NameNotFoundException is a CHECKED exception (unlike
            // RuntimeException) -- a real Gradle build confirmed this
            // wasn't being caught at all, since RuntimeException alone
            // doesn't cover it. A confirmed blind spot in my own test
            // sandbox is worth being upfront about: the stub Android SDK
            // used there didn't accurately replicate this method's real
            // checked-exception signature, so no amount of comprehensive
            // sandbox javac checking could have caught this one --
            // catching it here is the actual fix, not a testing gap I can
            // close on my end.
            return "Could not read install timing: " + e.getMessage();
        }
    }

    /**
     * Battery percentage and Doze-mode state -- distinguishes "the app
     * stopped because of low battery / Doze" from "the app stopped for
     * some other reason", which the log couldn't tell apart before.
     */
    private String getBatteryAndDozeInfo() {
        int batteryPct = -1;
        android.os.BatteryManager batteryManager =
                (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
        if (batteryManager != null) {
            batteryPct = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
        }
        boolean isDozing = false;
        android.os.PowerManager powerManager = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            isDozing = powerManager.isDeviceIdleMode();
        }
        return "battery=" + batteryPct + "% doze=" + isDozing + " " + getMemoryInfo();
    }

    /**
     * Device-wide memory state -- Android only exposes THIS, not what any
     * other specific app (like Dasher) is individually using, so this
     * can't prove "Dasher caused a kill" directly. What it CAN do: if
     * monitoring dies again, checking whether lowMemory was already true
     * shortly beforehand is real evidence for "memory pressure" as the
     * cause versus something else -- the closest honest signal available
     * from inside this app, short of adb logcat's authoritative kill
     * reason (which only the OS itself can report).
     */
    private String getMemoryInfo() {
        android.app.ActivityManager activityManager =
                (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (activityManager == null) {
            return "mem=unknown";
        }
        android.app.ActivityManager.MemoryInfo memInfo = new android.app.ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memInfo);
        long availMb = memInfo.availMem / (1024 * 1024);
        long totalMb = memInfo.totalMem / (1024 * 1024);
        return "mem=" + availMb + "MB/" + totalMb + "MB avail lowMemory=" + memInfo.lowMemory;
    }

    private long lastHeartbeatMs = 0;
    private static final long HEARTBEAT_INTERVAL_MS = 15 * 1000; // 15 sec

    // Requirement change (2026-08-30, docs/watchdog_reliability/PRD.md):
    // real uploaded field log evidence -- two full monitoring blackouts,
    // ~15min and ~7min, with ZERO WATCHDOG: log entries during either one
    // -- showed MonitoringWatchdogReceiver's alarm chain is a single point
    // of failure: it only ever reschedules itself from inside its own
    // firing, so one alarm the OS fails to deliver (a documented risk on
    // aggressive OEMs, which can clear an app's pending alarms alongside a
    // force-stop, not just kill the process) silently disables it for the
    // rest of the session with nothing else to re-arm it. Re-arming it
    // here too gives it a second, independent chance to recover -- but
    // only for as long as this service itself is still alive; it can't
    // help if the whole process is already dead (that's what
    // MonitoringWatchdogReceiver's own OS-invoked, cross-process design
    // exists for). AS OF 2026-08-31 (docs/watchdog_gps_independent_rearm/
    // PRD.md), this fires from watchdogRearmRunnable above -- a fixed
    // Handler.postDelayed schedule -- rather than from maybeLogHeartbeat,
    // which only ran when a GPS callback actually arrived and so shared a
    // failure dependency with the exact alarm-drop scenario this re-arm
    // exists to catch. Throttled well below the heartbeat's own 15s
    // cadence -- AlarmManager.setExactAndAllowWhileIdle with
    // FLAG_UPDATE_CURRENT safely replaces any still-pending alarm, so this
    // doesn't need to be frequent to be effective.
    private static final long WATCHDOG_REARM_INTERVAL_MS = 5 * 60 * 1000; // 5 min

    /**
     * Logs a periodic "still alive" entry while actively tracking -- not
     * every GPS tick. The real value: if these suddenly stop appearing
     * with no matching onDestroy() entry, that's direct evidence the
     * whole process was killed abruptly (by the OS or an OEM battery
     * manager) rather than stopped gracefully. Also includes battery/Doze/
     * memory state and re-checks permissions for silent revocation.
     *
     * Also writes the timestamp to SharedPreferences (not just the
     * Python-side diagnostic log) specifically so MonitoringWatchdogReceiver
     * can check it WITHOUT needing the Python engine or this process to
     * still be alive -- SharedPreferences is backed by a file on disk,
     * durable across a process being killed and restarted, unlike any
     * static field or in-memory state.
     */
    private void maybeLogHeartbeat(long nowMs) {
        if (!monitoringActive) {
            return;
        }
        if (nowMs - lastHeartbeatMs < HEARTBEAT_INTERVAL_MS) {
            return;
        }
        lastHeartbeatMs = nowMs;
        logDiagnostic("HEARTBEAT", "Still tracking (mode=" + lastKnownMode + ", trip=" + lastKnownTripState
                + ", " + getBatteryAndDozeInfo() + ")");
        checkAndLogPermissions(false);
        getSharedPreferences(MonitoringWatchdogReceiver.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putLong(MonitoringWatchdogReceiver.KEY_LAST_HEARTBEAT_MS, nowMs)
                .apply();
        // Redundant watchdog re-arm used to live here, gated on a GPS
        // callback actually arriving -- moved to watchdogRearmRunnable
        // (see startTracking()), which fires on its own fixed schedule
        // instead. See WATCHDOG_REARM_INTERVAL_MS's own comment for why.
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Android's official "I'm about to start killing background
        // processes" warning. If this fires shortly before an unexpected
        // stop, that confirms system memory/battery pressure was the
        // cause. If it's ABSENT right before a kill, that points toward
        // a manufacturer-specific killer that bypasses this standard
        // callback entirely (common on some OEM skins).
        logDiagnostic("MEMORY", "onTrimMemory(level=" + level + ")");
    }

    private void stopTracking() {
        if (!monitoringActive) {
            return; // already idle
        }
        logDiagnostic("SERVICE", "stopTracking() -- monitoring turned off");
        // Fixes a real, confirmed bug: previously, a trip that was still
        // genuinely active (not yet parked long enough to naturally end)
        // got silently abandoned the moment monitoring stopped -- the GPS
        // feed dies right here, so nothing could ever complete the normal
        // end-of-trip sequence, leaving the trip invisible everywhere
        // until the app process happened to restart. Explicitly finalizes
        // it now instead.
        boolean wasTripActive = "TRIP_ACTIVE".equals(engine.callAttr("get_state").toString());
        try {
            engine.callAttr("force_end_trip");
            // Manual stop doesn't necessarily go through handleGpsResult's
            // natural TRIP_ACTIVE -> IDLE transition detection (no more
            // GPS ticks arrive once monitoring stops) -- fires the same
            // feedback notification here instead, so this path isn't
            // silently missed the way the automatic trigger was before.
            if (wasTripActive) {
                notifyRateThisDelivery();
            }
        } catch (RuntimeException e) { // covers PyException too
            logDiagnostic("ERROR", "force_end_trip on stop exception: " + android.util.Log.getStackTraceString(e));
        }
        monitoringActive = false;
        isRunning = false;
        releaseTripWakeLock(); // safety net -- in case the trip never properly transitioned to IDLE first
        accessibilityHeartbeatHandler.removeCallbacks(accessibilityHeartbeatRunnable);
        watchdogRearmHandler.removeCallbacks(watchdogRearmRunnable);
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        startForeground(NOTIFICATION_ID, buildIdleNotification());
        refreshStatusDot();
        MonitoringWatchdogReceiver.markIntendedActive(this, false);
        MonitoringWatchdogReceiver.cancelWatchdog(this);
    }

    /**
     * Wrapper so a logging call itself can never crash the app (the same
     * defensive pattern as everywhere else). Falls back to FallbackLogger
     * (a plain file, bypassing Python/SQLite) if the engine isn't ready
     * yet -- e.g. logging from very early in onCreate before
     * PythonBridge has finished initializing.
     */
    private void logDiagnostic(String category, String message) {
        try {
            if (engine != null) {
                engine.callAttr("log_diagnostic", category, message);
            } else {
                FallbackLogger.log(this, category, message);
            }
        } catch (RuntimeException e) { // RuntimeException alone also catches PyException (Chaquopy PyException extends RuntimeException -- Java disallows both in one multi-catch since one is a subclass of the other)
            // Logging must never itself be a source of crashes.
            FallbackLogger.log(this, category, message);
        }
    }

    private String lastKnownTripState = null;

    private String lastApproachingAddress = null;
    private String lastApproachingPickupRestaurant = null;
    private boolean lastKnownIsWalking = false;

    private void handleGpsResult(String resultJson) {
        try {
            JSONObject obj = new JSONObject(resultJson);

            String mode = obj.optString("mode", "GENERAL");
            if (!mode.equals(lastKnownMode)) {
                onModeChanged(mode);
                lastKnownMode = mode;
            }

            String tripState = obj.optString("state", "IDLE");
            if (!tripState.equals(lastKnownTripState)) {
                // Rare event (not per-tick) -- cheap to log, and gives a
                // clear timeline of what the trip was doing right up to
                // any unexpected stop.
                logDiagnostic("STATE", "Trip state: " + lastKnownTripState + " -> " + tripState);

                // Scoped WakeLock -- held ONLY during an actual active
                // trip, not constantly, given a confirmed real incident:
                // an entire real delivery went completely untracked
                // because the process was dead the whole time, and
                // Doze-related CPU sleep is a real, plausible contributor
                // during exactly this kind of extended background
                // window. Deliberately scoped to the highest-value,
                // highest-risk period only (a real delivery in progress)
                // rather than held constantly, to keep the battery cost
                // bounded and justified.
                if ("TRIP_ACTIVE".equals(tripState) && !"TRIP_ACTIVE".equals(lastKnownTripState)) {
                    acquireTripWakeLock();
                } else if (!"TRIP_ACTIVE".equals(tripState) && "TRIP_ACTIVE".equals(lastKnownTripState)) {
                    releaseTripWakeLock();
                }

                // Fires ONLY on the genuine "all deliveries complete"
                // transition -- TRIP_ACTIVE to IDLE specifically, not any
                // other state change, and not per-stop within a batch.
                if ("TRIP_ACTIVE".equals(lastKnownTripState) && "IDLE".equals(tripState)
                        && TripForegroundService.hasValidLocation) {
                    try {
                        JSONObject check = new JSONObject(engine.callAttr("check_show_return_to_sweet_spot",
                                TripForegroundService.lastKnownLat, TripForegroundService.lastKnownLon).toString());
                        if (check.optBoolean("should_show", false)) {
                            double sweetSpotLat = check.optDouble("lat", 0);
                            double sweetSpotLon = check.optDouble("lon", 0);
                            // Waze specifically, not RoadWarrior -- per
                            // explicit request, RoadWarrior stays
                            // exclusively for pinpointing the actual
                            // delivery address.
                            OverlayHelper.showReturnToSweetSpotIcon(this, () ->
                                    NavigationHelper.openAddressWithWaze(this, sweetSpotLat, sweetSpotLon));
                            logDiagnostic("SWEET_SPOT", "Showing return-to-sweet-spot icon -- "
                                    + check.optDouble("distance_km", 0) + " km from usual pickup zone");
                        }
                    } catch (JSONException | RuntimeException e) {
                        logDiagnostic("ERROR", "check_show_return_to_sweet_spot exception: "
                                + android.util.Log.getStackTraceString(e));
                    }
                }

                // Confirmed real bug, fixed here: the feedback dialog
                // previously had ZERO automatic trigger anywhere -- it
                // only ever appeared if manually navigated to via Last
                // Trip Summary. Fires on the same natural trip-end
                // transition. A background service can't show a dialog
                // directly, so this brings MainActivity to the foreground
                // instead (docs/feedback_page_direct/PRD.md), which shows
                // the actual (already-working) feedback dialog via its
                // existing auto_show_feedback_trip_id handling. Stale
                // comment fixed here too: this used to (incorrectly) say
                // "opens TripHistoryActivity" -- notifyRateThisDelivery()
                // has always actually targeted MainActivity (see its own
                // comment for the real build-error history behind that).
                if ("TRIP_ACTIVE".equals(lastKnownTripState) && "IDLE".equals(tripState)) {
                    notifyRateThisDelivery();
                }

                lastKnownTripState = tripState;
            }

            // RoadWarrior quick-navigation icon: appears while approaching
            // an unmatched stop, disappears once matched/arrived or no
            // longer active. Runs on every tick (unlike the arrival
            // handling below, which only fires on the specific tick
            // arrival is detected) -- tracked by address so the overlay
            // view isn't needlessly re-added every single GPS tick.
            JSONObject approachingStop = obj.optJSONObject("approaching_stop");
            String approachingAddress = approachingStop != null
                    ? approachingStop.optString("address", "") : null;
            if (approachingAddress != null && !approachingAddress.equals(lastApproachingAddress)) {
                OverlayHelper.showNavigationIcon(this, () -> {
                    // Previously no way to tell whether a tap was ever
                    // actually received at all, versus being received but
                    // NavigationHelper failing silently afterward -- this
                    // confirms which one, if the icon is ever reported not
                    // to work again.
                    logDiagnostic("NAV_ICON", "Tapped -- copying " + approachingAddress);
                    NavigationHelper.copyAddressToClipboard(this, approachingAddress);
                });
                logDiagnostic("NAV_ICON", "Showing -- approaching: " + approachingAddress);
            } else if (approachingAddress == null && lastApproachingAddress != null) {
                OverlayHelper.clearNavigationIcon(this);
                logDiagnostic("NAV_ICON", "Cleared -- no longer approaching " + lastApproachingAddress);
            }
            lastApproachingAddress = approachingAddress;

            // Same RoadWarrior-style icon, extended to the pickup side --
            // previously only ever shown while approaching a dropoff.
            // Shares the single nav-icon overlay slot with the dropoff
            // handling above rather than a separate one: in the normal
            // single-delivery flow the two are naturally mutually
            // exclusive in time (the pickup icon only shows before the
            // restaurant is reached; the dropoff icon only shows for a
            // stop not yet matched, which only becomes relevant once
            // picked up), so there's no real collision to guard against.
            JSONObject approachingPickup = obj.optJSONObject("approaching_pickup");
            String approachingPickupRestaurant = approachingPickup != null
                    ? approachingPickup.optString("restaurant_name", "") : null;
            if (approachingPickupRestaurant != null && !approachingPickupRestaurant.equals(lastApproachingPickupRestaurant)) {
                // Real street address (see GoogleApiHelper.geocodeAddressWithFormatted /
                // DasherAccessibilityService.geocodePickupAndCheckTraffic) if it's
                // resolved by now, else null -- the restaurant name/coordinates
                // are already known and useful even before it has.
                String pickupAddress = approachingPickup.isNull("address")
                        ? null : approachingPickup.optString("address", null);
                if (pickupAddress == null || pickupAddress.isEmpty()) {
                    // Per explicit request: a visible "waiting for that address"
                    // signal, not just silently showing the icon with no address
                    // yet. Auto-dismisses like every other transient overlay
                    // message here -- this is a one-time heads-up, not a status
                    // that needs to stay pinned on screen.
                    OverlayHelper.showMessage(this,
                            "Waiting for pickup address (" + approachingPickupRestaurant + ")...");
                }
                String finalPickupAddress = pickupAddress;
                OverlayHelper.showNavigationIcon(this, () -> {
                    // Falls back to the restaurant name if the address hasn't
                    // resolved yet -- there's still something to copy and
                    // paste, just less precise than a real street address.
                    String target = (finalPickupAddress != null && !finalPickupAddress.isEmpty())
                            ? finalPickupAddress : approachingPickupRestaurant;
                    logDiagnostic("NAV_ICON", "Pickup icon tapped -- copying " + target);
                    NavigationHelper.copyAddressToClipboard(this, target);
                });
                logDiagnostic("NAV_ICON", "Showing pickup icon -- approaching: " + approachingPickupRestaurant
                        + (pickupAddress != null && !pickupAddress.isEmpty() ? " (" + pickupAddress + ")" : " (address pending)"));
            } else if (approachingPickupRestaurant == null && lastApproachingPickupRestaurant != null) {
                OverlayHelper.clearNavigationIcon(this);
                logDiagnostic("NAV_ICON", "Cleared pickup icon -- no longer approaching " + lastApproachingPickupRestaurant);
            }
            lastApproachingPickupRestaurant = approachingPickupRestaurant;

            // Persistent, tappable delivery-instruction overlay -- shown
            // the moment a pending instruction is detected while
            // APPROACHING a stop (not waiting for arrival), per explicit
            // request. Deliberately does NOT auto-clear for any reason
            // (see OverlayHelper.showPersistentTappableMessage's own
            // reasoning) -- only a tap dismisses it, since the delivery
            // may not actually be complete yet even after arrival.
            JSONObject approachInstruction = obj.optJSONObject("approach_instruction");
            if (approachInstruction != null) {
                String approachAddress = approachInstruction.optString("address", "your stop");
                JSONArray approachInstructions = approachInstruction.optJSONArray("instructions");
                StringBuilder approachSpoken = new StringBuilder("Heads up, approaching " + approachAddress + ". ");
                StringBuilder approachOverlayText = new StringBuilder("Approaching: " + approachAddress + "\n");
                if (approachInstructions != null) {
                    for (int i = 0; i < approachInstructions.length(); i++) {
                        String clean = VoiceAnnouncer.stripCategoryPrefix(approachInstructions.optString(i, ""));
                        approachSpoken.append(clean).append(". ");
                        approachOverlayText.append("\u2022 ").append(clean).append("\n");
                    }
                }
                VoiceAnnouncer.speak(approachSpoken.toString());
                java.util.List<String> cannedReplies = new java.util.ArrayList<>();
                try {
                    JSONArray repliesJson = new JSONArray(engine.callAttr("get_canned_replies_json").toString());
                    for (int i = 0; i < repliesJson.length(); i++) {
                        JSONObject replyObj = repliesJson.optJSONObject(i);
                        if (replyObj != null) {
                            cannedReplies.add(replyObj.optString("text", ""));
                        }
                    }
                } catch (JSONException | RuntimeException e) {
                    logDiagnostic("ERROR", "get_canned_replies_json exception: "
                            + android.util.Log.getStackTraceString(e));
                }
                OverlayHelper.showPersistentTappableMessage(this, approachOverlayText.toString(), cannedReplies);
                logDiagnostic("INSTRUCTION", "Approach instruction shown for " + approachAddress);
            }

            // Purple "walking" status dot -- DASHER mode only, the window
            // between "nearly at the stop" and "confirmed arrived" (see
            // TripManager.is_walking_pace). Checked every tick since this
            // can start/stop without mode itself changing (mode stays
            // DASHER the whole time you're walking to and from the door).
            boolean isWalking = obj.optBoolean("is_walking", false);
            if (isWalking != lastKnownIsWalking) {
                lastKnownIsWalking = isWalking;
                refreshStatusDot();
                // Previously zero trace of this feature ever triggering --
                // genuinely untested in the field until now. Logs only on
                // the transition, not every tick, to avoid spam.
                logDiagnostic("WALKING", isWalking ? "Walking status started" : "Walking status ended");
                // GAP 1a (diagnostic-coverage pass): confirms the purple
                // dot actually RENDERED, distinct from the status simply
                // changing -- OverlayHelper.showStatusDot silently does
                // nothing if overlay permission isn't currently granted,
                // which the log line above alone couldn't reveal.
                if (isWalking) {
                    logDiagnostic("WALKING", "Purple status dot render "
                            + (OverlayHelper.hasPermission(this) ? "succeeded" : "SKIPPED -- overlay permission not granted"));
                }
            }

            if (!obj.isNull("gap_sample_log")) {
                logDiagnostic("WALKING_LEARNING", obj.optString("gap_sample_log", ""));
            }

            // GAP 3 (diagnostic-coverage pass): previously silent.
            if (!obj.isNull("phase_capture_log")) {
                logDiagnostic("PHASE_TIMING", obj.optString("phase_capture_log", ""));
            }

            JSONObject arrival = obj.optJSONObject("arrival");
            if (arrival == null) {
                return;
            }
            String address = arrival.optString("address", "your stop");
            JSONArray instructions = arrival.optJSONArray("instructions");

            StringBuilder spoken = new StringBuilder("Arrived at " + address + ". ");
            StringBuilder overlayText = new StringBuilder("Arrived: " + address + "\n");
            if (instructions != null) {
                for (int i = 0; i < instructions.length(); i++) {
                    String clean = VoiceAnnouncer.stripCategoryPrefix(instructions.optString(i, ""));
                    spoken.append(clean).append(". ");
                    overlayText.append("\u2022 ").append(clean).append("\n");
                }
            }

            VoiceAnnouncer.speak(spoken.toString());
            OverlayHelper.showMessage(this, overlayText.toString());
        } catch (JSONException | RuntimeException e) { // covers PyException too -- this method calls
            // OverlayHelper/NavigationHelper/VoiceAnnouncer directly (real Java-side work, not just
            // Python calls or JSON parsing), so needs to catch more than JSONException|PyException
            // alone to avoid silently crashing the foreground service.
            logDiagnostic("ERROR", "handleGpsResult exception: " + android.util.Log.getStackTraceString(e));
        }
    }

    /**
     * Fires once per mode transition (not on every GPS tick): updates the
     * persistent notification's title/icon and speaks a brief cue, so it's
     * always obvious which mode is active without looking at the phone.
     */
    private void onModeChanged(String mode) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotificationForMode(mode));
        }
        refreshStatusDot();
        if (lastKnownMode != null) {
            // Don't announce on the very first reading after tracking
            // starts -- only on actual transitions afterward.
            boolean isDasher = "DASHER".equals(mode);
            VoiceAnnouncer.speak(isDasher
                    ? "Switched to Dasher delivery mode."
                    : "Switched to general driving mode.");
            logDiagnostic("MODE", (lastKnownMode == null ? "?" : lastKnownMode) + " -> " + mode);
        }
    }

    private static final long GPS_INTERVAL_DEEP_PARK_MS = 30000; // 30 sec -- deeply parked or screen off
    private static final long DEEP_PARK_THRESHOLD_MS = 5 * 60 * 1000; // 5 min continuously stationary

    // Confirmed via a real diagnostic log: a single GPS reading above the
    // stationary threshold is not reliable evidence of real movement --
    // GPS commonly produces small spurious speed readings (1-5 km/h) from
    // signal noise alone even on a completely motionless phone, especially
    // indoors. Without this, one noisy reading resets stationarySinceMs
    // back to zero, so the "5 minutes stationary" timer never actually
    // accumulates -- observed in the field as the GPS tier oscillating
    // between tier 0 and tier 1 continuously for 55+ minutes while the
    // phone was genuinely idle the whole time, burning battery at close to
    // the most power-hungry (1-second polling) rate instead of settling
    // into the efficient 30-second tier. Requiring a short run of
    // consecutive above-threshold readings filters out the noise while
    // still correctly detecting a real drive starting back up quickly.
    private static final int REQUIRED_CONSECUTIVE_MOVING_READINGS = 3;

    private int currentGpsIntervalTier = 0; // 0=moving, 1=stationary, 2=deep-park/screen-off
    private long stationarySinceMs = 0;
    private int consecutiveMovingReadings = 0;

    private void startLocationUpdates() {
        startLocationUpdatesAtInterval(GPS_INTERVAL_MOVING_MS);
        currentGpsIntervalTier = 0;
        stationarySinceMs = 0;
    }

    private void startLocationUpdatesAtInterval(long intervalMs) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        LocationRequest request = new LocationRequest.Builder(intervalMs)
                .setMinUpdateIntervalMillis(intervalMs)
                .setPriority(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY)
                .build();
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
    }

    /**
     * Three-tier GPS polling for battery savings: fast (1/sec) while
     * driving, moderate (1/5sec) while briefly stopped, and slow (1/30sec)
     * once either deeply parked (5+ continuous minutes stationary) or the
     * screen is off -- reducing polling further in exactly the two
     * situations where 1-second precision adds no real value. Only
     * re-registers with Fused Location Provider when the tier actually
     * changes, not on every tick.
     */
    private void updateGpsIntervalForSpeed(double speedKmh, long nowMs) {
        boolean isStationary = speedKmh < 2.0;
        boolean screenOff = false;
        android.os.PowerManager powerManager = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            screenOff = !powerManager.isInteractive();
        }

        int targetTier;
        if (!isStationary) {
            consecutiveMovingReadings++;
            if (consecutiveMovingReadings >= REQUIRED_CONSECUTIVE_MOVING_READINGS) {
                // Sustained, not a single noisy blip -- genuinely moving again.
                targetTier = 0;
                stationarySinceMs = 0;
            } else {
                // Not yet enough consecutive readings to trust this as real
                // movement -- keep whatever stationary-timer state already
                // existed rather than resetting it on a single noisy reading.
                targetTier = currentGpsIntervalTier;
            }
        } else {
            if (consecutiveMovingReadings > 0) {
                // A brief run of above-threshold readings just got
                // discarded as noise (never reached
                // REQUIRED_CONSECUTIVE_MOVING_READINGS before speed
                // dropped back down) -- this IS the jitter-filtering fix
                // actively working, previously invisible in the log.
                logDiagnostic("GPS_FILTER", "Discarded " + consecutiveMovingReadings
                        + " consecutive moving reading(s) as noise (speed=" + speedKmh + " km/h)");
            }
            consecutiveMovingReadings = 0;
            if (stationarySinceMs == 0) {
                stationarySinceMs = nowMs;
            }
            boolean deepParked = (nowMs - stationarySinceMs) >= DEEP_PARK_THRESHOLD_MS;
            targetTier = (deepParked || screenOff) ? 2 : 1;
        }

        if (targetTier == currentGpsIntervalTier) {
            return; // already at the right interval
        }
        currentGpsIntervalTier = targetTier;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        fusedLocationClient.removeLocationUpdates(locationCallback);
        long interval = targetTier == 0 ? GPS_INTERVAL_MOVING_MS
                : targetTier == 1 ? GPS_INTERVAL_STATIONARY_MS
                : GPS_INTERVAL_DEEP_PARK_MS;
        startLocationUpdatesAtInterval(interval);
        logDiagnostic("BATTERY", "GPS interval tier -> " + targetTier + " (screenOff=" + screenOff + ")");
    }

    /**
     * Distinct title/icon per mode -- this is the main, always-visible
     * indicator of which mode is active, since the notification is present
     * the whole time the service runs (report requirement: "clearly
     * indicate between the two modes").
     */
    private Notification buildNotificationForMode(String mode) {
        boolean isDasher = "DASHER".equals(mode);
        String title = isDasher ? "Dasher Mode" : "General Driving Mode";
        String text = isDasher
                ? "Tracking your delivery -- offers, stops, and messages"
                : "Tracking driving efficiency (Dasher app not active)";
        int icon = isDasher
                ? android.R.drawable.ic_menu_send
                : android.R.drawable.ic_menu_mylocation;

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(appendOverlayReminder(text))
                .setSmallIcon(icon)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        "Stop Monitoring", buildStopActionPendingIntent())
                .build();
    }

    /**
     * Lets you stop monitoring directly from the notification shade,
     * without needing to open the app first -- previously the only way
     * to stop was through the in-app button.
     */
    private PendingIntent buildStopActionPendingIntent() {
        Intent stopIntent = new Intent(this, TripForegroundService.class);
        stopIntent.setAction(ACTION_STOP_TRACKING);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, 0, stopIntent, flags);
    }

    /**
     * Lets you fully shut down -- no notification, no badge, nothing --
     * directly from the notification shade, without opening the app.
     */
    private PendingIntent buildQuitActionPendingIntent() {
        Intent quitIntent = new Intent(this, TripForegroundService.class);
        quitIntent.setAction(ACTION_QUIT_COMPLETELY);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, 1, quitIntent, flags);
    }

    /**
     * Shown whenever the service is alive but not actively tracking. Has
     * its own "Quit" action -- previously there was no way to reach a
     * definitively "fully off" state (no notification, no badge) short of
     * force-stopping the app via Android Settings, which made "Not
     * Monitoring" ambiguous: is it idle-but-still-running, or truly off?
     */
    private Notification buildIdleNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Dasher Monitor")
                .setContentText(appendOverlayReminder("Not monitoring -- app still running in background"))
                .setSmallIcon(android.R.drawable.presence_offline)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        "Quit Completely", buildQuitActionPendingIntent())
                .build();
    }

    /**
     * The on-screen MONITORING/NOT MONITORING badge (OverlayHelper.
     * showStatusDot) silently never appears at all if overlay permission
     * was never granted -- with nothing else telling you why. Since the
     * notification is guaranteed visible regardless of that permission,
     * it's the one place that can reliably nudge you to fix it.
     */
    private String appendOverlayReminder(String text) {
        if (OverlayHelper.hasPermission(this)) {
            return text;
        }
        return text + " (enable overlay permission to see on-screen status)";
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Trip Tracking", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Most important log entry in the whole diagnostic system: this
        // fires whenever the service is torn down for ANY reason -- a
        // deliberate quit, the OS killing it under memory/battery
        // pressure, or a crash elsewhere in the app taking the whole
        // process down. If monitoring stops unexpectedly, this entry
        // (or the conspicuous ABSENCE of a matching one, if the whole
        // process got killed outright without a graceful onDestroy) is
        // the first thing to check.
        logDiagnostic("SERVICE", "onDestroy() -- service process ending"
                + " (monitoringActive was " + monitoringActive + ", " + getBatteryAndDozeInfo() + ")");
        // Final safety net, closing the last remaining gap after the
        // stopTracking()/quitCompletely() fix: if the service is ever
        // torn down through some OTHER path that didn't already call
        // force_end_trip (both of those already do), this ensures an
        // in-progress trip still gets finalized here rather than staying
        // invisible. Safe no-op if no trip is active or engine isn't ready.
        try {
            if (engine != null) {
                engine.callAttr("force_end_trip");
            }
        } catch (RuntimeException e) { // covers PyException too
            logDiagnostic("ERROR", "force_end_trip in onDestroy exception: " + android.util.Log.getStackTraceString(e));
        }
        accessibilityHeartbeatHandler.removeCallbacks(accessibilityHeartbeatRunnable);
        watchdogRearmHandler.removeCallbacks(watchdogRearmRunnable);
        releaseTripWakeLock(); // final safety net -- must not leak a held wakelock if the service dies unexpectedly
        isRunning = false;
        serviceExists = false;
        monitoringActive = false;
        OverlayHelper.clearStatusDot(this);
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
