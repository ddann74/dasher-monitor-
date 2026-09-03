package com.drivingefficiency.app;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

/**
 * Driver backlog #1 (docs/location_profitability_map/PRD.md): "build a
 * database of smart score data and geolocation so I can determine the
 * most profitable locations to be in." Driver confirmed the full version
 * -- a dedicated map, not just a stat added to an existing screen.
 *
 * Uses osmdroid (OpenStreetMap tiles, no API key needed) rather than
 * Google Maps SDK -- see the PRD's own ss1.3 for why, and ss4 for this
 * class's elevated, explicitly disclosed verification risk (this is the
 * first real use of osmdroid anywhere in this codebase's current
 * history, unlike every other Java class in this app, which has at
 * least SOME existing working code to model new calls against).
 *
 * Markers use a plain drawn colored circle (same GradientDrawable-oval
 * technique OverlayHelper.showStatusDot already uses), not osmdroid's
 * own InfoWindow bubble UI -- a deliberate choice to keep this on
 * lower-risk, well-established Android APIs (GradientDrawable, Canvas,
 * AlertDialog -- all already used elsewhere in this exact app) rather
 * than a less-certain osmdroid-specific API surface for the interactive
 * part of this screen.
 */
public class LocationProfitabilityMapActivity extends AppCompatActivity {

    private PyObject engine;
    private MapView mapView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // osmdroid requires this configuration call, including a real
        // user-agent string, before any MapView is used -- per osmdroid's
        // own documented setup requirement (OpenStreetMap's tile usage
        // policy requires a real user agent, not the default one). Uses
        // the built-in android.preference.PreferenceManager (deprecated
        // but always part of the Android SDK, no new dependency) rather
        // than androidx.preference, which this app doesn't already
        // depend on -- deliberately avoiding a SECOND new dependency on
        // top of osmdroid itself for this one config call.
        Configuration.getInstance().load(this,
                android.preference.PreferenceManager.getDefaultSharedPreferences(this));
        Configuration.getInstance().setUserAgentValue(getPackageName());

        setContentView(R.layout.activity_location_profitability_map);
        engine = PythonBridge.getEngine(this);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Profitability Map");
        }

        mapView = findViewById(R.id.profitabilityMapView);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);

        loadAndShowEntries();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
    }

    private void loadAndShowEntries() {
        try {
            JSONObject result = new JSONObject(engine.callAttr("get_location_profitability").toString());
            JSONArray entries = result.optJSONArray("entries");
            TextView notEnoughDataText = findViewById(R.id.notEnoughDataText);
            if (entries == null || entries.length() == 0) {
                mapView.setVisibility(android.view.View.GONE);
                notEnoughDataText.setVisibility(android.view.View.VISIBLE);
                return;
            }

            double sumLat = 0;
            double sumLon = 0;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                double lat = entry.optDouble("lat", 0);
                double lon = entry.optDouble("lon", 0);
                sumLat += lat;
                sumLon += lon;

                Marker marker = new Marker(mapView);
                marker.setPosition(new GeoPoint(lat, lon));
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
                marker.setIcon(new BitmapDrawable(getResources(), coloredDotBitmap(colorForLabel(entry.optString("label", "")))));
                marker.setTitle(entry.optString("restaurant_name", "Unknown"));
                marker.setOnMarkerClickListener((tappedMarker, map) -> {
                    showEntryDetail(entry);
                    return true;
                });
                mapView.getOverlays().add(marker);
            }

            // Centered on the average of every plotted point -- no
            // driver home-location dependency (ShiftRoutingPrefs' home
            // address is optional and may not be configured), and this
            // naturally centers on wherever the driver's own real pickup
            // history actually is.
            mapView.getController().setZoom(11.0);
            mapView.getController().setCenter(new GeoPoint(sumLat / entries.length(), sumLon / entries.length()));
        } catch (JSONException | PyException e) {
            Toast.makeText(this, "Could not load profitability map: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showEntryDetail(JSONObject entry) {
        String message = String.format(
                "Avg Smart Score: %.1f (%s)\nAvg rate: $%.2f/km   $%.2f/hr\nBased on %d offer%s",
                entry.optDouble("avg_smart_score", 0), entry.optString("label", ""),
                entry.optDouble("avg_dollar_per_km", 0), entry.optDouble("avg_dollar_per_hr", 0),
                entry.optInt("sample_count", 0), entry.optInt("sample_count", 0) == 1 ? "" : "s");
        new AlertDialog.Builder(this)
                .setTitle(entry.optString("restaurant_name", "Unknown"))
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    /**
     * Same 4-label->color mapping already used throughout this app
     * (SmartScoreEngine._label's thresholds, DasherAccessibilityService.
     * colorForLabel's exact hex values) -- reused directly rather than
     * inventing a new palette for the same concept on this one screen.
     */
    private int colorForLabel(String label) {
        switch (label) {
            case "Excellent":
                return Color.parseColor("#CC1B5E20");
            case "Good":
                return Color.parseColor("#CC43A047");
            case "Fair":
                return Color.parseColor("#CCF9A825");
            default: // Poor
                return Color.parseColor("#CCC62828");
        }
    }

    /**
     * Same GradientDrawable-oval technique OverlayHelper.showStatusDot
     * already uses for a plain colored circle -- rendered to a small
     * Bitmap here since Marker.setIcon() needs a Drawable, not a live
     * View the way a floating overlay does.
     */
    private Bitmap coloredDotBitmap(int color) {
        int sizePx = (int) (24 * getResources().getDisplayMetrics().density);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        shape.setStroke(2, Color.WHITE);
        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        shape.setBounds(0, 0, sizePx, sizePx);
        shape.draw(canvas);
        return bitmap;
    }
}
