package com.drivingefficiency.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private static final long STATUS_POLL_INTERVAL_MS = 3000;

    private TextView statusText;
    private Button pickupNoteButton;
    private String lastKnownPickupRestaurant = null;
    private PyObject engine;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusPoller = new Runnable() {
        @Override
        public void run() {
            updateStatusText();
            statusHandler.postDelayed(this, STATUS_POLL_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_main);

            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(this));
            }
            engine = PythonBridge.getEngine(this);
            VoiceAnnouncer.init(this);
            attemptTrustedContactsAutoRecovery();
            maybeShowScreenRecordingDefaultPrompt();

            // Reached via TripDetailActivity's "Rate This Delivery"
            // button (docs/trip_history_redesign/PRD.md ss3.4) -- shows
            // the existing, already-working feedback dialog immediately.
            // Previously reached directly from the "Rate this delivery"
            // notification/BAL launch itself
            // (TripForegroundService.notifyRateThisDelivery), before
            // TripDetailActivity existed to show the trip's full detail
            // first; this handling is kept, not deleted, since it's
            // still the real entry point, just one hop later now.
            int autoShowFeedbackTripId = getIntent().getIntExtra("auto_show_feedback_trip_id", -1);
            if (autoShowFeedbackTripId >= 0) {
                showFeedbackDialog(autoShowFeedbackTripId);
            }

            // Bootstrap: start the always-on service in idle mode (red dot,
            // "Not monitoring" notification) as soon as the app opens, but
            // ONLY if location permission is already granted. This service is
            // declared with foregroundServiceType="location" in the manifest,
            // and on Android 14+, calling startForeground() for a location-type
            // service WITHOUT that permission already granted throws a
            // SecurityException immediately -- there is no way to start it
            // "empty-handed" and grant permission later. So on a fresh install
            // (before "Start Monitoring" has ever been tapped and permission
            // granted), the dot simply won't appear yet -- it starts showing
            // from the first time you grant location permission onward, and
            // then persists across app opens/closes after that.
            if (hasForegroundLocationPermission()) {
                startForegroundService(new Intent(this, TripForegroundService.class));
            }

            statusText = findViewById(R.id.statusText);
            Button startButton = findViewById(R.id.startButton);
            Button stopButton = findViewById(R.id.stopButton);
            Button quitCompletelyButton = findViewById(R.id.quitCompletelyButton);
            Button openRoadWarriorButton = findViewById(R.id.openRoadWarriorButton);
            pickupNoteButton = findViewById(R.id.pickupNoteButton);
            pickupNoteButton.setOnClickListener(v -> showPickupNoteDialog());
            Button tripHistoryNavButton = findViewById(R.id.tripHistoryNavButton);
            Button permissionsNavButton = findViewById(R.id.permissionsNavButton);
            Button dataManagementNavButton = findViewById(R.id.dataManagementNavButton);
            Button diagnosticsNavButton = findViewById(R.id.diagnosticsNavButton);
            Button developerTestingNavButton = findViewById(R.id.developerTestingNavButton);
            Button tutorialNavButton = findViewById(R.id.tutorialNavButton);

            startButton.setOnClickListener(v -> {
                logDiagnostic("BUTTON", "Start Monitoring tapped");
                startMonitoringFlow();
            });

            stopButton.setOnClickListener(v -> {
                logDiagnostic("BUTTON", "Stop Monitoring tapped");
                // Sends ACTION_STOP_TRACKING rather than stopping the service
                // outright -- the service keeps running so the status dot
                // (now solid red) and "Not monitoring" notification stay
                // visible, rather than disappearing entirely.
                Intent serviceIntent = new Intent(this, TripForegroundService.class);
                serviceIntent.setAction(TripForegroundService.ACTION_STOP_TRACKING);
                startForegroundService(serviceIntent);
                updateStatusText();
                // Since the app minimizes automatically when monitoring starts
                // (see actuallyStartMonitoring), stopping is usually the first
                // moment you're looking at this screen again after a shift --
                // show the trip summary right away instead of an empty screen.
                // Driver-requested (2026-09-14): review this shift's own
                // deliveries needing a rating FIRST -- see
                // maybeReviewDeliveryRatingsThenReviewDeclinedOffers's own
                // doc -- then declined/no-response offers (2026-09-13), then
                // finally the trip summary.
                maybeReviewDeliveryRatingsThenReviewDeclinedOffers();
            });

            // Genuine "fully off" -- no notification, no badge, nothing.
            // Distinct from Stop Monitoring, which deliberately keeps the
            // service alive so the idle status stays visible.
            quitCompletelyButton.setOnClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("Quit App Completely")
                        .setMessage("This fully shuts down monitoring -- no notification, no "
                                + "on-screen badge, nothing running in the background. You'll "
                                + "need to reopen the app and tap Start Monitoring again "
                                + "afterward. Continue?")
                        .setPositiveButton("Quit Completely", (dialog, which) -> {
                            logDiagnostic("BUTTON", "Quit App Completely confirmed");
                            Intent serviceIntent = new Intent(this, TripForegroundService.class);
                            serviceIntent.setAction(TripForegroundService.ACTION_QUIT_COMPLETELY);
                            startService(serviceIntent);
                            updateStatusText();
                            Toast.makeText(this, "Fully stopped.", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });

            // Requirement change (2026-08-30, docs/road_warrior_icon/PRD.md):
            // copies the most recently added delivery address to the
            // clipboard instead of auto-launching RoadWarrior.
            openRoadWarriorButton.setOnClickListener(v -> copyMostRecentStopAddress());

            // Each of these opens a dedicated screen -- previously this
            // activity had 23 buttons stacked in one long scroll with no
            // grouping at all. Reorganized into 6 purpose-built screens plus
            // the small set of controls actually needed constantly (above).
            tripHistoryNavButton.setOnClickListener(v ->
                    startActivity(new Intent(this, TripHistoryActivity.class)));
            permissionsNavButton.setOnClickListener(v ->
                    startActivity(new Intent(this, PermissionsActivity.class)));
            dataManagementNavButton.setOnClickListener(v ->
                    startActivity(new Intent(this, DataManagementActivity.class)));
            diagnosticsNavButton.setOnClickListener(v ->
                    startActivity(new Intent(this, DiagnosticsActivity.class)));
            developerTestingNavButton.setOnClickListener(v ->
                    startActivity(new Intent(this, DeveloperTestingActivity.class)));
            // docs/tutorial_mode/PRD.md (driver backlog #29) -- driver-
            // facing, unlike Developer Testing above (a hidden dev tool,
            // wrong audience for a teaching walkthrough).
            tutorialNavButton.setOnClickListener(v ->
                    startActivity(new Intent(this, TutorialActivity.class)));

            updateStatusText();
            maybeShowOemAutostartNudge();
        }

    /**
     * Real gap, confirmed by two separate real diagnostic logs (823
     * accessibility reconnects in one 2.5-day session; 17 app crashes in
     * another, both root-caused to OPPO/ColorOS aggressive background
     * management): PermissionsActivity's existing OEM guidance dialog
     * (docs/watchdog_reliability/PRD.md's own §1 finding 6) only ever
     * fires REACTIVELY -- when accessibility happens to already be off
     * AND the driver happens to visit that screen. A driver who hasn't
     * had it revoked yet this session gets no warning at all before the
     * first blackout, even on a phone this app already knows (via
     * OemBackgroundHelper.isKnownAggressiveOem()) is a documented
     * offender. Shown once ever (not on every launch) via a SharedPreferences
     * flag, right here at the app's one guaranteed entry point, so a
     * known-aggressive-OEM driver is prompted to fix autostart/protected-
     * apps settings BEFORE relying on monitoring for a real shift, not
     * only after it's already failed once.
     *
     * Reuses the exact same dialog PermissionsActivity's button already
     * shows (OemBackgroundHelper.showAutostartGuidanceDialog) -- same
     * honest limitation as that existing mitigation: this cannot confirm
     * the driver actually completes the OEM-side toggle (no cross-vendor
     * query API exists), and does not itself prevent an OS/OEM kill; it
     * only gets the existing, already-correct guidance in front of the
     * driver earlier.
     */
    private void maybeShowOemAutostartNudge() {
        if (!OemBackgroundHelper.isKnownAggressiveOem()) {
            return;
        }
        android.content.SharedPreferences prefs = getSharedPreferences("dasher_monitor_prefs", MODE_PRIVATE);
        if (prefs.getBoolean("oem_autostart_nudge_shown", false)) {
            return;
        }
        prefs.edit().putBoolean("oem_autostart_nudge_shown", true).apply();
        OemBackgroundHelper.showAutostartGuidanceDialog(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        statusHandler.post(statusPoller);
        if (hasForegroundLocationPermission()) {
            startForegroundService(new Intent(this, TripForegroundService.class));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        statusHandler.removeCallbacks(statusPoller);
    }

    private static final int REQUEST_FOREGROUND_LOCATION = 100;
    private static final int REQUEST_BACKGROUND_LOCATION = 101;
    private static final int REQUEST_NOTIFICATIONS = 102;

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_NOTIFICATIONS) {
            // Continue the flow regardless of the answer -- notifications
            // are important (the persistent status indicator depends on
            // them) but not so critical that declining should block
            // location permission and monitoring entirely.
            if (!hasNotificationPermission()) {
                Toast.makeText(this, "Without notification access, the persistent "
                        + "status notification won't show.", Toast.LENGTH_LONG).show();
            }
            startMonitoringFlow();
        } else if (requestCode == REQUEST_FOREGROUND_LOCATION) {
            if (!hasForegroundLocationPermission()) {
                Toast.makeText(this, "Location permission is required to track trips.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermission()) {
                showBackgroundLocationRationale();
            } else {
                actuallyStartMonitoring();
            }
        } else if (requestCode == REQUEST_BACKGROUND_LOCATION) {
            actuallyStartMonitoring();
        }
    }

    /**
         * Entry point for "Start Monitoring". Android requires location
         * permission to be requested at runtime (a manifest entry alone does
         * nothing) -- and on Android 10+, background location has to be
         * requested as a SEPARATE step after foreground location is granted;
         * requesting both at once is not allowed. This walks through both
         * stages before actually starting the foreground service.
         */
        private void startMonitoringFlow() {
            // Required on Android 13+ for ANY notification to show at all,
            // including the persistent status notification this app relies
            // on -- previously only declared in the manifest with no
            // runtime request built, a real gap flagged and now closed.
            if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
                ActivityCompat.requestPermissions(this,
                        new String[]{"android.permission.POST_NOTIFICATIONS"},
                        REQUEST_NOTIFICATIONS);
                return;
            }
            if (!hasForegroundLocationPermission()) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION},
                        REQUEST_FOREGROUND_LOCATION);
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermission()) {
                showBackgroundLocationRationale();
                return;
            }
            actuallyStartMonitoring();
        }

        /** POST_NOTIFICATIONS didn't exist before Android 13 -- always true on older versions. */
        private boolean hasNotificationPermission() {
            if (Build.VERSION.SDK_INT < 33) {
                return true;
            }
            return ActivityCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED;
        }

    /**
         * Background location needs its own explanation before the system
         * dialog, and on Android 11+ the system dialog for this permission
         * often doesn't offer "Allow all the time" at all -- Google's own
         * guidance is to send the user to the app's Settings page instead.
         * On Android 10 specifically, requesting it directly still works.
         */
        private void showBackgroundLocationRationale() {
            new AlertDialog.Builder(this)
                    .setTitle("Background Location Needed")
                    .setMessage("To keep tracking trips while you're using the Dasher app "
                            + "(not this app), location access needs to be set to \"Allow all "
                            + "the time\" on the next screen.")
                    .setPositiveButton("Continue", (dialog, which) -> {
                        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                            ActivityCompat.requestPermissions(this,
                                    new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                                    REQUEST_BACKGROUND_LOCATION);
                        } else {
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                            actuallyStartMonitoring();
                        }
                    })
                    .setNegativeButton("Foreground Only", (dialog, which) -> actuallyStartMonitoring())
                    .show();
        }

    private void actuallyStartMonitoring() {
            Intent serviceIntent = new Intent(this, TripForegroundService.class);
            serviceIntent.setAction(TripForegroundService.ACTION_START_TRACKING);
            startForegroundService(serviceIntent);
            updateStatusText();
            // Gets you out of the way immediately -- the whole point of this
            // app is to run quietly in the background while you use the
            // Dasher app, not to sit on screen requiring a manual switch away.
            moveTaskToBack(true);
        }

    private boolean hasForegroundLocationPermission() {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }

    private boolean hasBackgroundLocationPermission() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return true; // no separate background permission before Android 10
            }
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }

    /**
         * Wrapper so a logging call itself can never crash the app (same
         * defensive pattern as the background services). Falls back to
         * FallbackLogger if the engine isn't ready yet.
         */
        private void logDiagnostic(String category, String message) {
            try {
                if (engine != null) {
                    engine.callAttr("log_diagnostic", category, message);
                } else {
                    FallbackLogger.log(this, category, message);
                }
            } catch (RuntimeException e) { // RuntimeException alone also catches PyException (Chaquopy PyException extends RuntimeException -- Java disallows both in one multi-catch since one is a subclass of the other)
                FallbackLogger.log(this, category, message);
            }
        }

    /**
         * Polled every few seconds while the activity is visible: shows both
         * the trip state (Driving/Idle) and the current mode (Dasher/General)
         * so it's clear at a glance which mode is active, matching the
         * indicator already shown in the persistent notification.
         */
        /**
         * docs/screen_recording/PRD.md ss7/ss8, "capture screen recording
         * by default": a driver who never opens Setup would otherwise
         * never be asked about this feature at all. Shown at most ONCE
         * ever (see ScreenRecordingController.isDefaultPromptShown) --
         * gated on hasEverBeenConfigured(), not on isEnabled(), since the
         * whole point is distinguishing "never touched this" from
         * "explicitly chose a value" (see that method's own doc for why
         * a flipped isEnabled() default was considered and rejected).
         * Deliberately setCancelable(false): both buttons record a real,
         * explicit choice (Enable -> the real OS consent dialog, exactly
         * as if the driver had tapped the Setup switch themselves; Not
         * Now -> explicitly persisted off, not just skipped) rather than
         * leaving a dismiss-without-choosing case to reason about
         * separately.
         */
        private void maybeShowScreenRecordingDefaultPrompt() {
            if (ScreenRecordingController.hasEverBeenConfigured(this)
                    || ScreenRecordingController.isDefaultPromptShown(this)) {
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Record your screen during trips?")
                    .setMessage("Dasher Monitor can automatically record your screen during trips, "
                            + "so you can review a delivery later if something goes wrong.\n\n"
                            + "Screen recording captures your ENTIRE screen -- not just this app -- "
                            + "for the whole trip. If you switch to messages, banking, or anything "
                            + "else mid-trip, that's captured too. It also records audio from the "
                            + "microphone -- everything audible in the car, not just your voice -- "
                            + "if you grant that separately; recording still works video-only if you "
                            + "don't. Recordings stay private on this device only, never uploaded "
                            + "anywhere, and you can turn this off anytime in Setup.")
                    .setCancelable(false)
                    .setPositiveButton("Enable Screen Recording", (dialog, which) -> {
                        ScreenRecordingController.setDefaultPromptShown(this);
                        Intent intent = new Intent(this, PermissionsActivity.class);
                        intent.putExtra(PermissionsActivity.EXTRA_AUTO_REQUEST_RECORDING_CONSENT, true);
                        startActivity(intent);
                    })
                    .setNegativeButton("Not Now", (dialog, which) -> {
                        ScreenRecordingController.setDefaultPromptShown(this);
                        ScreenRecordingController.setEnabled(this, false);
                        logDiagnostic("SCREEN_RECORDING", "Declined the first-run default-on prompt -- staying off");
                    })
                    .show();
        }

        /**
         * Auto-recovery for trusted contacts: ONLY triggers if the list
         * is currently completely empty (e.g. after a reinstall or a
         * data reset) -- deliberately never runs if even one contact
         * already exists, so this can never silently overwrite anything
         * added since the last save. Uses the persistable URI permission
         * granted when the file was last saved/loaded (see
         * rememberContactsFileUri in TrustedContactsActivity) to re-read
         * it without needing to show the file picker again.
         */
        private void attemptTrustedContactsAutoRecovery() {
            try {
                JSONArray existing = new JSONArray(engine.callAttr("get_trusted_senders_json").toString());
                if (existing.length() > 0) {
                    return; // already has contacts -- never touch a non-empty list
                }
            } catch (JSONException | RuntimeException e) {
                return;
            }

            String uriString = getSharedPreferences("dasher_monitor_prefs", MODE_PRIVATE)
                    .getString("last_trusted_contacts_file_uri", null);
            if (uriString == null) {
                return; // nothing to recover from
            }

            try {
                android.net.Uri uri = android.net.Uri.parse(uriString);
                int addedCount = 0;
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) {
                        return;
                    }
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String name = line.trim();
                        if (!name.isEmpty()) {
                            engine.callAttr("add_trusted_sender", name);
                            addedCount++;
                        }
                    }
                }
                if (addedCount > 0) {
                    logDiagnostic("TRUSTED_CONTACTS", "Auto-recovered " + addedCount
                            + " contact(s) from last saved file -- list was found empty on startup");
                    Toast.makeText(this, "Restored " + addedCount + " trusted contact(s) from your last saved file.",
                            Toast.LENGTH_LONG).show();
                }
            } catch (RuntimeException | java.io.IOException e) {
                // Silent -- the file may have been moved/deleted/permission
                // revoked since it was last saved. Not worth interrupting
                // a normal app launch over; a real Load button still works.
                logDiagnostic("TRUSTED_CONTACTS", "Auto-recovery attempt failed: " + e.getMessage());
            }
        }

        private void updateStatusText() {
            String warning = buildMissingPermissionsWarning();

            if (!TripForegroundService.serviceExists) {
                statusText.setText("Status: Fully Off" + warning);
                pickupNoteButton.setVisibility(android.view.View.GONE);
                return;
            }
            if (!TripForegroundService.isRunning) {
                statusText.setText("Status: Not Monitoring (still running in background)" + warning);
                pickupNoteButton.setVisibility(android.view.View.GONE);
                return;
            }
            String state = engine.callAttr("get_state").toString();
            String mode = engine.callAttr("get_mode").toString();
            String stateLabel = "TRIP_ACTIVE".equals(state) ? "Driving" : "Idle";
            String modeLabel = "DASHER".equals(mode) ? "Dasher Mode" : "General Driving Mode";
            statusText.setText("Status: " + stateLabel + " -- " + modeLabel + warning
                    + buildDasherDetectionStatusLine() + "\n" + buildLastUpdateLine());
            updatePickupNoteButton();
        }

        /**
         * Driver-requested (2026-09-14, docs/dasher_detection_status/PRD.md):
         * "ensure the monitoring app is actively monitoring a dasher
         * session" -- several real signals already existed in the code
         * (Dasher app installed, accessibility enabled, the notification
         * listener's LIVE connection, an auto-detected Dash Paused state)
         * but were scattered across one-time push alerts and a voice
         * announcement, never all visible together on this screen. No new
         * detection mechanism -- this only consolidates what's already
         * tracked into one line the driver can check at a glance, instead
         * of having to notice and remember several separate alerts.
         *
         * Only called from the branch below where monitoring is confirmed
         * running -- the two early-return branches above (fully off / not
         * monitoring) never call this, since Dasher-specific detection
         * status has nothing meaningful to report when nothing's running.
         */
        private String buildDasherDetectionStatusLine() {
            java.util.List<String> problems = new java.util.ArrayList<>();
            if (!DasherAppInfo.isInstalled(this)) {
                problems.add("Dasher app not found installed");
            }
            if (!isAccessibilityServiceGranted()) {
                problems.add("Accessibility not enabled");
            } else if (DasherAccessibilityService.isHeartbeatStale()) {
                // docs/accessibility_liveness_heartbeat/PRD.md -- CONFIRMED
                // REAL GAP, fixed here: the Settings-permission check above
                // can stay green even after the OS/an OEM battery manager
                // silently kills the live accessibility binding (unlike
                // AppNotificationListenerService, Android gives this
                // service no onListenerDisconnected-style callback to catch
                // that). isHeartbeatStale() is the actual liveness signal
                // -- see its own doc. This is the exact false-positive
                // "everything says it's fine but nothing's being detected"
                // state the round-8 audit was built to catch.
                problems.add("Accessibility not responding");
            }
            // Cold-start guard, same as TripForegroundService's own
            // notificationListenerEverConnected -- "hasn't connected yet"
            // (normal for the first few seconds after any launch) must
            // never be shown the same as a genuine drop.
            if (AppNotificationListenerService.lastListenerConnectedMs > 0
                    && !AppNotificationListenerService.isListenerConnected) {
                problems.add("Notification listener disconnected");
            }
            // docs/notification_visibility_check/PRD.md -- CONFIRMED REAL
            // GAP, fixed here: every alert this app can raise (permission-
            // revoked, watchdog, engine-failure, recording-verification)
            // is a notification -- if the driver has disabled this app's
            // notifications at the OS level, ALL of them are silently
            // invisible, with nothing anywhere telling the driver that.
            // This status line is read directly (not via a notification),
            // so it's one of the few surfaces that can actually warn about
            // this specific failure mode.
            if (TripForegroundService.notificationsAppearDisabled) {
                problems.add("Notifications disabled -- alerts won't reach you");
            }
            if (!problems.isEmpty()) {
                return "\n⚠ Detection: " + String.join(", ", problems);
            }
            if (DasherAccessibilityService.pausedByAutoDetection) {
                return "\n⏸ Dash Paused (auto-detected) -- tracking is paused";
            }
            return "\n✓ Actively watching for Dasher activity";
        }

        /**
         * Shows/labels the Pickup Note button only while a pickup is
         * actually currently registered (offer accepted, not yet
         * departed) -- per explicit request, a comment section for the
         * pickup address, previously nowhere in the app at all. Polled
         * alongside the rest of the live status rather than its own timer.
         */
        private void updatePickupNoteButton() {
            String restaurant;
            try {
                restaurant = engine.callAttr("get_current_pickup_restaurant").toString();
            } catch (RuntimeException e) { // covers PyException too
                restaurant = "";
            }
            lastKnownPickupRestaurant = restaurant.isEmpty() ? null : restaurant;
            if (lastKnownPickupRestaurant != null) {
                pickupNoteButton.setVisibility(android.view.View.VISIBLE);
                pickupNoteButton.setText("Pickup Note (" + lastKnownPickupRestaurant + ")");
            } else {
                pickupNoteButton.setVisibility(android.view.View.GONE);
            }
        }

        /**
         * View/edit the persistent, per-restaurant note for the current
         * pickup location (e.g. "gate code 1234", "enter through side
         * door") - saved keyed by restaurant name (see
         * save_pickup_notes/get_pickup_notes), so it's still there next
         * time an offer comes in from the same place, the same "learn per
         * restaurant" pattern already used for parking difficulty.
         */
        private void showPickupNoteDialog() {
            String restaurant = lastKnownPickupRestaurant;
            if (restaurant == null) {
                return; // button shouldn't be visible in this state, but guard anyway
            }
            String existingNote;
            try {
                existingNote = engine.callAttr("get_pickup_notes", restaurant).toString();
            } catch (RuntimeException e) { // covers PyException too
                existingNote = "";
            }
            EditText noteInput = new EditText(this);
            noteInput.setHint("e.g. gate code, which door, where to park...");
            noteInput.setText(existingNote);
            noteInput.setMinLines(2);

            new AlertDialog.Builder(this)
                    .setTitle("Pickup Note -- " + restaurant)
                    .setMessage("Saved per restaurant -- still here next time an order comes from this place.")
                    .setView(noteInput)
                    .setPositiveButton("Save", (dialog, which) -> {
                        String note = noteInput.getText().toString().trim();
                        try {
                            engine.callAttr("save_pickup_notes", restaurant, note);
                            logDiagnostic("BUTTON", "Pickup note saved for " + restaurant);
                            Toast.makeText(this, note.isEmpty() ? "Note cleared." : "Note saved.",
                                    Toast.LENGTH_SHORT).show();
                        } catch (RuntimeException e) { // covers PyException too
                            Toast.makeText(this, "Could not save note: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }

        /**
         * Real proof of recency, not just a category state -- a color or
         * mode label can only ever show what's supposedly true, never
         * when it was last actually confirmed. This is the direct answer
         * to "is it really still working right now": it only ever resets
         * when a genuine GPS tick actually lands, so if it starts
         * climbing instead of staying near zero, that's honest, immediate
         * evidence something's stopped -- visible at a glance instead of
         * only discoverable afterward in the diagnostic log.
         */
        private String buildLastUpdateLine() {
            long lastMs = TripForegroundService.lastGpsUpdateMs;
            if (lastMs == 0) {
                return "Last GPS update: none yet this session";
            }
            long agoSeconds = (System.currentTimeMillis() - lastMs) / 1000;
            String agoLabel = agoSeconds < 60 ? agoSeconds + "s ago"
                    : agoSeconds < 3600 ? (agoSeconds / 60) + "m ago"
                    : (agoSeconds / 3600) + "h ago";
            return "Last GPS update: " + agoLabel;
        }

        /**
         * Links the 4 permissions directly to the status display -- a
         * missing permission (especially Overlay, which fails completely
         * silently: no error, no log entry, the badge just never shows)
         * previously required navigating to Permissions & Setup separately
         * to notice. Now shows up right alongside monitoring status.
         * Returns an empty string if everything's granted.
         */
        private String buildMissingPermissionsWarning() {
            java.util.List<String> missing = new java.util.ArrayList<>();
            if (!isNotificationAccessGranted()) {
                missing.add("Notification Access");
            }
            if (!isAccessibilityServiceGranted()) {
                missing.add("Accessibility Access");
            }
            if (!OverlayHelper.hasPermission(this)) {
                missing.add("Overlay Permission");
            }
            if (!isBatteryExemptionGranted()) {
                missing.add("Battery Optimization Exempt");
            }
            if (missing.isEmpty()) {
                return "";
            }
            return "\n\u26A0 Missing: " + String.join(", ", missing);
        }

        private boolean isNotificationAccessGranted() {
            String enabledListeners = Settings.Secure.getString(getContentResolver(),
                    "enabled_notification_listeners");
            return enabledListeners != null && enabledListeners.contains(getPackageName());
        }

        private boolean isAccessibilityServiceGranted() {
            String enabledServices = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabledServices != null
                    && enabledServices.contains(getPackageName() + "/" + getPackageName() + ".DasherAccessibilityService");
        }

        private boolean isBatteryExemptionGranted() {
            android.os.PowerManager powerManager =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName());
        }

    /**
         * Requirement change (2026-08-30, docs/road_warrior_icon/PRD.md):
         * grabs the most recently added delivery address from the stops
         * buffer and copies it to the clipboard, instead of auto-launching
         * navigation, so the driver can paste it wherever they choose.
         */
        private void copyMostRecentStopAddress() {
            try {
                String stopsJson = engine.callAttr("get_stops_buffer_json").toString();
                JSONArray stops = new JSONArray(stopsJson);
                if (stops.length() == 0) {
                    Toast.makeText(this, "No recent address to copy yet.", Toast.LENGTH_SHORT).show();
                    return;
                }
                JSONObject mostRecent = stops.getJSONObject(0);
                String address = mostRecent.optString("address", "");
                NavigationHelper.copyAddressToClipboard(this, address);
            } catch (JSONException | PyException e) {
                Toast.makeText(this, "Could not copy address: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

    // Driver-requested (2026-09-13) quick-pick reasons for a declined or
    // never-responded-to offer -- kept short and one-handed-tappable,
    // matching this dialog's "reviewed right after a shift, not typed
    // carefully at a desk" real use case. "Other" (see
    // showDeclineReasonOtherInput) covers anything that doesn't fit.
    private static final String[] DECLINE_REASON_QUICK_PICKS = {
            "Too far", "Pay too low", "Bad area", "Bad restaurant", "Missed it / too slow"
    };

    /**
     * Entry point for the shift-end decline/no-response review, driver-
     * requested (2026-09-13): "let me give a reason for all declined or
     * not responded offers, only after my shift has finished." Checks
     * whether THIS shift (TripForegroundService.sessionStartMs, the real
     * Start-Monitoring timestamp) actually had any declined/timed-out
     * offers still missing a reason -- if not (the common case most
     * shifts, especially a short GENERAL-mode one with no real offers at
     * all), falls straight through to the existing trip-summary flow
     * unchanged, no dialog interrupts anything. Any failure here (engine
     * exception, malformed JSON) degrades the same way -- never blocks
     * the existing, already-working trip-summary flow over this new,
     * optional feature.
     */
    /**
     * Driver-requested (2026-09-14, docs/zero_interaction_delivery_
     * completion/PRD.md): "Rate This Delivery" no longer force-opens
     * after every individual delivery mid-shift (see
     * TripForegroundService.notifyRateThisDelivery's updated comment) --
     * this is the shift-end counterpart, checking whether THIS shift
     * (same TripForegroundService.sessionStartMs real Start-Monitoring
     * timestamp maybeReviewDeclinedOffersThenShowTripSummary already
     * uses) has any completed deliveries still missing a rating. If not
     * (the common case for a short shift, or one where every delivery was
     * already rated via the still-available passive notification), falls
     * straight through to the decline-reason review unchanged -- no
     * dialog interrupts anything new. Any failure here degrades the same
     * way, never blocking the existing flow.
     */
    private void maybeReviewDeliveryRatingsThenReviewDeclinedOffers() {
        try {
            long sessionStartMs = TripForegroundService.sessionStartMs;
            if (sessionStartMs <= 0) {
                maybeReviewDeclinedOffersThenShowTripSummary();
                return;
            }
            JSONArray deliveries = new JSONArray(
                    engine.callAttr("get_deliveries_needing_rating", sessionStartMs / 1000.0).toString());
            if (deliveries.length() == 0) {
                maybeReviewDeclinedOffersThenShowTripSummary();
                return;
            }
            logDiagnostic("TRIP_FEEDBACK", "Shift-end review: " + deliveries.length()
                    + " deliverie(s) from this shift still need a rating");
            showDeliveryRatingReview(deliveries, 0);
        } catch (JSONException | RuntimeException e) { // covers PyException too
            logDiagnostic("ERROR", "maybeReviewDeliveryRatingsThenReviewDeclinedOffers exception: "
                    + android.util.Log.getStackTraceString(e));
            maybeReviewDeclinedOffersThenShowTripSummary();
        }
    }

    /**
     * Shows one delivery at a time from the shift-end rating-review list,
     * recursing to the next on Save, Skip, or a dismissal (back button/
     * tap-outside -- treated the same as Skip). Falls through to the
     * decline-reason review once every delivery in the list has been
     * handled, mirroring showDeclineReasonReview's own recursive shape.
     *
     * Deliberately does NOT include a parking category -- parking
     * difficulty is already auto-recorded with zero interaction for every
     * stop (see drive_monitor.py's _auto_parking_difficulty_label), and a
     * driver who wants to correct it right after a specific delivery
     * already can, via that delivery's own passive notification, which
     * still opens the full original dialog (parking category included).
     */
    private void showDeliveryRatingReview(JSONArray deliveries, int index) {
        if (index >= deliveries.length()) {
            maybeReviewDeclinedOffersThenShowTripSummary();
            return;
        }
        JSONObject delivery = deliveries.optJSONObject(index);
        if (delivery == null) {
            showDeliveryRatingReview(deliveries, index + 1);
            return;
        }
        int tripId = delivery.optInt("trip_id", -1);
        String pickupAddress = delivery.optString("pickup_address", "");

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        TextView subtitle = new TextView(this);
        subtitle.setText(String.format(java.util.Locale.US, "%s\ndelivery %d of %d -- all optional",
                pickupAddress.isEmpty() ? "This delivery" : pickupAddress,
                index + 1, deliveries.length()));
        subtitle.setTextSize(13f);
        layout.addView(subtitle);

        android.widget.RatingBar ratingBar = new android.widget.RatingBar(this);
        ratingBar.setNumStars(5);
        ratingBar.setStepSize(1f);
        layout.addView(ratingBar);

        String[] navigationSelected = {null};
        String[] merchantWaitSelected = {null};
        String[] customerSelected = {null};
        String[] overallSelected = {null};
        layout.addView(buildFeedbackCategoryRow("Navigation",
                new String[]{"Simple", "Confusing", "Lost"}, navigationSelected));
        layout.addView(buildFeedbackCategoryRow("Merchant Wait",
                new String[]{"Fast", "Okay", "Slow"}, merchantWaitSelected));
        layout.addView(buildFeedbackCategoryRow("Customer",
                new String[]{"Nice", "Neutral", "Rude"}, customerSelected));
        layout.addView(buildFeedbackCategoryRow("Overall",
                new String[]{"Good", "Okay", "Bad"}, overallSelected));

        EditText notesInput = new EditText(this);
        notesInput.setHint("Notes (optional)");
        layout.addView(notesInput);

        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.addView(layout);

        new AlertDialog.Builder(this)
                .setTitle("Rate this delivery")
                .setMessage("Parking was already auto-recorded from how long it took you to get moving.")
                .setView(scrollView)
                .setPositiveButton("Save", (d, w) -> {
                    int rating = Math.round(ratingBar.getRating());
                    String notes = notesInput.getText().toString().trim();
                    try {
                        engine.callAttr("save_trip_feedback", tripId, rating, notes,
                                null, navigationSelected[0], merchantWaitSelected[0],
                                customerSelected[0], overallSelected[0]);
                        engine.callAttr("recalculate_personal_calibration");
                        logDiagnostic("TRIP_FEEDBACK", "Batched shift-end rating saved for trip " + tripId);
                    } catch (RuntimeException e) { // covers PyException too
                        logDiagnostic("ERROR", "save_trip_feedback (shift-end batch) exception: "
                                + android.util.Log.getStackTraceString(e));
                    }
                    showDeliveryRatingReview(deliveries, index + 1);
                })
                .setNegativeButton("Skip", (d, w) -> showDeliveryRatingReview(deliveries, index + 1))
                .setOnCancelListener(d -> showDeliveryRatingReview(deliveries, index + 1))
                .show();
    }

    private void maybeReviewDeclinedOffersThenShowTripSummary() {
        try {
            long sessionStartMs = TripForegroundService.sessionStartMs;
            if (sessionStartMs <= 0) {
                // No real session-start recorded this app process (e.g. a
                // stray Stop Monitoring tap with monitoring already off) --
                // nothing to look back over.
                showLastTripSummaryThenPromptFeedback();
                return;
            }
            JSONArray offers = new JSONArray(
                    engine.callAttr("get_offers_needing_reason", sessionStartMs / 1000.0).toString());
            if (offers.length() == 0) {
                showLastTripSummaryThenPromptFeedback();
                return;
            }
            logDiagnostic("DECLINE_REASON", "Shift-end review: " + offers.length()
                    + " declined/no-response offer(s) from this shift");
            showDeclineReasonReview(offers, 0);
        } catch (JSONException | RuntimeException e) { // covers PyException too
            logDiagnostic("ERROR", "maybeReviewDeclinedOffersThenShowTripSummary exception: "
                    + android.util.Log.getStackTraceString(e));
            showLastTripSummaryThenPromptFeedback();
        }
    }

    /**
     * Shows one offer at a time from the shift-end review list, recursing
     * to the next on every answer (a quick-pick tap), Skip, or a
     * dismissal (back button/tap-outside -- treated the same as Skip,
     * not re-shown). Falls through to the existing trip-summary flow
     * once every offer in the list has been handled.
     */
    private void showDeclineReasonReview(JSONArray offers, int index) {
        if (index >= offers.length()) {
            showLastTripSummaryThenPromptFeedback();
            return;
        }
        JSONObject offer = offers.optJSONObject(index);
        if (offer == null) {
            showDeclineReasonReview(offers, index + 1);
            return;
        }
        int offerId = offer.optInt("id", -1);
        String restaurantName = offer.optString("restaurant_name", "");
        double payout = offer.optDouble("payout", -1);
        boolean timedOut = "timed_out".equals(offer.optString("outcome", ""));

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        TextView subtitle = new TextView(this);
        subtitle.setText(String.format(java.util.Locale.US, "%s%s\n%s  •  offer %d of %d",
                restaurantName.isEmpty() ? "This offer" : restaurantName,
                payout >= 0 ? String.format(java.util.Locale.US, " ($%.2f)", payout) : "",
                timedOut ? "No response (timed out)" : "Declined",
                index + 1, offers.length()));
        subtitle.setTextSize(13f);
        layout.addView(subtitle);

        int buttonTopMargin = (int) (8 * getResources().getDisplayMetrics().density);
        for (String reason : DECLINE_REASON_QUICK_PICKS) {
            Button reasonButton = new Button(this);
            reasonButton.setText(reason);
            android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = buttonTopMargin;
            reasonButton.setLayoutParams(params);
            layout.addView(reasonButton);
        }
        Button otherButton = new Button(this);
        otherButton.setText("Other…");
        android.widget.LinearLayout.LayoutParams otherParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        otherParams.topMargin = buttonTopMargin;
        otherButton.setLayoutParams(otherParams);
        layout.addView(otherButton);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Why didn't this work out?")
                .setView(layout)
                .setNegativeButton("Skip", (d, w) -> showDeclineReasonReview(offers, index + 1))
                .setOnCancelListener(d -> showDeclineReasonReview(offers, index + 1))
                .create();

        // Buttons reference `dialog` itself (to dismiss before advancing),
        // so their listeners are wired after creation rather than inline
        // above -- the LayoutParams loop stays simple, this is the only
        // part that actually needs the AlertDialog instance.
        for (int i = 0; i < DECLINE_REASON_QUICK_PICKS.length; i++) {
            String reason = DECLINE_REASON_QUICK_PICKS[i];
            Button reasonButton = (Button) layout.getChildAt(i + 1); // +1 skips the subtitle TextView
            reasonButton.setOnClickListener(v -> {
                saveDeclineReason(offerId, reason);
                dialog.dismiss();
                showDeclineReasonReview(offers, index + 1);
            });
        }
        otherButton.setOnClickListener(v -> {
            dialog.dismiss();
            showDeclineReasonOtherInput(offers, index, offerId, restaurantName);
        });

        dialog.show();
    }

    /** "Other" free-text sub-dialog -- Save stores "Other: <text>" (or just skips if left blank), Skip/cancel moves on without saving. */
    private void showDeclineReasonOtherInput(JSONArray offers, int index, int offerId, String restaurantName) {
        EditText input = new EditText(this);
        input.setHint("What happened?");
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle(restaurantName.isEmpty() ? "Other reason" : "Other reason -- " + restaurantName)
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (!text.isEmpty()) {
                        saveDeclineReason(offerId, "Other: " + text);
                    }
                    showDeclineReasonReview(offers, index + 1);
                })
                .setNegativeButton("Skip", (d, w) -> showDeclineReasonReview(offers, index + 1))
                .setOnCancelListener(d -> showDeclineReasonReview(offers, index + 1))
                .show();
    }

    private void saveDeclineReason(int offerId, String reason) {
        if (offerId < 0) {
            return; // malformed entry from get_offers_needing_reason -- nothing real to save against
        }
        try {
            boolean success = engine.callAttr("set_decline_reason", offerId, reason).toBoolean();
            logDiagnostic("DECLINE_REASON", (success ? "Saved" : "Failed -- offer no longer exists")
                    + " (#" + offerId + "): " + reason);
        } catch (RuntimeException e) { // covers PyException too
            logDiagnostic("ERROR", "set_decline_reason exception: " + android.util.Log.getStackTraceString(e));
        }
    }

    /**
         * Used specifically right after "Stop Monitoring" -- shows the last
         * trip's detail via TripDetailActivity, for reference.
         *
         * docs/trip_history_redesign/PRD.md ss3.4 (revised during
         * implementation, driver's own correction): this used to ALSO
         * chain into showFeedbackDialog() on dismiss -- but "Stop
         * Monitoring" can fire long after the actual last delivery
         * completed, and by then a real Dasher trip was already prompted
         * for feedback at the moment it actually finished
         * (TripForegroundService.notifyRateThisDelivery(), which now
         * launches TripDetailActivity directly with
         * EXTRA_PROMPT_FEEDBACK_ON_CLOSE=true). Chaining feedback here
         * too would risk a confusing double-prompt for the same trip --
         * launched here WITHOUT that extra, pure viewing.
         */
        private void showLastTripSummaryThenPromptFeedback() {
            try {
                JSONObject summary = new JSONObject(engine.callAttr("get_last_trip_summary").toString());
                if (!summary.optBoolean("found", false)) {
                    new AlertDialog.Builder(this)
                            .setTitle("Last Trip Summary")
                            .setMessage("No completed trips yet.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }
                Intent intent = new Intent(this, TripDetailActivity.class);
                intent.putExtra(TripDetailActivity.EXTRA_TRIP_ID, summary.optInt("trip_id", -1));
                startActivity(intent);
            } catch (JSONException | PyException e) {
                Toast.makeText(this, "Could not load trip summary.", Toast.LENGTH_LONG).show();
            }
        }

    /** Simple 1-5 star rating + optional notes, saved against this specific trip. */
        private void showFeedbackDialog(int tripId) {
            android.widget.RatingBar ratingBar = new android.widget.RatingBar(this);
            ratingBar.setNumStars(5);
            ratingBar.setStepSize(1f);
            ratingBar.setRating(3f);

            EditText notesInput = new EditText(this);
            notesInput.setHint("Optional notes (e.g. what made this good or bad)");

            android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            layout.setPadding(pad, pad, pad, pad);

            // docs/feedback_dialog_phase_timings/PRD.md ss4A -- this dialog
            // is the one actually shown automatically right after a real
            // delivery (see auto_show_feedback_trip_id in onCreate), but it
            // never showed any context about the trip itself. NOW REDUNDANT
            // (docs/trip_history_redesign/PRD.md ss3.4, disclosed there,
            // not fixed here): TripDetailActivity's own "Where The Time
            // Went" card already shows this exact breakdown one screen
            // earlier on this same flow -- left as a small, low-cost
            // duplication rather than risk editing this method further.
            // Uses get_trip_summary_by_id (not
            // get_last_trip_summary) since this dialog is always given a
            // specific tripId by its caller -- correct regardless of which
            // of the two real call sites invoked it. Degrades silently to
            // the exact previous rating-only behavior on any failure --
            // this is supplementary context, never worth blocking the
            // actual feedback form over.
            try {
                JSONObject summary = new JSONObject(engine.callAttr("get_trip_summary_by_id", tripId).toString());
                JSONObject phaseBreakdown = summary.optBoolean("found", false)
                        ? summary.optJSONObject("phase_breakdown") : null;
                if (phaseBreakdown != null && phaseBreakdown.length() > 0) {
                    double totalTripSeconds = summary.optDouble("end_time", 0) - summary.optDouble("start_time", 0);
                    TextView phaseTimingText = new TextView(this);
                    StringBuilder phaseBody = new StringBuilder("Where the time went:\n");
                    if (!phaseBreakdown.isNull("driving_to_pickup_seconds")) {
                        double seconds = phaseBreakdown.optDouble("driving_to_pickup_seconds", 0);
                        String percentSuffix = totalTripSeconds > 0
                                ? " -- " + formatPercentOfTotal(seconds, totalTripSeconds) : "";
                        phaseBody.append(String.format("Driving to pickup: %s%s\n",
                                formatMinutesSeconds(seconds), percentSuffix));
                    }
                    if (!phaseBreakdown.isNull("wait_at_restaurant_seconds")) {
                        double seconds = phaseBreakdown.optDouble("wait_at_restaurant_seconds", 0);
                        String percentSuffix = totalTripSeconds > 0
                                ? " -- " + formatPercentOfTotal(seconds, totalTripSeconds) : "";
                        phaseBody.append(String.format("Waiting at restaurant: %s%s\n",
                                formatMinutesSeconds(seconds), percentSuffix));
                    }
                    if (!phaseBreakdown.isNull("driving_to_dropoff_seconds")) {
                        double seconds = phaseBreakdown.optDouble("driving_to_dropoff_seconds", 0);
                        String percentSuffix = totalTripSeconds > 0
                                ? " -- " + formatPercentOfTotal(seconds, totalTripSeconds) : "";
                        phaseBody.append(String.format("Driving to dropoff: %s%s\n",
                                formatMinutesSeconds(seconds), percentSuffix));
                    }
                    if (!phaseBreakdown.isNull("parking_to_walking_seconds")) {
                        double seconds = phaseBreakdown.optDouble("parking_to_walking_seconds", 0);
                        String percentSuffix = totalTripSeconds > 0
                                ? " -- " + formatPercentOfTotal(seconds, totalTripSeconds) : "";
                        phaseBody.append(String.format("Parking to walking: %s%s\n",
                                formatMinutesSeconds(seconds), percentSuffix));
                    }
                    if (!phaseBreakdown.isNull("completing_dropoff_seconds")) {
                        double seconds = phaseBreakdown.optDouble("completing_dropoff_seconds", 0);
                        String percentSuffix = totalTripSeconds > 0
                                ? " -- " + formatPercentOfTotal(seconds, totalTripSeconds) : "";
                        phaseBody.append(String.format("Completing dropoff: %s%s\n",
                                formatMinutesSeconds(seconds), percentSuffix));
                    }
                    phaseTimingText.setText(phaseBody.toString());
                    int bottomMargin = (int) (12 * getResources().getDisplayMetrics().density);
                    phaseTimingText.setPadding(0, 0, 0, bottomMargin);
                    layout.addView(phaseTimingText);
                }
            } catch (JSONException | RuntimeException e) { // covers PyException too
                logDiagnostic("ERROR", "showFeedbackDialog phase-timing fetch exception: "
                        + android.util.Log.getStackTraceString(e));
            }

            layout.addView(ratingBar);

            // Five quick-tap categories, one word per option, no typing
            // required -- each row tracks its own selection in a 1-element
            // array (needs to be effectively final for the button lambdas).
            String[] parkingSelected = {null};
            String[] navigationSelected = {null};
            String[] merchantWaitSelected = {null};
            String[] customerSelected = {null};
            String[] overallSelected = {null};

            // Reuses the existing Parking category rather than adding a
            // redundant second parking question -- gives it real context
            // (the actual measured park-to-walk duration) when one was
            // recorded this trip, and feeds the answer into the new
            // per-restaurant parking-difficulty learning (see
            // record_parking_difficulty_feedback) alongside its normal role.
            String parkingLabel = "Parking";
            String pendingParkingRestaurant = null;
            double pendingParkingGapSeconds = 0;
            // -1 = no auto-labeled row to upgrade (Python's "no id" sentinel --
            // see record_parking_difficulty_feedback's feedback_id docstring).
            int pendingParkingFeedbackId = -1;
            try {
                String gapJson = engine.callAttr("get_last_parking_gap_for_feedback").toString();
                if (!"null".equals(gapJson)) {
                    JSONObject gapObj = new JSONObject(gapJson);
                    pendingParkingRestaurant = gapObj.optString("restaurant_name", null);
                    pendingParkingGapSeconds = gapObj.optDouble("gap_seconds", 0);
                    pendingParkingFeedbackId = gapObj.optInt("feedback_id", -1);
                    parkingLabel = String.format("Parking (took %.0fs to get moving)", pendingParkingGapSeconds);
                    // GAP 1b (diagnostic-coverage pass): confirms the
                    // feedback dialog is being shown WITH real measured
                    // context specifically, distinct from the generic
                    // "Parking" label -- previously nothing logged this
                    // distinction at all.
                    logDiagnostic("WALKING", "Feedback dialog shown with real parking context: "
                            + parkingLabel + " for " + pendingParkingRestaurant);
                }
            } catch (JSONException | RuntimeException e) {
                // Falls back to the plain "Parking" label -- not worth
                // blocking the whole feedback dialog over this.
            }
            final String finalPendingParkingRestaurant = pendingParkingRestaurant;
            final double finalPendingParkingGapSeconds = pendingParkingGapSeconds;
            final int finalPendingParkingFeedbackId = pendingParkingFeedbackId;

            layout.addView(buildFeedbackCategoryRow(parkingLabel,
                    new String[]{"Easy", "Okay", "Hard"}, parkingSelected));
            layout.addView(buildFeedbackCategoryRow("Navigation",
                    new String[]{"Simple", "Confusing", "Lost"}, navigationSelected));
            layout.addView(buildFeedbackCategoryRow("Merchant Wait",
                    new String[]{"Fast", "Okay", "Slow"}, merchantWaitSelected));
            layout.addView(buildFeedbackCategoryRow("Customer",
                    new String[]{"Nice", "Neutral", "Rude"}, customerSelected));
            layout.addView(buildFeedbackCategoryRow("Overall",
                    new String[]{"Good", "Okay", "Bad"}, overallSelected));

            layout.addView(notesInput);

            android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
            scrollView.addView(layout);

            new AlertDialog.Builder(this)
                    .setTitle("Rate This Delivery")
                    .setMessage("How was this offer, in your own judgment? Tap what applies -- all optional.")
                    .setView(scrollView)
                    .setPositiveButton("Save Rating", (dialog, which) -> {
                        int rating = Math.round(ratingBar.getRating());
                        String notes = notesInput.getText().toString().trim();
                        try {
                            engine.callAttr("save_trip_feedback", tripId, rating, notes,
                                    parkingSelected[0], navigationSelected[0], merchantWaitSelected[0],
                                    customerSelected[0], overallSelected[0]);
                            logDiagnostic("BUTTON", "Trip feedback saved: rating=" + rating);
                            // Recalculate personal calibration with this new data point --
                            // cheap enough to run on every save, and keeps it current
                            // rather than requiring a separate manual trigger.
                            engine.callAttr("recalculate_personal_calibration");

                            // Feeds the same Easy/Okay/Hard answer into the
                            // per-restaurant parking-difficulty learning --
                            // only if a real gap was actually measured this
                            // trip AND the user answered the parking question.
                            if (finalPendingParkingRestaurant != null && parkingSelected[0] != null) {
                                String difficulty = "Easy".equals(parkingSelected[0]) ? "easy"
                                        : "Hard".equals(parkingSelected[0]) ? "difficult" : "normal";
                                engine.callAttr("record_parking_difficulty_feedback",
                                        finalPendingParkingRestaurant, finalPendingParkingGapSeconds, difficulty,
                                        finalPendingParkingFeedbackId);
                            }
                            engine.callAttr("clear_last_parking_gap_for_feedback");

                            Toast.makeText(this, "Feedback saved.", Toast.LENGTH_SHORT).show();
                        } catch (RuntimeException e) { // covers PyException too
                            Toast.makeText(this, "Could not save feedback: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("Skip", (dialog, which) -> {
                        // Still clears the pending gap even if the whole
                        // dialog is skipped -- otherwise it could linger and
                        // get attached to a later, unrelated trip's feedback.
                        try {
                            engine.callAttr("clear_last_parking_gap_for_feedback");
                        } catch (RuntimeException e) { // covers PyException too
                            logDiagnostic("ERROR", "clear_last_parking_gap_for_feedback exception: "
                                    + android.util.Log.getStackTraceString(e));
                        }
                    })
                    .show();
        }

    /**
         * One quick-tap category row: a label followed by 3 buttons in a
         * horizontal row, radio-style (tapping one deselects the others in
         * the same row). Tracks the current selection in selectedHolder[0].
         */
        private android.widget.LinearLayout buildFeedbackCategoryRow(
                String categoryLabel, String[] options, String[] selectedHolder) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.VERTICAL);
            int topMargin = (int) (8 * getResources().getDisplayMetrics().density);
            row.setPadding(0, topMargin, 0, 0);

            TextView label = new TextView(this);
            label.setText(categoryLabel);
            label.setTextSize(13f);
            row.addView(label);

            android.widget.LinearLayout buttonRow = new android.widget.LinearLayout(this);
            buttonRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);

            Button[] buttons = new Button[options.length];
            for (int i = 0; i < options.length; i++) {
                Button button = new Button(this);
                button.setText(options[i]);
                button.setTextSize(11f);
                android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                button.setLayoutParams(params);
                buttons[i] = button;
            }

            for (int i = 0; i < options.length; i++) {
                String option = options[i];
                Button thisButton = buttons[i];
                thisButton.setOnClickListener(v -> {
                    boolean alreadySelected = option.equals(selectedHolder[0]);
                    selectedHolder[0] = alreadySelected ? null : option;
                    for (Button b : buttons) {
                        boolean isNowSelected = b.getText().toString().equals(selectedHolder[0]);
                        b.setBackgroundColor(isNowSelected
                                ? android.graphics.Color.parseColor("#1565C0")
                                : android.graphics.Color.LTGRAY);
                        b.setTextColor(isNowSelected ? android.graphics.Color.WHITE : android.graphics.Color.BLACK);
                    }
                });
                buttonRow.addView(thisButton);
            }

            row.addView(buttonRow);
            return row;
        }

    /**
     * Real CI failure fixed here (PR #38): this is still used by
     * showFeedbackDialog()'s own embedded "where the time went" recap
     * (see that method's own doc -- now redundant with TripDetailActivity's
     * card but deliberately left untouched, per docs/trip_history_redesign/
     * PROGRESS.md) -- deleting it alongside buildTripSummaryBody() was
     * wrong; that method was never its only caller in this file.
     */
    private String formatMinutesSeconds(double totalSeconds) {
        int rounded = (int) Math.round(Math.abs(totalSeconds));
        int minutes = rounded / 60;
        int seconds = rounded % 60;
        return minutes > 0 ? minutes + "m " + seconds + "s" : seconds + "s";
    }

    /**
     * Same computation as TripDetailActivity's own formatPercentOfTotal --
     * this dialog's phase-timing breakdown is a disclosed duplicate of that
     * screen's "Where The Time Went" card (see the comment above the call
     * site in showFeedbackDialog), and previously showed only the raw
     * duration with no share-of-total suffix, unlike that card.
     */
    private String formatPercentOfTotal(double phaseSeconds, double totalSeconds) {
        long pct = Math.round((phaseSeconds / totalSeconds) * 100);
        return pct + "%";
    }
}
