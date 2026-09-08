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

    private PyObject engine;
    private JSONArray allTrips = new JSONArray();
    private String activeFilter = null; // null = All, "DASHER", or "GENERAL"

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

    private void loadTrips() {
        try {
            JSONObject history = new JSONObject(engine.callAttr("get_trip_history").toString());
            allTrips = history.optJSONArray("trips");
            if (allTrips == null) {
                allTrips = new JSONArray();
            }
        } catch (JSONException | PyException e) {
            Toast.makeText(this, "Could not load trip history: " + e.getMessage(), Toast.LENGTH_LONG).show();
            allTrips = new JSONArray();
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
            noTripsText.setVisibility(android.view.View.VISIBLE);
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
    }
}
