package com.drivingefficiency.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * docs/trip_history_redesign/PRD.md -- replaces the single giant
 * AlertDialog.setMessage() text block buildTripSummaryBody() used to
 * build (previously duplicated near-verbatim in both MainActivity and
 * TripHistoryActivity -- both copies deleted as part of this same
 * change) with real, scannable card sections in
 * activity_trip_detail.xml.
 *
 * Two ways to reach this screen, distinguished by
 * EXTRA_PROMPT_FEEDBACK_ON_CLOSE:
 * - Right after a real delivery completes (TripForegroundService.
 *   notifyRateThisDelivery(), the ACTUAL "I just finished a delivery"
 *   moment -- see that method's own doc) -- extra is true, and the
 *   primary button reads "Rate This Delivery."
 * - Browsing (TripListActivity row tap) or via "Last Trip Summary"
 *   after tapping "Stop Monitoring" (MainActivity.
 *   showLastTripSummaryThenPromptFeedback) -- extra is false/absent,
 *   pure viewing, the primary button just reads "Done." A Dasher trip
 *   reached this way was already prompted for feedback at the moment
 *   it actually completed (or never applies, General mode) --
 *   re-prompting here would risk a confusing double-prompt.
 */
public class TripDetailActivity extends AppCompatActivity {

    public static final String EXTRA_TRIP_ID = "trip_id";
    public static final String EXTRA_PROMPT_FEEDBACK_ON_CLOSE = "prompt_feedback_on_close";

    private int tripId = -1;
    private boolean promptFeedbackOnClose = false;
    private boolean isDasherTrip = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trip_detail);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Trip Summary");
        }

        tripId = getIntent().getIntExtra(EXTRA_TRIP_ID, -1);
        promptFeedbackOnClose = getIntent().getBooleanExtra(EXTRA_PROMPT_FEEDBACK_ON_CLOSE, false);

        if (tripId < 0) {
            Toast.makeText(this, "No trip to show.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        PyObject engine = PythonBridge.getEngine(this);
        try {
            JSONObject summary = new JSONObject(
                    engine.callAttr("get_trip_summary_by_id", tripId).toString());
            if (!summary.optBoolean("found", false)) {
                Toast.makeText(this, "That trip could not be found.", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            isDasherTrip = "DASHER".equals(summary.optString("mode", ""));
            if (getSupportActionBar() != null) {
                getSupportActionBar().setSubtitle(isDasherTrip ? "Dasher Delivery" : "General Driving");
            }
            populateOfferAssessment(summary);
            populatePickupAddress(summary);
            populateStoreWaitTimer(summary);
            JSONObject phaseBreakdown = summary.optJSONObject("phase_breakdown");
            populateFullTimeDetail(summary);
            populateWhereTimeWent(summary, phaseBreakdown);
            populateDeadline(summary);
            populateTripStats(summary);
            populateSafetyEvents(summary);
            populateStops(summary);
            populateInstructions(summary);
            populateYourRating(summary);
        } catch (JSONException | PyException e) {
            Toast.makeText(this, "Could not load trip: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        com.google.android.material.button.MaterialButton primaryActionButton = findViewById(R.id.primaryActionButton);
        boolean showRateButton = promptFeedbackOnClose && isDasherTrip;
        primaryActionButton.setText(showRateButton ? "Rate This Delivery" : "Done");
        primaryActionButton.setOnClickListener(v -> {
            if (showRateButton) {
                // Reuses MainActivity's existing, already-working
                // showFeedbackDialog() flow completely unchanged (see
                // PRD ss3.4's revised design) -- same extra it already
                // reads in onCreate(), just reached one hop later than
                // before instead of directly from notifyRateThisDelivery().
                Intent intent = new Intent(this, MainActivity.class);
                intent.putExtra("auto_show_feedback_trip_id", tripId);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            }
            finish();
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void populateOfferAssessment(JSONObject summary) {
        JSONObject offer = summary.optJSONObject("offer_score_snapshot");
        com.google.android.material.card.MaterialCardView card = findViewById(R.id.offerAssessmentCard);
        if (offer == null) {
            card.setVisibility(android.view.View.GONE);
            return;
        }
        ((TextView) findViewById(R.id.offerVerdictText)).setText(offer.optString("verdict_sentence", ""));

        String label = offer.optString("label", "");
        ((TextView) findViewById(R.id.offerScoreNumberText)).setText(
                String.format(java.util.Locale.US, "%.0f", offer.optDouble("final_score", 0)));
        TextView pill = findViewById(R.id.offerScoreLabelPill);
        pill.setText(label);
        pill.setBackground(OverlayHelper.backgroundForScoreLabel(this, label));

        ((TextView) findViewById(R.id.statDollarPerKm)).setText(
                String.format(java.util.Locale.US, "$%.2f", offer.optDouble("base_rate_per_km", 0)));
        ((TextView) findViewById(R.id.statDollarPerHr)).setText(
                String.format(java.util.Locale.US, "$%.2f", offer.optDouble("hourly_rate", 0)));

        // Deadhead TIME alongside the km figure -- reuses
        // phase_breakdown's own driving_to_pickup_seconds, same reuse
        // buildTripSummaryBody already relied on (single/first-job
        // scope, see that field's own Python-side comment).
        JSONObject phaseBreakdown = summary.optJSONObject("phase_breakdown");
        String deadheadTimeSuffix = (phaseBreakdown != null && !phaseBreakdown.isNull("driving_to_pickup_seconds"))
                ? " (" + formatMinutesSeconds(phaseBreakdown.optDouble("driving_to_pickup_seconds", 0)) + ")"
                : "";
        ((TextView) findViewById(R.id.statDeadhead)).setText(
                String.format(java.util.Locale.US, "%.1f km%s", offer.optDouble("deadhead_km", 0), deadheadTimeSuffix));
        ((TextView) findViewById(R.id.statPickupWait)).setText(
                String.format(java.util.Locale.US, "%.0f min", offer.optDouble("restaurant_wait_minutes", 0)));

        String trafficRatioSuffix = offer.isNull("traffic_ratio") ? ""
                : String.format(java.util.Locale.US, " (%.0f%%)", offer.optDouble("traffic_ratio", 1.0) * 100.0);
        ((TextView) findViewById(R.id.statTraffic)).setText(offer.optString("traffic_risk", "") + trafficRatioSuffix);
        ((TextView) findViewById(R.id.statWeather)).setText(offer.optString("weather", ""));
    }

    private void populatePickupAddress(JSONObject summary) {
        String address = summary.optString("pickup_address", "");
        com.google.android.material.card.MaterialCardView card = findViewById(R.id.pickupAddressCard);
        if (address.isEmpty()) {
            card.setVisibility(android.view.View.GONE);
            return;
        }
        ((TextView) findViewById(R.id.pickupAddressText)).setText(address);
    }

    private void populateStoreWaitTimer(JSONObject summary) {
        com.google.android.material.card.MaterialCardView card = findViewById(R.id.storeWaitTimerCard);
        if (summary.isNull("store_wait_over_grace_seconds")) {
            card.setVisibility(android.view.View.GONE);
            return;
        }
        card.setVisibility(android.view.View.VISIBLE);
        ((TextView) findViewById(R.id.storeWaitTimerText)).setText(
                formatMinutesSeconds(summary.optDouble("store_wait_over_grace_seconds", 0))
                        + " beyond the 1-min grace period");
    }

    private void populateFullTimeDetail(JSONObject summary) {
        JSONObject phaseTimestamps = summary.optJSONObject("phase_timestamps");
        com.google.android.material.card.MaterialCardView card = findViewById(R.id.fullTimeDetailCard);
        LinearLayout container = findViewById(R.id.fullTimeDetailRows);
        if (phaseTimestamps == null || phaseTimestamps.length() == 0) {
            card.setVisibility(android.view.View.GONE);
            return;
        }
        java.text.SimpleDateFormat clockFormat =
                new java.text.SimpleDateFormat("h:mm:ss a", java.util.Locale.getDefault());
        String[][] clockLabels = {
                {"trip_start_ts", "Trip started"},
                {"pickup_arrival_ts", "Arrived at pickup"},
                {"pickup_departure_ts", "Left pickup"},
                {"dropoff_arrival_ts", "Arrived at dropoff"},
                {"walking_confirmed_ts", "Walking confirmed"},
                {"trip_end_ts", "Trip ended"},
        };
        for (String[] entry : clockLabels) {
            if (!phaseTimestamps.isNull(entry[0])) {
                long tsMs = (long) (phaseTimestamps.optDouble(entry[0], 0) * 1000);
                addRow(container, entry[1], clockFormat.format(new java.util.Date(tsMs)));
            }
        }
    }

    private void populateWhereTimeWent(JSONObject summary, JSONObject phaseBreakdown) {
        com.google.android.material.card.MaterialCardView card = findViewById(R.id.whereTimeWentCard);
        if (phaseBreakdown == null || phaseBreakdown.length() == 0) {
            card.setVisibility(android.view.View.GONE);
            return;
        }

        int jobCount = summary.optInt("job_count", 0);
        TextView warning = findViewById(R.id.stackedOrderWarningText);
        if (jobCount > 1) {
            warning.setVisibility(android.view.View.VISIBLE);
            warning.setText("This trip had " + jobCount + " stacked orders -- the breakdown below "
                    + "may mix timestamps from different jobs (known limitation, not yet fixed).");
        } else {
            warning.setVisibility(android.view.View.GONE);
        }

        double totalTripSeconds = summary.optDouble("end_time", 0) - summary.optDouble("start_time", 0);
        LinearLayout container = findViewById(R.id.phaseBreakdownRows);

        String waitRating = summary.optString("feedback_merchant_wait", "");
        String waitRatingSuffix = waitRating.isEmpty() ? "" : " (rated: " + waitRating + ")";

        String[][] phases = {
                {"driving_to_pickup_seconds", "Driving to pickup", ""},
                {"wait_at_restaurant_seconds", "Waiting at restaurant", waitRatingSuffix},
                {"driving_to_dropoff_seconds", "Driving to dropoff", ""},
                {"parking_to_walking_seconds", "Parking to walking", ""},
                {"completing_dropoff_seconds", "Completing dropoff", ""},
        };
        for (String[] phase : phases) {
            if (!phaseBreakdown.isNull(phase[0])) {
                double seconds = phaseBreakdown.optDouble(phase[0], 0);
                String percentSuffix = totalTripSeconds > 0
                        ? " -- " + formatPercentOfTotal(seconds, totalTripSeconds) : "";
                addRow(container, phase[1] + phase[2], formatMinutesSeconds(seconds) + percentSuffix);
            }
        }
    }

    private void populateDeadline(JSONObject summary) {
        JSONObject deadline = summary.optJSONObject("deadline_comparison");
        com.google.android.material.card.MaterialCardView card = findViewById(R.id.deadlineCard);
        if (deadline == null) {
            card.setVisibility(android.view.View.GONE);
            return;
        }
        card.setVisibility(android.view.View.VISIBLE);
        boolean wasLate = deadline.optBoolean("was_late", false);
        double diffSeconds = Math.abs(deadline.optDouble("seconds_relative_to_deadline", 0));
        ((TextView) findViewById(R.id.deadlineText)).setText(String.format(java.util.Locale.US,
                "Deliver by %s -- %s by %s", deadline.optString("deadline_text", ""),
                wasLate ? "LATE" : "on time, with", formatMinutesSeconds(diffSeconds)));
    }

    private void populateTripStats(JSONObject summary) {
        LinearLayout container = findViewById(R.id.tripStatsRows);
        addRow(container, "Distance", String.format(java.util.Locale.US, "%.2f km", summary.optDouble("distance_km", 0)));
        addRow(container, "Time efficiency", String.format(java.util.Locale.US, "%.0f%%", summary.optDouble("time_efficiency_score", 0)));
        addRow(container, "Safety score", String.format(java.util.Locale.US, "%.0f%%", summary.optDouble("safety_score", 0)));
        addRow(container, "Stops completed", String.format(java.util.Locale.US, "%.0f%%", summary.optDouble("geofence_hit_ratio", 0)));
        addRow(container, "Overall score", String.format(java.util.Locale.US, "%.0f%%", summary.optDouble("composite_score", 0)));
        addRow(container, "Est. fuel cost", String.format(java.util.Locale.US, "$%.2f", summary.optDouble("fuel_cost_estimate", 0)));

        // Major delays -- folded in here rather than its own card, per
        // driver's explicit answer (PRD ss5 #1). Omitted (not shown as
        // "0 delays") when there weren't any, same as buildTripSummaryBody
        // already did.
        int delayCount = summary.optInt("delay_count", 0);
        if (delayCount > 0) {
            int totalDelaySeconds = summary.optInt("total_delay_seconds", 0);
            addRow(container, "Major delays", delayCount + " (" + (totalDelaySeconds / 60) + " min total)");
        }
    }

    private void populateSafetyEvents(JSONObject summary) {
        JSONObject eventCounts = summary.optJSONObject("event_counts");
        com.google.android.material.card.MaterialCardView card = findViewById(R.id.safetyEventsCard);
        if (eventCounts == null || eventCounts.length() == 0) {
            card.setVisibility(android.view.View.GONE);
            return;
        }
        card.setVisibility(android.view.View.VISIBLE);
        LinearLayout container = findViewById(R.id.safetyEventsRows);
        java.util.Iterator<String> keys = eventCounts.keys();
        while (keys.hasNext()) {
            String eventType = keys.next();
            int count = eventCounts.optInt(eventType, 0);
            addRow(container, friendlyEventTypeLabel(eventType), String.valueOf(count));
        }
    }

    private void populateStops(JSONObject summary) {
        JSONArray stops = summary.optJSONArray("stops");
        com.google.android.material.card.MaterialCardView card = findViewById(R.id.stopsCard);
        if (stops == null || stops.length() == 0) {
            card.setVisibility(android.view.View.GONE);
            return;
        }
        card.setVisibility(android.view.View.VISIBLE);
        LinearLayout container = findViewById(R.id.stopsRows);
        for (int i = 0; i < stops.length(); i++) {
            JSONObject stop = stops.optJSONObject(i);
            if (stop != null) {
                String address = stop.optString("address", "(no address)");
                boolean matched = stop.optBoolean("matched", false);
                addSimpleLine(container, "• " + address + (matched ? " -- reached" : " -- not reached"));
            }
        }
    }

    private void populateInstructions(JSONObject summary) {
        JSONArray instructions = summary.optJSONArray("instructions");
        com.google.android.material.card.MaterialCardView card = findViewById(R.id.instructionsCard);
        if (instructions == null || instructions.length() == 0) {
            card.setVisibility(android.view.View.GONE);
            return;
        }
        card.setVisibility(android.view.View.VISIBLE);
        LinearLayout container = findViewById(R.id.instructionsRows);
        for (int i = 0; i < instructions.length(); i++) {
            JSONObject instr = instructions.optJSONObject(i);
            if (instr != null) {
                addSimpleLine(container, "• " + VoiceAnnouncer.friendlyCategoryLabel(
                        instr.optString("extracted", "")));
            }
        }
    }

    private void populateYourRating(JSONObject summary) {
        com.google.android.material.card.MaterialCardView card = findViewById(R.id.yourRatingCard);
        if (summary.isNull("feedback_rating")) {
            card.setVisibility(android.view.View.GONE);
            return;
        }
        card.setVisibility(android.view.View.VISIBLE);
        int rating = summary.optInt("feedback_rating");
        String notes = summary.optString("feedback_notes", "");
        String text = rating + "/5" + (notes.isEmpty() ? "" : " -- \"" + notes + "\"");
        ((TextView) findViewById(R.id.yourRatingText)).setText(text);
    }

    /** One label/value row, matching this app's plain existing text-row style -- no custom drawable/bar. */
    private void addRow(LinearLayout container, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 6, 0, 6);
        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(0xFF757575);
        labelView.setTextSize(13f);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(labelView, labelParams);

        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextColor(0xFF212121);
        valueView.setTextSize(13.5f);
        valueView.setGravity(Gravity.END);
        row.addView(valueView);

        container.addView(row);
    }

    private void addSimpleLine(LinearLayout container, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(0xFF212121);
        view.setTextSize(13f);
        view.setPadding(0, 4, 0, 4);
        container.addView(view);
    }

    /** Moved from TripHistoryActivity's now-deleted buildTripSummaryBody -- was only ever used there. */
    private String formatMinutesSeconds(double totalSeconds) {
        int rounded = (int) Math.round(Math.abs(totalSeconds));
        int minutes = rounded / 60;
        int seconds = rounded % 60;
        return minutes > 0 ? minutes + "m " + seconds + "s" : seconds + "s";
    }

    /** Moved from TripHistoryActivity's now-deleted buildTripSummaryBody -- was only ever used there. */
    private String formatPercentOfTotal(double phaseSeconds, double totalSeconds) {
        long pct = Math.round((phaseSeconds / totalSeconds) * 100);
        return pct + "%";
    }

    /** Moved from TripHistoryActivity's now-deleted buildTripSummaryBody -- was only ever used there. */
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
