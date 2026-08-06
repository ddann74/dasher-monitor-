package com.drivingefficiency.app;

import android.app.AlertDialog;
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

public class TripHistoryActivity extends AppCompatActivity {

    private PyObject engine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trip_history);
        engine = PythonBridge.getEngine(this);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Trip & History");
        }

        Button viewSummaryButton = findViewById(R.id.viewSummaryButton);
        Button viewTripHistoryButton = findViewById(R.id.viewTripHistoryButton);
        Button viewDistanceAccuracyButton = findViewById(R.id.viewDistanceAccuracyButton);
        Button addressBookButton = findViewById(R.id.addressBookButton);
        Button acceptanceStatsButton = findViewById(R.id.acceptanceStatsButton);
        Button personalCalibrationButton = findViewById(R.id.personalCalibrationButton);
        Button rejectedOffersReportButton = findViewById(R.id.rejectedOffersReportButton);

        viewSummaryButton.setOnClickListener(v -> showLastTripSummary());
        viewTripHistoryButton.setOnClickListener(v -> showTripHistory());
        viewDistanceAccuracyButton.setOnClickListener(v -> showDistanceAccuracy());
        addressBookButton.setOnClickListener(v -> showAddressBook());
        acceptanceStatsButton.setOnClickListener(v -> showAcceptanceStats());
        personalCalibrationButton.setOnClickListener(v -> showPersonalCalibration());
        rejectedOffersReportButton.setOnClickListener(v -> showRejectedOffersReport());
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    /**
         * Shows whether the offer screen's claimed distance empirically
         * matches delivery-only or total-trip distance, based on real measured
         * GPS distances across every delivery where pickup tracking completed.
         * Needs at least one full delivery (offer -> pickup arrival -> pickup
         * departure -> trip end) to have any data at all.
         */
        private void showDistanceAccuracy() {
            try {
                JSONObject result = new JSONObject(engine.callAttr("get_distance_accuracy_summary").toString());
                int sampleCount = result.optInt("sample_count", 0);
                if (sampleCount == 0) {
                    new AlertDialog.Builder(this)
                            .setTitle("Distance Accuracy")
                            .setMessage("No completed deliveries with pickup tracking yet -- "
                                    + "this needs at least one full delivery (offer accepted, "
                                    + "arrived at pickup, left pickup, trip ended) to have data.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }

                double deliveryOnlyError = result.optDouble("avg_delivery_only_error_km", 0);
                double totalTripError = result.optDouble("avg_total_trip_error_km", 0);
                String conclusion = result.optString("conclusion", "");
                String conclusionLabel = "delivery_only".equals(conclusion)
                        ? "Delivery-only (restaurant to customer)"
                        : "Total trip (your location to restaurant to customer)";

                String message = String.format(
                        "Based on %d completed deliver%s:\n\n"
                                + "Best match: %s\n\n"
                                + "Avg error if delivery-only: %.2f km\n"
                                + "Avg error if total-trip: %.2f km",
                        sampleCount, sampleCount == 1 ? "y" : "ies",
                        conclusionLabel, deliveryOnlyError, totalTripError);

                new AlertDialog.Builder(this)
                        .setTitle("Distance Accuracy")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show();
            } catch (JSONException | PyException e) {
                Toast.makeText(this, "Could not load distance accuracy: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        }

    private void showLastTripSummary() {
            try {
                JSONObject summary = new JSONObject(engine.callAttr("get_last_trip_summary").toString());
                showTripSummaryDialog("Last Trip Summary", summary);
            } catch (JSONException | PyException e) {
                new AlertDialog.Builder(this)
                        .setTitle("Last Trip Summary")
                        .setMessage("Could not read trip summary.")
                        .setPositiveButton("OK", null)
                        .show();
            }
        }

    /**
         * Lists every restaurant with learned wait-time/deadhead history --
         * this data already existed (feeding the Smart Score's learned
         * factors) but had no dedicated browsable view until now.
         */
        private void showAddressBook() {
            try {
                JSONObject result = new JSONObject(engine.callAttr("get_address_book").toString());
                JSONArray entries = result.optJSONArray("entries");
                if (entries == null || entries.length() == 0) {
                    new AlertDialog.Builder(this)
                            .setTitle("Address Book")
                            .setMessage("No restaurant history yet -- this fills in as you complete "
                                    + "real deliveries with pickup tracking.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }

                StringBuilder body = new StringBuilder();

                // Sweet-spot suggestion, shown as a summary header before
                // the per-restaurant list -- see get_pickup_sweet_spot_zone
                // for the real, evidence-gated reasoning behind it.
                try {
                    JSONObject sweetSpot = new JSONObject(engine.callAttr("get_pickup_sweet_spot_zone").toString());
                    if (sweetSpot.optBoolean("has_suggestion", false)) {
                        body.append(String.format(
                                "\uD83D\uDCCD Suggested waiting zone: %.5f, %.5f\n"
                                + "(%.0f%% of your %d real pickups have come from this area)\n\n",
                                sweetSpot.optDouble("lat", 0), sweetSpot.optDouble("lon", 0),
                                sweetSpot.optDouble("pct_of_total", 0), sweetSpot.optInt("total_sample_count", 0)));
                    } else {
                        body.append(String.format("Not enough real pickup history yet for a sweet-spot "
                                + "suggestion (%d of %d needed).\n\n",
                                sweetSpot.optInt("sample_count", 0), sweetSpot.optInt("min_required", 0)));
                    }
                } catch (JSONException | RuntimeException e) {
                    // Not worth blocking the whole Address Book over this.
                }

                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.optJSONObject(i);
                    if (entry == null) {
                        continue;
                    }
                    body.append("\u2022 ").append(entry.optString("restaurant_name", "Unknown")).append("\n");
                    body.append(String.format("   Wait: %.1f min (%d visit%s)\n",
                            entry.optDouble("avg_wait_minutes", 0), entry.optInt("wait_samples", 0),
                            entry.optInt("wait_samples", 0) == 1 ? "" : "s"));
                    if (!entry.isNull("avg_deadhead_km")) {
                        body.append(String.format("   Deadhead: %.2f km (%d trip%s)\n",
                                entry.optDouble("avg_deadhead_km", 0), entry.optInt("deadhead_samples", 0),
                                entry.optInt("deadhead_samples", 0) == 1 ? "" : "s"));
                    }
                    if (!entry.isNull("parking_difficulty")) {
                        body.append(String.format("   Parking: %s (%d confirmation%s)\n",
                                entry.optString("parking_difficulty", ""), entry.optInt("parking_difficulty_samples", 0),
                                entry.optInt("parking_difficulty_samples", 0) == 1 ? "" : "s"));
                    }
                    body.append("\n");
                }

                new AlertDialog.Builder(this)
                        .setTitle("Address Book")
                        .setMessage(body.toString())
                        .setPositiveButton("OK", null)
                        .show();
            } catch (JSONException | PyException e) {
                Toast.makeText(this, "Could not load address book: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

    /**
         * Shows how many offers you've accepted vs. declined (detected via
         * real Accept/Decline button taps), and whether the Smart Score
         * actually tracks that choice -- average score for accepted offers
         * should be meaningfully higher than for declined ones, if the score
         * is doing its job.
         */
        private void showAcceptanceStats() {
            try {
                JSONObject stats = new JSONObject(engine.callAttr("get_acceptance_stats").toString());
                if (stats.optInt("sample_count", 0) == 0) {
                    new AlertDialog.Builder(this)
                            .setTitle("Accept/Decline Stats")
                            .setMessage("No data yet -- this fills in as you tap Accept or Decline "
                                    + "on real offers.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }

                StringBuilder body = new StringBuilder();
                body.append(String.format("Total offers seen: %d\n", stats.optInt("sample_count", 0)));
                body.append(String.format("Accepted: %d   Declined: %d   Timed out: %d\n",
                        stats.optInt("accepted_count", 0), stats.optInt("declined_count", 0),
                        stats.optInt("timed_out_count", 0)));
                body.append(String.format("Acceptance rate: %.1f%%\n\n", stats.optDouble("acceptance_rate_pct", 0)));
                if (!stats.isNull("avg_score_accepted")) {
                    body.append(String.format("Avg Smart Score, accepted: %.1f\n", stats.optDouble("avg_score_accepted", 0)));
                }
                if (!stats.isNull("avg_score_declined")) {
                    body.append(String.format("Avg Smart Score, declined: %.1f\n", stats.optDouble("avg_score_declined", 0)));
                }
                if (!stats.isNull("avg_score_timed_out")) {
                    body.append(String.format("Avg Smart Score, timed out: %.1f\n", stats.optDouble("avg_score_timed_out", 0)));
                }
                if (!stats.isNull("avg_payout_accepted")) {
                    body.append(String.format("Avg payout, accepted: $%.2f", stats.optDouble("avg_payout_accepted", 0)));
                }

                new AlertDialog.Builder(this)
                        .setTitle("Accept/Decline Stats")
                        .setMessage(body.toString())
                        .setPositiveButton("OK", null)
                        .show();
            } catch (RuntimeException | JSONException e) { // RuntimeException covers PyException too
                Toast.makeText(this, "Could not load stats: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        /**
         * Shows whether personal calibration is active, and exactly what
         * it adjusted and why -- deliberately NOT a silent black box. See
         * SmartScoreEngine.recalculate_personal_calibration for how these
         * numbers are actually computed.
         */
        private void showPersonalCalibration() {
            try {
                JSONObject summary = new JSONObject(engine.callAttr("get_personal_calibration_summary").toString());
                if (!summary.optBoolean("active", false)) {
                    new AlertDialog.Builder(this)
                            .setTitle("Personal Calibration")
                            .setMessage("Not active yet -- needs at least 25 rated trips (with the "
                                    + "5-category feedback filled in) before any adjustment is made. "
                                    + "Keep rating trips after each delivery to build this up.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }

                JSONArray factors = summary.optJSONArray("factors");
                StringBuilder body = new StringBuilder(
                        "Base weights are always the floor -- each factor below is nudged by "
                                + "at most \u00B115% based on how well it actually correlates with your "
                                + "own ratings.\n\n");
                for (int i = 0; i < factors.length(); i++) {
                    JSONObject f = factors.optJSONObject(i);
                    if (f == null) continue;
                    double pct = f.optDouble("adjustment_pct", 0);
                    body.append(String.format("%s: %s%.1f%% (correlation %.2f, %d trips)\n",
                            friendlyFactorName(f.optString("factor", "")),
                            pct >= 0 ? "+" : "", pct, f.optDouble("correlation", 0), f.optInt("sample_count", 0)));
                }

                new AlertDialog.Builder(this)
                        .setTitle("Personal Calibration")
                        .setMessage(body.toString())
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Reset to Base Weights", (dialog, which) -> {
                            try {
                                engine.callAttr("reset_personal_calibration");
                                Toast.makeText(this, "Calibration reset -- back to base weights.",
                                        Toast.LENGTH_SHORT).show();
                            } catch (RuntimeException e) {
                                Toast.makeText(this, "Could not reset: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        })
                        .show();
            } catch (RuntimeException | JSONException e) {
                Toast.makeText(this, "Could not load calibration: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        /**
         * Report card for declined offers: what got rejected and its full
         * score breakdown, plus a per-factor comparison against what got
         * accepted -- answers "what does the algorithm rate well that I
         * actually don't want" directly, feeding the same calibration
         * system as trip feedback (see recalculate_personal_calibration).
         */
        private void showRejectedOffersReport() {
            try {
                JSONObject report = new JSONObject(engine.callAttr("get_rejected_offers_report").toString());
                JSONArray entries = report.optJSONArray("entries");
                if (entries == null || entries.length() == 0) {
                    new AlertDialog.Builder(this)
                            .setTitle("Rejected Offers Report")
                            .setMessage("No declined or timed-out offers recorded yet.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }

                StringBuilder body = new StringBuilder();
                JSONArray comparison = report.optJSONArray("comparison");
                if (comparison != null && comparison.length() > 0) {
                    body.append("--- Accepted vs Declined vs Timed Out, by factor ---\n");
                    for (int i = 0; i < comparison.length(); i++) {
                        JSONObject c = comparison.optJSONObject(i);
                        if (c == null) continue;
                        body.append(String.format("%s -- accepted: %s, declined: %s, timed out: %s\n",
                                friendlyFactorName(c.optString("factor", "")),
                                c.isNull("avg_accepted") ? "n/a" : String.valueOf(c.optDouble("avg_accepted")),
                                c.isNull("avg_declined") ? "n/a" : String.valueOf(c.optDouble("avg_declined")),
                                c.isNull("avg_timed_out") ? "n/a" : String.valueOf(c.optDouble("avg_timed_out"))));
                    }
                    body.append("\n");
                }

                // Declined and timed-out offers are shown together but
                // clearly labeled per entry -- an active decline and
                // losing an offer to inaction are genuinely different,
                // even though neither ended up accepted.
                body.append("--- Recent Declined / Timed-Out Offers ---\n");
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject e = entries.optJSONObject(i);
                    if (e == null) continue;
                    String outcome = e.optString("outcome", "");
                    String outcomeLabel = "timed_out".equals(outcome) ? "TIMED OUT" : "DECLINED";
                    body.append(String.format("[%s] %s -- $%.2f, %.1f km, score %.0f\n",
                            outcomeLabel, e.optString("restaurant_name", "Unknown"), e.optDouble("payout", 0),
                            e.optDouble("distance_km", 0), e.optDouble("smart_score", 0)));
                }

                new AlertDialog.Builder(this)
                        .setTitle("Rejected Offers Report")
                        .setMessage(body.toString())
                        .setPositiveButton("OK", null)
                        .show();
            } catch (RuntimeException | JSONException e) {
                Toast.makeText(this, "Could not load report: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        private String friendlyFactorName(String factor) {
            switch (factor) {
                case "base_rate": return "$/km";
                case "hourly_rate": return "$/hr";
                case "deadhead": return "Deadhead";
                case "restaurant_wait": return "Pickup wait";
                case "time_of_day": return "Traffic/time of day";
                case "weather": return "Weather";
                default: return factor;
            }
        }

    private void showTripHistory() {
            try {
                JSONObject history = new JSONObject(engine.callAttr("get_trip_history").toString());
                JSONArray trips = history.optJSONArray("trips");
                if (trips == null || trips.length() == 0) {
                    new AlertDialog.Builder(this)
                            .setTitle("Trip History")
                            .setMessage("No completed trips yet.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }

                String[] labels = new String[trips.length()];
                int[] tripIds = new int[trips.length()];
                java.text.SimpleDateFormat dateFormat =
                        new java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault());
                for (int i = 0; i < trips.length(); i++) {
                    JSONObject trip = trips.optJSONObject(i);
                    if (trip == null) {
                        continue;
                    }
                    tripIds[i] = trip.optInt("trip_id", -1);
                    long startTimeMs = (long) (trip.optDouble("start_time", 0) * 1000);
                    String dateLabel = dateFormat.format(new java.util.Date(startTimeMs));
                    String mode = "DASHER".equals(trip.optString("mode", "GENERAL")) ? "Dasher" : "General";
                    labels[i] = String.format("%s -- %.1f km -- %.0f%% -- %s",
                            dateLabel, trip.optDouble("distance_km", 0),
                            trip.optDouble("composite_score", 0), mode);
                }

                new AlertDialog.Builder(this)
                        .setTitle("Trip History (tap for detail)")
                        .setItems(labels, (dialog, which) -> showTripSummaryById(tripIds[which]))
                        .setNegativeButton("Close", null)
                        .show();
            } catch (JSONException | PyException e) {
                Toast.makeText(this, "Could not load trip history: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        }

    private void showTripSummaryById(int tripId) {
            try {
                JSONObject summary = new JSONObject(
                        engine.callAttr("get_trip_summary_by_id", tripId).toString());
                showTripSummaryDialog("Trip Summary", summary);
            } catch (JSONException | PyException e) {
                Toast.makeText(this, "Could not load trip: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

    /** Shared dialog body builder -- used by both the last-trip button and trip history. */
        private void showTripSummaryDialog(String title, JSONObject summary) {
            if (!summary.optBoolean("found", false)) {
                new AlertDialog.Builder(this)
                        .setTitle(title)
                        .setMessage("No completed trips yet.")
                        .setPositiveButton("OK", null)
                        .show();
                return;
            }

            StringBuilder body = buildTripSummaryBody(summary);

            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(body.toString())
                    .setPositiveButton("OK", null)
                    .show();
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
