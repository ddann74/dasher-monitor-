package com.drivingefficiency.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ToggleButton;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * docs/trip_history_redesign/PRD.md ss3.5 -- replaces
 * showTripHistoryFiltered()'s AlertDialog.setItems() picker with a real
 * screen. Same three-way client-side filter that method already did
 * (All / Dasher Only / General Only), re-rendered as ToggleButtons
 * instead of a second AlertDialog. Tapping a row opens
 * TripDetailActivity for that trip WITHOUT
 * EXTRA_PROMPT_FEEDBACK_ON_CLOSE -- browsing history never
 * surprise-prompts for feedback (see TripDetailActivity's own class doc).
 */
public class TripListActivity extends AppCompatActivity {

    // Real, confirmed gap fix (2026-09-14, docs/trip_history_pagination/
    // PRD.md): this screen previously called get_trip_history() with no
    // arguments at all, silently relying on its limit=20 default --
    // every trip older than the 20 most recent was permanently invisible
    // here, with no indication more existed. Now pages backward via
    // get_trip_history's new before_id cursor.
    private static final int TRIP_HISTORY_PAGE_SIZE = 20;

    private PyObject engine;
    private JSONArray allTrips = new JSONArray();
    private String activeFilter = null; // null = All, "DASHER", or "GENERAL"
    // null = no page loaded yet / at the very start; set to the last
    // loaded trip's id after each successful page so the next load
    // continues right after it (cursor pagination -- correct even if a
    // new trip completes between page loads, unlike an OFFSET, which
    // would skip or repeat rows under exactly that condition).
    private Integer nextBeforeId = null;
    private boolean hasMoreTrips = false;
    private boolean loadingMore = false;

    private ToggleButton filterAllButton;
    private ToggleButton filterDasherButton;
    private ToggleButton filterGeneralButton;
    private LinearLayout tripRowsContainer;
    private TextView noTripsText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trip_list);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Trip History");
        }

        engine = PythonBridge.getEngine(this);
        filterAllButton = findViewById(R.id.filterAllButton);
        filterDasherButton = findViewById(R.id.filterDasherButton);
        filterGeneralButton = findViewById(R.id.filterGeneralButton);
        tripRowsContainer = findViewById(R.id.tripRowsContainer);
        noTripsText = findViewById(R.id.noTripsText);

        filterAllButton.setOnClickListener(v -> setFilter(null));
        filterDasherButton.setOnClickListener(v -> setFilter("DASHER"));
        filterGeneralButton.setOnClickListener(v -> setFilter("GENERAL"));

        loadTrips();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    /** Resets to the very first (most recent) page -- called once at onCreate. */
    private void loadTrips() {
        allTrips = new JSONArray();
        nextBeforeId = null;
        hasMoreTrips = false;
        loadMoreTrips();
    }

    /**
     * Fetches and appends the next page of trips, oldest-so-far first,
     * to allTrips. Safe to call repeatedly (e.g. from a rapidly-tapped
     * "Load More" button) -- loadingMore guards against a re-entrant
     * second fetch stacking duplicate trips into allTrips while the
     * first one is still in flight.
     */
    private void loadMoreTrips() {
        if (loadingMore) {
            return;
        }
        loadingMore = true;
        try {
            PyObject result = nextBeforeId == null
                    ? engine.callAttr("get_trip_history", TRIP_HISTORY_PAGE_SIZE)
                    : engine.callAttr("get_trip_history", TRIP_HISTORY_PAGE_SIZE, nextBeforeId);
            JSONObject history = new JSONObject(result.toString());
            JSONArray page = history.optJSONArray("trips");
            if (page == null) {
                page = new JSONArray();
            }
            for (int i = 0; i < page.length(); i++) {
                allTrips.put(page.get(i));
            }
            hasMoreTrips = history.optBoolean("has_more", false);
            if (page.length() > 0) {
                JSONObject lastOnPage = page.optJSONObject(page.length() - 1);
                nextBeforeId = lastOnPage != null ? lastOnPage.optInt("trip_id", -1) : null;
            }
        } catch (JSONException | RuntimeException e) { // covers PyException too
            logDiagnostic("Could not load trip history -- " + e.getMessage());
            Toast.makeText(this, "Could not load trip history: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            loadingMore = false;
        }
        renderRows();
    }

    private void setFilter(String mode) {
        activeFilter = mode;
        filterAllButton.setChecked(mode == null);
        filterDasherButton.setChecked("DASHER".equals(mode));
        filterGeneralButton.setChecked("GENERAL".equals(mode));
        renderRows();
    }

    private void renderRows() {
        tripRowsContainer.removeAllViews();
        List<JSONObject> filtered = new ArrayList<>();
        for (int i = 0; i < allTrips.length(); i++) {
            JSONObject trip = allTrips.optJSONObject(i);
            if (trip == null) {
                continue;
            }
            String tripMode = "DASHER".equals(trip.optString("mode", "GENERAL")) ? "DASHER" : "GENERAL";
            if (activeFilter == null || activeFilter.equals(tripMode)) {
                filtered.add(trip);
            }
        }

        if (filtered.isEmpty()) {
            // Real gap fix (2026-09-14): previously this always meant
            // "no trips, period." Now it can also mean "none of the
            // trips LOADED SO FAR match this filter, but more exist
            // further back" -- shown distinctly, with a way to keep
            // looking, instead of a flat dead end.
            if (hasMoreTrips) {
                noTripsText.setText("None of the trips loaded so far match this filter -- "
                        + "load more to keep looking.");
            } else {
                noTripsText.setText("No trips yet.");
            }
            noTripsText.setVisibility(android.view.View.VISIBLE);
            addLoadMoreRowIfNeeded();
            return;
        }
        noTripsText.setVisibility(android.view.View.GONE);

        java.text.SimpleDateFormat dateFormat =
                new java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault());
        for (JSONObject trip : filtered) {
            int tripId = trip.optInt("trip_id", -1);
            long startTimeMs = (long) (trip.optDouble("start_time", 0) * 1000);
            String dateLabel = dateFormat.format(new java.util.Date(startTimeMs));
            String mode = "DASHER".equals(trip.optString("mode", "GENERAL")) ? "Dasher" : "General";
            double distanceKm = trip.optDouble("distance_km", 0);
            double compositeScore = trip.optDouble("composite_score", 0);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int vPad = (int) (11 * getResources().getDisplayMetrics().density);
            int hPad = (int) (10 * getResources().getDisplayMetrics().density);
            row.setPadding(hPad, vPad, hPad, vPad);
            row.setClickable(true);
            row.setFocusable(true);
            android.util.TypedValue outValue = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
            row.setBackgroundResource(outValue.resourceId);

            LinearLayout meta = new LinearLayout(this);
            meta.setOrientation(LinearLayout.VERTICAL);
            TextView when = new TextView(this);
            when.setText(dateLabel);
            when.setTextSize(13.5f);
            when.setTextColor(0xFF212121);
            TextView sub = new TextView(this);
            sub.setText(String.format(java.util.Locale.US, "%.1f km · %s", distanceKm, mode));
            sub.setTextSize(11.5f);
            sub.setTextColor(0xFF757575);
            meta.addView(when);
            meta.addView(sub);
            LinearLayout.LayoutParams metaParams =
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(meta, metaParams);

            TextView score = new TextView(this);
            score.setText(String.format(java.util.Locale.US, "%.0f%%", compositeScore));
            score.setTextSize(15f);
            score.setTypeface(null, android.graphics.Typeface.BOLD);
            score.setTextColor(0xFF00897B);
            row.addView(score);

            row.setOnClickListener(v -> {
                if (tripId >= 0) {
                    Intent intent = new Intent(this, TripDetailActivity.class);
                    intent.putExtra(TripDetailActivity.EXTRA_TRIP_ID, tripId);
                    startActivity(intent);
                }
            });

            tripRowsContainer.addView(row);
        }

        addLoadMoreRowIfNeeded();
    }

    /**
     * Appends a tappable "Load More Trips" footer row when more exist
     * past what's currently loaded -- the actual fix for the silent
     * 20-trip cap: previously there was no way to reach anything older
     * from this screen, and no indication older trips even existed.
     */
    private void addLoadMoreRowIfNeeded() {
        if (!hasMoreTrips) {
            return;
        }
        TextView loadMoreRow = new TextView(this);
        loadMoreRow.setText(loadingMore ? "Loading..." : "Load More Trips");
        loadMoreRow.setTextSize(13.5f);
        loadMoreRow.setTextColor(0xFF00897B);
        loadMoreRow.setGravity(Gravity.CENTER);
        int vPad = (int) (14 * getResources().getDisplayMetrics().density);
        loadMoreRow.setPadding(0, vPad, 0, vPad);
        loadMoreRow.setClickable(!loadingMore);
        loadMoreRow.setFocusable(!loadingMore);
        if (!loadingMore) {
            android.util.TypedValue outValue = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
            loadMoreRow.setBackgroundResource(outValue.resourceId);
            loadMoreRow.setOnClickListener(v -> loadMoreTrips());
        }
        tripRowsContainer.addView(loadMoreRow);
    }

    /** Same "a logging call can never crash the app" wrapper pattern used
      * elsewhere in this app -- this Activity had none until the
      * field-test checklist audit found its one load failure was
      * Toast-only (gone once dismissed). */
    private void logDiagnostic(String message) {
        try {
            engine.callAttr("log_diagnostic", "TRIP_LIST", message);
        } catch (RuntimeException e) { // covers PyException too
        }
    }
}
