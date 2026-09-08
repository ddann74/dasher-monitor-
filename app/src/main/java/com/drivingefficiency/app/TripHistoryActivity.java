package com.drivingefficiency.app;

import android.app.AlertDialog;
import android.content.Intent;
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
        Button parkingZoneMapButton = findViewById(R.id.parkingZoneMapButton);
        Button payTrendButton = findViewById(R.id.payTrendButton);
        Button weatherPayCorrelationButton = findViewById(R.id.weatherPayCorrelationButton);

        // docs/trip_history_redesign/PRD.md -- both now launch real
        // Activities (TripDetailActivity/TripListActivity) instead of
        // building an AlertDialog in-place, same shape as
        // locationProfitabilityMapButton below.
        viewSummaryButton.setOnClickListener(v -> showLastTripSummary());
        viewTripHistoryButton.setOnClickListener(v ->
                startActivity(new Intent(this, TripListActivity.class)));
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
        // docs/parking_zone_map/PRD.md - same shape as
        // locationProfitabilityMapButton immediately above.
        parkingZoneMapButton.setOnClickListener(v ->
                startActivity(new Intent(this, ParkingZoneMapActivity.class)));
        payTrendButton.setOnClickListener(v -> showPayTrend());
        weatherPayCorrelationButton.setOnClickListener(v -> showWeatherPayCorrelation());
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

    /**
     * docs/trip_history_redesign/PRD.md ss3.4 -- launches
     * TripDetailActivity WITHOUT EXTRA_PROMPT_FEEDBACK_ON_CLOSE, pure
     * viewing. A Dasher trip reached this way (manually, via this
     * button) was already prompted for feedback at the moment it
     * actually completed (TripForegroundService.notifyRateThisDelivery),
     * or never applies (General mode) -- re-prompting here would risk a
     * confusing double-prompt for the same trip.
     */
    private void showLastTripSummary() {
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
         * docs/pay_trend/PRD.md -- driver asked directly whether this app
         * has solved gig-platform algorithmic pay steering (a driver's own
         * acceptance history influencing what they're offered later). It
         * hasn't, and structurally can't -- that decision happens entirely
         * on DoorDash's own backend, invisible to a client-side app that
         * only reads the screen and notifications. This surfaces the
         * driver's OWN recorded $/km and $/hr trend instead -- their own
         * evidence, not a diagnosis of why it moved (framed explicitly in
         * the dialog text itself, not just in this comment, since a
         * decline here could be platform-side steering OR ordinary market
         * seasonality/fewer active restaurants/plain chance, and this
         * can't tell those apart).
         */
        private void showPayTrend() {
            try {
                JSONObject result = new JSONObject(engine.callAttr("get_pay_trend").toString());
                JSONArray weekly = result.optJSONArray("weekly");
                // Self-caught bug: get_pay_trend() always returns exactly
                // 8 weekly buckets (empty ones included, so a gap week
                // shows "no offers recorded" rather than silently
                // vanishing) -- so `weekly` is never actually null/empty,
                // even for a driver with zero offer history ever. Checking
                // array LENGTH here never caught that case; checking
                // whether every bucket's own sample_count is 0 does.
                boolean hasAnyData = false;
                if (weekly != null) {
                    for (int i = 0; i < weekly.length(); i++) {
                        JSONObject w = weekly.optJSONObject(i);
                        if (w != null && w.optInt("sample_count", 0) > 0) {
                            hasAnyData = true;
                            break;
                        }
                    }
                }
                if (!hasAnyData) {
                    new AlertDialog.Builder(this)
                            .setTitle("Pay Trend")
                            .setMessage("No offer history recorded yet.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }

                StringBuilder body = new StringBuilder();
                body.append("Your own recorded $/km and $/hr over time, from every offer you've been "
                        + "shown (accepted, declined, or timed out) -- not a diagnosis of why it moved.\n\n");

                JSONObject trend = result.optJSONObject("trend");
                if (trend != null) {
                    body.append("--- Recent weeks vs. earlier weeks ---\n");
                    if (!trend.isNull("dollar_per_km_change_pct")) {
                        double pct = trend.optDouble("dollar_per_km_change_pct", 0);
                        body.append(String.format("$/km: %s%.1f%%\n", pct >= 0 ? "+" : "", pct));
                    }
                    if (!trend.isNull("dollar_per_hr_change_pct")) {
                        double pct = trend.optDouble("dollar_per_hr_change_pct", 0);
                        body.append(String.format("$/hr: %s%.1f%%\n", pct >= 0 ? "+" : "", pct));
                    }
                    body.append("\n");
                } else {
                    body.append("Not enough offer history yet in both halves of the window for a "
                            + "reliable recent-vs-earlier comparison.\n\n");
                }

                body.append("--- Weekly breakdown (most recent first) ---\n");
                java.text.SimpleDateFormat dateFormat =
                        new java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault());
                for (int i = 0; i < weekly.length(); i++) {
                    JSONObject w = weekly.optJSONObject(i);
                    if (w == null) continue;
                    long startMs = (long) (w.optDouble("week_start_ts", 0) * 1000);
                    long endMs = (long) (w.optDouble("week_end_ts", 0) * 1000);
                    int sampleCount = w.optInt("sample_count", 0);
                    String range = dateFormat.format(new java.util.Date(startMs)) + " - "
                            + dateFormat.format(new java.util.Date(endMs));
                    if (sampleCount == 0) {
                        body.append(String.format("%s: no offers recorded\n", range));
                        continue;
                    }
                    body.append(String.format("%s: %s, %s (%d offer%s)\n", range,
                            w.isNull("avg_dollar_per_km") ? "$/km n/a"
                                    : String.format("$%.2f/km", w.optDouble("avg_dollar_per_km")),
                            w.isNull("avg_dollar_per_hr") ? "$/hr n/a"
                                    : String.format("$%.2f/hr", w.optDouble("avg_dollar_per_hr")),
                            sampleCount, sampleCount == 1 ? "" : "s"));
                }

                new AlertDialog.Builder(this)
                        .setTitle("Pay Trend")
                        .setMessage(body.toString())
                        .setPositiveButton("OK", null)
                        .show();
            } catch (JSONException | PyException e) {
                Toast.makeText(this, "Could not load pay trend: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        /**
         * docs/weather_pay_correlation/PRD.md -- driver asked to "compare
         * pay rate with weather." Reads the same live Open-Meteo weather
         * already fetched for every offer's weather_score, now persisted
         * alongside that offer's own pay data (record_offer_outcome/
         * record_offer_timeout) instead of only ever used transiently.
         * Going forward only -- offers recorded before this shipped have
         * no weather columns and are excluded, same as any other newly
         * added measurement in this app.
         */
        private void showWeatherPayCorrelation() {
            try {
                JSONObject result = new JSONObject(engine.callAttr("get_weather_pay_correlation").toString());
                if (result.optInt("total_samples", 0) == 0) {
                    new AlertDialog.Builder(this)
                            .setTitle("Weather vs. Pay")
                            .setMessage("No offers with a weather snapshot recorded yet -- this fills in as "
                                    + "you see new offers going forward.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }

                JSONObject rain = result.optJSONObject("rain");
                JSONObject noRain = result.optJSONObject("no_rain");
                StringBuilder body = new StringBuilder();
                body.append("Your own recorded pay, split by whether it was raining at the moment each "
                        + "offer came in.\n\n");

                if (!result.optBoolean("has_enough_data", false)) {
                    body.append(String.format("Not enough offers with a weather snapshot yet in both "
                            + "conditions for a reliable comparison (need at least %d each way).\n\n",
                            result.optInt("min_required", 3)));
                }

                body.append(String.format("Rain (%d offer%s):\n", rain.optInt("sample_count", 0),
                        rain.optInt("sample_count", 0) == 1 ? "" : "s"));
                body.append(weatherBucketLines(rain));
                body.append("\n");
                body.append(String.format("No rain (%d offer%s):\n", noRain.optInt("sample_count", 0),
                        noRain.optInt("sample_count", 0) == 1 ? "" : "s"));
                body.append(weatherBucketLines(noRain));

                new AlertDialog.Builder(this)
                        .setTitle("Weather vs. Pay")
                        .setMessage(body.toString())
                        .setPositiveButton("OK", null)
                        .show();
            } catch (JSONException | PyException e) {
                Toast.makeText(this, "Could not load weather vs. pay: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        private String weatherBucketLines(JSONObject bucket) {
            if (bucket == null || bucket.optInt("sample_count", 0) == 0) {
                return "  no offers recorded\n";
            }
            StringBuilder lines = new StringBuilder();
            lines.append(String.format("  %s\n",
                    bucket.isNull("avg_dollar_per_km") ? "$/km n/a"
                            : String.format("$%.2f/km", bucket.optDouble("avg_dollar_per_km"))));
            lines.append(String.format("  %s\n",
                    bucket.isNull("avg_dollar_per_hr") ? "$/hr n/a"
                            : String.format("$%.2f/hr", bucket.optDouble("avg_dollar_per_hr"))));
            lines.append(String.format("  %s\n",
                    bucket.isNull("avg_smart_score") ? "Smart Score n/a"
                            : String.format("Smart Score %.0f avg", bucket.optDouble("avg_smart_score"))));
            return lines.toString();
        }

}
