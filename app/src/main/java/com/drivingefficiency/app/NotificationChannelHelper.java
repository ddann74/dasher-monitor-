package com.drivingefficiency.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

/**
 * One shared notification-channel-creation helper, replacing 9 near-
 * identical copies of the same "SDK-version-gate, construct, optional
 * description/vibration, register" block that had grown independently
 * across TripForegroundService (6 sites), AppNotificationListenerService,
 * BootAndUpdateReceiver, and MonitoringWatchdogReceiver -- a real-audit
 * finding, not a hypothetical one, since several of those copies had
 * already started to drift (one omitted the caller's own null-check on
 * NotificationManager before calling createNotificationChannel on it).
 *
 * createNotificationChannel() is itself always safe to call unconditionally
 * on a real channel id -- creating an already-existing channel with the
 * same id is a documented no-op, not an error -- so this helper doesn't
 * need its own "already created" tracking; every call site already only
 * calls it right before posting that channel's notification, same as
 * before.
 */
final class NotificationChannelHelper {

    private NotificationChannelHelper() {}

    /**
     * @param manager     may be null (getSystemService(NotificationManager.class)
     *                    can return null); a null manager is a silent no-op here,
     *                    same as every call site's own pre-existing null-check.
     * @param description may be null to skip setDescription -- CHANNEL_ID's
     *                    "Trip Tracking" channel is the one site that never set one.
     * @param vibrate     true to call channel.enableVibration(true) -- only the
     *                    alert-style channels (permission-revoked, recording-
     *                    verification-failed, monitoring-not-active, watchdog)
     *                    did this; routine channels didn't.
     */
    static void ensureChannel(NotificationManager manager, String channelId, String name,
                               int importance, String description, boolean vibrate) {
        if (manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(channelId, name, importance);
        if (description != null) {
            channel.setDescription(description);
        }
        if (vibrate) {
            channel.enableVibration(true);
        }
        manager.createNotificationChannel(channel);
    }

    /**
     * docs/notification_visibility_check/PRD.md -- CONFIRMED REAL GAP,
     * fixed here (round-10 scouting finding #5): every alert this app
     * raises (permission-revoked, watchdog staleness/escalation, engine-
     * failure, recording-verification-failed) ends in a bare
     * manager.notify(...) call, with nothing anywhere checking whether
     * the driver can actually SEE it. A driver who has disabled this
     * app's notifications entirely (Settings -> Apps -> Dasher Monitor
     * -> Notifications) gets zero visible symptom -- notify() doesn't
     * throw or report failure in that case, it just silently delivers
     * nothing. Every fail-safe mechanism this whole session built
     * ultimately terminates in this same unverified call, making this a
     * single point of silent failure for the entire alerting layer.
     * areNotificationsEnabled() (real platform API, added API 24; this
     * app's minSdk is 26, no version gate needed) is Android's own
     * documented, authoritative answer to "can this app post anything
     * the user will ever see," independent of any specific channel.
     */
    static boolean areNotificationsDisabled(NotificationManager manager) {
        return manager != null && !manager.areNotificationsEnabled();
    }
}
