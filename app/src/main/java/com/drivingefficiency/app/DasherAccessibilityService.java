package com.drivingefficiency.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Two jobs, kept clearly separated:
 *
 * 1. DUAL-MODE DETECTION -- listens for window-state-changed events across
 *    ALL apps (not just Dasher) purely to know which app currently has
 *    focus, and reports that to drive_monitor.py via set_dasher_foreground()
 *    so it can tell DASHER mode (delivery in progress) apart from GENERAL
 *    mode (plain driving-efficiency tracking) -- see report requirement:
 *    "function as a navigation efficiency monitor when the Dasher app isn't
 *    running, and clearly indicate between the two modes."
 *
 *    PRIVACY SAFEGUARD: even though this service now listens broadly for
 *    the *fact* that some other app came to the foreground, it NEVER reads
 *    that other app's on-screen content -- getRootInActiveWindow() and the
 *    node-tree text walk below only ever run when the event's package is
 *    DASHER_PACKAGE. For every other app, only the bare package name is
 *    checked, then discarded.
 *
 * 2. OFFER / ADDRESS READING -- (unchanged from before) reads on-screen
 *    text while the Dasher offer screen (or accepted-offer screen) is
 *    showing. Offer details ($ payout, distance, restaurant, deadline) are
 *    rendered INSIDE the app's own UI (a map + bottom sheet), not as a
 *    system notification -- so this has to come from here, not
 *    NotificationListenerService. See drive_monitor.py's OfferScreenParser
 *    for the parsing rules, derived from a real offer screenshot:
 *
 *      $13.65 / Guaranteed / 5.1 km / Deliver by 4:25 pm / Pickup /
 *      KFC Fairy Meadow / Customer drop-off / Accept / 17
 *
 *    The real customer address is NOT shown on the pre-accept offer screen
 *    (only a generic "Customer drop-off" placeholder) -- it only appears
 *    after Accept is tapped, so a second pass over the post-accept screen
 *    is needed to capture the actual delivery address for geocoding.
 */
public class DasherAccessibilityService extends AccessibilityService {

    private static final String DASHER_PACKAGE = "com.doordash.driverapp";
    private PyObject engine;
    private String lastOfferKey = null;

    // Remembers the most recently seen offer's details so a subsequent
    // Accept/Decline tap can be recorded against it.
    private String lastSeenRestaurantName = null;
    private android.graphics.Rect acceptNodeBounds = null;
    private android.graphics.Rect declineNodeBounds = null;
    private long offerShownAtMs = 0;
    // Minimum real delay since an offer was first shown before treating
    // any node-bounds match as a genuine tap -- specifically to rule out
    // accessibility focus landing on the button automatically as the
    // screen first loads, which is not a real user interaction.
    private static final long NODE_MATCH_MIN_DELAY_MS = 1500;

    // Driver-requested data-completeness hardening (driver backlog #21,
    // docs/driver_backlog_2026_09_03/PRD.md), applied without a specific
    // diagnostic log to root-cause against -- the driver's actual goal
    // ("collect all data to build the smart score engine") is served by
    // hardening the ALREADY-NAMED weak point in this detection, not by
    // guessing at an unconfirmed bug. checkNodeBoundsMatch previously
    // required byte-exact Rect equality between the bounds recorded at
    // scan time and the bounds on the actual tap event -- if the screen
    // re-renders by even a few pixels in between (a real, disclosed risk
    // this class's own comments already named: "a bounds-shift edge
    // case"), a genuine decline silently falls through to the timeout
    // fallback instead, which recalculate_personal_calibration then
    // deliberately EXCLUDES from learning (a timeout isn't treated as a
    // real preference signal the way an active decline is) -- real data
    // loss for exactly the goal the driver stated. This tolerance lets
    // each edge of the tapped bounds drift up to this many pixels from
    // the recorded bounds and still count as the same button, while
    // still being spatially precise enough that Accept and Decline
    // (always separate, non-adjacent buttons) can't be confused with
    // each other. HONEST LIMIT: a fixed pixel value, not density-scaled
    // -- generous enough for a minor re-render shift on any real screen
    // density, not verified against a real device in this environment.
    private static final int NODE_MATCH_BOUNDS_TOLERANCE_PX = 24;

    // Tap-to-expand state for the live Smart Score badge -- lives in
    // fields (not lambda captures) so toggleSmartScoreBadge can
    // reference itself indefinitely as the next tap's action.
    private String smartScoreBadgeCompactText = "";
    private String smartScoreBadgeExpandedText = "";
    private android.graphics.drawable.Drawable smartScoreBadgeBackground;
    private boolean smartScoreBadgeExpanded = false;
    private double lastSeenPayout = -1;
    private double lastSeenDistanceKm = -1;
    private double lastSeenSmartScore = -1;
    private String lastSeenComponentsJson = null;
    private double lastSeenHourlyRate = -1;

    // Grace period before committing a timeout -- Android doesn't
    // strictly guarantee that a TYPE_VIEW_CLICKED event for a tap always
    // arrives before the TYPE_WINDOW_CONTENT_CHANGED event for the screen
    // transitioning away because of that same tap. Without this, a real
    // Decline tap could theoretically get recorded as a timeout instead,
    // if the "screen just disappeared" event happened to be processed
    // first. Delaying the actual timeout commit gives a near-simultaneous
    // click event a short window to arrive and cancel it, recording the
    // real outcome instead.
    private static final long TIMEOUT_GRACE_PERIOD_MS = 1500;
    private final android.os.Handler timeoutHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingTimeoutRunnable = null;

    // docs/store_wait_timer/PRD.md -- driver-requested (2026-09-08): "a
    // timer that starts 1 minute after i press arrived at store and
    // stops when i press confirm pickup". A third, separate signal from
    // the existing GPS-geofence-based pickup_arrival_ts/pickup_departure_ts
    // (drive_monitor.py) and the subjective merchant_wait_rating -- this
    // one is driven by the driver's own two button taps. Button text is
    // the driver's own literal wording from the request, NOT confirmed
    // against a real screenshot (see the PRD's own ss3) -- if wrong,
    // this simply never fires, silently; the diagnostic log lines below
    // are what would confirm or correct that from a future real log.
    private static final long STORE_WAIT_GRACE_PERIOD_MS = 60_000;
    private static final long STORE_WAIT_TIMER_TICK_MS = 1000;
    // docs/store_wait_timer/PRD.md §8 -- driver-audit finding
    // (2026-09-11): arrivedAtStoreTapMs was pure in-memory state with no
    // recovery path, unlike offers (save_pending_offer_for_recovery) or
    // trips (_recover_interrupted_trips) which both already solved this
    // exact "process restart mid-activity" problem. If an OEM kill (the
    // same real, confirmed instability class behind
    // docs/watchdog_reliability/PRD.md and docs/screen_recording/PRD.md
    // §21) happened while a driver was genuinely waiting at a store, the
    // overlay would vanish, the eventual "Confirm Pickup" tap would find
    // arrivedAtStoreTapMs already null, and the whole wait -- possibly
    // the very over-grace wait this feature exists to measure -- would
    // be silently, permanently lost. Persisted via SharedPreferences,
    // the same durability mechanism MonitoringWatchdogReceiver already
    // uses for this identical class of problem (a file on disk survives
    // a process restart; a field or static in-memory value doesn't).
    private static final String STORE_WAIT_PREFS_NAME = "store_wait_timer_prefs";
    private static final String KEY_ARRIVED_AT_STORE_MS = "arrived_at_store_ms";
    // A resumed timer this old is far more likely an orphaned leftover
    // from a pickup that was actually completed (or abandoned/unassigned)
    // during whatever gap caused the restart, than a real, still-ongoing
    // wait -- no real restaurant wait plausibly runs this long. Discarded
    // rather than resumed past this age, same "don't trust indefinitely
    // stale state blindly" reasoning as screen_recording's own orphaned-
    // segment cleanup.
    private static final long STORE_WAIT_MAX_RESUMABLE_AGE_MS = 2 * 60 * 60 * 1000;
    private Long arrivedAtStoreTapMs = null;
    private boolean storeWaitTimerVisible = false;
    private final android.os.Handler storeWaitTimerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable storeWaitTimerStartRunnable = null;
    private final Runnable storeWaitTimerTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (arrivedAtStoreTapMs == null) {
                return; // stopped/cancelled since this tick was scheduled
            }
            long overGraceMs = System.currentTimeMillis() - arrivedAtStoreTapMs - STORE_WAIT_GRACE_PERIOD_MS;
            OverlayHelper.showStoreWaitTimer(DasherAccessibilityService.this, formatStoreWaitTimer(overGraceMs));
            storeWaitTimerHandler.postDelayed(this, STORE_WAIT_TIMER_TICK_MS);
        }
    };

    private String formatStoreWaitTimer(long elapsedMs) {
        long totalSeconds = Math.max(0, elapsedMs / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(java.util.Locale.US, "Waiting: %d:%02d", minutes, seconds);
    }

    /**
     * "Arrived at Store" tapped -- starts the 1-minute grace period.
     * Nothing is shown or logged unless the grace period actually
     * elapses before "Confirm Pickup" (see stopStoreWaitTimer) -- a
     * quick in-and-out pickup shouldn't show a timer for a wait that
     * never really happened.
     */
    private void startStoreWaitGracePeriod() {
        cancelStoreWaitTimer(); // defensive -- clears any stale prior arrival that was never confirmed
        arrivedAtStoreTapMs = System.currentTimeMillis();
        getSharedPreferences(STORE_WAIT_PREFS_NAME, MODE_PRIVATE).edit()
                .putLong(KEY_ARRIVED_AT_STORE_MS, arrivedAtStoreTapMs).apply();
        logDiagnostic("STORE_WAIT", "Arrived at Store tapped -- grace period started ("
                + (STORE_WAIT_GRACE_PERIOD_MS / 1000) + "s)");
        storeWaitTimerStartRunnable = () -> {
            storeWaitTimerVisible = true;
            storeWaitTimerHandler.post(storeWaitTimerTickRunnable);
            logDiagnostic("STORE_WAIT", "Grace period elapsed -- timer now visible");
        };
        storeWaitTimerHandler.postDelayed(storeWaitTimerStartRunnable, STORE_WAIT_GRACE_PERIOD_MS);
    }

    /**
     * "Confirm Pickup" tapped -- always cancels the pending grace-period
     * start first, so a pickup confirmed WITHIN the first minute shows
     * and persists nothing at all. If the timer had already become
     * visible (grace period genuinely elapsed), stops it, clears the
     * overlay, and persists the measured over-grace duration -- silently
     * (no voice/toast), per the driver's own explicit choice.
     */
    private void stopStoreWaitTimer() {
        removeStoreWaitTimerCallbacks();

        if (arrivedAtStoreTapMs == null) {
            return; // Confirm Pickup with no matching Arrived tap this session -- nothing to record
        }

        if (storeWaitTimerVisible) {
            double overGraceSeconds = Math.max(0.0,
                    (System.currentTimeMillis() - arrivedAtStoreTapMs - STORE_WAIT_GRACE_PERIOD_MS) / 1000.0);
            OverlayHelper.clearStoreWaitTimer(this);
            try {
                String resultJson = engine.callAttr("record_store_wait_timer", overGraceSeconds).toString();
                logDiagnostic("STORE_WAIT", "Confirm Pickup tapped -- over-grace wait "
                        + Math.round(overGraceSeconds) + "s. " + resultJson);
            } catch (RuntimeException e) { // covers PyException too
                logDiagnostic("ERROR", "record_store_wait_timer exception: "
                        + android.util.Log.getStackTraceString(e));
            }
        } else {
            logDiagnostic("STORE_WAIT", "Confirm Pickup tapped within the 1-minute grace period -- no over-grace wait");
        }

        arrivedAtStoreTapMs = null;
        storeWaitTimerVisible = false;
        getSharedPreferences(STORE_WAIT_PREFS_NAME, MODE_PRIVATE).edit().remove(KEY_ARRIVED_AT_STORE_MS).apply();
    }

    /**
     * Just the Handler-callback-cancellation step, shared by
     * stopStoreWaitTimer, cancelStoreWaitTimer (both had their own copy
     * of this exact block before), and the real service-teardown fix in
     * onUnbind/onDestroy below. Deliberately NOT cancelStoreWaitTimer's
     * other side effects (clearing the visible overlay, clearing the
     * persisted SharedPreferences arrival timestamp) -- onUnbind/onDestroy
     * must NOT wipe that persisted state, since
     * resumeStoreWaitTimerIfPending's whole reason to exist is resuming
     * this exact in-progress wait across a process/service restart;
     * clearing it here on a mere unbind (the driver may re-enable the
     * accessibility service moments later) would silently defeat that.
     */
    private void removeStoreWaitTimerCallbacks() {
        if (storeWaitTimerStartRunnable != null) {
            storeWaitTimerHandler.removeCallbacks(storeWaitTimerStartRunnable);
            storeWaitTimerStartRunnable = null;
        }
        storeWaitTimerHandler.removeCallbacks(storeWaitTimerTickRunnable);
    }

    /**
     * Cancels any in-progress store-wait timer (pending grace-period
     * start, running tick loop, and the visible overlay if any) without
     * persisting anything -- used when there's no real "Confirm Pickup"
     * coming for this pickup at all (an unassign; see the click handler)
     * or defensively before starting a fresh grace period.
     */
    private void cancelStoreWaitTimer() {
        removeStoreWaitTimerCallbacks();
        if (storeWaitTimerVisible) {
            OverlayHelper.clearStoreWaitTimer(this);
        }
        arrivedAtStoreTapMs = null;
        storeWaitTimerVisible = false;
        getSharedPreferences(STORE_WAIT_PREFS_NAME, MODE_PRIVATE).edit().remove(KEY_ARRIVED_AT_STORE_MS).apply();
    }

    /**
     * Resumes an in-progress store-wait timer that survived a process
     * restart -- see STORE_WAIT_PREFS_NAME's own doc for why this exists
     * and STORE_WAIT_MAX_RESUMABLE_AGE_MS for why a too-old value is
     * discarded rather than trusted. Called once from onServiceConnected
     * (fires on every fresh connection, including after a process
     * restart -- the exact moment in-memory state would otherwise have
     * silently reset to "nothing happened").
     */
    private void resumeStoreWaitTimerIfPending() {
        android.content.SharedPreferences prefs = getSharedPreferences(STORE_WAIT_PREFS_NAME, MODE_PRIVATE);
        long persistedMs = prefs.getLong(KEY_ARRIVED_AT_STORE_MS, 0);
        if (persistedMs == 0) {
            return; // nothing pending -- the ordinary case
        }
        long ageMs = System.currentTimeMillis() - persistedMs;
        if (ageMs < 0 || ageMs > STORE_WAIT_MAX_RESUMABLE_AGE_MS) {
            // Negative age (clock changed) is just as untrustworthy as
            // too-old -- either way, more likely an orphaned leftover
            // from an already-completed or abandoned pickup than a real,
            // still-ongoing wait no real restaurant visit plausibly runs
            // this long.
            prefs.edit().remove(KEY_ARRIVED_AT_STORE_MS).apply();
            logDiagnostic("STORE_WAIT", "Discarded a stale pending timer from a previous session ("
                    + (ageMs / 1000) + "s old) -- too old to trust as a still-ongoing wait");
            return;
        }
        arrivedAtStoreTapMs = persistedMs;
        long remainingGraceMs = STORE_WAIT_GRACE_PERIOD_MS - ageMs;
        if (remainingGraceMs <= 0) {
            storeWaitTimerVisible = true;
            storeWaitTimerHandler.post(storeWaitTimerTickRunnable);
            logDiagnostic("STORE_WAIT", "Resumed an in-progress wait timer from before a process restart "
                    + "-- already past the grace period, showing now");
        } else {
            storeWaitTimerStartRunnable = () -> {
                storeWaitTimerVisible = true;
                storeWaitTimerHandler.post(storeWaitTimerTickRunnable);
                logDiagnostic("STORE_WAIT", "Grace period elapsed -- timer now visible");
            };
            storeWaitTimerHandler.postDelayed(storeWaitTimerStartRunnable, remainingGraceMs);
            logDiagnostic("STORE_WAIT", "Resumed a pending grace period from before a process restart -- "
                    + (remainingGraceMs / 1000) + "s remaining");
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        engine = PythonBridge.getEngine(this);
        checkCurrentForegroundWindow();
        resumeStoreWaitTimerIfPending();
        // Previously this only ran ONCE, at connect time -- confirmed
        // real gap: Android's accessibility API only reports CHANGES
        // (onAccessibilityEvent), and if an expected change event never
        // fires (e.g. resuming an already-open app after a brief system
        // dialog interruption doesn't always generate a fresh
        // TYPE_WINDOW_STATE_CHANGED the same way opening it fresh does),
        // mode could get stuck on the wrong value indefinitely, with
        // nothing to ever correct it. Running this periodically lets
        // mode self-correct even when an expected event never arrives.
        foregroundCheckHandler.postDelayed(foregroundCheckRunnable, FOREGROUND_CHECK_INTERVAL_MS);
    }

    private static final long FOREGROUND_CHECK_INTERVAL_MS = 20 * 1000;
    private final android.os.Handler foregroundCheckHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable foregroundCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkCurrentForegroundWindow();
            foregroundCheckHandler.postDelayed(this, FOREGROUND_CHECK_INTERVAL_MS);
        }
    };

    /**
     * Fixes a real, confirmed gap: Android's accessibility API only
     * reports CHANGES (onAccessibilityEvent), never "what's currently in
     * the foreground right now" -- there's no query for that. If Dasher
     * is already the foreground app the moment this service (re)connects
     * (e.g. the whole process restarted while Dasher stayed open the
     * entire time, or the service was toggled off/on without Dasher's
     * own window ever actually changing), nothing would ever tell Monitor
     * about it -- mode stayed stuck on its default (GENERAL) until the
     * user genuinely switched away from Dasher and back, a real window
     * transition, not just toggling Monitor's own Start/Stop.
     *
     * getWindows() lets this service directly inspect what's currently
     * visible instead of only waiting for a future change event. Runs
     * once immediately at connect time, AND periodically thereafter
     * (every FOREGROUND_CHECK_INTERVAL_MS) -- confirmed real gap: if an
     * expected TYPE_WINDOW_STATE_CHANGED event never fires (e.g. resuming
     * an already-open app after a brief system dialog interruption),
     * mode could get stuck on the wrong value indefinitely with nothing
     * to ever correct it. Corrects mode in BOTH directions -- toward
     * DASHER if it's confirmed active, and back toward GENERAL if
     * something else is confirmed active but mode was stuck showing
     * DASHER.
     */
    private void checkCurrentForegroundWindow() {
        try {
            boolean foundActiveWindow = false;
            for (AccessibilityWindowInfo window : getWindows()) {
                if (!window.isActive()) {
                    continue;
                }
                foundActiveWindow = true;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null || root.getPackageName() == null) {
                    continue;
                }
                boolean isDasher = root.getPackageName().toString().equals(DASHER_PACKAGE);
                if (isDasher) {
                    engine.callAttr("set_dasher_foreground", true);
                    isDasherForeground = true;
                    lastDasherForegroundMs = System.currentTimeMillis();
                    refreshStatusDot();
                    logDiagnostic("MODE", "Dasher already in foreground at service connect -- set immediately, not waiting for a future change event");
                    // Same auto-start as the debounced transition below --
                    // Dasher could already be open the moment this service
                    // (re)connects (e.g. after a crash/restart), and
                    // monitoring should start immediately in that case too,
                    // not just be silently detected.
                    if (!TripForegroundService.isRunning) {
                        attemptAutoStartMonitoring(TripForegroundService.ACTION_START_TRACKING, "AUTO_START",
                                "Dasher already open at service connect, monitoring was off -- started automatically",
                                "Dasher open at service connect");
                    }
                } else if (isDasherForeground) {
                    // Reverse-direction correction: mode was stuck showing
                    // DASHER, but the real active window is confirmed to be
                    // something else -- corrects it here too, so this
                    // periodic check can fix mode both ways, not just
                    // toward DASHER. Without this, a genuine switch AWAY
                    // from Dasher could go uncorrected just as easily as a
                    // switch back TO it, if the expected event never fired.
                    engine.callAttr("set_dasher_foreground", false);
                    isDasherForeground = false;
                    refreshStatusDot();
                    logDiagnostic("MODE", "Periodic re-check found mode stuck on DASHER -- actual foreground is \""
                            + root.getPackageName() + "\", corrected");
                }
                break; // only one window is ever active at a time
            }
            if (!foundActiveWindow && isDasherForeground) {
                // No active window found at all (rare, but possible
                // during a transition) -- can't confirm what's actually in
                // front, so leave the current state alone rather than
                // guessing.
                logDiagnostic("MODE", "Periodic re-check found no active window -- leaving current mode as-is");
            }
        } catch (RuntimeException e) {
            logDiagnostic("ERROR", "checkCurrentForegroundWindow exception: " + android.util.Log.getStackTraceString(e));
        }
    }

    // docs/dash_monitoring_awareness/PRD.md ss4 point 1's "give it a
    // moment, then check for real" delay -- a few seconds, matching the
    // order of magnitude of this app's other re-arm/re-check timers.
    // The PRIMARY failure mode (Android rejecting a background
    // startForegroundService call) throws synchronously and is already
    // caught below without needing this delay; this is the secondary,
    // belt-and-suspenders check for "the call didn't throw, but
    // monitoring still isn't actually running."
    private static final long MONITORING_VERIFY_DELAY_MS = 5 * 1000;
    private final android.os.Handler monitoringVerifyHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * docs/dash_monitoring_awareness/PRD.md -- shared by all three real
     * auto-start call sites in this file (service-connect detection,
     * the debounced foreground transition, and Dash-Paused auto-resume)
     * so the alerting logic exists exactly once, not copied three times.
     * Every one of them already calls this instead of
     * startForegroundService directly.
     */
    private void attemptAutoStartMonitoring(String action, String logCategory, String successMessage,
                                             String failureReasonForAlert) {
        Intent intent = new Intent(this, TripForegroundService.class);
        intent.setAction(action);
        try {
            startForegroundService(intent);
            logDiagnostic(logCategory, successMessage);
            monitoringVerifyHandler.postDelayed(() -> {
                if (!TripForegroundService.isRunning) {
                    logDiagnostic("ERROR", "Auto-start appeared to succeed but monitoring still "
                            + "isn't running " + MONITORING_VERIFY_DELAY_MS / 1000 + "s later ("
                            + failureReasonForAlert + ")");
                    TripForegroundService.raiseMonitoringNotActiveAlert(this, failureReasonForAlert);
                }
            }, MONITORING_VERIFY_DELAY_MS);
        } catch (RuntimeException e) {
            logDiagnostic("ERROR", "Auto-start threw (" + failureReasonForAlert + "): "
                    + android.util.Log.getStackTraceString(e));
            TripForegroundService.raiseMonitoringNotActiveAlert(this, failureReasonForAlert);
        }
    }

    /**
     * Records whether the last-seen offer was accepted or declined,
     * based on a real Accept/Decline button tap. No-ops if no offer has
     * been seen yet this session (nothing to record against), and clears
     * the remembered offer afterward so a stray repeat click can't
     * double-record the same outcome.
     */
    // Bounds how far scanAndRecordAcceptDeclineNodeBounds's ancestor walk
    // can climb -- generous enough for any realistic layout nesting depth,
    // just a safety bound against a pathological/cyclic tree.
    private static final int MAX_CLICKABLE_ANCESTOR_DEPTH = 10;

    /**
     * Scans the current accessibility tree for the real Accept/Decline
     * nodes by their visible text, and records their exact screen bounds
     * for later matching. Best-effort -- if Dasher's real button text
     * ever differs from an exact "Accept"/"Decline" match, this won't
     * find them; that's a real, honest limitation, not a guaranteed fix.
     *
     * REAL BUG FIX, confirmed via a real diagnostic log: this found
     * "Accept node found=false, Decline node found=false" every single
     * time (5 for 5 in that log), even though FULL_TEXT_DUMP confirmed
     * "Accept"/"Decline" text was genuinely on screen, AND the real
     * TYPE_VIEW_CLICKED event for an actual tap fired with
     * class=android.view.ViewGroup, text=[Decline] -- the clickable
     * element is a ViewGroup container, but findAccessibilityNodeInfosByText
     * matches the TEXT node inside it, which isn't itself clickable. The
     * old `node.isClickable()` check discarded that match every time. Now
     * walks up the matched node's own ancestor chain to find the actual
     * clickable target, the standard fix for "text lives on a
     * non-clickable child of the real clickable row/card" layouts.
     */
    /** Called exactly once, when a NEW offer is first detected (see that
      * call site) -- resets any bounds left over from a previous offer,
      * then does an initial scan via refreshAcceptDeclineNodeBounds().
      * The reset matters here specifically: without it, a stale bounds
      * pair from an offer that timed out without ever matching (so
      * never got explicitly cleared -- see recordLastOfferOutcome) could
      * otherwise survive into the NEXT offer and wrongly match a tap
      * against this new offer's completely different button position. */
    private void scanAndRecordAcceptDeclineNodeBounds() {
        acceptNodeBounds = null;
        declineNodeBounds = null;
        refreshAcceptDeclineNodeBounds();
    }

    /**
     * Driver-audit finding (2026-09-11): the bounds snapshot used to be
     * taken exactly once, at offer-detection time
     * (scanAndRecordAcceptDeclineNodeBounds()'s only call site, before
     * this fix). That goes stale the moment the screen re-renders for
     * any reason -- and it genuinely does: the offer screen has a real,
     * confirmed live accept/decline countdown
     * (OfferScreenParser.extract_countdown_seconds ticks every second).
     * A driver who takes even a few seconds to read and decide -- normal
     * behavior, not an edge case -- could easily be tapping against
     * button positions that shifted since that one snapshot was taken,
     * especially if the shift exceeds the 24px tolerance
     * (NODE_MATCH_BOUNDS_TOLERANCE_PX). Fixed by calling this from
     * checkNodeBoundsMatch() on every check for the same still-pending
     * offer, keeping the comparison target fresh instead of stale.
     *
     * Deliberately does NOT clear an already-known bounds just because
     * THIS pass didn't find it -- a transient miss (the screen is
     * mid-recompose, or the button was already removed because the tap
     * that triggered this very check just landed) must not wipe out a
     * still-good previous value from a moment ago. Callers that need a
     * genuine reset between DIFFERENT offers use
     * scanAndRecordAcceptDeclineNodeBounds() above instead, which resets
     * first, THEN calls this.
     */
    private void refreshAcceptDeclineNodeBounds() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        try {
            android.graphics.Rect foundAcceptBounds = null;
            android.graphics.Rect foundDeclineBounds = null;
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText("Accept")) {
                AccessibilityNodeInfo clickable = nearestClickableAncestor(node);
                if (clickable != null && foundAcceptBounds == null) {
                    android.graphics.Rect bounds = new android.graphics.Rect();
                    clickable.getBoundsInScreen(bounds);
                    foundAcceptBounds = bounds;
                }
            }
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText("Decline")) {
                AccessibilityNodeInfo clickable = nearestClickableAncestor(node);
                if (clickable != null && foundDeclineBounds == null) {
                    android.graphics.Rect bounds = new android.graphics.Rect();
                    clickable.getBoundsInScreen(bounds);
                    foundDeclineBounds = bounds;
                }
            }
            // Only overwrite on an actual find -- see this method's own
            // doc for why a miss must not clear a previously known-good
            // value. Logged only when something actually changed from
            // the previous scan (a genuine initial find, or real drift
            // between refreshes) -- this now runs on every qualifying
            // event during a pending offer, not just once, so logging
            // unconditionally every time would spam the rotation-capped
            // diagnostic log with identical lines for no new information.
            // A logged CHANGE is itself useful evidence: it's exactly
            // the "did the countdown re-render shift the buttons"
            // question this whole fix exists to answer.
            boolean acceptIsNewFind = foundAcceptBounds != null && acceptNodeBounds == null;
            boolean acceptMoved = foundAcceptBounds != null && acceptNodeBounds != null
                    && !foundAcceptBounds.equals(acceptNodeBounds);
            boolean declineIsNewFind = foundDeclineBounds != null && declineNodeBounds == null;
            boolean declineMoved = foundDeclineBounds != null && declineNodeBounds != null
                    && !foundDeclineBounds.equals(declineNodeBounds);
            if (acceptIsNewFind || acceptMoved || declineIsNewFind || declineMoved) {
                logDiagnostic("NODE_SCAN", "Accept node found=" + (foundAcceptBounds != null)
                        + (acceptMoved ? " (moved since last scan)" : "")
                        + ", Decline node found=" + (foundDeclineBounds != null)
                        + (declineMoved ? " (moved since last scan)" : ""));
            }
            if (foundAcceptBounds != null) {
                acceptNodeBounds = foundAcceptBounds;
            }
            if (foundDeclineBounds != null) {
                declineNodeBounds = foundDeclineBounds;
            }
        } catch (RuntimeException e) {
            logDiagnostic("ERROR", "refreshAcceptDeclineNodeBounds exception: "
                    + android.util.Log.getStackTraceString(e));
        }
    }

    /** Returns node itself if already clickable, otherwise the nearest clickable ancestor, or null if none within range. */
    private AccessibilityNodeInfo nearestClickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < MAX_CLICKABLE_ANCESTOR_DEPTH; depth++) {
            if (current.isClickable()) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * Checks whether a given event's source node's bounds genuinely
     * match one of the recorded Accept/Decline nodes -- called from the
     * broadened event handler (see EVENT_DEBUG logging) for every event
     * type, not just clicks, since real evidence shows clicks likely
     * never fire on these buttons at all. Deliberately requires
     * NODE_MATCH_MIN_DELAY_MS to have passed since the offer was shown,
     * to rule out accessibility focus landing on the button
     * automatically as the screen loads -- not a real tap.
     *
     * Driver-audit finding (2026-09-11): re-scans the current bounds
     * (refreshAcceptDeclineNodeBounds()) on every call, not just once at
     * offer-detection time -- the offer screen's own live countdown
     * means it genuinely keeps re-rendering while the driver decides,
     * so comparing against a stale one-time snapshot risked missing a
     * real tap that landed correctly on buttons that had simply moved
     * since. See that method's own doc for why a transient miss during
     * this refresh can't wipe out an already-known-good value.
     *
     * HONESTY NOTE: this is a best-effort heuristic, not a certainty.
     * It's entirely possible NO event type fires on these buttons at
     * all, in which case this won't help either -- logged clearly as
     * "NODE_MATCH" so it's easy to tell apart from a real confirmed
     * click if this turns out not to work reliably in the field.
     */
    private void checkNodeBoundsMatch(AccessibilityEvent event) {
        if (lastSeenRestaurantName == null || offerShownAtMs == 0) {
            return;
        }
        if (System.currentTimeMillis() - offerShownAtMs < NODE_MATCH_MIN_DELAY_MS) {
            return; // too soon -- likely just the screen loading, not a real tap
        }
        refreshAcceptDeclineNodeBounds();
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) {
            return;
        }
        try {
            android.graphics.Rect eventBounds = new android.graphics.Rect();
            source.getBoundsInScreen(eventBounds);
            if (acceptNodeBounds != null && boundsRoughlyMatch(eventBounds, acceptNodeBounds)) {
                logDiagnostic("NODE_MATCH", "Event on Accept node bounds -- type="
                        + AccessibilityEvent.eventTypeToString(event.getEventType()));
                recordLastOfferOutcome(true);
                acceptNodeBounds = null;
                declineNodeBounds = null;
            } else if (declineNodeBounds != null && boundsRoughlyMatch(eventBounds, declineNodeBounds)) {
                logDiagnostic("NODE_MATCH", "Event on Decline node bounds -- type="
                        + AccessibilityEvent.eventTypeToString(event.getEventType()));
                recordLastOfferOutcome(false);
                acceptNodeBounds = null;
                declineNodeBounds = null;
            }
        } catch (RuntimeException e) {
            logDiagnostic("ERROR", "checkNodeBoundsMatch exception: " + android.util.Log.getStackTraceString(e));
        }
    }

    /**
     * True if every edge of `a` is within NODE_MATCH_BOUNDS_TOLERANCE_PX
     * of the corresponding edge of `b` -- a tolerant replacement for
     * Rect.equals() (see NODE_MATCH_BOUNDS_TOLERANCE_PX's own comment for
     * why byte-exact equality was too fragile). Still requires all four
     * edges to be close, not just an overlap, so this can't accidentally
     * match a different, nearby element.
     */
    private boolean boundsRoughlyMatch(android.graphics.Rect a, android.graphics.Rect b) {
        return Math.abs(a.left - b.left) <= NODE_MATCH_BOUNDS_TOLERANCE_PX
                && Math.abs(a.top - b.top) <= NODE_MATCH_BOUNDS_TOLERANCE_PX
                && Math.abs(a.right - b.right) <= NODE_MATCH_BOUNDS_TOLERANCE_PX
                && Math.abs(a.bottom - b.bottom) <= NODE_MATCH_BOUNDS_TOLERANCE_PX;
    }

    private void recordLastOfferOutcome(boolean accepted) {
        if (lastSeenRestaurantName == null) {
            return;
        }
        // Cancel any pending timeout for this offer -- a real tap just
        // won the race (see handleOfferResult's grace-period scheduling),
        // so the scheduled timeout must not ALSO fire later for an offer
        // that's already been correctly resolved as accepted/declined.
        if (pendingTimeoutRunnable != null) {
            timeoutHandler.removeCallbacks(pendingTimeoutRunnable);
            pendingTimeoutRunnable = null;
        }
        try {
            engine.callAttr("record_offer_outcome", lastSeenRestaurantName, lastSeenPayout,
                    lastSeenDistanceKm, lastSeenSmartScore, accepted, lastSeenComponentsJson,
                    false, lastSeenHourlyRate >= 0 ? (Double) lastSeenHourlyRate : null);
            logDiagnostic("OUTCOME", (accepted ? "Accepted: " : "Declined: ") + lastSeenRestaurantName
                    + " (score " + Math.round(lastSeenSmartScore) + ")");
            engine.callAttr("clear_pending_offer_recovery");
        } catch (RuntimeException e) { // covers PyException too -- it extends RuntimeException, so listing both is an illegal redundant multi-catch (confirmed by a real build error before)
            logDiagnostic("ERROR", "recordLastOfferOutcome exception: " + android.util.Log.getStackTraceString(e));
        }
        lastSeenRestaurantName = null;
    }

    /**
     * Toggles the live Smart Score badge between its compact view and
     * the full 6-factor breakdown -- called on every tap, and passed as
     * the NEXT tap's action too, so this can toggle back and forth
     * indefinitely rather than only working once or twice.
     */
    private void toggleSmartScoreBadge() {
        smartScoreBadgeExpanded = !smartScoreBadgeExpanded;
        logDiagnostic("BADGE", smartScoreBadgeExpanded ? "Expanded to full breakdown" : "Collapsed to compact view");
        OverlayHelper.showMessage(this,
                smartScoreBadgeExpanded ? smartScoreBadgeExpandedText : smartScoreBadgeCompactText,
                0, smartScoreBadgeBackground, this::toggleSmartScoreBadge);
    }

    /**
     * Recomputes and shows the correct status dot state -- called
     * whenever Dasher's foreground status changes. This is the one
     * component that can detect "Dasher is open right now" independent
     * of whether monitoring is active (TripForegroundService only runs
     * GPS-tick-driven logic while actively tracking), which is exactly
     * what the RED_FLASHING warning state needs to work correctly.
     */
    private void refreshStatusDot() {
        if (!TripForegroundService.isRunning) {
            if (isDasherForeground) {
                OverlayHelper.showStatusDot(this, OverlayHelper.DotState.RED_FLASHING);
            } else {
                OverlayHelper.clearStatusDot(this);
            }
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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (engine == null) {
            return;
        }
        String packageName = event.getPackageName() != null
                ? event.getPackageName().toString() : "";
        boolean isDasher = packageName.equals(DASHER_PACKAGE);

        try {
            // --- 0. Screen recording consent dialog auto-tap (docs/
            // screen_recording/PRD.md §23) --- deliberately runs BEFORE
            // the Dasher-only gate below: the consent dialog is never
            // Dasher's own window. See tryAutoTapConsentDialog()'s own
            // doc for why this is the one exception to this class's
            // "only ever reads Dasher's content" rule, and why it's
            // scoped by time rather than by package name.
            if ((event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                    && ScreenRecordingController.isExpectingConsentDialog()) {
                tryAutoTapConsentDialog(packageName);
            }

            // --- 1. Mode detection (see class doc) ---
            // Debounced: a single reading of a DIFFERENT package is not
            // enough to commit a real mode change on its own -- confirmed
            // via a real diagnostic log that this was oscillating every
            // 1-4 seconds, each flip triggering a spoken announcement,
            // even though the user hadn't actually switched apps that
            // rapidly. Requires the new state to persist for
            // MODE_CHANGE_DEBOUNCE_MS before it's treated as genuine and
            // actually committed -- filters out a brief, non-Dasher
            // accessibility event (system overlay, IME, etc.) without
            // meaningfully delaying detection of an actual, sustained
            // app switch.
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                long nowMs = System.currentTimeMillis();
                if (isDasher == isDasherForeground) {
                    // Matches the currently-committed state -- nothing
                    // pending to confirm, reset any stale candidate.
                    if (pendingIsDasher != null) {
                        // A candidate WAS being tracked and just got
                        // discarded because it flipped back before
                        // completing the debounce -- this IS the fix
                        // actively filtering noise, previously
                        // completely invisible in the log.
                        logDiagnostic("MODE_FILTER", "Discarded a brief, non-sustained mode candidate ("
                                + pendingIsDasher + ") before it could commit");
                    }
                    pendingIsDasher = null;
                } else if (pendingIsDasher == null || pendingIsDasher != isDasher) {
                    // A new candidate state -- start the debounce clock.
                    pendingIsDasher = isDasher;
                    pendingIsDasherSinceMs = nowMs;
                } else if (nowMs - pendingIsDasherSinceMs >= MODE_CHANGE_DEBOUNCE_MS) {
                    // Same candidate has now persisted long enough -- commit it for real.
                    if (!isDasher) {
                        // Previously the MODE log only showed "DASHER ->
                        // GENERAL", never WHICH app actually took the
                        // foreground -- couldn't distinguish a genuine app
                        // switch from a system UI element or Dasher's own
                        // internal sub-component reporting differently.
                        logDiagnostic("MODE", "Committing switch away from Dasher -- new foreground package: \""
                                + packageName + "\"");
                    }
                    engine.callAttr("set_dasher_foreground", isDasher);
                    isDasherForeground = isDasher;
                    refreshStatusDot();
                    pendingIsDasher = null;

                    // Auto-start monitoring the moment Dasher is genuinely
                    // (not just briefly) in the foreground, if it isn't
                    // already running -- directly closes the exact gap
                    // the flashing-red warning exists to flag in the
                    // first place ("Dasher's open but you're not being
                    // tracked"), rather than only ever warning about it.
                    // Uses the same proven cross-component trigger
                    // already used for Dash Paused auto-resume.
                    if (isDasher && !TripForegroundService.isRunning) {
                        attemptAutoStartMonitoring(TripForegroundService.ACTION_START_TRACKING, "AUTO_START",
                                "Dasher opened while monitoring was off -- started automatically",
                                "Dasher opened, foreground transition");
                    }
                }
                if (isDasher) {
                    lastDasherForegroundMs = nowMs;
                }
            }

            if (!isDasher) {
                return; // Never read content for any app other than Dasher.
            }

            // --- 1a-diagnostic. Broadened event-type logging while an
            // offer is pending -- pure discovery, not a fix. Confirmed
            // zero CLICK entries have ever appeared despite offers being
            // detected and (presumably) acted on in the real world; the
            // leading hypothesis is that Dasher's buttons don't generate
            // a standard TYPE_VIEW_CLICKED event (possible Jetpack
            // Compose UI). This logs EVERY event type while an offer is
            // pending -- not just clicks -- so the next real tap should
            // reveal what actually arrives, rather than continuing to
            // guess. Deliberately gated to only fire while an offer is
            // genuinely pending, to avoid logging every unrelated
            // accessibility event in the whole app.
            if (lastSeenRestaurantName != null) {
                logDiagnostic("EVENT_DEBUG", "type=" + AccessibilityEvent.eventTypeToString(event.getEventType())
                        + " class=" + event.getClassName() + " text=" + event.getText());
                checkNodeBoundsMatch(event);
            }

            // --- 1b. Accept/Decline outcome tracking ---
            // Previously the app scored every offer but never recorded
            // what you actually did about it -- a real, repeatedly-
            // flagged gap blocking shift stats, EPK, and any future
            // calibration of the Smart Score against real choices.
            //
            // HONESTY NOTE, confirmed via a real diagnostic log showing
            // zero OUTCOME entries across 3 real detected offers: this
            // exact-match assumption (equalsIgnoreCase "Accept"/"Decline")
            // was never confirmed against Dasher's real button text, the
            // same class of gap that turned out to be wrong for the
            // offer-notification parser. The raw-text log below (gated to
            // only fire while an offer is actually pending, to avoid
            // logging every unrelated tap in the app) is what will
            // actually confirm or correct this against reality.
            if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                List<CharSequence> clickedText = event.getText();
                if (clickedText != null) {
                    for (CharSequence text : clickedText) {
                        String clicked = text.toString().trim();
                        if (lastSeenRestaurantName != null && !clicked.isEmpty()) {
                            logDiagnostic("CLICK", "Tapped while offer pending (" + lastSeenRestaurantName
                                    + "): \"" + clicked + "\"");
                        }
                        if (clicked.equalsIgnoreCase("Accept")) {
                            recordLastOfferOutcome(true);
                        } else if (clicked.equalsIgnoreCase("Decline")) {
                            recordLastOfferOutcome(false);
                        } else if (clicked.equalsIgnoreCase("Yes, I want to unassign")) {
                            // docs/unassign_long_wait_tracking/PRD.md ss3.1 -- real button
                            // text confirmed from a real screenshot of DoorDash's own
                            // "You've been waiting a while, would you like to unassign
                            // from this order?" prompt. Deliberately NOT gated on
                            // lastSeenRestaurantName != null (that field is only for the
                            // brief offer-pending window and is already cleared by the
                            // time this screen can appear, well after acceptance) --
                            // record_pickup_unassigned_for_long_wait handles "nothing to
                            // record" safely on its own if self.pickup is None.
                            try {
                                String resultJson = engine.callAttr("record_pickup_unassigned_for_long_wait").toString();
                                logDiagnostic("OUTCOME", "Unassigned due to long wait: " + resultJson);
                            } catch (RuntimeException e) { // covers PyException too
                                logDiagnostic("ERROR", "record_pickup_unassigned_for_long_wait exception: "
                                        + android.util.Log.getStackTraceString(e));
                            }
                            // docs/store_wait_timer/PRD.md ss5 P4 -- an unassign means
                            // no "Confirm Pickup" is ever coming for this pickup, so any
                            // in-progress store-wait timer needs to be cancelled here
                            // too, not just left running/leaked.
                            cancelStoreWaitTimer();
                        } else if (clicked.equalsIgnoreCase("Arrived at Store")) {
                            startStoreWaitGracePeriod();
                        } else if (clicked.equalsIgnoreCase("Confirm Pickup")) {
                            stopStoreWaitTimer();
                        }
                    }
                }
                return; // click events don't need the screen-reading logic below
            }

            // --- 2. Offer / address reading (Dasher only) ---
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                return;
            }

            List<String> lines = new ArrayList<>();
            collectVisibleText(root, lines);
            if (lines.isEmpty()) {
                return;
            }

            String linesJson = new JSONArray(lines).toString();

            // --- 2a. Auto-pause/resume: saves battery and keeps driving-
            // efficiency stats from being skewed by time spent not
            // actually dashing. Only auto-RESUMES if WE were the ones who
            // auto-paused -- never overrides a deliberate manual "Stop
            // Monitoring" tap just because "Resume Dash" text happens to
            // appear on screen.
            //
            // REAL BUG FIX, confirmed via a real diagnostic log: the actual
            // "Dash Paused" screen shows BOTH "Dash Paused" (title) AND
            // "Resume dash" (button) text AT THE SAME TIME -- they are not
            // two separate screens. The screen also has a live countdown
            // timer, so it re-renders (firing a new accessibility event)
            // roughly once per second. The previous logic checked isPaused
            // and isResumed independently on every tick, which caused a
            // rapid oscillation: pause fires (tracking stops) -> next tick,
            // the pause condition no longer applies since tracking is
            // already stopped, so it falls through to the resume check,
            // which ALSO matches (since "Resume dash" is still on the same
            // screen) -> resume fires -> next tick, pause fires again --
            // repeating indefinitely for as long as the paused screen was
            // shown, observed cycling roughly once per second for over a
            // minute in real use.
            //
            // Fix: while the paused screen is showing AT ALL, always
            // return here -- never fall through to evaluate resume, no
            // matter what other text happens to co-occur on it. Resume is
            // only ever evaluated once isPaused is genuinely false (the
            // paused screen has actually gone away), and no longer depends
            // on is_dash_resumed_screen matching anything specific --
            // "we were auto-paused, and the paused screen is no longer
            // showing" is itself the resume signal.
            boolean isPaused = engine.callAttr("is_dash_paused_screen", linesJson).toBoolean();
            if (isPaused) {
                if (TripForegroundService.isRunning) {
                    Intent pauseIntent = new Intent(this, TripForegroundService.class);
                    pauseIntent.setAction(TripForegroundService.ACTION_STOP_TRACKING);
                    // Real diagnostic log, 2026-09-06: without this flag,
                    // stopTracking()'s own manual-stop feedback fallback
                    // fired here AND the natural GPS-driven completion path
                    // fired again 3 seconds later for the same trip once
                    // tracking resumed -- see EXTRA_AUTO_PAUSE_STOP's own
                    // comment in TripForegroundService.
                    pauseIntent.putExtra(TripForegroundService.EXTRA_AUTO_PAUSE_STOP, true);
                    startForegroundService(pauseIntent);
                    pausedByAutoDetection = true;
                    logDiagnostic("AUTO_PAUSE", "Dash Paused screen detected -- GPS tracking paused");
                    // Driver-reported real gap (docs/driver_backlog_2026_09_03/PRD.md
                    // ss4, #27): this detection was entirely silent -- it stopped
                    // GPS tracking and wrote a log line, but never spoke anything,
                    // so a driver relying on the voice announcements (the same
                    // mechanism as the mode-switch/smart-score speech elsewhere in
                    // this class) would never know a pause was auto-detected at all.
                    VoiceAnnouncer.speak("Dash paused. Monitoring stopped.");
                }
                return; // stay paused for as long as this screen shows, regardless of what else is on it
            }
            if (pausedByAutoDetection && !TripForegroundService.isRunning) {
                attemptAutoStartMonitoring(TripForegroundService.ACTION_START_TRACKING, "AUTO_PAUSE",
                        "Dash Paused screen no longer showing -- GPS tracking resumed",
                        "resuming after Dash Paused screen cleared");
                pausedByAutoDetection = false;
                return;
            }

            // drive_monitor.py checks is_offer_screen() itself and returns
            // {"is_offer_screen": false} harmlessly if this isn't the right
            // screen, so it's safe to call on every content-changed event.
            Double currentLat = TripForegroundService.hasValidLocation
                    ? TripForegroundService.lastKnownLat : null;
            Double currentLon = TripForegroundService.hasValidLocation
                    ? TripForegroundService.lastKnownLon : null;
            String resultJson = engine.callAttr("parse_offer_screen", linesJson,
                    currentLat, currentLon).toString();
            handleOfferResult(resultJson);

            // Real post-accept dropoff address extraction -- built from
            // two real screenshots (see DropoffScreenParser), closing the
            // single most-flagged gap in this whole project. Every
            // dropoff previously used placeholder (0.0, 0.0) coordinates
            // since nothing could read the real address after accepting.
            boolean isDropoff = engine.callAttr("is_dropoff_screen", linesJson).toBoolean();
            if (isDropoff) {
                handleDropoffScreen(linesJson);
            }

            // docs/zone_activity_log/PRD.md -- captures whatever Dasher's
            // own screen shows when NEITHER an offer nor a dropoff screen
            // matched (most likely the home/map screen), so a driver can
            // monitor zone activity without ever signing on to dash.
            // Reuses resultJson/isDropoff already computed above -- no
            // second screen-text read, no new getRootInActiveWindow()
            // call. record_zone_activity_snapshot throttles/caps itself
            // and returns {"recorded": false} harmlessly when it skips,
            // same "safe to call on every content-changed event" shape
            // as parse_offer_screen/is_dropoff_screen right above it.
            boolean isOfferScreen = false;
            try {
                isOfferScreen = new JSONObject(resultJson).optBoolean("is_offer_screen", false);
            } catch (JSONException ignored) {
                // Malformed JSON here would be a real bug elsewhere (parse_offer_screen
                // always returns a JSON object) -- fail safe by treating it as "not an
                // offer screen" rather than throwing, since this is a best-effort
                // capture, not a correctness-critical path.
            }
            if (!isOfferScreen && !isDropoff) {
                String zoneResultJson = engine.callAttr("record_zone_activity_snapshot",
                        linesJson, currentLat, currentLon).toString();
                try {
                    if (new JSONObject(zoneResultJson).optBoolean("recorded", false)) {
                        logDiagnostic("ZONE_ACTIVITY", "Snapshot captured");
                    }
                } catch (JSONException ignored) {
                    // Same reasoning as isOfferScreen's own guard above -- best-effort
                    // logging only, never worth failing the whole event over.
                }
            }
        } catch (RuntimeException e) { // covers PyException too -- confirmed via a real diagnostic log:
            // an offer was detected and scored successfully, then the whole
            // process died with no further log entries for 77 seconds before
            // restarting -- this path does real Java-side work (badge
            // display, TTS, JSON parsing, dropoff handling) that could throw
            // an uncaught RuntimeException, which "catch (PyException e)"
            // alone would NOT have caught, crashing the entire app process.
            logDiagnostic("ERROR", "onAccessibilityEvent exception: " + android.util.Log.getStackTraceString(e));
        }
    }

    private String lastDropoffAddressKey = null;
    // docs/dropoff_parse_failure_visibility/PRD.md -- distinguishes "no
    // usable address parsed" from "already handled this address" and
    // "not a dropoff screen at all," none of which shared a log line
    // before this fix. Only guards against re-logging on every single
    // content-changed tick while the same still-unparseable screen
    // stays open -- reset on the next successful parse (a different
    // delivery, or this same screen eventually rendering completely),
    // not on any "left the screen" event, since is_dropoff_screen going
    // false has no equivalent state-transition hook the way offers do
    // (see handleOfferResult).
    private boolean lastDropoffParseFailureLogged = false;

    /**
     * Parses the real "Deliver to X" screen, geocodes the full address
     * (street + suburb/state/postcode -- far more precise than the bare
     * restaurant name geocoding already used for pickups), and registers
     * it as a real stop once geocoding resolves. Deduped by address so
     * this doesn't re-geocode on every single content-changed tick while
     * the same screen stays open.
     */
    private void handleDropoffScreen(String linesJson) {
        try {
            JSONObject parsed = new JSONObject(engine.callAttr("parse_dropoff_screen", linesJson).toString());
            String fullAddress = parsed.isNull("full_address") ? null : parsed.optString("full_address", null);
            if (fullAddress == null) {
                // Driver-audit finding (2026-09-11): is_dropoff_screen
                // already confirmed this IS a real dropoff screen (the
                // caller only reaches here when that's true) -- so a
                // null full_address here specifically means the address
                // format on screen didn't match DropoffScreenParser's
                // known patterns (built from only two real screenshots,
                // per its own honesty note), not that there's nothing to
                // parse. Previously silent: this whole method just
                // returned, with no log line distinguishing "not a
                // dropoff screen" from "a dropoff screen we couldn't
                // read" -- the latter means arrival detection gets
                // nothing at all for this delivery, worth knowing about.
                if (!lastDropoffParseFailureLogged) {
                    lastDropoffParseFailureLogged = true;
                    logDiagnostic("DROPOFF", "Recognized a dropoff screen but could not parse a usable "
                            + "address from it -- possible unfamiliar address format (business name, "
                            + "apartment complex, etc.) or a DoorDash layout change. Arrival detection "
                            + "will not work for this delivery.");
                }
                return;
            }
            lastDropoffParseFailureLogged = false;
            if (fullAddress.equals(lastDropoffAddressKey)) {
                return;
            }
            lastDropoffAddressKey = fullAddress;
            logDiagnostic("DROPOFF", "Detected: " + fullAddress);
            // docs/dropoff_delivery_instruction_wiring/PRD.md -- this was
            // already parsed correctly by parse_dropoff_screen (confirmed
            // against real screenshots) and then silently discarded here:
            // only full_address was ever read from the same result. Real
            // driver report: "I've also not seen any customer instructions
            // appear as I near their address."
            final String deliveryInstruction = parsed.isNull("delivery_instruction")
                    ? null : parsed.optString("delivery_instruction", null);
            if (deliveryInstruction != null) {
                logDiagnostic("DROPOFF", "Delivery instruction: " + deliveryInstruction);
            }

            if (!GoogleApiHelper.hasApiKey(this)) {
                // Still register the stop with a placeholder so arrival
                // detection has SOMETHING to work with -- geocoding will
                // just never upgrade it to real coordinates without a key.
                engine.callAttr("add_stop_to_buffer", fullAddress, 0.0, 0.0, deliveryInstruction);
                return;
            }
            GoogleApiHelper.geocodeAddress(this, fullAddress, new GoogleApiHelper.GeocodeCallback() {
                @Override
                public void onResult(double lat, double lon) {
                    // CRITICAL: same guard as the pickup-geocoding path --
                    // this runs via Handler.post() on the main thread, and
                    // an uncaught exception here would crash the entire
                    // app process, not just this call.
                    try {
                        engine.callAttr("add_stop_to_buffer", fullAddress, lat, lon, deliveryInstruction);
                        // Same "start persisting real coordinates going
                        // forward" pattern as the pickup-side geocode
                        // callback above -- see record_dropoff_location.
                        engine.callAttr("record_dropoff_location", fullAddress, lat, lon);
                        logDiagnostic("GEOCODE", "Resolved dropoff " + fullAddress + " -> " + lat + "," + lon);
                    } catch (RuntimeException e) {
                        logDiagnostic("ERROR", "Dropoff geocode callback exception: "
                                + android.util.Log.getStackTraceString(e));
                    }
                }

                @Override
                public void onError(String message) {
                    // Defensive: arrival detection simply won't fire for
                    // this real delivery if geocoding never resolves --
                    // same limitation as pickup geocoding failing.
                    logDiagnostic("GEOCODE", "Dropoff failed: " + message);
                    // Premortem finding, fixed here (docs/road_warrior_icon/
                    // PRD.md ss4a, P3): previously log-only -- the
                    // RoadWarrior icon's "try again in a moment" toast had
                    // no way to know this address will NEVER resolve
                    // without a network/API fix, not just "not yet."
                    NavigationHelper.recordGeocodeFailure(DasherAccessibilityService.this, fullAddress, message);
                }
            });
        } catch (JSONException | RuntimeException e) { // covers PyException too -- calls
            // GoogleApiHelper directly (real Java-side work), not just Python/JSON.
            logDiagnostic("ERROR", "handleDropoffScreen exception: " + android.util.Log.getStackTraceString(e));
        }
    }

    /**
     * True only when THIS detector was the one that paused tracking (via
     * a detected "Dash Paused" screen) -- distinguishes that from a
     * deliberate manual "Stop Monitoring" tap, so a later "Resume Dash"
     * screen never auto-resumes tracking the user genuinely intended to
     * keep off.
     */
    private boolean pausedByAutoDetection = false;

    // Debounce for mode/foreground detection -- see the fix in
    // onAccessibilityEvent's mode-detection block for the full reasoning.
    // Confirmed via a real diagnostic log: mode was oscillating between
    // DASHER and GENERAL as often as every 1-4 seconds, each one
    // triggering a spoken announcement -- a brief, non-Dasher
    // accessibility event (a system overlay, IME, or similar) was being
    // treated as a genuine, sustained app switch instantly, rather than
    // requiring the new state to actually persist first.
    private static final long MODE_CHANGE_DEBOUNCE_MS = 2000;
    private Boolean pendingIsDasher = null;
    private long pendingIsDasherSinceMs = 0;

    /**
     * True whenever Dasher is the current foreground app -- kept as a
     * public static field (mirroring TripForegroundService.isRunning)
     * specifically so the status dot's flashing-red warning state
     * ("dashing without tracking") can be computed from EITHER component
     * without a Python round-trip, and works correctly even when
     * TripForegroundService itself isn't currently running (this
     * accessibility service operates independently of monitoring state).
     */
    public static volatile boolean isDasherForeground = false;
    // Honest approximation only, not a definitive process-alive check --
    // Android doesn't let one app query whether a DIFFERENT app's process
    // is currently alive in the background without a separate, heavier
    // "Usage Access" permission. This is the closest signal available
    // without that: how recently Dasher was actually confirmed in the
    // foreground. A very recent value suggests it was likely still warm;
    // a long gap makes a cold restart considerably more likely -- but
    // this can't prove either way with certainty.
    public static volatile long lastDasherForegroundMs = 0;

    /** Driver-audit finding (2026-09-11), docs/screen_recognition_canary/PRD.md
      * -- set the moment `parse_offer_screen` confirms `is_offer_screen
      * == true` for a real accessibility event, regardless of whether a
      * score was computable yet. Read by
      * AppNotificationListenerService's own delayed check to answer a
      * question nothing in this codebase could answer before: when a
      * notification independently confirms an offer arrived and Dasher
      * came to the foreground, did the screen-based parser ever actually
      * recognize the offer screen it should be looking at right now, or
      * has it silently stopped matching anything (the same "DoorDash
      * changed its UI and nothing noticed" risk flagged across several
      * parsers in this codebase, never previously given a general
      * detector). */
    public static volatile long lastOfferScreenConfirmedMs = 0;

    /**
     * Surfaces the Smart Score that drive_monitor.py already calculates on
     * every offer -- previously computed and then silently discarded. Shows
     * a color-coded floating badge (score + $/km + $/hr + restaurant wait +
     * traffic risk) that stays up as long as the offer screen is showing,
     * and speaks the score once per distinct offer (not on every
     * content-changed event, which would spam repeatedly while the screen
     * re-renders). Also registers pickup tracking once per offer so
     * restaurant wait time becomes real learned data over time (see
     * SmartScoreEngine.record_restaurant_wait in drive_monitor.py).
     */
    private void handleOfferResult(String resultJson) {
        try {
            JSONObject parsed = new JSONObject(resultJson);
            if (!parsed.optBoolean("is_offer_screen", false)) {
                OverlayHelper.clear(this);
                lastOfferKey = null;
                // Timeout detection: the offer screen just disappeared,
                // but lastSeenRestaurantName is still set -- meaning
                // handleOfferResult saw and scored an offer, yet neither
                // Accept nor Decline was ever tapped for it (see
                // TYPE_VIEW_CLICKED handling). Previously this made a
                // timed-out offer completely invisible: not accepted, not
                // declined, not recorded as anything at all.
                //
                // NOT committed immediately -- scheduled after
                // TIMEOUT_GRACE_PERIOD_MS instead, since a real Accept/
                // Decline tap's click event isn't guaranteed to arrive
                // before this "screen just disappeared" event does. A
                // snapshot of the current state is captured now (not
                // read again later), since lastSeenRestaurantName could
                // be cleared by a click that arrives during the grace
                // period. If recordLastOfferOutcome runs first, it
                // cancels this pending runnable and the real outcome
                // wins instead.
                if (lastSeenRestaurantName != null) {
                    final String snapshotRestaurantName = lastSeenRestaurantName;
                    final double snapshotPayout = lastSeenPayout;
                    final double snapshotDistanceKm = lastSeenDistanceKm;
                    final double snapshotSmartScore = lastSeenSmartScore;
                    final String snapshotComponentsJson = lastSeenComponentsJson;
                    final Double snapshotHourlyRate = lastSeenHourlyRate >= 0 ? (Double) lastSeenHourlyRate : null;
                    if (pendingTimeoutRunnable != null) {
                        timeoutHandler.removeCallbacks(pendingTimeoutRunnable);
                    }
                    pendingTimeoutRunnable = () -> {
                        try {
                            engine.callAttr("record_offer_timeout", snapshotRestaurantName,
                                    snapshotPayout, snapshotDistanceKm, snapshotSmartScore, snapshotComponentsJson,
                                    false, snapshotHourlyRate);
                            logDiagnostic("OUTCOME", "Timed out (no tap detected): " + snapshotRestaurantName);
                            engine.callAttr("clear_pending_offer_recovery");
                        } catch (RuntimeException e) { // covers PyException too
                            logDiagnostic("ERROR", "record_offer_timeout exception: "
                                    + android.util.Log.getStackTraceString(e));
                        }
                        // Only cleared HERE, once the timeout actually
                        // fires -- NOT when merely scheduled. Deliberately
                        // left set during the grace period itself so a
                        // click arriving in that window still finds a
                        // real offer to record against (see
                        // recordLastOfferOutcome, which cancels this
                        // runnable if it wins the race).
                        lastSeenRestaurantName = null;
                        pendingTimeoutRunnable = null;
                    };
                    timeoutHandler.postDelayed(pendingTimeoutRunnable, TIMEOUT_GRACE_PERIOD_MS);
                }
                return;
            }

            // docs/screen_recognition_canary/PRD.md -- set as soon as
            // is_offer_screen is confirmed true, regardless of whether a
            // score is computable yet (below). This is the "the
            // screen-based parser genuinely recognized an offer screen
            // just now" signal AppNotificationListenerService's own
            // delayed check cross-references against its independent
            // notification-based detection.
            lastOfferScreenConfirmedMs = System.currentTimeMillis();

            JSONObject score = parsed.optJSONObject("smart_score");
            if (score == null) {
                return; // Not enough data parsed yet to compute a score.
            }

            double finalScore = score.optDouble("final_score", 0);
            String label = score.optString("label", "");
            boolean isBatchOffer = parsed.optBoolean("is_batch_offer", false);

            // HONESTY NOTE (see parse_offer_screen's docstring): this is
            // detection only, not a real per-stop parse -- the payout/
            // distance/score above likely reflect only one leg of a
            // multi-stop order, so this is flagged as a warning rather
            // than presented as a confident, correct total.
            String batchWarning = isBatchOffer
                    ? "\n\u26A0 BATCH OFFER -- score may only reflect one stop" : "";

            // Proactive restaurant-history warning (idea #3 -- surfaced
            // directly rather than something you'd have to remember to
            // check in the Address Book yourself).
            String restaurantWarning = score.isNull("restaurant_warning") ? null
                    : score.optString("restaurant_warning", null);
            String warningLine = restaurantWarning != null ? "\n\u26A0 " + restaurantWarning : "";

            // Restored to the live badge per explicit request: $/km and
            // $/hr specifically -- everything else (deadhead, wait,
            // traffic, weather) still stays out of the live view, only
            // in the post-trip summary.
            double perKm = score.optDouble("base_rate_per_km", 0);
            double perHr = score.optDouble("hourly_rate", 0);

            String compactBadgeText = String.format(
                    "Smart Score: %.0f/100 - %s\n$%.2f/km   $%.2f/hr%s%s\n(tap for full breakdown)",
                    finalScore, label, perKm, perHr, warningLine, batchWarning);

            // Tap-to-expand: the full 6-factor breakdown, previously only
            // ever visible in the post-trip summary, now available live
            // without waiting for the delivery to finish. Built once here
            // (not recomputed on every tap) since the offer's components
            // don't change after being scored.
            JSONObject components = score.optJSONObject("components");
            StringBuilder expandedText = new StringBuilder(
                    String.format("Smart Score: %.0f/100 - %s\n\n", finalScore, label));
            if (components != null) {
                expandedText.append(String.format("Base rate: %.0f   Hourly: %.0f\n",
                        components.optDouble("base_score", 0), components.optDouble("hourly_score", 0)));
                expandedText.append(String.format("Deadhead: %.0f   Wait: %.0f\n",
                        components.optDouble("deadhead_score", 0), components.optDouble("wait_score", 0)));
                expandedText.append(String.format("Time-of-day: %.0f   Weather: %.0f\n\n",
                        components.optDouble("time_score", 0), components.optDouble("weather_score", 0)));
            }
            String verdict = score.optString("verdict_sentence", "");
            if (!verdict.isEmpty()) {
                expandedText.append(verdict).append("\n\n");
            }
            expandedText.append(String.format("$%.2f/km   $%.2f/hr%s%s\n(tap to collapse)",
                    perKm, perHr, warningLine, batchWarning));

            // Toggles between compact and expanded views on every tap,
            // indefinitely -- state lives in instance fields (not lambda
            // captures) specifically so the same toggle method can
            // reference itself as the next tap's action.
            smartScoreBadgeCompactText = compactBadgeText;
            smartScoreBadgeExpandedText = expandedText.toString();
            smartScoreBadgeBackground = backgroundForLabel(label);
            smartScoreBadgeExpanded = false;
            OverlayHelper.showMessage(this, smartScoreBadgeCompactText, 0, smartScoreBadgeBackground,
                    this::toggleSmartScoreBadge);

            String restaurantName = parsed.optString("restaurant_name", "");
            String offerKey = restaurantName + "|" + parsed.optDouble("payout", -1);
            if (!offerKey.equals(lastOfferKey)) {
                VoiceAnnouncer.speak(String.format("Smart score %d, %s. %.2f dollars per kilometer, "
                                + "%.2f dollars per hour", Math.round(finalScore), label, perKm, perHr)
                        + (isBatchOffer ? ". Warning: this looks like a batch offer, "
                                + "the score may only reflect one stop." : ""));
                HapticFeedback.vibrateForLabel(this, label);
                lastOfferKey = offerKey;
                logDiagnostic("OFFER", "Detected via screen: " + restaurantName
                        + ", $" + parsed.optDouble("payout", -1) + ", score " + Math.round(finalScore)
                        + (isBatchOffer ? " [BATCH OFFER]" : ""));
                // Remembered so a subsequent Accept/Decline tap (see
                // TYPE_VIEW_CLICKED handling in onAccessibilityEvent) can
                // be recorded against THIS specific offer.
                lastSeenRestaurantName = restaurantName;
                lastSeenPayout = parsed.optDouble("payout", -1);
                lastSeenDistanceKm = parsed.optDouble("distance_km", -1);
                lastSeenSmartScore = finalScore;
                JSONObject componentsObj = score.optJSONObject("components");
                lastSeenComponentsJson = componentsObj != null ? componentsObj.toString() : null;
                // docs/hotspot_or_home_routing/PRD.md -- the dollar $/hr
                // figure (perHr, already computed above for the live
                // badge), not the 0-100 sub-score already captured in
                // lastSeenComponentsJson. Never persisted anywhere before
                // this feature needed it.
                lastSeenHourlyRate = perHr;

                // CONFIRMED via a deliberate real test (two genuine
                // declines, zero click events captured across the whole
                // session): Dasher's Accept/Decline buttons very likely
                // don't generate any standard accessibility event on tap
                // at all. This scans for the actual nodes by their text
                // and records their screen bounds directly, rather than
                // continuing to wait for an event type that real evidence
                // suggests never arrives. See the bounds-matching check
                // in onAccessibilityEvent for how this gets used --
                // deliberately requires a real delay since detection
                // before treating any match as genuine, to avoid a false
                // trigger from accessibility focus simply landing on the
                // button as the screen first loads.
                scanAndRecordAcceptDeclineNodeBounds();
                offerShownAtMs = System.currentTimeMillis();

                // CONFIRMED via real evidence: scanAndRecordAcceptDeclineNodeBounds
                // found neither button, and every EVENT_DEBUG entry around
                // a real offer shows empty text -- something more
                // fundamental than "wrong button label" may be going on.
                // This reuses the EXACT extraction method already proven
                // working for offer detection itself (node.getText(), not
                // event.getText() or findAccessibilityNodeInfosByText())
                // to show everything actually visible, including
                // whatever the real Accept/Decline labels turn out to be
                // -- rather than continue guessing at another approach
                // blind.
                try {
                    AccessibilityNodeInfo dumpRoot = getRootInActiveWindow();
                    if (dumpRoot != null) {
                        List<String> allVisibleText = new ArrayList<>();
                        collectVisibleText(dumpRoot, allVisibleText);
                        logDiagnostic("FULL_TEXT_DUMP", "All visible text on offer screen: " + allVisibleText);
                    }
                } catch (RuntimeException e) {
                    logDiagnostic("ERROR", "FULL_TEXT_DUMP exception: " + android.util.Log.getStackTraceString(e));
                }

                // Durable persistence -- fixes a real, confirmed bug: the
                // in-memory grace-period mechanism can lose an offer's
                // outcome forever if the process crashes before it
                // resolves. This survives that crash; recovered on the
                // next engine startup if it turns out to have genuinely
                // expired in the meantime.
                Integer countdownSeconds = parsed.isNull("countdown_seconds")
                        ? null : parsed.optInt("countdown_seconds");
                try {
                    engine.callAttr("save_pending_offer_for_recovery", restaurantName,
                            lastSeenPayout, lastSeenDistanceKm, lastSeenSmartScore,
                            lastSeenComponentsJson, countdownSeconds);
                } catch (RuntimeException e) { // covers PyException too
                    logDiagnostic("ERROR", "save_pending_offer_for_recovery exception: "
                            + android.util.Log.getStackTraceString(e));
                }
                // Placeholder lat/lon until real geocoding resolves below --
                // this call happens immediately so pickup wait tracking etc.
                // start right away even if geocoding is slow, fails, or no
                // API key is configured. The claimed distance IS real
                // (straight from the offer screen), stored so it can be
                // checked against actual measured distance once this
                // delivery completes.
                // docs/hourly_rate_actual_vs_estimated/PRD.md ss4.B -- payout
                // appended as the LAST arg (address=null threaded through
                // explicitly since Chaquopy matches by position, not name);
                // lastSeenPayout carries the same -1 "unknown" sentinel this
                // codebase already uses elsewhere (see the
                // save_pending_offer_for_recovery call above) -- the Python
                // side guards against it rather than needing a null here.
                engine.callAttr("add_pickup", restaurantName, 0.0, 0.0,
                        parsed.optDouble("distance_km", 0.0), score.toString(),
                        parsed.isNull("deadline_text") ? null : parsed.optString("deadline_text", null),
                        null, lastSeenPayout);
                geocodePickupAndCheckTraffic(restaurantName);
                checkCurrentWeather();
            }
        } catch (JSONException | RuntimeException e) { // covers PyException too -- this method calls
            // OverlayHelper.showMessage, VoiceAnnouncer.speak, HapticFeedback, and kicks off
            // geocoding/weather requests directly (real Java-side work, not just Python/JSON),
            // so needs to catch more than JSONException|PyException alone. This is the method
            // that logs the OFFER entry -- a real diagnostic log showed an offer detected and
            // scored successfully, then the whole process died 77 seconds later with zero
            // entries in between, strongly suggesting an uncaught error right here went
            // uncaught and took the entire app process down with it.
            logDiagnostic("ERROR", "handleOfferResult exception: " + android.util.Log.getStackTraceString(e));
        }
    }

    /**
     * Real Google Maps integration: geocodes the restaurant name into
     * actual coordinates (replacing the (0.0, 0.0) placeholder), then --
     * if a real current GPS position is available -- checks live traffic
     * for the route there right now. Both calls are async; nothing here
     * blocks this accessibility service's main thread on a network call.
     * No-ops silently if no Google Maps API key is configured.
     */
    private void geocodePickupAndCheckTraffic(String restaurantName) {
        if (!GoogleApiHelper.hasApiKey(this) || restaurantName.isEmpty()) {
            return;
        }
        GoogleApiHelper.geocodeAddressWithFormatted(this, restaurantName, new GoogleApiHelper.GeocodeWithAddressCallback() {
            @Override
            public void onResult(double lat, double lon, String formattedAddress) {
                // CRITICAL: this runs via Handler.post() on the main thread.
                // An uncaught exception here crashes the ENTIRE app process,
                // not just this call -- silently killing TripForegroundService
                // and every other component along with it. This was a real,
                // serious bug: monitoring appeared to "stop as soon as an
                // offer arrived" because offer-handling is exactly when this
                // callback fires.
                try {
                    engine.callAttr("update_pickup_coordinates", lat, lon);
                    logDiagnostic("GEOCODE", "Resolved " + restaurantName + " -> " + lat + "," + lon);
                    // Real street address for the pickup (not just the
                    // restaurant name) -- previously never captured at all.
                    // Guarded separately from the block below so a failure
                    // here can never take down coordinate resolution/traffic
                    // checking, which this whole delivery's tracking depends
                    // on far more than the address text does.
                    if (formattedAddress != null && !formattedAddress.isEmpty()) {
                        try {
                            engine.callAttr("update_pickup_address", formattedAddress);
                        } catch (RuntimeException e) { // covers PyException too
                            logDiagnostic("ERROR", "update_pickup_address exception: "
                                    + android.util.Log.getStackTraceString(e));
                        }
                    }
                    // Starts building real sweet-spot history going
                    // forward -- see record_pickup_location's own
                    // reasoning for why this can't be backfilled.
                    try {
                        engine.callAttr("record_pickup_location", restaurantName, lat, lon);
                    } catch (RuntimeException e) { // covers PyException too
                        logDiagnostic("ERROR", "record_pickup_location exception: "
                                + android.util.Log.getStackTraceString(e));
                    }

                    if (!TripForegroundService.hasValidLocation) {
                        // Previously silent -- a real diagnostic log
                        // investigation couldn't tell whether this path was
                        // ever reached at all, or whether something failed
                        // after it. Now it's explicit either way.
                        logDiagnostic("TRAFFIC", "Skipped -- no current GPS fix yet this session");
                        return;
                    }
                    GoogleApiHelper.getTrafficDelayRatio(DasherAccessibilityService.this,
                            TripForegroundService.lastKnownLat, TripForegroundService.lastKnownLon,
                            lat, lon,
                            new GoogleApiHelper.TrafficCallback() {
                                @Override
                                public void onResult(double trafficDelayRatio, int durationInTrafficSeconds,
                                                      int typicalDurationSeconds) {
                                    try {
                                        engine.callAttr("record_live_traffic_delay", trafficDelayRatio);
                                        logDiagnostic("TRAFFIC", "Delay ratio " + trafficDelayRatio + " for " + restaurantName);
                                    } catch (RuntimeException e) { // RuntimeException alone also catches PyException (Chaquopy PyException extends RuntimeException -- Java disallows both in one multi-catch since one is a subclass of the other)
                                        // Same critical guard as above.
                                        logDiagnostic("ERROR", "Traffic callback exception: " + android.util.Log.getStackTraceString(e));
                                    }
                                }

                                @Override
                                public void onError(String message) {
                                    // Defensive: live traffic is a nice-to-have:
                                    // the score already fell back to the
                                    // personal-history/generic proxy, so a
                                    // failed query here just means it stays
                                    // that way for this offer.
                                    logDiagnostic("TRAFFIC", "Query failed: " + message + " (" + getNetworkInfo() + ")");
                                }
                            });
                } catch (RuntimeException e) { // RuntimeException alone also catches PyException (Chaquopy PyException extends RuntimeException -- Java disallows both in one multi-catch since one is a subclass of the other)
                    // Same critical guard as above -- never let a geocoding
                    // result crash the whole app.
                    logDiagnostic("ERROR", "Geocode callback exception: " + android.util.Log.getStackTraceString(e));
                }
            }

            @Override
            public void onError(String message) {
                // Defensive: pickup already has placeholder coordinates
                // from add_pickup, and wait/deadhead learning simply
                // won't fire for this specific delivery if geocoding
                // never resolves.
                logDiagnostic("GEOCODE", "Failed: " + message + " (" + getNetworkInfo() + ")");
                // Premortem finding, fixed here (docs/road_warrior_icon/
                // PRD.md ss4a, P3): same fix as the dropoff onError above --
                // the icon's toast can now tell "this geocode already
                // failed" apart from "still resolving."
                NavigationHelper.recordGeocodeFailure(DasherAccessibilityService.this, restaurantName, message);
            }
        });
    }

    /**
     * Distinguishes "there's a bug in the geocode/traffic/weather calls"
     * from "you had no internet connectivity at that moment" -- a failed
     * query previously just said "failed", with no way to tell which of
     * those two very different causes was responsible.
     */
    private String getNetworkInfo() {
        android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) {
            return "network=unknown";
        }
        android.net.Network network = cm.getActiveNetwork();
        if (network == null) {
            return "network=none";
        }
        android.net.NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
        boolean hasInternet = capabilities != null
                && capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        return "network=" + (hasInternet ? "connected" : "no internet");
    }

    private long lastWeatherCheckMs = 0;
    private static final long WEATHER_CHECK_COOLDOWN_MS = 10 * 60 * 1000; // 10 min

    /**
     * Fetches real current weather for your current GPS position and
     * feeds it into the Smart Score's weather factor. Cooldown-limited --
     * conditions don't meaningfully change minute to minute, so this
     * doesn't re-fetch on every single offer.
     */
    private void checkCurrentWeather() {
        long now = System.currentTimeMillis();
        if (now - lastWeatherCheckMs < WEATHER_CHECK_COOLDOWN_MS) {
            return; // frequent, expected -- deliberately not logged to avoid noise
        }
        if (!TripForegroundService.hasValidLocation) {
            // Previously silent, same gap as the traffic check above -- now
            // explicit so a future log can confirm whether this path was
            // reached at all.
            logDiagnostic("WEATHER", "Skipped -- no current GPS fix yet this session");
            return;
        }
        lastWeatherCheckMs = now;
        WeatherHelper.getCurrentWeather(
                TripForegroundService.lastKnownLat, TripForegroundService.lastKnownLon,
                new WeatherHelper.WeatherCallback() {
                    @Override
                    public void onResult(double precipitationMm, double windSpeedKmh, double temperatureC) {
                        // CRITICAL: same guard as geocodePickupAndCheckTraffic
                        // -- this runs via Handler.post() on the main thread,
                        // and an uncaught exception here would crash the
                        // entire app process, not just this call.
                        try {
                            engine.callAttr("record_live_weather", precipitationMm, windSpeedKmh, temperatureC);
                            logDiagnostic("WEATHER", "precip=" + precipitationMm + "mm wind=" + windSpeedKmh + "km/h");
                        } catch (RuntimeException e) { // RuntimeException alone also catches PyException (Chaquopy PyException extends RuntimeException -- Java disallows both in one multi-catch since one is a subclass of the other)
                            // swallow -- weather is a nice-to-have
                            logDiagnostic("ERROR", "Weather callback exception: " + android.util.Log.getStackTraceString(e));
                        }
                    }

                    @Override
                    public void onError(String message) {
                        // Defensive: weather is a nice-to-have -- the score
                        // already defaults to "assumed fine" when no live
                        // reading exists, so a failed query just means it
                        // stays that way.
                        logDiagnostic("WEATHER", "Query failed: " + message + " (" + getNetworkInfo() + ")");
                    }
                });
    }

    /** Set as a side effect of findConsentDialogButton() -- whichever of
      * the two candidates it returns, this says which one, so the caller
      * knows whether a further dialog step (mode picker -> confirm) is
      * still expected before disarming the auto-tap window. */
    private AccessibilityNodeInfo consentDialogEntireScreenMatch;
    private AccessibilityNodeInfo consentDialogAffirmativeMatch;

    /**
     * docs/screen_recording/PRD.md §23 -- driver-requested: re-granting
     * screen-recording consent after it's lost should need zero manual
     * interaction. Auto-taps the real OS MediaProjection consent dialog
     * THIS app itself just triggered (see
     * ScreenRecordingController.armConsentDialogAutoTap() /
     * PermissionsActivity.requestScreenRecordingConsent()) -- the dialog
     * still genuinely appears and still requires a real tap, Android
     * gives no API to skip that; this just performs that tap on the
     * driver's behalf instead of leaving it to them.
     *
     * PRIVACY NOTE: this is the one deliberate exception to this class's
     * own "only ever reads Dasher's content" rule (see the accessibility
     * service's XML config comment, now updated to match). It is scoped
     * by TIME, not by package name -- isExpectingConsentDialog() is only
     * ever true for a few seconds immediately after this app's own call
     * to createScreenCaptureIntent(), so there's no window where this
     * could act on some unrelated app's dialog. A package check was
     * deliberately NOT used as the primary guard: the dialog's actual
     * owning package varies by Android version/OEM skin (unconfirmed --
     * no real device available in this environment to check), and
     * getting that wrong would silently disable this whole feature.
     * Button text is inspected here ONLY, never logged in full and never
     * fed into collectVisibleText's general Dasher-content path.
     *
     * HONEST LIMIT: Android 14+ can show a two-step flow (pick "Entire
     * screen" vs "A single app", THEN a "Start now" confirm) instead of
     * one dialog -- this handles both by preferring an "entire screen"
     * option when present, otherwise a "start now"/"allow"/"ok"
     * affirmative button, and disarms the window only after a terminal
     * (non-"entire screen") tap. Never taps anything matching a
     * cancel/deny/negative label. Best-effort: if the real dialog's
     * wording doesn't match any of these (a different Android version or
     * OEM skin), this silently does nothing and the tappable notification
     * (§21) remains the fallback -- not a guarantee, since there's no
     * device here to confirm the real button text against.
     */
    private void tryAutoTapConsentDialog(String packageName) {
        if (packageName.isEmpty() || packageName.equals(DASHER_PACKAGE) || packageName.equals(getPackageName())) {
            return; // this app's own Setup screen, or Dasher -- never the system dialog
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        consentDialogEntireScreenMatch = null;
        consentDialogAffirmativeMatch = null;
        collectConsentDialogButtons(root);
        AccessibilityNodeInfo target = consentDialogEntireScreenMatch != null
                ? consentDialogEntireScreenMatch : consentDialogAffirmativeMatch;
        if (target != null) {
            boolean isTerminal = (target == consentDialogAffirmativeMatch);
            boolean clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            logDiagnostic("SCREEN_RECORDING", "Auto-tap: clicked \"" + target.getText()
                    + "\" in package \"" + packageName + "\" (clicked=" + clicked
                    + ", terminal=" + isTerminal + ")");
            if (isTerminal) {
                ScreenRecordingController.disarmConsentDialogAutoTap();
            }
        }
    }

    private void collectConsentDialogButtons(AccessibilityNodeInfo node) {
        if (node == null) {
            return;
        }
        CharSequence text = node.getText();
        String lower = text != null ? text.toString().trim().toLowerCase(java.util.Locale.US) : "";
        if (node.isClickable() && !lower.isEmpty()
                && !lower.contains("cancel") && !lower.contains("deny")
                && !lower.contains("don't") && !lower.contains("dont") && !lower.contains("no thanks")) {
            if (lower.contains("entire screen")) {
                consentDialogEntireScreenMatch = node;
            } else if (lower.contains("start now") || lower.equals("allow") || lower.equals("ok")) {
                consentDialogAffirmativeMatch = node;
            }
        }
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            collectConsentDialogButtons(node.getChild(i));
        }
    }

    /**
     * Wrapper so a logging call itself can never crash the app (same
     * defensive pattern as TripForegroundService's version). Falls back
     * to FallbackLogger if the engine isn't ready yet.
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

    /** See OverlayHelper.backgroundForScoreLabel -- centralized there so every screen that shows this stays in sync. */
    private android.graphics.drawable.Drawable backgroundForLabel(String label) {
        return OverlayHelper.backgroundForScoreLabel(this, label);
    }

    /**
     * Recursively collects every non-empty text node's text, in tree order
     * (roughly top-to-bottom for typical stacked layouts like the offer
     * bottom sheet). This is what OfferScreenParser's line-adjacency rules
     * (e.g. "the line after Pickup is the restaurant name") rely on.
     */
    private void collectVisibleText(AccessibilityNodeInfo node, List<String> out) {
        if (node == null) {
            return;
        }
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            out.add(text.toString());
        }
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            collectVisibleText(child, out);
            if (child != null) {
                child.recycle();
            }
        }
    }

    @Override
    public void onInterrupt() {
        foregroundCheckHandler.removeCallbacks(foregroundCheckRunnable);
    }

    /**
     * Real, confirmed gap (feature-audit finding, not a driver report):
     * foregroundCheckRunnable (see onServiceConnected above) reposts
     * itself forever via a Handler on the main Looper, and onInterrupt
     * above was the ONLY place ever cancelling it -- but onInterrupt is
     * Android's "stop giving feedback right now" signal, unrelated to
     * the service actually being unbound, and isn't reliably called on
     * every real disable path. onUnbind is the actual callback Android
     * invokes when the driver disables this service under Settings ->
     * Accessibility (or the system force-unbinds it) -- without this
     * override, that self-repost loop kept running every 20s forever
     * (the app process stays alive via TripForegroundService), each
     * firing calling checkCurrentForegroundWindow -> getWindows(), an
     * AccessibilityService-only API that throws IllegalStateException
     * once disconnected -- caught and logged, so never a crash, but an
     * indefinite diagnostic-log spam loop and a leaked service instance
     * (the Runnable is a non-static inner class holding an implicit
     * reference to it) for the remaining life of the process.
     * onDestroy is overridden too as a second safety net for whichever
     * teardown path actually fires on a given OS/OEM -- removeCallbacks
     * on an already-empty queue is a harmless no-op either way.
     *
     * Fresh scouting-pass finding (2026-09-12, docs/
     * accessibility_service_unbind_cleanup/PRD.md's own direct follow-up):
     * storeWaitTimerTickRunnable (see startStoreWaitGracePeriod above) is
     * the exact same shape of self-reposting Handler loop as
     * foregroundCheckRunnable -- reposts itself every
     * STORE_WAIT_TIMER_TICK_MS (1s) once a driver's store-wait timer
     * becomes visible -- and had the identical gap: nothing cancelled it
     * on real teardown. removeStoreWaitTimerCallbacks() (NOT the broader
     * cancelStoreWaitTimer(), which also wipes the persisted arrival
     * timestamp resumeStoreWaitTimerIfPending needs -- see that method's
     * own doc) is called here too.
     */
    @Override
    public boolean onUnbind(Intent intent) {
        foregroundCheckHandler.removeCallbacks(foregroundCheckRunnable);
        removeStoreWaitTimerCallbacks();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        foregroundCheckHandler.removeCallbacks(foregroundCheckRunnable);
        removeStoreWaitTimerCallbacks();
        super.onDestroy();
    }
}
