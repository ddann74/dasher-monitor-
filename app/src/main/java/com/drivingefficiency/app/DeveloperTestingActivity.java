package com.drivingefficiency.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class DeveloperTestingActivity extends AppCompatActivity {

    private PyObject engine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_developer_testing);
        engine = PythonBridge.getEngine(this);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Developer Testing");
        }

        Button simulateOfferButton = findViewById(R.id.simulateOfferButton);
        Button simulateMessageButton = findViewById(R.id.simulateMessageButton);
        Button simulateTrustedButton = findViewById(R.id.simulateTrustedButton);
        Button simulateArrivalButton = findViewById(R.id.simulateArrivalButton);
        Button addTestStopButton = findViewById(R.id.addTestStopButton);
        Button simulateDashPausedButton = findViewById(R.id.simulateDashPausedButton);
        Button simulateOfferOutcomesButton = findViewById(R.id.simulateOfferOutcomesButton);

        // Developer testing: same code paths the real Dasher app/GPS would
        // trigger, fed canned data instead -- lets you see the badge, hear
        // the TTS, and confirm the state machine on an emulator with no
        // Dasher account and no real GPS movement needed.
        simulateOfferButton.setOnClickListener(v -> simulateOfferScreen());
        simulateMessageButton.setOnClickListener(v -> simulateCustomerMessage());
        simulateTrustedButton.setOnClickListener(v -> simulateTrustedAndUnknownText());
        simulateArrivalButton.setOnClickListener(v -> simulateDriveAndArrival());
        addTestStopButton.setOnClickListener(v -> addTestStopNearby());
        simulateDashPausedButton.setOnClickListener(v -> simulateDashPausedResumed());
        simulateOfferOutcomesButton.setOnClickListener(v -> simulateOfferOutcomes());
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    /** Mirrors DasherAccessibilityService.handleOfferResult(), fed a canned offer screen. */
        private void simulateOfferScreen() {
            if (blockedByLiveMonitoring()) {
                return;
            }
            try {
                JSONArray offerLines = new JSONArray();
                offerLines.put("$13.65");
                offerLines.put("Guaranteed");
                offerLines.put("5.1 km");
                offerLines.put("Deliver by 4:25 pm");
                offerLines.put("Pickup");
                offerLines.put("KFC Fairy Meadow");
                offerLines.put("Customer drop-off");
                offerLines.put("Accept");
                offerLines.put("17");

                String resultJson = engine.callAttr("parse_offer_screen", offerLines.toString()).toString();
                JSONObject parsed = new JSONObject(resultJson);
                JSONObject score = parsed.optJSONObject("smart_score");
                if (score == null) {
                    Toast.makeText(this, "Offer parsing returned no score.", Toast.LENGTH_SHORT).show();
                    return;
                }

                double finalScore = score.optDouble("final_score", 0);
                String label = score.optString("label", "");

                // Matches the real live badge exactly (see
                // DasherAccessibilityService.handleOfferResult) --
                // $/km and $/hr included per explicit request; everything
                // else (deadhead, wait, traffic, weather) still stays out
                // of the live view, only in the post-trip summary.
                String restaurantWarning = score.isNull("restaurant_warning") ? null
                        : score.optString("restaurant_warning", null);
                String warningLine = restaurantWarning != null ? "\n\u26A0 " + restaurantWarning : "";
                double perKm = score.optDouble("base_rate_per_km", 0);
                double perHr = score.optDouble("hourly_rate", 0);

                String badgeText = String.format(
                        "Smart Score: %.0f/100 - %s\n$%.2f/km   $%.2f/hr%s",
                        finalScore, label, perKm, perHr, warningLine);
                // Mirrors DasherAccessibilityService.colorForLabel() -- kept in
                // sync manually since it's a small static color mapping.
                int color;
                switch (label) {
                    case "Excellent":
                        color = android.graphics.Color.parseColor("#CC1B5E20"); // deep green
                        break;
                    case "Good":
                        color = android.graphics.Color.parseColor("#CC43A047"); // lighter green
                        break;
                    case "Fair":
                        color = android.graphics.Color.parseColor("#CCF9A825"); // amber
                        break;
                    default:
                        color = android.graphics.Color.parseColor("#CCC62828"); // red
                }
                OverlayHelper.showMessage(this, badgeText, 8000, color);
                VoiceAnnouncer.speak(String.format("Smart score %d, %s. %.2f dollars per kilometer, "
                        + "%.2f dollars per hour", Math.round(finalScore), label, perKm, perHr));
                engine.callAttr("add_pickup", parsed.optString("restaurant_name", ""),
                        -33.900, 151.200, parsed.optDouble("distance_km", 0.0));
                Toast.makeText(this, "Simulated offer shown -- badge auto-clears in 8s.", Toast.LENGTH_SHORT).show();
            } catch (JSONException | PyException e) {
                Toast.makeText(this, "Simulation error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

    /** Mirrors AppNotificationListenerService's Dasher-message path, fed a canned SMS. */
        private void simulateCustomerMessage() {
            if (blockedByLiveMonitoring()) {
                return;
            }
            try {
                String body = "Please leave it at the back door, thank you!";
                PyObject result = engine.callAttr("on_notification",
                        "com.doordash.driverapp", "Customer", body, System.currentTimeMillis(), true);
                if (result != null) {
                    String instruction = result.toString();
                    VoiceAnnouncer.speak("Customer message: " + VoiceAnnouncer.stripCategoryPrefix(instruction));
                    Toast.makeText(this, "Extracted: " + instruction, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "No instruction recognized in canned message.", Toast.LENGTH_SHORT).show();
                }
            } catch (RuntimeException e) { // covers PyException too
                Toast.makeText(this, "Simulation error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

    /** Mirrors the personal-messages allowlist path for one trusted and one unknown sender. */
        private void simulateTrustedAndUnknownText() {
            if (blockedByLiveMonitoring()) {
                return;
            }
            try {
                engine.callAttr("add_trusted_sender", "Mom");
                boolean trustedResult = engine.callAttr("is_trusted_sender", "Mom").toBoolean();
                if (trustedResult) {
                    VoiceAnnouncer.speak("Message from Mom: Don't forget milk on your way home!");
                }
                boolean unknownResult = engine.callAttr("is_trusted_sender", "+61 400 111 222").toBoolean();
                Toast.makeText(this,
                        "Mom trusted: " + trustedResult + " | Unknown number trusted: " + unknownResult,
                        Toast.LENGTH_LONG).show();
            } catch (RuntimeException e) { // covers PyException too
                Toast.makeText(this, "Simulation error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

    /**
         * Mirrors TripForegroundService's arrival-announcement pipeline, fed
         * synthetic (instant, no real waiting) GPS points instead of real
         * movement -- drives the trip from IDLE through a customer message to
         * a detected arrival with instructions attached.
         *
         * Runs on a background thread: ~77 sequential Python calls could take
         * long enough on a slow emulator to trigger an ANR if run on the UI
         * thread. Results are posted back via runOnUiThread.
         */
        private void simulateDriveAndArrival() {
            if (blockedByLiveMonitoring()) {
                return;
            }
            new Thread(() -> {
                try {
                    long clock = System.currentTimeMillis();
                    double lat = -33.905, lon = 151.205;

                    for (int i = 0; i < 12; i++) {
                        engine.callAttr("on_gps_update", lat - 0.001, lon - 0.001, 30.0, clock + i * 1000L);
                    }
                    engine.callAttr("add_stop_to_buffer", "42 Example St, Fairy Meadow", lat, lon);
                    engine.callAttr("on_notification", "com.doordash.driverapp", "Customer",
                            "Please leave it at the back door, thank you!", clock + 15000L, true);

                    boolean arrived = false;
                    String spokenText = null;
                    String overlayTextResult = null;

                    for (int i = 0; i < 65; i++) {
                        String resultJson = engine.callAttr("on_gps_update", lat, lon, 0.5,
                                clock + (20 + i) * 1000L).toString();
                        JSONObject obj = new JSONObject(resultJson);
                        if (!obj.isNull("arrival")) {
                            arrived = true;
                            JSONObject arrival = obj.getJSONObject("arrival");
                            String address = arrival.optString("address", "your stop");
                            JSONArray instructions = arrival.optJSONArray("instructions");

                            StringBuilder spoken = new StringBuilder("Arrived at " + address + ". ");
                            StringBuilder overlayText = new StringBuilder("Arrived: " + address + "\n");
                            if (instructions != null) {
                                for (int j = 0; j < instructions.length(); j++) {
                                    String clean = VoiceAnnouncer.stripCategoryPrefix(instructions.optString(j, ""));
                                    spoken.append(clean).append(". ");
                                    overlayText.append("\u2022 ").append(clean).append("\n");
                                }
                            }
                            spokenText = spoken.toString();
                            overlayTextResult = overlayText.toString();
                            break;
                        }
                    }

                    boolean arrivedFinal = arrived;
                    String spokenFinal = spokenText;
                    String overlayFinal = overlayTextResult;
                    runOnUiThread(() -> {
                        if (arrivedFinal) {
                            VoiceAnnouncer.speak(spokenFinal);
                            OverlayHelper.showMessage(this, overlayFinal);
                        }
                        Toast.makeText(this, arrivedFinal ? "Arrival detected and announced."
                                : "No arrival detected -- check geofence constants.", Toast.LENGTH_LONG).show();
                    });
                } catch (JSONException | PyException e) {
                    String message = e.getMessage();
                    runOnUiThread(() -> Toast.makeText(this,
                            "Simulation error: " + message, Toast.LENGTH_LONG).show());
                }
            }).start();
        }

        /**
         * Registers a fake delivery stop at (or very near) wherever you
         * currently are -- specifically so walking detection and the
         * RoadWarrior navigation icon can actually be exercised by
         * physically walking around, rather than only through a purely
         * simulated GPS sequence. Neither of those features can be
         * meaningfully tested without a real registered stop to measure
         * proximity against.
         */
        private void addTestStopNearby() {
            // Deliberately NOT gated by blockedByLiveMonitoring() -- unlike
            // the offer/drive simulations, this doesn't feed any fake GPS
            // timestamps into the trip state, and the whole point is
            // testing walking detection / the navigation icon WHILE
            // actually driving with real monitoring active.
            double lat = TripForegroundService.hasValidLocation ? TripForegroundService.lastKnownLat : -33.905;
            double lon = TripForegroundService.hasValidLocation ? TripForegroundService.lastKnownLon : 151.205;
            try {
                engine.callAttr("add_stop_to_buffer", "Test Stop (Developer Testing)", lat, lon);
                Toast.makeText(this, String.format("Test stop added at %.5f, %.5f -- walk toward/away from "
                        + "here to test walking detection and the navigation icon.", lat, lon),
                        Toast.LENGTH_LONG).show();
            } catch (RuntimeException e) {
                Toast.makeText(this, "Could not add test stop: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        /**
         * Feeds the real "Dash Paused" / "Resume dash" screen text
         * (confirmed against an actual screenshot -- see
         * DashPauseDetector) through the same detection used in the real
         * accessibility service, and triggers the actual pause action --
         * since no simulate coverage previously existed for this at all.
         * Deliberately NOT gated by blockedByLiveMonitoring() -- the
         * entire point is testing that this correctly pauses REAL, active
         * monitoring, which requires monitoring to actually be running
         * for the test to mean anything.
         */
        private void simulateDashPausedResumed() {
            try {
                JSONArray pausedLines = new JSONArray();
                pausedLines.put("Dash Paused");
                pausedLines.put("34:01");
                pausedLines.put("You won't get offers while you're paused");
                pausedLines.put("Resume dash");
                boolean isPaused = engine.callAttr("is_dash_paused_screen", pausedLines.toString()).toBoolean();

                Intent pauseIntent = new Intent(this, TripForegroundService.class);
                pauseIntent.setAction(TripForegroundService.ACTION_STOP_TRACKING);
                startForegroundService(pauseIntent);

                Toast.makeText(this, "is_dash_paused_screen() = " + isPaused
                        + " -- monitoring paused (matches the real screen's detection + action)", Toast.LENGTH_LONG).show();
            } catch (RuntimeException e) { // JSONArray.put()/.toString() never actually throw JSONException here (a real Gradle build confirmed this -- only checked-exception-throwing getters like getString() do), so JSONException isn't legal to catch in this specific try block
                Toast.makeText(this, "Simulation error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        /**
         * Directly exercises record_offer_outcome/record_offer_timeout --
         * previously had zero simulate coverage, meaning the Rejected
         * Offers Report, Acceptance Stats, and Personal Calibration could
         * only ever be tested by waiting for real Dasher taps or timeouts.
         * Records one of each of the three real outcomes (accepted,
         * declined, timed out) with distinct fake component breakdowns.
         */
        private void simulateOfferOutcomes() {
            // Deliberately NOT gated by blockedByLiveMonitoring() -- only
            // writes rows to the offer_outcomes table, doesn't touch trip
            // GPS state at all, so there's nothing for it to corrupt.
            try {
                String components = "{\"base_score\": 70, \"hourly_score\": 65, \"deadhead_score\": 80, "
                        + "\"wait_score\": 60, \"time_score\": 75, \"weather_score\": 90}";
                // is_test_data=true -- excluded from every report and stat
                // (Acceptance Stats, Rejected Offers Report, Full Report,
                // Personal Calibration), since a simulated test isn't a
                // real decision and shouldn't pollute real stats.
                engine.callAttr("record_offer_outcome", "Test Accepted Place", 18.0, 4.5, 85.0, true, components, true);
                engine.callAttr("record_offer_outcome", "Test Declined Place", 6.0, 9.0, 30.0, false, components, true);
                engine.callAttr("record_offer_timeout", "Test Timed Out Place", 9.5, 6.0, 55.0, components, true);
                Toast.makeText(this, "Recorded one accepted, one declined, and one timed-out TEST offer -- "
                        + "these are excluded from Acceptance Stats and every other report.", Toast.LENGTH_LONG).show();
            } catch (RuntimeException e) {
                Toast.makeText(this, "Simulation error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

    /**
         * True (and shows a warning) if real monitoring is active. The
         * simulate buttons share the same Python engine singleton as
         * TripForegroundService, so running both at once would interleave
         * real and fake GPS timestamps into the same trip state and corrupt it.
         */
        private boolean blockedByLiveMonitoring() {
            if (TripForegroundService.isRunning) {
                Toast.makeText(this, "Stop Monitoring first -- simulations share the live engine.",
                        Toast.LENGTH_LONG).show();
                return true;
            }
            return false;
        }
}
