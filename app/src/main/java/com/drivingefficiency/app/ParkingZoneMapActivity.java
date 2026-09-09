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
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

/**
 * docs/parking_zone_map/PRD.md - a real satellite-basemap map of
 * per-restaurant parking difficulty, built as a structural copy of
 * LocationProfitabilityMapActivity (same osmdroid setup, same
 * tap-to-detail pattern) with two deliberate differences: a satellite
 * tile source instead of the street-map MAPNIK default, and shaded
 * (translucent, larger) zone circles instead of small solid dots.
 *
 * Uses Esri World Imagery (server.arcgisonline.com) for the satellite
 * layer, NOT literal Google Maps tiles - Google's own tile servers
 * aren't freely usable outside the Google Maps SDK, which would
 * reintroduce the API-key/Cloud-Console cost this app has already
 * chosen twice to avoid (see docs/location_profitability_map/PRD.md
 * ss1.3 for the original osmdroid-over-Google-Maps-SDK reasoning).
 * Esri's REST tile API addresses tiles as z/y/x (row before column),
 * the OPPOSITE of the z/x/y order XYTileSource's own default
 * getTileURLString() assumes - the override below builds the URL in
 * Esri's actual order explicitly. HONEST LIMIT: this exact ordering
 * could not be confirmed against a real device/tile response in this
 * environment (no Android SDK available here) - if tiles fail to load
 * or come back scrambled, this URL construction is the first place to
 * check.
 */
public class ParkingZoneMapActivity extends AppCompatActivity {

    private PyObject engine;
    private MapView mapView;

    private static final XYTileSource ESRI_WORLD_IMAGERY = new XYTileSource(
            "EsriWorldImagery", 0, 19, 256, ".jpg",
            new String[]{"https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"}) {
        @Override
        public String getTileURLString(long pMapTileIndex) {
            // Esri's REST tile addressing is .../tile/{z}/{y}/{x} -
            // deliberately NOT the z/x/y order the XYTileSource base
            // class assumes by default (see this class's own doc).
            return getBaseUrl()
                    + MapTileIndex.getZoom(pMapTileIndex) + "/"
                    + MapTileIndex.getY(pMapTileIndex) + "/"
                    + MapTileIndex.getX(pMapTileIndex);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Same real osmdroid setup requirement
        // LocationProfitabilityMapActivity already established -- a
        // real user-agent string, loaded before any MapView is used.
        Configuration.getInstance().load(this,
                android.preference.PreferenceManager.getDefaultSharedPreferences(this));
        Configuration.getInstance().setUserAgentValue(getPackageName());

        setContentView(R.layout.activity_parking_zone_map);
        engine = PythonBridge.getEngine(this);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Parking Zones");
        }

        mapView = findViewById(R.id.parkingZoneMapView);
        mapView.setTileSource(ESRI_WORLD_IMAGERY);
        mapView.setMultiTouchControls(true);

        loadAndShowZones();
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

    private void loadAndShowZones() {
        try {
            JSONObject result = new JSONObject(engine.callAttr("get_parking_difficulty_zones").toString());
            JSONArray entries = result.optJSONArray("entries");
            TextView notEnoughDataText = findViewById(R.id.notEnoughDataText);
            if (entries == null || entries.length() == 0) {
                // Field-test note: previously nothing in the visible
                // diagnostic log distinguished "empty because no
                // restaurant has 3+ samples yet" (expected, not a bug)
                // from "the query itself silently failed."
                logDiagnostic("PARKING_ZONE_MAP", "No restaurants have both a GPS anchor and "
                        + "enough parking samples yet -- showing the empty-state message");
                mapView.setVisibility(android.view.View.GONE);
                notEnoughDataText.setVisibility(android.view.View.VISIBLE);
                return;
            }
            logDiagnostic("PARKING_ZONE_MAP", "Loaded " + entries.length() + " restaurant zone"
                    + (entries.length() == 1 ? "" : "s") + " onto the satellite map");

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
                marker.setIcon(new BitmapDrawable(getResources(), shadedZoneBitmap(entry.optString("label", ""))));
                marker.setTitle(entry.optString("restaurant_name", "Unknown"));
                marker.setOnMarkerClickListener((tappedMarker, map) -> {
                    showZoneDetail(entry);
                    return true;
                });
                mapView.getOverlays().add(marker);
            }

            // Centered on the average of every plotted zone -- same
            // reasoning LocationProfitabilityMapActivity's own centering
            // already uses: no driver home-location dependency, and
            // this naturally centers on wherever the driver's own real
            // pickup history actually is.
            mapView.getController().setZoom(15.0);
            mapView.getController().setCenter(new GeoPoint(sumLat / entries.length(), sumLon / entries.length()));
        } catch (JSONException | PyException e) {
            Toast.makeText(this, "Could not load parking zones: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showZoneDetail(JSONObject entry) {
        int sampleCount = entry.optInt("sample_count", 0);
        int manualCount = entry.optInt("manual_sample_count", 0);
        int autoCount = entry.optInt("auto_sample_count", 0);
        String restaurantName = entry.optString("restaurant_name", "Unknown");
        String label = entry.optString("label", "");
        String message = String.format(java.util.Locale.US,
                "Parking difficulty: %s (%.0f/100)\nBased on %d sample%s (%d manual, %d automatic)",
                label, entry.optDouble("avg_score", 0),
                sampleCount, sampleCount == 1 ? "" : "s", manualCount, autoCount);
        logDiagnostic("PARKING_ZONE_MAP", "Tapped zone: " + restaurantName + " (" + label + ")");
        new AlertDialog.Builder(this)
                .setTitle(restaurantName)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    /** Same "a logging call can never crash the app" wrapper pattern
      * already used elsewhere in this app (e.g. TripForegroundService,
      * DiagnosticsActivity) -- this Activity had none until the
      * field-test checklist audit found it logged nothing at all. */
    private void logDiagnostic(String category, String message) {
        try {
            engine.callAttr("log_diagnostic", category, message);
        } catch (RuntimeException e) { // covers PyException too
        }
    }

    /**
     * A translucent, larger oval than LocationProfitabilityMapActivity's
     * own small solid coloredDotBitmap -- a "shaded zone," not a pin,
     * per the driver's own request. Same GradientDrawable-oval-onto-a-
     * Bitmap technique that method already uses, just bigger and more
     * transparent (alpha in the color itself, not a separate layer).
     */
    private Bitmap shadedZoneBitmap(String label) {
        int sizePx = (int) (72 * getResources().getDisplayMetrics().density);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        int baseColor;
        switch (label) {
            case "Easy":
                baseColor = Color.parseColor("#2E7D32");
                break;
            case "Difficult":
                baseColor = Color.parseColor("#C62828");
                break;
            default: // "Normal"
                baseColor = Color.parseColor("#EF6C00");
                break;
        }
        // Alpha ~0x66 (40%) -- a translucent shaded area, not a solid
        // fill, so the satellite imagery underneath stays visible.
        int shadedColor = (0x66 << 24) | (baseColor & 0x00FFFFFF);
        shape.setColor(shadedColor);
        shape.setStroke(3, baseColor);
        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        shape.setBounds(0, 0, sizePx, sizePx);
        shape.draw(canvas);
        return bitmap;
    }
}
