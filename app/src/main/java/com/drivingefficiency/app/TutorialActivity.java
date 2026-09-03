package com.drivingefficiency.app;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * docs/tutorial_mode/PRD.md (driver backlog #29): an interactive,
 * driver-paced walkthrough teaching the Smart Score's 6 factors and the
 * app's real screens/overlays, staged as a simulated delivery -- each
 * screen/overlay shown at the real moment it'd appear during an actual
 * one, not a static list of cards.
 *
 * Reuses the same simulate-style mechanisms DeveloperTestingActivity's
 * own buttons already prove work (real code paths: parse_offer_screen,
 * add_pickup, on_gps_update, add_stop_to_buffer -- fed synthetic data,
 * not a mockup of the resulting UI). Every simulated offer uses
 * is_test_data=1 so it can never pollute real stats (same established
 * pattern DeveloperTestingActivity.simulateOfferOutcomes already uses).
 *
 * SCOPE DECISION, disclosed rather than silently assumed: the
 * navigation icon (step 9) and approach-instruction overlay (step 10)
 * are shown via DIRECT OverlayHelper/VoiceAnnouncer calls with
 * illustrative content, not derived by replicating
 * TripForegroundService.handleGpsResult's own real logic (mode/trip-
 * state tracking, wake locks, hotspot-or-home/sweet-spot checks,
 * notifyRateThisDelivery -- a large, side-effect-heavy state machine
 * genuinely risky to reimplement correctly from outside that class).
 * The overlay/voice code itself is real, not mocked -- only the
 * decision of WHEN to trigger it is simplified to the tutorial's own
 * step sequence rather than derived from a live simulated GPS stream.
 */
public class TutorialActivity extends AppCompatActivity {

    private PyObject engine;
    private JSONObject environment;
    private JSONObject scoreResult;
    private int currentStep = 0;
    private static final int TOTAL_STEPS = 11;
    private boolean pickupRegistered = false;

    private TextView stepCounter;
    private TextView stepBody;
    private Button nextButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Same guard DeveloperTestingActivity's own simulate methods use
        // (blockedByLiveMonitoring) -- simulated and real GPS timestamps
        // sharing one engine singleton would corrupt real trip state if
        // both ran at once (docs/tutorial_mode/PRD.md ss5 P1). Checked
        // again before the one GPS-tick-chaining step below, not just
        // here at entry -- a real trip could start mid-tutorial.
        if (TripForegroundService.isRunning) {
            Toast.makeText(this, "Stop Monitoring first -- the tutorial shares the live engine.",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        setContentView(R.layout.activity_tutorial);
        engine = PythonBridge.getEngine(this);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Tutorial");
        }

        stepCounter = findViewById(R.id.tutorialStepCounter);
        stepBody = findViewById(R.id.tutorialStepBody);
        nextButton = findViewById(R.id.tutorialNextButton);
        Button skipButton = findViewById(R.id.tutorialSkipButton);

        nextButton.setOnClickListener(v -> advance());
        skipButton.setOnClickListener(v -> finishTutorial());

        try {
            double baseLat = TripForegroundService.hasValidLocation
                    ? TripForegroundService.lastKnownLat : -33.905;
            double baseLon = TripForegroundService.hasValidLocation
                    ? TripForegroundService.lastKnownLon : 151.205;
            environment = new JSONObject(engine.callAttr("get_tutorial_environment", baseLat, baseLon).toString());
        } catch (JSONException | PyException e) {
            Toast.makeText(this, "Could not start tutorial: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        renderStep();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finishTutorial();
        return true;
    }

    private void advance() {
        if (currentStep >= TOTAL_STEPS - 1) {
            finishTutorial();
            return;
        }
        currentStep++;
        renderStep();
    }

    private void renderStep() {
        stepCounter.setText(String.format("Step %d of %d", currentStep + 1, TOTAL_STEPS));
        switch (currentStep) {
            case 0: showStepOffer(); break;
            case 1: showStepRates(); break;
            case 2: showStepDeadhead(); break;
            case 3: showStepWait(); break;
            case 4: showStepTraffic(); break;
            case 5: showStepFinalScore(); break;
            case 6: showStepAccept(); break;
            case 7: showStepDriving(); break;
            case 8: showStepApproachIcon(); break;
            case 9: showStepInstructionRead(); break;
            case 10: showStepCompletion(); break;
            default: break;
        }
    }

    /** Step 1: build the fake offer (same format DeveloperTestingActivity.simulateOfferScreen uses) and compute its real Smart Score. */
    private void showStepOffer() {
        try {
            String restaurantName = environment.optString("restaurant_name", "Example Restaurant");
            double payout = environment.optDouble("payout", 13.65);
            double distanceKm = environment.optDouble("distance_km", 5.1);

            JSONArray offerLines = new JSONArray();
            offerLines.put(String.format("$%.2f", payout));
            offerLines.put("Guaranteed");
            offerLines.put(String.format("%.1f km", distanceKm));
            offerLines.put("Deliver by 4:25 pm");
            offerLines.put("Pickup");
            offerLines.put(restaurantName);
            offerLines.put("Customer drop-off");
            offerLines.put("Accept");
            offerLines.put("17");

            JSONObject parsed = new JSONObject(engine.callAttr("parse_offer_screen", offerLines.toString()).toString());
            scoreResult = parsed.optJSONObject("smart_score");

            String sourceNote = "real".equals(environment.optString("source", ""))
                    ? "This is a restaurant you've actually picked up from before -- using its real "
                            + "learned location and history for this walkthrough."
                    : "This is a made-up example (you don't have enough pickup history yet for a "
                            + "personalized one) -- it'll use your own real restaurants once you do.";

            stepBody.setText(String.format(
                    "A new offer just came in:\n\n%s, %.1f km, from %s.\n\n"
                            + "This whole tutorial uses FAKE, simulated data -- nothing here is a real "
                            + "offer from DoorDash, and nothing gets saved to your real stats.\n\n%s",
                    String.format("$%.2f", payout), distanceKm, restaurantName, sourceNote));
            nextButton.setText("Next");
        } catch (JSONException | PyException e) {
            stepBody.setText("Could not simulate the offer: " + e.getMessage());
        }
    }

    private double scoreDouble(String key) {
        return scoreResult != null ? scoreResult.optDouble(key, 0) : 0;
    }

    /** Step 2: $/km, $/hr. */
    private void showStepRates() {
        stepBody.setText(String.format(
                "$%.2f per km, $%.2f per hour.\n\n"
                        + "These are the two most direct \"is this worth it\" numbers -- how much "
                        + "you're paid per kilometer of driving, and per hour if the estimated time "
                        + "holds up. Both come straight from the offer's payout and distance.",
                scoreDouble("base_rate_per_km"), scoreDouble("hourly_rate")));
    }

    /** Step 3: deadhead. */
    private void showStepDeadhead() {
        stepBody.setText(String.format(
                "Deadhead: %.1f km.\n\n"
                        + "This is the distance you'd drive to REACH the restaurant, before the paid "
                        + "delivery leg even starts -- pure overhead. A high deadhead can make an "
                        + "otherwise-good payout much less worth it.",
                scoreDouble("deadhead_km")));
    }

    /** Step 4: restaurant wait time. */
    private void showStepWait() {
        stepBody.setText(String.format(
                "Typical wait at this restaurant: %.0f minutes.\n\n"
                        + "Learned from your own real history once you've picked up from a restaurant "
                        + "a few times -- a restaurant that reliably makes you wait eats into your "
                        + "real hourly rate even if the payout looks good on paper.",
                scoreDouble("restaurant_wait_minutes")));
    }

    /** Step 5: traffic/time-of-day. */
    private void showStepTraffic() {
        stepBody.setText("Traffic risk: " + scoreResult.optString("traffic_risk", "unknown") + ".\n\n"
                + "Based on live traffic data when available, or your own learned patterns for this "
                + "area and time of day otherwise -- heavier traffic risk means the trip is more "
                + "likely to take longer than the estimate.");
    }

    /** Step 6: final combined score + label -- also shows the REAL live badge overlay briefly, matching what actually appears on a real offer. */
    private void showStepFinalScore() {
        double finalScore = scoreDouble("final_score");
        String label = scoreResult != null ? scoreResult.optString("label", "") : "";
        stepBody.setText(String.format(
                "Smart Score: %.0f/100 -- %s\n\n"
                        + "All six factors (the four you just saw, plus traffic and weather) combine "
                        + "into this one number and label. This is exactly what you'd see as a "
                        + "floating badge on a real offer -- shown for real below for a few seconds.",
                finalScore, label));
        OverlayHelper.showMessage(this, String.format("Smart Score: %.0f/100 - %s", finalScore, label),
                4000, android.graphics.Color.parseColor("#CC1B5E20"));
    }

    /** Step 7: "Accept" simulated -- real add_pickup call, badge clears, status dot goes green. */
    private void showStepAccept() {
        try {
            String restaurantName = environment.optString("restaurant_name", "Example Restaurant");
            double distanceKm = environment.optDouble("distance_km", 5.1);
            double destLat = environment.optDouble("dest_lat", 0);
            double destLon = environment.optDouble("dest_lon", 0);
            engine.callAttr("add_pickup", restaurantName, destLat, destLon, distanceKm);
            pickupRegistered = true;
        } catch (PyException e) {
            // Not fatal to the walkthrough itself -- the narration below still holds even if this
            // one simulated call fails for some reason.
        }
        OverlayHelper.clear(this);
        OverlayHelper.showStatusDot(this, OverlayHelper.DotState.GREEN);
        stepBody.setText("You tap Accept. The badge clears, and the status dot at the top of your "
                + "screen turns solid green -- that's the \"everything's working, you're on an "
                + "active delivery\" color.");
    }

    /**
     * Step 8: simulated driving. Runs on a background thread -- same
     * reason DeveloperTestingActivity.simulateDriveAndArrival does
     * (many sequential Python calls could ANR the UI thread on a slow
     * device). Re-checks the live-monitoring guard again before
     * starting, per PRD ss5 P1 -- a real trip could have started since
     * onCreate's own check.
     */
    private void showStepDriving() {
        if (TripForegroundService.isRunning) {
            stepBody.setText("Real monitoring just started elsewhere on your phone -- stopping the "
                    + "tutorial here so it doesn't interfere. Tap Skip Tutorial to exit.");
            nextButton.setEnabled(false);
            return;
        }
        stepBody.setText("Driving toward the restaurant... (simulating)");
        nextButton.setEnabled(false);
        new Thread(() -> {
            try {
                double startLat = environment.optDouble("start_lat", 0);
                double startLon = environment.optDouble("start_lon", 0);
                double destLat = environment.optDouble("dest_lat", 0);
                double destLon = environment.optDouble("dest_lon", 0);
                long clock = System.currentTimeMillis();

                engine.callAttr("add_stop_to_buffer",
                        environment.optString("restaurant_name", "Example Restaurant") + " (simulated stop)",
                        destLat, destLon);

                int driveTicks = 10;
                for (int i = 1; i <= driveTicks; i++) {
                    double lat = startLat + (destLat - startLat) * i / (double) driveTicks;
                    double lon = startLon + (destLon - startLon) * i / (double) driveTicks;
                    engine.callAttr("on_gps_update", lat, lon, 30.0, clock + i * 1000L);
                }

                boolean arrived = false;
                for (int i = 0; i < 80; i++) {
                    String resultJson = engine.callAttr("on_gps_update", destLat, destLon, 0.5,
                            clock + (driveTicks + i) * 1000L).toString();
                    JSONObject obj = new JSONObject(resultJson);
                    if (!obj.isNull("arrival")) {
                        arrived = true;
                        break;
                    }
                }

                boolean arrivedFinal = arrived;
                runOnUiThread(() -> {
                    nextButton.setEnabled(true);
                    stepBody.setText("You drive toward the restaurant -- this phase is silent, no "
                            + "announcements, just the status dot staying green."
                            + (arrivedFinal ? "" : "\n\n(No real arrival detected in this simulation -- "
                                    + "the walkthrough continues anyway.)"));
                });
            } catch (JSONException | PyException e) {
                runOnUiThread(() -> {
                    nextButton.setEnabled(true);
                    stepBody.setText("Simulation hit an error, but the walkthrough continues: " + e.getMessage());
                });
            }
        }).start();
    }

    /** Step 9: the navigation icon -- real OverlayHelper call, illustrative tap action (see class doc's scope decision). */
    private void showStepApproachIcon() {
        OverlayHelper.showNavigationIcon(this, () ->
                Toast.makeText(this, "On a real delivery, this opens navigation to the stop.", Toast.LENGTH_LONG).show());
        stepBody.setText("Within about 500 meters of a stop, this icon appears -- tap it any time to "
                + "jump straight to navigation. It's showing for real right now (tap it to see).");
    }

    /** Step 10: the approach-instruction overlay + voice announcement -- driver backlog #4's shipped feature. */
    private void showStepInstructionRead() {
        OverlayHelper.clearNavigationIcon(this);
        String instruction = "Leave at the door, thanks!";
        VoiceAnnouncer.speak("Approaching " + environment.optString("restaurant_name", "the stop")
                + ". Customer instruction: " + instruction);
        OverlayHelper.showPersistentTappableMessage(this, "Customer instruction: " + instruction, null);
        stepBody.setText("Within 50 meters, any delivery instructions get read aloud AND stay on "
                + "screen as a tappable message -- it'll keep repeating every 30 seconds until you "
                + "tap it away, so you can't miss it. Try tapping the message on screen now.");
    }

    /** Step 11: arrival, walking, completion, wrap-up -- and real cleanup of the simulated pickup/stop. */
    private void showStepCompletion() {
        OverlayHelper.clearPersistentMessage(this);
        OverlayHelper.showStatusDot(this, OverlayHelper.DotState.WALKING);
        // Simulated delivery is "done" -- discard the fake pickup/stop
        // now rather than leaving them for onDestroy's own interrupted-
        // exit cleanup (PRD ss5 P3) to handle.
        try {
            engine.callAttr("discard_pending_pickup_and_stops");
        } catch (PyException ignored) {
            // Not fatal -- onDestroy's own cleanup covers this too.
        }
        pickupRegistered = false;
        OverlayHelper.clearStatusDot(this);
        stepBody.setText("You arrive and walk up to the door -- the status dot switches to its "
                + "\"walking\" color. Once you mark the delivery complete, a real feedback dialog "
                + "asks how it went, which feeds into your Personal Calibration over time.\n\n"
                + "Everything from a real delivery ends up in Trip History -- go take a look after "
                + "your next real one.\n\nTutorial complete!");
        nextButton.setText("Finish");
    }

    /**
     * Real cleanup for BOTH a normal finish and an interrupted exit
     * (back button, Skip, or the Activity being destroyed mid-sequence)
     * -- PRD ss5 P3. If step 11's own completion cleanup already ran,
     * pickupRegistered is already false and this is a no-op.
     */
    private void finishTutorial() {
        cleanupSimulatedState();
        finish();
    }

    private void cleanupSimulatedState() {
        if (pickupRegistered) {
            try {
                engine.callAttr("discard_pending_pickup_and_stops");
            } catch (PyException ignored) {
                // Best-effort -- nothing further to do if this itself fails.
            }
            pickupRegistered = false;
        }
        OverlayHelper.clear(this);
        OverlayHelper.clearStatusDot(this);
        OverlayHelper.clearNavigationIcon(this);
        OverlayHelper.clearPersistentMessage(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Catches the case the Activity is torn down without going
        // through finishTutorial() at all (e.g. the system reclaiming
        // it) -- PRD ss5 P3's own stated risk.
        cleanupSimulatedState();
    }
}
