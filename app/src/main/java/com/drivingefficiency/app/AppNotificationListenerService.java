package com.drivingefficiency.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures notifications from THREE filtered/triaged paths -- deliberately
 * NOT a catch-all that reads every notification on the phone. Only these
 * three things are ever read aloud; everything else is silently ignored:
 *
 * 1. WORK messages -- Dasher app offers + customer delivery texts (report
 *    sections 2 & 4). Parsed by keyword via MessageIntelligence in
 *    drive_monitor.py, regardless of who sent them. Triaged by urgency:
 *    delivery notes/address corrections interrupt immediately; ETA/
 *    lateness updates are batched together instead of each interrupting
 *    individually (see pendingLowPriorityMessages).
 *
 * 2. OFFER DETECTION FROM NOTIFICATIONS -- previously, a new offer was only
 *    ever detected while the Dasher app's own screen was open and being
 *    read by DasherAccessibilityService. If an offer notification arrived
 *    while you were doing something else entirely (e.g. navigating via
 *    Google Maps), nothing happened until you actually opened Dasher to
 *    look. This announces and scores offers the moment the notification
 *    itself arrives, regardless of what's on screen. See
 *    parse_offer_notification()'s docstring for an important honesty
 *    note: this parser was built without a real offer-notification sample
 *    to calibrate against (unlike the screen parser, built from two real
 *    screenshots) -- it's intentionally lenient, and may need correcting
 *    against a real example if it doesn't detect offers reliably.
 *
 * 3. PERSONAL messages -- SMS / Facebook Messenger from people the user has
 *    explicitly added to their trusted-contacts allowlist. Read aloud
 *    verbatim if the sender matches, otherwise silently ignored. This is
 *    NOT keyword-based and does NOT apply to the Dasher app.
 *
 * Every other app's notifications, and untrusted-sender personal messages,
 * are never read aloud at all -- a broader "everything else" catch-all
 * existed briefly but was deliberately removed, since reading every
 * notification on the phone was more noise than signal.
 *
 * NOTE: "com.facebook.orca" is Messenger's package name as of this writing;
 * verify it against your installed version if this doesn't work as-is.
 */
public class AppNotificationListenerService extends NotificationListenerService {

    // Update with the real Dasher app package name.
    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
    private static final String SMS_PACKAGE = "com.google.android.apps.messaging";
    private static final String MESSENGER_PACKAGE = "com.facebook.orca";

    // Messenger posts these as its own system/status notifications, not as
    // a message from a person -- but their EXTRA_TITLE still gets passed to
    // is_trusted_sender() like any real sender name, and if it happens to
    // satisfy a trusted-contact substring match, it gets read aloud
    // verbatim as if it were a message. Confirmed against a real 12-day
    // diagnostic log: "Chat heads active" alone accounted for 264 of 396
    // total personal-message reads (67%) -- by far the single noisiest
    // thing in the log. Checked before the trusted-sender lookup so these
    // never reach it, regardless of what's on the trusted list.
    private static final java.util.Set<String> MESSENGER_SYSTEM_NOTIFICATION_TITLES = new java.util.HashSet<>(
            java.util.Arrays.asList("Chat heads active", "Messenger Audio call", "Messenger Video call"));

    private PyObject engine;
    private String lastNotificationOfferKey = null;
    // Defense in depth alongside the empty-content guard in
    // onNotificationPosted: if Dasher ever reposts a *non-empty* not-an-
    // offer notification (e.g. "You're still dashing...") repeatedly in a
    // tight loop the same way it did with empty content in the real log
    // this was found from, this stops it from flooding the log with
    // identical lines too - only a title+text pair that actually changed
    // gets logged again.
    private String lastUnrecognizedNotificationKey = null;

    // Message triage (idea #5): urgent instructions (delivery notes,
    // address corrections) are read immediately as before. Lower-priority
    // ones (ETA/lateness updates) are batched instead of each interrupting
    // individually -- accumulated here and announced together once no new
    // low-priority message has arrived for BATCH_WINDOW_MS.
    private final List<String> pendingLowPriorityMessages = new ArrayList<>();
    private final Handler batchHandler = new Handler(Looper.getMainLooper());
    private static final long BATCH_WINDOW_MS = 15 * 1000;
    private final Runnable batchAnnouncer = new Runnable() {
        @Override
        public void run() {
            if (pendingLowPriorityMessages.isEmpty()) {
                return;
            }
            String announcement;
            if (pendingLowPriorityMessages.size() == 1) {
                announcement = "Update: " + pendingLowPriorityMessages.get(0);
            } else {
                announcement = pendingLowPriorityMessages.size() + " other updates: "
                        + String.join("; ", pendingLowPriorityMessages);
            }
            VoiceAnnouncer.speak(announcement);
            pendingLowPriorityMessages.clear();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        engine = PythonBridge.getEngine(this);
        VoiceAnnouncer.init(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String packageName = sbn.getPackageName();
        if (packageName.equals(getPackageName())) {
            return; // never read our own notifications (status badge, etc.)
        }

        boolean isDasher = packageName.equals(DASHER_PACKAGE);
        boolean isPersonalMessagingApp = packageName.equals(SMS_PACKAGE)
                || packageName.equals(MESSENGER_PACKAGE);

        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras;
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE, "");
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT, "");

        // Dasher's own ongoing/foreground-service notification ("You're
        // still dashing...") gets reposted by the OS very frequently while
        // an active dash is running - confirmed against a real diagnostic
        // log: roughly once per second, sustained for 30+ minutes straight,
        // with EXTRA_TITLE and EXTRA_TEXT empty on nearly every repost.
        // Every repost used to trigger two unconditional cross-language
        // calls into the embedded Python engine below (parse_offer_notification
        // here, on_notification further down) plus a diagnostic log write -
        // with real content that's structurally impossible: an offer or a
        // customer message can never be conveyed by an empty notification,
        // so there's nothing to parse either way. That sustained load is
        // the most likely cause of a real, observed failure in the same
        // log: a 12-minute total monitoring blackout (no heartbeats, no GPS,
        // nothing) immediately followed by the service process restarting.
        // Bailing out here, before any Python call, costs nothing real and
        // fixes both the log spam and the underlying load.
        if (isDasher && title.length() == 0 && text.length() == 0) {
            return;
        }

        try {
            if (isDasher) {
                handleDasherNotification(title.toString(), text.toString());
            }

            boolean isMessagingStyle = extras.containsKey(Notification.EXTRA_MESSAGES)
                    || extras.containsKey(Notification.EXTRA_SELF_DISPLAY_NAME);

            // --- Work: Dasher offers / customer delivery instructions ---
            // on_notification() only actually matches for DASHER_PACKAGE or
            // SMS_PACKAGE internally, so this call is harmless (returns None)
            // for Messenger notifications.
            //
            // Passes the current known position so on_notification can
            // tag this message with the closest real stop at this exact
            // moment (see TripManager.on_message) -- fixes a real,
            // previously-known limitation where a batch order's messages
            // were only ever matched by time window, not by which stop
            // they were actually about. Falls back to null/null (and
            // Python's own time-window-only fallback) if no valid
            // location is known yet.
            PyObject workResult = TripForegroundService.hasValidLocation
                    ? engine.callAttr("on_notification", packageName, title.toString(), text.toString(),
                            System.currentTimeMillis(), isMessagingStyle,
                            TripForegroundService.lastKnownLat, TripForegroundService.lastKnownLon)
                    : engine.callAttr("on_notification", packageName, title.toString(), text.toString(),
                            System.currentTimeMillis(), isMessagingStyle);

            if (workResult != null) {
                String instruction = workResult.toString();
                if (!instruction.isEmpty()) {
                    boolean urgent = engine.callAttr("is_instruction_urgent", instruction).toBoolean();
                    String clean = VoiceAnnouncer.stripCategoryPrefix(instruction);
                    if (urgent) {
                        // Delivery notes / address corrections -- affects
                        // where you're going or what to do right now, so
                        // this still interrupts immediately as before.
                        VoiceAnnouncer.speak("Customer message: " + clean);
                    } else {
                        // ETA/lateness updates -- lower priority, doesn't
                        // need to interrupt immediately. Batched with any
                        // other low-priority messages that arrive within
                        // the same window, instead of each one
                        // interrupting individually.
                        pendingLowPriorityMessages.add(clean);
                        batchHandler.removeCallbacks(batchAnnouncer);
                        batchHandler.postDelayed(batchAnnouncer, BATCH_WINDOW_MS);
                    }
                    return; // already handled via the work path -- don't also read it as "everything else"
                }
            }

            // --- Personal: trusted-contacts allowlist (SMS / Messenger) ---
            if (isPersonalMessagingApp) {
                if (MESSENGER_SYSTEM_NOTIFICATION_TITLES.contains(title.toString())) {
                    logDiagnostic("PERSONAL_MSG", "Ignored (Messenger system notification, not a message: " + title + ")");
                    return;
                }
                boolean trusted = engine.callAttr("is_trusted_sender", title.toString())
                        .toBoolean();
                if (trusted && text.length() > 0) {
                    VoiceAnnouncer.speak("Message from " + title + ": " + text);
                    // Sender name only, never the message body/content --
                    // same privacy principle already used for personal
                    // messages elsewhere. This is specifically what makes
                    // it verifiable that this path is actually working,
                    // which it previously wasn't -- no logging existed
                    // here at all, in either direction.
                    logDiagnostic("PERSONAL_MSG", "Read aloud (trusted sender: " + title + ")");
                } else {
                    logDiagnostic("PERSONAL_MSG", "Ignored (not on trusted list: " + title + ")");
                }
                return; // SMS/Messenger notifications are always handled by
                        // the paths above (trusted or silently ignored) --
                        // untrusted senders are never read aloud at all.
            }

            if (isDasher) {
                return; // Dasher's own notifications are fully handled above
                        // (offer detection + work path).
            }

            // No catch-all "everything else" path -- deliberately removed.
            // Only two things ever get read aloud: Dasher work messages
            // (triaged urgent/batched above) and personal messages from a
            // trusted contact. Every other app's notifications are
            // silently ignored, not read verbatim.
        } catch (RuntimeException e) { // covers PyException too -- this path calls VoiceAnnouncer
            // and does string work directly (not just Python calls), so needs to catch more than
            // PyException alone to avoid silently crashing this always-on notification listener.
            logDiagnostic("ERROR", "onNotificationPosted exception (pkg=" + packageName + "): " + android.util.Log.getStackTraceString(e));
        }
    }

    /**
     * Offer detection from the notification itself -- see class doc #2.
     * Independent of, and in addition to, the existing accessibility-
     * service screen-reading path; whichever one sees a given offer first
     * announces it (deduped by restaurant+payout key, same pattern as
     * DasherAccessibilityService's offer badge).
     */
    private void handleDasherNotification(String title, String text) {
        try {
            JSONObject result = new JSONObject(engine.callAttr("parse_offer_notification", title, text).toString());
            if (!result.optBoolean("is_offer", false)) {
                // Previously silent -- if this notification WAS a real
                // offer but the lenient, unconfirmed regex parser (see
                // parse_offer_notification's honesty note) failed to
                // recognize it, nothing happened at all: no log, no
                // announcement, nothing. Logging the raw title/text here
                // is what actually makes it possible to correct that
                // parser against a real sample, instead of guessing.
                // Safe to log content here (unlike personal SMS/Messenger
                // messages) -- Dasher notifications aren't private the
                // same way.
                String unrecognizedKey = title + "|" + text;
                if (unrecognizedKey.equals(lastUnrecognizedNotificationKey)) {
                    return; // identical repost of the same non-offer notification - already logged
                }
                lastUnrecognizedNotificationKey = unrecognizedKey;
                logDiagnostic("NOTIFICATION", "Dasher notification NOT recognized as an offer -- title: \""
                        + title + "\", text: \"" + text + "\"");
                return;
            }
            String restaurantName = result.optString("restaurant_name", "");
            boolean hasPayout = !result.isNull("payout");
            double payout = hasPayout ? result.optDouble("payout", -1) : -1;
            String offerKey = restaurantName + "|" + payout;
            if (offerKey.equals(lastNotificationOfferKey)) {
                return; // already announced this exact offer
            }
            lastNotificationOfferKey = offerKey;

            JSONObject score = result.optJSONObject("smart_score");
            if (score != null) {
                double finalScore = score.optDouble("final_score", 0);
                String label = score.optString("label", "");
                double perKm = score.optDouble("base_rate_per_km", 0);
                double perHr = score.optDouble("hourly_rate", 0);
                VoiceAnnouncer.speak(String.format("New offer detected: %d, %s. %.2f dollars per kilometer, "
                        + "%.2f dollars per hour", Math.round(finalScore), label, perKm, perHr));
                HapticFeedback.vibrateForLabel(this, label);
                logDiagnostic("OFFER", "Detected via notification: " + restaurantName
                        + ", $" + payout + ", score " + Math.round(finalScore));

                // Auto-launch Dasher for the offer, addressing the real
                // reported gap: Monitor detects and announces an offer
                // instantly regardless of what app you're in, but if
                // Dasher's own process was reclaimed while backgrounded,
                // the offer itself can disappear before you manually
                // switch back to look. Excludes only "Poor" -- every
                // other label (Excellent, Good, Fair) auto-launches, per
                // explicit direction.
                if (!"Poor".equals(label)) {
                    launchDasherApp(restaurantName, finalScore);
                }
            } else if (hasPayout) {
                // Payout found but no distance -- can't compute a real
                // score, but still worth a heads-up. No score to filter
                // by label, so always launch -- can't tell if it's a
                // "Poor" offer without one.
                VoiceAnnouncer.speak(String.format("New offer detected: $%.2f. Open Dasher for details.", payout));
                logDiagnostic("OFFER", "Detected via notification (no distance): " + restaurantName + ", $" + payout);
                launchDasherApp(restaurantName, -1);
            } else {
                // CONFIRMED real format: "New Delivery!" / "New Order: Go
                // to X" -- no payout or distance in the notification at
                // all, just an announcement to go check the app. This was
                // previously completely unrecognized (see
                // parse_offer_notification's confirmed-real fix). Always
                // launches Dasher -- this IS the exact scenario reported:
                // an offer arrives while on another app, with nothing
                // else to show except by opening Dasher directly.
                VoiceAnnouncer.speak("New offer detected. Open Dasher for details.");
                logDiagnostic("OFFER", "Detected via notification (no payout/distance): " + restaurantName);
                launchDasherApp(restaurantName, -1);
                // Fixes a real, confirmed root cause behind three separate
                // reported gaps at once (empty Address Book, no traffic
                // ratio ever logged, the sweet-spot icon never appearing):
                // none of that data was ever recorded for an offer only
                // ever seen via notification, since geocoding previously
                // only ever ran from the screen-reading path. Runs the
                // same real geocode + pickup-location-recording here too.
                geocodePickupForNotificationOnlyOffer(restaurantName);
            }
        } catch (JSONException | RuntimeException e) { // covers PyException too -- calls
            // VoiceAnnouncer and HapticFeedback directly (real Java-side work), not just Python/JSON.
            logDiagnostic("ERROR", "handleDasherNotification exception: " + android.util.Log.getStackTraceString(e));
        }
    }

    /**
     * A focused duplicate of DasherAccessibilityService's
     * geocodePickupAndCheckTraffic, adapted for this class -- the same
     * real geocode + pickup-location-recording + traffic-check pipeline,
     * just triggered from the notification path instead of screen-
     * reading. This is what actually closes the gap: Address Book,
     * traffic ratio, and the sweet-spot icon all depend on this data
     * existing, and previously it only ever got recorded when an offer
     * was detected via the full screen-reading path.
     */
    private void geocodePickupForNotificationOnlyOffer(String restaurantName) {
        if (!GoogleApiHelper.hasApiKey(this) || restaurantName.isEmpty()) {
            return;
        }
        GoogleApiHelper.geocodeAddress(this, restaurantName, new GoogleApiHelper.GeocodeCallback() {
            @Override
            public void onResult(double lat, double lon) {
                // CRITICAL: runs via Handler.post() on the main thread --
                // same guard as the screen-reading version, an uncaught
                // exception here would crash the entire app process.
                try {
                    logDiagnostic("GEOCODE", "Resolved " + restaurantName + " -> " + lat + "," + lon
                            + " (via notification)");
                    try {
                        engine.callAttr("record_pickup_location", restaurantName, lat, lon);
                        // GAP 2 (diagnostic-coverage pass): explicit
                        // success confirmation -- previously only a
                        // failure would ever be logged here, success was
                        // completely silent, so there was no direct way
                        // to confirm this notification-only path was
                        // actually feeding the Address Book / sweet-spot
                        // learning.
                        logDiagnostic("PICKUP_PERSIST", "record_pickup_location succeeded (via notification): "
                                + restaurantName);
                    } catch (RuntimeException e) { // covers PyException too
                        logDiagnostic("ERROR", "record_pickup_location exception: "
                                + android.util.Log.getStackTraceString(e));
                    }
                    if (!TripForegroundService.hasValidLocation) {
                        logDiagnostic("TRAFFIC", "Skipped -- no current GPS fix yet this session");
                        return;
                    }
                    GoogleApiHelper.getTrafficDelayRatio(
                            AppNotificationListenerService.this, TripForegroundService.lastKnownLat,
                            TripForegroundService.lastKnownLon, lat, lon,
                            new GoogleApiHelper.TrafficCallback() {
                                @Override
                                public void onResult(double trafficDelayRatio, int durationInTrafficSeconds,
                                                      int typicalDurationSeconds) {
                                    try {
                                        engine.callAttr("record_live_traffic_delay", trafficDelayRatio);
                                        logDiagnostic("TRAFFIC", "Delay ratio " + trafficDelayRatio
                                                + " for " + restaurantName);
                                    } catch (RuntimeException e) { // covers PyException too
                                        logDiagnostic("ERROR", "Traffic callback exception: "
                                                + android.util.Log.getStackTraceString(e));
                                    }
                                }

                                @Override
                                public void onError(String message) {
                                    logDiagnostic("TRAFFIC", "Query failed: " + message);
                                }
                            });
                } catch (RuntimeException e) { // covers PyException too
                    logDiagnostic("ERROR", "Geocode callback exception: " + android.util.Log.getStackTraceString(e));
                }
            }

            @Override
            public void onError(String message) {
                logDiagnostic("GEOCODE", "Failed: " + message);
            }
        });
    }

    private static final String AUTO_LAUNCH_CHANNEL_ID = "dasher_auto_launch_channel";
    private static final int AUTO_LAUNCH_NOTIFICATION_ID = 9200;

    /**
     * Requests that Dasher be brought to the foreground automatically --
     * called for any offer except "Poor" (see handleDasherNotification).
     *
     * REAL BUG FIX, confirmed via a real diagnostic log: this used to call
     * startActivity() directly from this NotificationListenerService.
     * Since Android 10, starting an Activity from a background service
     * context is subject to Background Activity Launch (BAL) restrictions
     * -- the OS can silently drop the call with no exception thrown, so
     * the old "Brought Dasher to foreground" log line only ever proved
     * this method didn't crash, not that Dasher actually appeared on
     * screen. A real log showed two AUTO_LAUNCH calls fire cleanly, yet
     * DasherAccessibilityService never logged a single MODE/EVENT_DEBUG/
     * NODE_SCAN line for the rest of that session -- Dasher's own screen
     * was never actually read, meaning the on-screen Smart Score badge
     * never got a chance to compute or show either.
     *
     * Fix: post a high-priority notification with setFullScreenIntent(),
     * the same documented mechanism incoming-call and alarm apps use to
     * reliably launch an Activity from the background (including over the
     * lock screen) -- one of Android's actual BAL exemptions, unlike a
     * bare startActivity() call. Requires USE_FULL_SCREEN_INTENT (see
     * manifest). HONESTY NOTE: on Android 14+ this permission can be
     * revoked by the user (Settings), in which case Android falls back to
     * a normal heads-up notification the user must tap themselves rather
     * than launching automatically -- still strictly better than the
     * previous silent-drop failure mode, but not a 100% guarantee either.
     */
    private void launchDasherApp(String restaurantName, double finalScore) {
        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(DASHER_PACKAGE);
            if (launchIntent == null) {
                logDiagnostic("AUTO_LAUNCH", "Could not launch Dasher -- no launch intent found for "
                        + DASHER_PACKAGE);
                return;
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            // Explicit request: bring Dasher to the foreground automatically
            // again, the way it did before the BAL restriction was
            // discovered -- not just a notification the driver has to tap.
            //
            // showOfferOverlayFallback() is called FIRST, before the direct
            // startActivity() attempt below, specifically so its
            // SYSTEM_ALERT_WINDOW overlay window is genuinely on screen
            // by the time startActivity() runs: an app currently showing a
            // visible overlay window is one of Android's real Background
            // Activity Launch exemptions, unlike the plain background
            // service context the original bare startActivity() call ran
            // from (see commit 2719d00's honesty note on why that one was
            // silently dropped). This wasn't available before, since no
            // overlay window existed at the moment of that original call.
            //
            // HONESTY NOTE: a blocked BAL launch fails SILENTLY -- no
            // exception, same as before -- so this can't actually confirm
            // whether the direct switch worked, only that it was attempted
            // under a condition where it plausibly can now succeed. The
            // full-screen-intent notification below still runs regardless,
            // as a second, independent mechanism (reliable when locked),
            // and the overlay's own tap-to-open remains a manual fallback
            // if both automatic paths are blocked on a given device/OS
            // version.
            showOfferOverlayFallback(restaurantName, finalScore, launchIntent);
            try {
                startActivity(launchIntent);
                logDiagnostic("AUTO_LAUNCH", "Attempted direct foreground launch for offer (" + restaurantName
                        + ") while an overlay window was active -- not confirmable whether it actually "
                        + "switched to Dasher, see class docs");
            } catch (RuntimeException e) {
                logDiagnostic("AUTO_LAUNCH", "Direct foreground launch attempt failed/blocked for offer ("
                        + restaurantName + "): " + e.getClass().getSimpleName()
                        + " -- falling back to full-screen-intent notification + overlay tap");
            }

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) {
                logDiagnostic("AUTO_LAUNCH", "Could not launch Dasher -- NotificationManager unavailable");
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(AUTO_LAUNCH_CHANNEL_ID,
                        "Auto-Launch Dasher for Offers", NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("Brings Dasher to the foreground the moment a new offer is detected");
                manager.createNotificationChannel(channel);
            }

            PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                    this, restaurantName.hashCode(), launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            long lastSeenMs = DasherAccessibilityService.lastDasherForegroundMs;
            String recencyNote;
            if (lastSeenMs == 0) {
                recencyNote = "Dasher not seen in foreground yet this session -- likely a cold start";
            } else {
                long agoSeconds = (System.currentTimeMillis() - lastSeenMs) / 1000;
                String agoLabel = agoSeconds < 60 ? agoSeconds + "s ago"
                        : agoSeconds < 3600 ? (agoSeconds / 60) + "m ago"
                        : (agoSeconds / 3600) + "h ago";
                recencyNote = "Dasher last seen in foreground " + agoLabel
                        + " (approximation only, not a certainty -- see class docs)";
            }
            String scoreNote = finalScore >= 0 ? ", score " + Math.round(finalScore) : " (no score available)";

            Notification notification = new Notification.Builder(this, AUTO_LAUNCH_CHANNEL_ID)
                    .setContentTitle("New offer: " + restaurantName)
                    .setContentText("Opening Dasher" + scoreNote)
                    .setSmallIcon(android.R.drawable.ic_menu_directions)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setCategory(Notification.CATEGORY_CALL)
                    .setFullScreenIntent(fullScreenPendingIntent, true)
                    .setContentIntent(fullScreenPendingIntent)
                    .setAutoCancel(true)
                    .build();
            manager.notify(AUTO_LAUNCH_NOTIFICATION_ID, notification);
            logDiagnostic("AUTO_LAUNCH", "Requested Dasher foreground via full-screen-intent notification for offer ("
                    + restaurantName + scoreNote + ") -- " + recencyNote);
        } catch (RuntimeException e) {
            logDiagnostic("ERROR", "launchDasherApp exception: " + android.util.Log.getStackTraceString(e));
        }
    }

    private static final long OFFER_OVERLAY_AUTO_DISMISS_MS = 20 * 1000;

    /**
     * PREMORTEM FIX: setFullScreenIntent() above only truly auto-launches
     * Dasher's Activity when the device is LOCKED -- confirmed Android
     * platform behavior since Android 10. If the screen is already on and
     * unlocked (the normal state while actively navigating with Maps/Waze
     * open, phone mounted -- arguably the MOST common real driving
     * scenario, not an edge case), the system downgrades it to a regular
     * heads-up banner that still needs a manual tap. That would silently
     * defeat the whole point of auto-launch ("the offer itself can
     * disappear before you manually switch back to look") in exactly the
     * situation it exists for.
     *
     * This overlay is a genuinely hands-free fallback that doesn't depend
     * on lock state at all: SYSTEM_ALERT_WINDOW draws directly on top of
     * whatever's currently on screen (already granted -- same permission
     * OverlayHelper's status dot and Smart Score badge rely on), so the
     * restaurant name and score are visible immediately regardless of
     * whether the full-screen intent above actually fired or degraded.
     * Tapping it launches Dasher directly -- a real user tap on our own
     * window is a recognized BAL exemption, unlike the bare startActivity()
     * call this whole fix replaced.
     *
     * HONESTY NOTE: like every other overlay in this app, this silently
     * never appears at all if SYSTEM_ALERT_WINDOW was never granted --
     * same caveat as OverlayHelper's own class doc.
     */
    private void showOfferOverlayFallback(String restaurantName, double finalScore, Intent launchIntent) {
        String scoreLine = finalScore >= 0
                ? String.format("Smart Score: %.0f/100", finalScore)
                : "Open Dasher for details";
        String message = "📦 New Offer: " + restaurantName + "\n" + scoreLine + "\n(tap to open Dasher)";
        OverlayHelper.showMessage(this, message, OFFER_OVERLAY_AUTO_DISMISS_MS,
                android.graphics.Color.parseColor("#CC1565C0"),
                () -> {
                    try {
                        startActivity(launchIntent);
                    } catch (RuntimeException e) {
                        logDiagnostic("ERROR", "Offer overlay tap-to-launch exception: "
                                + android.util.Log.getStackTraceString(e));
                    }
                });
    }

    /**
     * Wrapper so a logging call itself can never crash the app (same
     * defensive pattern used elsewhere). Falls back to FallbackLogger
     * if the engine isn't ready yet.
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
}
