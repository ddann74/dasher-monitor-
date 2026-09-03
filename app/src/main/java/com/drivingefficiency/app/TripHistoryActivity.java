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
        Button viewHourlyRateAccuracyButton = findViewById(R.id.viewHourlyRateAccuracyButton);
        Button addressBookButton = findViewById(R.id.addressBookButton);
        Button acceptanceStatsButton = findViewById(R.id.acceptanceStatsButton);
        Button personalCalibrationButton = findViewById(R.id.personalCalibrationButton);
        Button rejectedOffersReportButton = findViewById(R.id.rejectedOffersReportButton);
        Button restaurantVisitHistoryButton = findViewById(R.id.restaurantVisitHistoryButton);
        Button locationProfitabilityMapButton = findViewById(R.id.locationProfitabilityMapButton);

        viewSummaryButton.setOnClickListener(v -> showLastTripSummary());
        viewTripHistoryButton.setOnClickListener(v -> showTripHistory());
        viewDistanceAccuracyButton.setOnClickListener(v -> showDistanceAccuracy());
        viewHourlyRateAccuracyButton.setOnClickListener(v -> showHourlyRateAccuracy());
        addressBookButton.setOnClickListener(v -> showAddressBook());
        acceptanceStatsButton.setOnClickListener(v -> showAcceptanceStats());
        personalCalibrationButton.setOnClickListener(v -> showPersonalCalibration());
        rejectedOffersReportButton.setOnClickListener(v -> showRejectedOffersReport());
        restaurantVisitHistoryButton.setOnClickListener(v -> showRestaurantChooserThenVisitHistory());
        // docs/location_profitability_map/PRD.md (driver backlog #1) --
        // a full new Activity (osmdroid MapView), not an in-place dialog
        // like every other button above, so this launches via Intent
        // rather than a show*() method on this Activity.
        locationProfitabilityMapButton.setOnClickListener(v ->
                startActivity(new Intent(this, LocationProfitabilityMapActivity.class)));
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

    /**
         * docs/hourly_rate_actual_vs_estimated/PRD.md ss4.B/ss6 -- mirrors
         * showDistanceAccuracy() above: how far off the live hourly-rate
         * estimate is from what a delivery actually paid per hour, from
         * this driver's own recorded jobs. Only jobs where BOTH the
         * estimate and a real actual rate were captured count -- see
         * get_hourly_rate_accuracy_summary()'s own doc for why an earlier
         * job in a stacked order doesn't have both yet.
         */
        private void showHourlyRateAccuracy() {
            try {
                JSONObject result = new JSONObject(engine.callAttr("get_hourly_rate_accuracy_summary").toString());
                int sampleCount = result.optInt("sample_count", 0);
                if (sampleCount == 0) {
                    new AlertDialog.Builder(this)
                            .setTitle("Hourly Rate Accuracy")
                            .setMessage("No completed deliveries with both an estimate and a real "
                                    + "result yet -- this needs at least one full delivery (offer "
                                    + "accepted, pickup completed, trip ended) to have data.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }

                double avgSignedError = result.optDouble("avg_signed_error", 0);
                double avgAbsError = result.optDouble("avg_abs_error", 0);
                String biasDirection = result.optString("bias_direction", "");
                String biasLabel;
                switch (biasDirection) {
                    case "overestimating":
                        biasLabel = "The live estimate tends to run HIGHER than what deliveries actually pay per hour.";
                        break;
                    case "underestimating":
                        biasLabel = "The live estimate tends to run LOWER than what deliveries actually pay per hour.";
                        break;
                    default:
                        biasLabel = "The live estimate is tracking real results closely.";
                }

                String message = String.format(
                        "Based on %d completed deliver%s:\n\n"
                                + "%s\n\n"
                                + "Avg signed error: $%.2f/hr\n"
                                + "Avg absolute error: $%.2f/hr",
                        sampleCount, sampleCount == 1 ? "y" : "ies",
                        biasLabel, avgSignedError, avgAbsError);

                new AlertDialog.Builder(this)
                        .setTitle("Hourly Rate Accuracy")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show();
            } catch (JSONException | PyException e) {
                Toast.makeText(this, "Could not load hourly rate accuracy: " + e.getMessage(),
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

                // Recency-windowed hotspot (driver backlog #5,
                // docs/driver_backlog_2026_09_03/PRD.md) -- a genuinely
                // different, complementary signal from the all-history
                // sweet spot above ("busiest LATELY" vs. "busiest
                // OVERALL"). Coordinates saved for the copy button below,
                // added to the dialog only when a suggestion actually
                // exists (nothing to copy otherwise).
                final double[] recentHotspotCoords = {Double.NaN, Double.NaN};
                try {
                    JSONObject recentHotspot = new JSONObject(engine.callAttr("get_recent_pickup_hotspot").toString());
                    if (recentHotspot.optBoolean("has_suggestion", false)) {
                        recentHotspotCoords[0] = recentHotspot.optDouble("lat", 0);
                        recentHotspotCoords[1] = recentHotspot.optDouble("lon", 0);
                        body.append(String.format(
                                "🔥 Recent hotspot (last %d pickups): %.5f, %.5f\n"
                                + "(%d of your last %d pickups have come from this area)\n\n",
                                recentHotspot.optInt("total_sample_count", 0),
                                recentHotspotCoords[0], recentHotspotCoords[1],
                                recentHotspot.optInt("zone_sample_count", 0), recentHotspot.optInt("total_sample_count", 0)));
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
                    // Driver backlog #26 follow-up (2026-09-03, docs/
                    // driver_backlog_2026_09_03/PRD.md): avg $/km, avg
                    // $/hr, avg Smart Score + standard deviation per
                    // restaurant. Each omitted (not shown as 0/n-a)
                    // when this restaurant has no offer_outcomes rows
                    // with that specific value yet -- same "omit rather
                    // than guess" rule as every other field on this
                    // screen.
                    if (!entry.isNull("avg_dollar_per_km")) {
                        body.append(String.format("   Avg rate: $%.2f/km (%d offer%s)\n",
                                entry.optDouble("avg_dollar_per_km", 0), entry.optInt("dollar_per_km_samples", 0),
                                entry.optInt("dollar_per_km_samples", 0) == 1 ? "" : "s"));
                    }
                    if (!entry.isNull("avg_dollar_per_hr")) {
                        body.append(String.format("   Avg rate: $%.2f/hr (%d offer%s)\n",
                                entry.optDouble("avg_dollar_per_hr", 0), entry.optInt("dollar_per_hr_samples", 0),
                                entry.optInt("dollar_per_hr_samples", 0) == 1 ? "" : "s"));
                    }
                    if (!entry.isNull("avg_smart_score")) {
                        String stdevSuffix = entry.isNull("stdev_smart_score") ? ""
                                : String.format(" (sd %.1f)", entry.optDouble("stdev_smart_score", 0));
                        body.append(String.format("   Avg Smart Score: %.1f%s (%d offer%s)\n",
                                entry.optDouble("avg_smart_score", 0), stdevSuffix,
                                entry.optInt("smart_score_samples", 0),
                                entry.optInt("smart_score_samples", 0) == 1 ? "" : "s"));
                    }
                    body.append("\n");
                }

                AlertDialog.Builder addressBookDialog = new AlertDialog.Builder(this)
                        .setTitle("Address Book")
                        .setMessage(body.toString())
                        .setPositiveButton("OK", null);
                // Only offered when there's an actual coordinate to copy --
                // driver backlog #5's own ask ("take a copy of the
                // coordinates where I can then paste them in a
                // navigator"), reusing this app's existing simple
                // clipboard-copy pattern (see DiagnosticsActivity's log-copy
                // button for the same shape).
                if (!Double.isNaN(recentHotspotCoords[0])) {
                    addressBookDialog.setNeutralButton("Copy Recent Hotspot", (dialog, which) -> {
                        String coordsText = recentHotspotCoords[0] + ", " + recentHotspotCoords[1];
                        android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                                getSystemService(CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Recent Hotspot", coordsText));
                            Toast.makeText(this, "Copied to clipboard: " + coordsText, Toast.LENGTH_LONG).show();
                        }
                    });
                }
                addressBookDialog.show();
            } catch (JSONException | PyException e) {
                Toast.makeText(this, "Could not load address book: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

    /**
         * Driver backlog #7 (docs/driver_backlog_2026_09_03/PRD.md): "for
         * each restaurant populated, show a breakdown of the last 10
         * visits." Reuses get_address_book()'s own entries as the list of
         * known restaurant names to choose from -- no separate "list all
         * restaurant names" query needed, that data is already fetched
         * for the Address Book screen.
         */
        private void showRestaurantChooserThenVisitHistory() {
            try {
                JSONObject result = new JSONObject(engine.callAttr("get_address_book").toString());
                JSONArray entries = result.optJSONArray("entries");
                if (entries == null || entries.length() == 0) {
                    new AlertDialog.Builder(this)
                            .setTitle("Restaurant Visit History")
                            .setMessage("No restaurant history yet -- this fills in as you complete "
                                    + "real deliveries with pickup tracking.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }
                String[] names = new String[entries.length()];
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.optJSONObject(i);
                    names[i] = entry == null ? "Unknown" : entry.optString("restaurant_name", "Unknown");
                }
                new AlertDialog.Builder(this)
                        .setTitle("Choose a Restaurant")
                        .setItems(names, (dialog, which) -> showRestaurantVisitHistory(names[which]))
                        .setNegativeButton("Cancel", null)
                        .show();
            } catch (JSONException | PyException e) {
                Toast.makeText(this, "Could not load restaurant list: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        private void showRestaurantVisitHistory(String restaurantName) {
            try {
                JSONObject result = new JSONObject(
                        engine.callAttr("get_restaurant_visit_history", restaurantName).toString());
                JSONArray visits = result.optJSONArray("visits");
                if (visits == null || visits.length() == 0) {
                    new AlertDialog.Builder(this)
                            .setTitle(restaurantName)
                            .setMessage("No real (non-test) visits recorded for this restaurant yet.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }

                StringBuilder body = new StringBuilder();
                if (!result.isNull("avg_smart_score")) {
                    body.append(String.format("Avg Smart Score: %.1f", result.optDouble("avg_smart_score", 0)));
                    if (!result.isNull("stdev_smart_score")) {
                        body.append(String.format(" (stdev %.1f)", result.optDouble("stdev_smart_score", 0)));
                    }
                    body.append("\n");
                }
                // Honest gap disclosed directly in the dialog, not just in
                // code comments -- driver ratings aren't currently linkable
                // to a specific restaurant, so Smart Score is shown instead
                // and that substitution is named plainly, not left implicit.
                body.append(result.optString("rating_note", "")).append("\n\n");

                java.text.SimpleDateFormat dateFormat =
                        new java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault());
                for (int i = 0; i < visits.length(); i++) {
                    JSONObject v = visits.optJSONObject(i);
                    if (v == null) continue;
                    long tsMs = (long) (v.optDouble("timestamp", 0) * 1000);
                    body.append(String.format("%s -- $%.2f, %.1f km, score %.0f [%s]\n",
                            dateFormat.format(new java.util.Date(tsMs)),
                            v.optDouble("payout", 0), v.optDouble("distance_km", 0),
                            v.optDouble("smart_score", 0), v.optString("outcome", "")));
                }

                new AlertDialog.Builder(this)
                        .setTitle(restaurantName + " -- Last " + visits.length() + " Visits")
                        .setMessage(body.toString())
                        .setPositiveButton("OK", null)
                        .show();
            } catch (JSONException | PyException e) {
                Toast.makeText(this, "Could not load visit history: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
                        .setNegativeButton("Edit Offers Used", (dialog, which) -> showCalibrationOffersToggle())
                        .show();
            } catch (RuntimeException | JSONException e) {
                Toast.makeText(this, "Could not load calibration: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        /**
         * Driver backlog #2 (docs/driver_backlog_2026_09_03/PRD.md):
         * "give me the option to omit or include each offer" from what
         * recalculate_personal_calibration learns from. A checklist --
         * checked means included (the default for every offer), unchecked
         * means omitted. Each toggle is persisted immediately via
         * set_offer_omitted_from_calibration, not batched behind a
         * separate "Save" step -- matches this screen's own "Reset to
         * Base Weights" button, which also acts immediately.
         */
        private void showCalibrationOffersToggle() {
            try {
                JSONObject result = new JSONObject(engine.callAttr("get_calibration_offers_list").toString());
                JSONArray offers = result.optJSONArray("offers");
                if (offers == null || offers.length() == 0) {
                    new AlertDialog.Builder(this)
                            .setTitle("Edit Offers Used")
                            .setMessage("No real (non-test) offers recorded yet.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }

                String[] labels = new String[offers.length()];
                int[] offerIds = new int[offers.length()];
                boolean[] checked = new boolean[offers.length()];
                java.text.SimpleDateFormat dateFormat =
                        new java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault());
                for (int i = 0; i < offers.length(); i++) {
                    JSONObject o = offers.optJSONObject(i);
                    offerIds[i] = o.optInt("id", -1);
                    checked[i] = !o.optBoolean("omitted", false);
                    long tsMs = (long) (o.optDouble("timestamp", 0) * 1000);
                    labels[i] = String.format("%s -- %s -- $%.2f, %.1f km [%s]",
                            dateFormat.format(new java.util.Date(tsMs)),
                            o.optString("restaurant_name", "Unknown"),
                            o.optDouble("payout", 0), o.optDouble("distance_km", 0),
                            o.optString("outcome", ""));
                }

                new AlertDialog.Builder(this)
                        .setTitle("Edit Offers Used (checked = included)")
                        .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                            try {
                                engine.callAttr("set_offer_omitted_from_calibration", offerIds[which], !isChecked);
                            } catch (RuntimeException e) {
                                Toast.makeText(this, "Could not save: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        })
                        .setPositiveButton("Done", null)
                        .show();
            } catch (RuntimeException | JSONException e) {
                Toast.makeText(this, "Could not load offers: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
                // Average $/km by outcome (driver backlog #6,
                // docs/driver_backlog_2026_09_03/PRD.md) -- a real dollar
                // figure, shown separately from the 0-100 per-factor scores
                // below since it isn't one of those factors, it's the raw
                // rate those factors are trying to judge.
                JSONObject rateComparison = report.optJSONObject("rate_comparison");
                if (rateComparison != null) {
                    body.append(String.format("Average $/km -- accepted: %s, declined: %s, timed out: %s\n\n",
                            rateComparison.isNull("avg_dollar_per_km_accepted") ? "n/a"
                                    : String.format("$%.2f", rateComparison.optDouble("avg_dollar_per_km_accepted")),
                            rateComparison.isNull("avg_dollar_per_km_declined") ? "n/a"
                                    : String.format("$%.2f", rateComparison.optDouble("avg_dollar_per_km_declined")),
                            rateComparison.isNull("avg_dollar_per_km_timed_out") ? "n/a"
                                    : String.format("$%.2f", rateComparison.optDouble("avg_dollar_per_km_timed_out"))));
                }
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

    /**
     * Driver backlog #9 (docs/driver_backlog_2026_09_03/PRD.md):
     * "separate dasher and general trips from the report" -- trip.mode
     * was already returned by get_trip_history() and already shown as a
     * suffix on each row's own label, but there was no way to filter the
     * list down to just one mode. A simple up-front chooser, filtered
     * client-side (the full list is already in memory either way) --
     * no Python change needed.
     */
    private void showTripHistory() {
            String[] modeChoices = {"All Trips", "Dasher Only", "General Only"};
            new AlertDialog.Builder(this)
                    .setTitle("Trip History")
                    .setItems(modeChoices, (dialog, which) -> {
                        String modeFilter = which == 1 ? "DASHER" : which == 2 ? "GENERAL" : null;
                        showTripHistoryFiltered(modeFilter);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }

    /** modeFilter: null shows all trips; "DASHER" or "GENERAL" shows only that mode. */
    private void showTripHistoryFiltered(String modeFilter) {
            try {
                JSONObject history = new JSONObject(engine.callAttr("get_trip_history").toString());
                JSONArray allTrips = history.optJSONArray("trips");
                if (allTrips == null || allTrips.length() == 0) {
                    new AlertDialog.Builder(this)
                            .setTitle("Trip History")
                            .setMessage("No completed trips yet.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }

                java.util.List<JSONObject> trips = new java.util.ArrayList<>();
                for (int i = 0; i < allTrips.length(); i++) {
                    JSONObject trip = allTrips.optJSONObject(i);
                    if (trip == null) {
                        continue;
                    }
                    String tripMode = "DASHER".equals(trip.optString("mode", "GENERAL")) ? "DASHER" : "GENERAL";
                    if (modeFilter == null || modeFilter.equals(tripMode)) {
                        trips.add(trip);
                    }
                }
                if (trips.isEmpty()) {
                    new AlertDialog.Builder(this)
                            .setTitle("Trip History")
                            .setMessage("No completed trips match this filter.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }

                String[] labels = new String[trips.size()];
                int[] tripIds = new int[trips.size()];
                java.text.SimpleDateFormat dateFormat =
                        new java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault());
                for (int i = 0; i < trips.size(); i++) {
                    JSONObject trip = trips.get(i);
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
            // Hoisted above offerSnapshot's own block, and above its usual
            // "Where The Time Went" section below (driver backlog #8,
            // docs/driver_backlog_2026_09_03/PRD.md), so the "Deadhead"
            // line right below can show a time alongside the existing km
            // figure, using the same JSONObject the phase breakdown itself
            // reads later -- fetched once, not twice.
            JSONObject phaseBreakdown = summary.optJSONObject("phase_breakdown");
            if (offerSnapshot != null) {
                body.append("--- Original Offer Assessment ---\n");
                body.append(offerSnapshot.optString("verdict_sentence", "")).append("\n\n");
                body.append(String.format("Smart Score: %.0f/100 - %s\n",
                        offerSnapshot.optDouble("final_score", 0), offerSnapshot.optString("label", "")));
                body.append(String.format("$/km: $%.2f   $/hr: $%.2f\n",
                        offerSnapshot.optDouble("base_rate_per_km", 0), offerSnapshot.optDouble("hourly_rate", 0)));
                // Deadhead TIME added alongside the existing km figure
                // (driver backlog #8). Reuses phase_breakdown's own
                // "driving_to_pickup_seconds" -- for the single/first-job
                // scope phase_breakdown already documents (see its own
                // Python-side comment), driving-to-pickup time IS the
                // deadhead leg's time, not a separately-tracked value; not
                // shown for a job phase_breakdown couldn't capture (no
                // pickup this trip, or an older trip predating it).
                String deadheadTimeSuffix = (phaseBreakdown != null
                        && !phaseBreakdown.isNull("driving_to_pickup_seconds"))
                        ? " (" + formatMinutesSeconds(phaseBreakdown.optDouble("driving_to_pickup_seconds", 0)) + ")"
                        : "";
                body.append(String.format("Deadhead: %.1f km%s\n", offerSnapshot.optDouble("deadhead_km", 0), deadheadTimeSuffix));
                body.append(String.format("Pickup wait: %.0f min\n", offerSnapshot.optDouble("restaurant_wait_minutes", 0)));
                // Raw ratio added alongside the existing High/Low label
                // (driver backlog #14) -- only ever present when
                // traffic_risk_source was "live" (a real Google Maps
                // Distance Matrix result), since that's the only source
                // with an actual ratio behind it; the zone/personal/
                // generic sources are binary flags with nothing numeric
                // to show, so this is correctly omitted for those rather
                // than showing a fabricated number.
                String trafficRatioSuffix = offerSnapshot.isNull("traffic_ratio") ? ""
                        : String.format(" (%.0f%% of typical)", offerSnapshot.optDouble("traffic_ratio", 1.0) * 100.0);
                body.append("Traffic: ").append(offerSnapshot.optString("traffic_risk", ""))
                        .append(trafficRatioSuffix).append("\n");
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
            // omitted rather than guessed at. (phaseBreakdown itself is
            // fetched above, before offerSnapshot's own block -- see that
            // declaration's comment.)
            if (phaseBreakdown != null && phaseBreakdown.length() > 0) {
                body.append("--- Where The Time Went ---\n");
                // Driver backlog #26 follow-up (2026-09-03, docs/driver_
                // backlog_2026_09_03/PRD.md): driver said some of these
                // numbers don't look accurate. A real, already-documented
                // reason this can happen for a stacked/batch order (2+
                // jobs in one trip) is a known limitation, not fixed here:
                // docs/deadhead_stacked_order_baseline/PRD.md Part 2B --
                // pickup/dropoff phase timestamps can mix data from
                // different jobs for that case, and the real fix is
                // explicitly blocked pending a real stacked-order dropoff
                // screenshot. job_count (from the same summary JSON) lets
                // this screen warn honestly rather than presenting a
                // possibly-mixed number as if it were reliable.
                int jobCount = summary.optInt("job_count", 0);
                if (jobCount > 1) {
                    body.append("⚠️ This trip had ").append(jobCount)
                            .append(" stacked orders -- the breakdown below may mix timestamps "
                                    + "from different jobs (known limitation, not yet fixed).\n");
                }
                if (!phaseBreakdown.isNull("driving_to_pickup_seconds")) {
                    body.append(String.format("Driving to pickup: %s\n",
                            formatMinutesSeconds(phaseBreakdown.optDouble("driving_to_pickup_seconds", 0))));
                }
                if (!phaseBreakdown.isNull("wait_at_restaurant_seconds")) {
                    // Wait-time RATING added alongside the duration (driver
                    // backlog #8) -- feedback_merchant_wait was already
                    // returned in this same summary JSON
                    // (drive_monitor.py's get_trip_summary), just never
                    // surfaced in this view before.
                    String waitRating = summary.optString("feedback_merchant_wait", "");
                    String waitRatingSuffix = waitRating.isEmpty() ? "" : " (rated: " + waitRating + ")";
                    body.append(String.format("Waiting at restaurant: %s%s\n",
                            formatMinutesSeconds(phaseBreakdown.optDouble("wait_at_restaurant_seconds", 0)),
                            waitRatingSuffix));
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

                // Raw clock times behind the durations above (driver
                // backlog #26 follow-up) -- lets an inaccurate-looking
                // duration actually be diagnosed ("wait was 45 min" is
                // hard to sanity-check; "arrived 2:03pm, left 2:48pm"
                // isn't). Only real, captured timestamps are shown -- a
                // key simply isn't present in phase_timestamps if that
                // moment was never captured (see Python-side comment).
                JSONObject phaseTimestamps = summary.optJSONObject("phase_timestamps");
                if (phaseTimestamps != null && phaseTimestamps.length() > 0) {
                    java.text.SimpleDateFormat clockFormat =
                            new java.text.SimpleDateFormat("h:mm:ss a", java.util.Locale.getDefault());
                    body.append("Full time detail:\n");
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
                            body.append("   ").append(entry[1]).append(": ")
                                    .append(clockFormat.format(new java.util.Date(tsMs))).append("\n");
                        }
                    }
                    body.append("\n");
                }

                // Same phases as a share of this trip's total elapsed time
                // (end_time - start_time) -- shows which phase actually ate
                // the shift, not just its raw duration. Omitted entirely,
                // not guessed, if the total itself isn't available (older
                // trip missing either timestamp) -- same "omit rather than
                // guess" rule as the phase breakdown above.
                double totalTripSeconds = summary.optDouble("end_time", 0) - summary.optDouble("start_time", 0);
                if (totalTripSeconds > 0) {
                    body.append("As % of total trip time:\n");
                    if (!phaseBreakdown.isNull("driving_to_pickup_seconds")) {
                        body.append(String.format("Driving to pickup: %s\n",
                                formatPercentOfTotal(phaseBreakdown.optDouble("driving_to_pickup_seconds", 0), totalTripSeconds)));
                    }
                    if (!phaseBreakdown.isNull("wait_at_restaurant_seconds")) {
                        body.append(String.format("Waiting at restaurant: %s\n",
                                formatPercentOfTotal(phaseBreakdown.optDouble("wait_at_restaurant_seconds", 0), totalTripSeconds)));
                    }
                    if (!phaseBreakdown.isNull("driving_to_dropoff_seconds")) {
                        body.append(String.format("Driving to dropoff: %s\n",
                                formatPercentOfTotal(phaseBreakdown.optDouble("driving_to_dropoff_seconds", 0), totalTripSeconds)));
                    }
                    if (!phaseBreakdown.isNull("parking_to_walking_seconds")) {
                        body.append(String.format("Parking to walking: %s\n",
                                formatPercentOfTotal(phaseBreakdown.optDouble("parking_to_walking_seconds", 0), totalTripSeconds)));
                    }
                    if (!phaseBreakdown.isNull("completing_dropoff_seconds")) {
                        body.append(String.format("Completing dropoff: %s\n",
                                formatPercentOfTotal(phaseBreakdown.optDouble("completing_dropoff_seconds", 0), totalTripSeconds)));
                    }
                    body.append("\n");
                }
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

    /** Formats a phase duration as a rounded percentage of the trip's total elapsed time, for the "As % of total trip time" section. */
        private String formatPercentOfTotal(double phaseSeconds, double totalSeconds) {
            long pct = Math.round((phaseSeconds / totalSeconds) * 100);
            return pct + "%";
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
