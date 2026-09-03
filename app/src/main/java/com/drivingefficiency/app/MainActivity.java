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

            // Opened via the "Rate this delivery" notification -- shows
            // the existing, already-working feedback dialog immediately,
            // rather than requiring the manual Last Trip Summary -> OK
            // path. Fixed a real, confirmed build error here: this was
            // originally (incorrectly) added to TripHistoryActivity,
            // but showFeedbackDialog actually lives in MainActivity.
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
                showLastTripSummaryThenPromptFeedback();
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
            statusText.setText("Status: " + stateLabel + " -- " + modeLabel + warning + "\n" + buildLastUpdateLine());
            updatePickupNoteButton();
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

    /**
         * Used specifically right after "Stop Monitoring" -- shows the trip
         * summary, then (once dismissed) prompts for your own rating and
         * optional notes on that trip. This is the actual data collection
         * for eventually checking whether the Smart Score's predictions
         * track what you consider a good delivery -- see
         * save_trip_feedback()'s docstring for the honest scope note on what
         * this does and doesn't do yet.
         */
        private void showLastTripSummaryThenPromptFeedback() {
            try {
                JSONObject summary = new JSONObject(engine.callAttr("get_last_trip_summary").toString());
                if (!summary.optBoolean("found", false)) {
                    // showTripSummaryDialog() moved to TripHistoryActivity
                    // along with the rest of the trip-browsing screen --
                    // this simple "nothing to show" case is inlined here
                    // directly rather than reaching across activities for it.
                    new AlertDialog.Builder(this)
                            .setTitle("Last Trip Summary")
                            .setMessage("No completed trips yet.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }
                int tripId = summary.optInt("trip_id", -1);
                boolean isDasherTrip = "DASHER".equals(summary.optString("mode", ""));

                StringBuilder body = buildTripSummaryBody(summary);

                new AlertDialog.Builder(this)
                        .setTitle("Last Trip Summary")
                        .setMessage(body.toString())
                        .setPositiveButton("OK", (dialog, which) -> {
                            if (tripId >= 0 && isDasherTrip) {
                                showFeedbackDialog(tripId);
                            }
                        })
                        .show();
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
            // never showed any context about the trip itself, even though
            // the exact same phase-by-phase breakdown already displays
            // correctly in buildTripSummaryBody for the separate MANUAL
            // "Last Trip Summary" flow. Uses get_trip_summary_by_id (not
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
                    TextView phaseTimingText = new TextView(this);
                    StringBuilder phaseBody = new StringBuilder("Where the time went:\n");
                    if (!phaseBreakdown.isNull("driving_to_pickup_seconds")) {
                        phaseBody.append(String.format("Driving to pickup: %s\n",
                                formatMinutesSeconds(phaseBreakdown.optDouble("driving_to_pickup_seconds", 0))));
                    }
                    if (!phaseBreakdown.isNull("wait_at_restaurant_seconds")) {
                        phaseBody.append(String.format("Waiting at restaurant: %s\n",
                                formatMinutesSeconds(phaseBreakdown.optDouble("wait_at_restaurant_seconds", 0))));
                    }
                    if (!phaseBreakdown.isNull("driving_to_dropoff_seconds")) {
                        phaseBody.append(String.format("Driving to dropoff: %s\n",
                                formatMinutesSeconds(phaseBreakdown.optDouble("driving_to_dropoff_seconds", 0))));
                    }
                    if (!phaseBreakdown.isNull("parking_to_walking_seconds")) {
                        phaseBody.append(String.format("Parking to walking: %s\n",
                                formatMinutesSeconds(phaseBreakdown.optDouble("parking_to_walking_seconds", 0))));
                    }
                    if (!phaseBreakdown.isNull("completing_dropoff_seconds")) {
                        phaseBody.append(String.format("Completing dropoff: %s\n",
                                formatMinutesSeconds(phaseBreakdown.optDouble("completing_dropoff_seconds", 0))));
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

    /** Shared by showTripSummaryDialog() and showLastTripSummaryThenPromptFeedback(). */
        private StringBuilder buildTripSummaryBody(JSONObject summary) {
            StringBuilder body = new StringBuilder();
            String tripMode = summary.optString("mode", "GENERAL");
            body.append("Mode: ").append("DASHER".equals(tripMode) ? "Dasher Delivery" : "General Driving").append("\n\n");

            JSONObject offerSnapshot = summary.optJSONObject("offer_score_snapshot");
            if (offerSnapshot != null) {
                body.append("--- Original Offer Assessment ---\n");
                body.append(offerSnapshot.optString("verdict_sentence", "")).append("\n\n");
                body.append(String.format("Smart Score: %.0f/100 - %s\n",
                        offerSnapshot.optDouble("final_score", 0), offerSnapshot.optString("label", "")));
                body.append(String.format("$/km: $%.2f   $/hr: $%.2f\n",
                        offerSnapshot.optDouble("base_rate_per_km", 0), offerSnapshot.optDouble("hourly_rate", 0)));
                body.append(String.format("Deadhead: %.1f km\n", offerSnapshot.optDouble("deadhead_km", 0)));
                body.append(String.format("Pickup wait: %.0f min\n", offerSnapshot.optDouble("restaurant_wait_minutes", 0)));
                body.append("Traffic: ").append(offerSnapshot.optString("traffic_risk", "")).append("\n");
                body.append("Weather: ").append(offerSnapshot.optString("weather", "")).append("\n\n");
            }

            // Real street address for the pickup, not just the restaurant
            // name -- previously never captured or shown anywhere.
            String pickupAddress = summary.optString("pickup_address", "");
            if (!pickupAddress.isEmpty()) {
                body.append("Pickup address: ").append(pickupAddress).append("\n\n");
            }

            // Phase-by-phase timing breakdown: where did the time
            // actually go for THIS delivery, not just a learned average.
            // Any phase not captured (no walking detected, no pickup this
            // trip, or an older trip from before this existed) is simply
            // omitted rather than guessed at.
            JSONObject phaseBreakdown = summary.optJSONObject("phase_breakdown");
            if (phaseBreakdown != null && phaseBreakdown.length() > 0) {
                body.append("--- Where The Time Went ---\n");
                if (!phaseBreakdown.isNull("driving_to_pickup_seconds")) {
                    body.append(String.format("Driving to pickup: %s\n",
                            formatMinutesSeconds(phaseBreakdown.optDouble("driving_to_pickup_seconds", 0))));
                }
                if (!phaseBreakdown.isNull("wait_at_restaurant_seconds")) {
                    body.append(String.format("Waiting at restaurant: %s\n",
                            formatMinutesSeconds(phaseBreakdown.optDouble("wait_at_restaurant_seconds", 0))));
                }
                if (!phaseBreakdown.isNull("driving_to_dropoff_seconds")) {
                    body.append(String.format("Driving to dropoff: %s\n",
                            formatMinutesSeconds(phaseBreakdown.optDouble("driving_to_dropoff_seconds", 0))));
                }
                if (!phaseBreakdown.isNull("parking_to_walking_seconds")) {
                    body.append(String.format("Parking to walking: %s\n",
                            formatMinutesSeconds(phaseBreakdown.optDouble("parking_to_walking_seconds", 0))));
                }
                if (!phaseBreakdown.isNull("completing_dropoff_seconds")) {
                    body.append(String.format("Completing dropoff: %s\n",
                            formatMinutesSeconds(phaseBreakdown.optDouble("completing_dropoff_seconds", 0))));
                }
                body.append("\n");
            }
            JSONObject deadlineComparison = summary.optJSONObject("deadline_comparison");
            if (deadlineComparison != null) {
                boolean wasLate = deadlineComparison.optBoolean("was_late", false);
                double diffSeconds = Math.abs(deadlineComparison.optDouble("seconds_relative_to_deadline", 0));
                body.append(String.format("Deadline was %s -- %s by %s\n\n",
                        deadlineComparison.optString("deadline_text", ""),
                        wasLate ? "LATE" : "on time, with", formatMinutesSeconds(diffSeconds)));
            }

            body.append(String.format("Distance: %.2f km\n", summary.optDouble("distance_km", 0)));
            body.append(String.format("Time efficiency: %.0f%%\n", summary.optDouble("time_efficiency_score", 0)));
            body.append(String.format("Safety score: %.0f%%\n", summary.optDouble("safety_score", 0)));
            body.append(String.format("Stops completed: %.0f%%\n", summary.optDouble("geofence_hit_ratio", 0)));
            body.append(String.format("Overall score: %.0f%%\n", summary.optDouble("composite_score", 0)));
            body.append(String.format("Est. fuel cost: $%.2f\n\n", summary.optDouble("fuel_cost_estimate", 0)));

            // Safety events (harsh braking/acceleration, speeding) and major
            // delays were already being recorded into the database every
            // trip -- feeding into the safety score -- but were never shown
            // anywhere until now.
            JSONObject eventCounts = summary.optJSONObject("event_counts");
            if (eventCounts != null && eventCounts.length() > 0) {
                body.append("Safety events:\n");
                java.util.Iterator<String> keys = eventCounts.keys();
                while (keys.hasNext()) {
                    String eventType = keys.next();
                    int count = eventCounts.optInt(eventType, 0);
                    body.append("\u2022 ").append(friendlyEventTypeLabel(eventType))
                            .append(": ").append(count).append("\n");
                }
                body.append("\n");
            }
            int delayCount = summary.optInt("delay_count", 0);
            if (delayCount > 0) {
                int totalDelaySeconds = summary.optInt("total_delay_seconds", 0);
                body.append(String.format("Major delays: %d (%d min total)\n\n",
                        delayCount, totalDelaySeconds / 60));
            }

            JSONArray stops = summary.optJSONArray("stops");
            if (stops != null && stops.length() > 0) {
                body.append("Stops:\n");
                for (int i = 0; i < stops.length(); i++) {
                    JSONObject stop = stops.optJSONObject(i);
                    if (stop != null) {
                        String address = stop.optString("address", "(no address)");
                        boolean matched = stop.optBoolean("matched", false);
                        body.append("\u2022 ").append(address)
                                .append(matched ? " -- reached" : " -- not reached")
                                .append("\n");
                    }
                }
                body.append("\n");
            }

            JSONArray instructions = summary.optJSONArray("instructions");
            if (instructions != null && instructions.length() > 0) {
                body.append("Customer instructions during this trip:\n");
                for (int i = 0; i < instructions.length(); i++) {
                    JSONObject instr = instructions.optJSONObject(i);
                    if (instr != null) {
                        body.append("\u2022 ").append(VoiceAnnouncer.friendlyCategoryLabel(
                                instr.optString("extracted", ""))).append("\n");
                    }
                }
            } else {
                body.append("No customer instructions were captured this trip.");
            }

            Integer feedbackRating = summary.isNull("feedback_rating") ? null : summary.optInt("feedback_rating");
            if (feedbackRating != null) {
                body.append("\n\nYour rating: ").append(feedbackRating).append("/5");
                String feedbackNotes = summary.optString("feedback_notes", "");
                if (!feedbackNotes.isEmpty()) {
                    body.append(" -- \"").append(feedbackNotes).append("\"");
                }
            }

            return body;
        }

    /** Formats a raw seconds value as a human-readable "Xm Ys" or "Ys" string for the phase breakdown. */
        private String formatMinutesSeconds(double totalSeconds) {
            int rounded = (int) Math.round(Math.abs(totalSeconds));
            int minutes = rounded / 60;
            int seconds = rounded % 60;
            return minutes > 0 ? minutes + "m " + seconds + "s" : seconds + "s";
        }

    /** Maps raw event_type strings from the events table to readable labels. */
        private String friendlyEventTypeLabel(String eventType) {
            switch (eventType) {
                case "harsh_brake":
                    return "Harsh braking";
                case "harsh_accel":
                    return "Harsh acceleration";
                case "speeding":
                    return "Speeding";
                default:
                    return eventType;
            }
        }
}
