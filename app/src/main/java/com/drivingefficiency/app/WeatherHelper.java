package com.drivingefficiency.app;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Real current-weather data via Open-Meteo -- free, no API key required,
 * lat/lon based. Open-Meteo can specifically wrap the Australian Bureau
 * of Meteorology's own ACCESS-G model (&models=bom_access_global), but as
 * of this writing BOM's open-data delivery is temporarily suspended
 * during a platform upgrade on their end -- so rather than depend on
 * something explicitly flagged as unavailable right now, this uses
 * Open-Meteo's default best-available model for the given coordinates,
 * which is reliable today. No fallback logic needed for "no API key"
 * (there isn't one), only for network/parse failures.
 */
public final class WeatherHelper {

    public interface WeatherCallback {
        void onResult(double precipitationMm, double windSpeedKmh, double temperatureC);
        void onError(String message);
    }

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private WeatherHelper() {}

    public static void getCurrentWeather(double lat, double lon, WeatherCallback callback) {
        new Thread(() -> {
            try {
                String urlString = "https://api.open-meteo.com/v1/forecast?latitude=" + lat
                        + "&longitude=" + lon
                        + "&current=precipitation,wind_speed_10m,temperature_2m"
                        + "&timezone=auto";
                JSONObject json = new JSONObject(httpGet(urlString));
                JSONObject current = json.optJSONObject("current");
                if (current == null) {
                    postError(callback, "No current weather data returned.");
                    return;
                }
                double precipitation = current.optDouble("precipitation", 0.0);
                double windSpeed = current.optDouble("wind_speed_10m", 0.0);
                double temperature = current.optDouble("temperature_2m", 20.0);
                MAIN_HANDLER.post(() -> callback.onResult(precipitation, windSpeed, temperature));
            } catch (Exception e) {
                postError(callback, "Weather error: " + e.getMessage());
            }
        }).start();
    }

    private static String httpGet(String urlString) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                URI.create(urlString).toURL().openConnection();
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

    private static void postError(WeatherCallback callback, String message) {
        MAIN_HANDLER.post(() -> callback.onError(message));
    }
}
