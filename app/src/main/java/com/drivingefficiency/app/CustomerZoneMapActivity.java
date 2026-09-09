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
 * Structural copy of ParkingZoneMapActivity -- same satellite basemap,
 * same shaded-zone/tap-to-detail shape -- but for CUSTOMER dropoff
 * addresses instead of restaurants, backed by
 * get_customer_parking_difficulty_zones() rather than
 * get_parking_difficulty_zones().
 *
 * Found while adding this screen, later fixed: parking_
 * difficulty_feedback rows are named "restaurant_name" throughout, but
 * until TripManager.is_walking_pace's own fix, the only place that ever
 * wrote them only ever checked the DROPOFF stop list -- see
 * get_customer_parking_difficulty_zones's own doc comment in
 * drive_monitor.py. is_walking_pace now checks pickup too and tags each
 * sample with the stop it actually happened at, so ParkingZoneMapActivity's
 * restaurant-keyed join and this screen's dropoff-keyed one each see only
 * their own real data going forward.
 */
public class CustomerZoneMapActivity extends AppCompatActivity {

    private PyObject engine;
    private MapView mapView;

    private static final XYTileSource ESRI_WORLD_IMAGERY = new XYTileSource(
            "EsriWorldImagery", 0, 19, 256, ".jpg",
            new String[]{"https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"}) {
        @Override
        public String getTileURLString(long pMapTileIndex) {
            // Same Esri z/y/x addressing override as
            // ParkingZoneMapActivity's own tile source -- see that
            // class's doc comment for the full explanation.
            return getBaseUrl()
                    + MapTileIndex.getZoom(pMapTileIndex) + "/"
                    + MapTileIndex.getY(pMapTileIndex) + "/"
                    + MapTileIndex.getX(pMapTileIndex);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Configuration.getInstance().load(this,
                android.preference.PreferenceManager.getDefaultSharedPreferences(this));
        Configuration.getInstance().setUserAgentValue(getPackageName());

        setContentView(R.layout.activity_customer_zone_map);
        engine = PythonBridge.getEngine(this);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Customer Zones");
        }

        mapView = findViewById(R.id.customerZoneMapView);
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
            JSONObject result = new JSONObject(engine.callAttr("get_customer_parking_difficulty_zones").toString());
            JSONArray entries = result.optJSONArray("entries");
            TextView notEnoughDataText = findViewById(R.id.notEnoughDataText);
            if (entries == null || entries.length() == 0) {
                // Same field-test note as ParkingZoneMapActivity's own:
                // distinguishes "empty because no address has 3+
                // samples yet" (expected) from a silent query failure.
                logDiagnostic("CUSTOMER_ZONE_MAP", "No addresses have both a GPS anchor and "
                        + "enough parking samples yet -- showing the empty-state message");
                mapView.setVisibility(android.view.View.GONE);
                notEnoughDataText.setVisibility(android.view.View.VISIBLE);
                return;
            }
            logDiagnostic("CUSTOMER_ZONE_MAP", "Loaded " + entries.length() + " customer zone"
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
                marker.setTitle(entry.optString("address", "Unknown address"));
                marker.setOnMarkerClickListener((tappedMarker, map) -> {
                    showZoneDetail(entry);
                    return true;
                });
                mapView.getOverlays().add(marker);
            }

            mapView.getController().setZoom(15.0);
            mapView.getController().setCenter(new GeoPoint(sumLat / entries.length(), sumLon / entries.length()));
        } catch (JSONException | PyException e) {
            Toast.makeText(this, "Could not load customer zones: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showZoneDetail(JSONObject entry) {
        int sampleCount = entry.optInt("sample_count", 0);
        int manualCount = entry.optInt("manual_sample_count", 0);
        int autoCount = entry.optInt("auto_sample_count", 0);
        String address = entry.optString("address", "Unknown address");
        String label = entry.optString("label", "");
        String message = String.format(java.util.Locale.US,
                "Parking difficulty: %s (%.0f/100)\nBased on %d sample%s (%d manual, %d automatic)",
                label, entry.optDouble("avg_score", 0),
                sampleCount, sampleCount == 1 ? "" : "s", manualCount, autoCount);
        logDiagnostic("CUSTOMER_ZONE_MAP", "Tapped zone: " + address + " (" + label + ")");
        new AlertDialog.Builder(this)
                .setTitle(address)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    /** Same wrapper pattern already used elsewhere (e.g.
      * TripForegroundService, DiagnosticsActivity) -- this Activity had
      * none until the field-test checklist audit found it. */
    private void logDiagnostic(String category, String message) {
        try {
            engine.callAttr("log_diagnostic", category, message);
        } catch (RuntimeException e) { // covers PyException too
        }
    }

    /** Same shaded-oval technique as ParkingZoneMapActivity's own. */
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
