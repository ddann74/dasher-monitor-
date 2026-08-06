package com.drivingefficiency.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Real Google Maps Platform integration: geocoding (turns a restaurant
 * name/address into real lat/lon) and traffic-aware Distance Matrix
 * (live traffic delay for a specific route right now).
 *
 * This closes a much bigger gap than it looks like: every pickup in the
 * real app previously used hardcoded (0.0, 0.0) placeholder coordinates,
 * since nothing converted an address into real coordinates anywhere --
 * meaning arrival detection, and every "learned" Smart Score factor
 * (deadhead, restaurant wait, delivery speed) built on top of it, could
 * never actually fire in real-world use; they only worked in simulated
 * testing with realistic fake coordinates. Real geocoding is what makes
 * all of that (not just traffic) actually functional in the field.
 *
 * Requires a Google Maps Platform API key with the Geocoding API and
 * Distance Matrix API enabled, with billing configured on the Google
 * Cloud project (both are paid beyond a monthly free credit).
 *
 * TWO ways to provide the key, checked in this order:
 * 1. Entered at runtime via Permissions & Setup -- stored in
 *    SharedPreferences, takes priority if present. This is the
 *    convenient path: survives re-extracting the generated project into
 *    a new folder, no local.properties editing needed.
 * 2. BuildConfig.GOOGLE_MAPS_API_KEY, populated from local.properties
 *    (NOT committed to version control) at build time -- see README.
 *    Used only as a fallback if nothing was entered at runtime.
 *
 * All network calls here run on a background thread and return results
 * via a callback posted back to the main thread -- an accessibility
 * service or activity must never block directly on a live network call.
 */
public final class GoogleApiHelper {

    public static final String PREFS_NAME = "google_api_prefs";
    public static final String KEY_API_KEY = "google_maps_api_key";

    public interface GeocodeCallback {
        void onResult(double lat, double lon);
        void onError(String message);
    }

    public interface TrafficCallback {
        /** trafficDelayRatio: 1.0 = no delay, 1.5 = 50% slower than typical, etc. */
        void onResult(double trafficDelayRatio, int durationInTrafficSeconds, int typicalDurationSeconds);
        void onError(String message);
    }

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private GoogleApiHelper() {}

    /** Runtime-entered key takes priority; falls back to the build-time BuildConfig value. */
    public static String getApiKey(Context context) {
        String runtimeKey = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_API_KEY, "");
        if (runtimeKey != null && !runtimeKey.isEmpty()) {
            return runtimeKey;
        }
        return BuildConfig.GOOGLE_MAPS_API_KEY;
    }

    public static void setApiKey(Context context, String key) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_API_KEY, key == null ? "" : key.trim())
                .apply();
    }

    public static boolean hasApiKey(Context context) {
        String key = getApiKey(context);
        return key != null && !key.isEmpty();
    }

    public static void geocodeAddress(Context context, String address, GeocodeCallback callback) {
        if (!hasApiKey(context)) {
            postError(callback, "No Google Maps API key configured (see Permissions & Setup).");
            return;
        }
        String apiKey = getApiKey(context);
        new Thread(() -> {
            try {
                String encoded = URLEncoder.encode(address, "UTF-8");
                String urlString = "https://maps.googleapis.com/maps/api/geocode/json?address="
                        + encoded + "&key=" + apiKey;
                JSONObject json = new JSONObject(httpGet(urlString));
                if (!"OK".equals(json.optString("status"))) {
                    // error_message carries Google's SPECIFIC reason (e.g.
                    // "This API project is not authorized to use this API"
                    // vs "The provided API key is invalid") -- the bare
                    // status code alone ("REQUEST_DENIED") doesn't say
                    // which of several possible causes it actually is.
                    String detail = json.optString("error_message", "");
                    postError(callback, "Geocoding failed: " + json.optString("status")
                            + (detail.isEmpty() ? "" : " -- " + detail));
                    return;
                }
                JSONArray results = json.getJSONArray("results");
                JSONObject location = results.getJSONObject(0)
                        .getJSONObject("geometry").getJSONObject("location");
                double lat = location.getDouble("lat");
                double lon = location.getDouble("lng");
                MAIN_HANDLER.post(() -> callback.onResult(lat, lon));
            } catch (Exception e) {
                postError(callback, "Geocoding error: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Live traffic delay ratio for a route right now (departure_time=now).
     * Needs real coordinates on both ends -- meaningless with placeholder
     * (0,0) values.
     */
    public static void getTrafficDelayRatio(Context context, double originLat, double originLon,
                                              double destLat, double destLon,
                                              TrafficCallback callback) {
        if (!hasApiKey(context)) {
            postError(callback, "No Google Maps API key configured (see Permissions & Setup).");
            return;
        }
        String apiKey = getApiKey(context);
        new Thread(() -> {
            try {
                String urlString = "https://maps.googleapis.com/maps/api/distancematrix/json"
                        + "?origins=" + originLat + "," + originLon
                        + "&destinations=" + destLat + "," + destLon
                        + "&departure_time=now&traffic_model=best_guess"
                        + "&key=" + apiKey;
                JSONObject json = new JSONObject(httpGet(urlString));
                if (!"OK".equals(json.optString("status"))) {
                    String detail = json.optString("error_message", "");
                    postError(callback, "Distance Matrix failed: " + json.optString("status")
                            + (detail.isEmpty() ? "" : " -- " + detail));
                    return;
                }
                JSONObject element = json.getJSONArray("rows").getJSONObject(0)
                        .getJSONArray("elements").getJSONObject(0);
                if (!"OK".equals(element.optString("status"))) {
                    postError(callback, "Route not found: " + element.optString("status"));
                    return;
                }
                int typicalSeconds = element.getJSONObject("duration").getInt("value");
                int trafficSeconds = element.has("duration_in_traffic")
                        ? element.getJSONObject("duration_in_traffic").getInt("value")
                        : typicalSeconds;
                double ratio = typicalSeconds > 0 ? (double) trafficSeconds / typicalSeconds : 1.0;
                int finalTypical = typicalSeconds;
                int finalTraffic = trafficSeconds;
                MAIN_HANDLER.post(() -> callback.onResult(ratio, finalTraffic, finalTypical));
            } catch (Exception e) {
                postError(callback, "Distance Matrix error: " + e.getMessage());
            }
        }).start();
    }

    private static String httpGet(String urlString) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                java.net.URI.create(urlString).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        StringBuilder response = new StringBuilder();
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
        } finally {
            connection.disconnect();
        }
        return response.toString();
    }

    private static void postError(GeocodeCallback callback, String message) {
        MAIN_HANDLER.post(() -> callback.onError(message));
    }

    private static void postError(TrafficCallback callback, String message) {
        MAIN_HANDLER.post(() -> callback.onError(message));
    }
}
