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

    // Tap-to-expand state for the live Smart Score badge -- lives in
    // fields (not lambda captures) so toggleSmartScoreBadge can
    // reference itself indefinitely as the next tap's action.
    private String smartScoreBadgeCompactText = "";
    private String smartScoreBadgeExpandedText = "";
    private int smartScoreBadgeColor = 0;
    private boolean smartScoreBadgeExpanded = false;
    private double lastSeenPayout = -1;
    private double lastSeenDistanceKm = -1;
    private double lastSeenSmartScore = -1;
    private String lastSeenComponentsJson = null;

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

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        engine = PythonBridge.getEngine(this);
        checkCurrentForegroundWindow();
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
    private void scanAndRecordAcceptDeclineNodeBounds() {
        acceptNodeBounds = null;
        declineNodeBounds = null;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        try {
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText("Accept")) {
                AccessibilityNodeInfo clickable = nearestClickableAncestor(node);
                if (clickable != null && acceptNodeBounds == null) {
                    android.graphics.Rect bounds = new android.graphics.Rect();
                    clickable.getBoundsInScreen(bounds);
                    acceptNodeBounds = bounds;
                }
            }
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText("Decline")) {
                AccessibilityNodeInfo clickable = nearestClickableAncestor(node);
                if (clickable != null && declineNodeBounds == null) {
                    android.graphics.Rect bounds = new android.graphics.Rect();
                    clickable.getBoundsInScreen(bounds);
                    declineNodeBounds = bounds;
                }
            }
            logDiagnostic("NODE_SCAN", "Accept node found=" + (acceptNodeBounds != null)
                    + ", Decline node found=" + (declineNodeBounds != null));
        } catch (RuntimeException e) {
            logDiagnostic("ERROR", "scanAndRecordAcceptDeclineNodeBounds exception: "
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
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) {
            return;
        }
        try {
            android.graphics.Rect eventBounds = new android.graphics.Rect();
            source.getBoundsInScreen(eventBounds);
            if (acceptNodeBounds != null && eventBounds.equals(acceptNodeBounds)) {
                logDiagnostic("NODE_MATCH", "Event on Accept node bounds -- type="
                        + AccessibilityEvent.eventTypeToString(event.getEventType()));
                recordLastOfferOutcome(true);
                acceptNodeBounds = null;
                declineNodeBounds = null;
            } else if (declineNodeBounds != null && eventBounds.equals(declineNodeBounds)) {
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
                    lastSeenDistanceKm, lastSeenSmartScore, accepted, lastSeenComponentsJson);
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
                0, smartScoreBadgeColor, this::toggleSmartScoreBadge);
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
                    startForegroundService(pauseIntent);
                    pausedByAutoDetection = true;
                    logDiagnostic("AUTO_PAUSE", "Dash Paused screen detected -- GPS tracking paused");
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
            if (fullAddress == null || fullAddress.equals(lastDropoffAddressKey)) {
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
                    if (pendingTimeoutRunnable != null) {
                        timeoutHandler.removeCallbacks(pendingTimeoutRunnable);
                    }
                    pendingTimeoutRunnable = () -> {
                        try {
                            engine.callAttr("record_offer_timeout", snapshotRestaurantName,
                                    snapshotPayout, snapshotDistanceKm, snapshotSmartScore, snapshotComponentsJson);
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
            smartScoreBadgeColor = colorForLabel(label);
            smartScoreBadgeExpanded = false;
            OverlayHelper.showMessage(this, smartScoreBadgeCompactText, 0, smartScoreBadgeColor,
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
                engine.callAttr("add_pickup", restaurantName, 0.0, 0.0,
                        parsed.optDouble("distance_km", 0.0), score.toString(),
                        parsed.isNull("deadline_text") ? null : parsed.optString("deadline_text", null));
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

    /** Green/amber/red matching the existing (previously unused) colors.xml scheme. */
    private int colorForLabel(String label) {
        switch (label) {
            case "Excellent":
                return android.graphics.Color.parseColor("#CC1B5E20"); // deep green
            case "Good":
                return android.graphics.Color.parseColor("#CC43A047"); // lighter green
            case "Fair":
                return android.graphics.Color.parseColor("#CCF9A825"); // amber
            default:
                return android.graphics.Color.parseColor("#CCC62828"); // red
        }
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
}
