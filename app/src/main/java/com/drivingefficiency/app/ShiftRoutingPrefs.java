package com.drivingefficiency.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * docs/hotspot_or_home_routing/PRD.md -- driver-configured home address
 * (geocoded via GoogleApiHelper) plus a driver-typed $/hr rate threshold.
 * Both are required before the feature does anything at all -- no
 * fabricated default address or threshold, same "opt-in, nothing
 * assumed" discipline as ScreenRecordingController's own toggle
 * (isEnabled() defaults to false, never silently on).
 */
final class ShiftRoutingPrefs {

    private static final String PREFS_NAME = "shift_routing_prefs";
    private static final String KEY_HOME_ADDRESS = "home_address";
    private static final String KEY_HOME_LAT = "home_lat";
    private static final String KEY_HOME_LON = "home_lon";
    private static final String KEY_THRESHOLD = "rate_threshold_dollars_per_hr";

    private ShiftRoutingPrefs() {}

    static String getHomeAddress(Context context) {
        return prefs(context).getString(KEY_HOME_ADDRESS, "");
    }

    static void setHomeAddress(Context context, String address, double lat, double lon) {
        prefs(context).edit()
                .putString(KEY_HOME_ADDRESS, address)
                .putFloat(KEY_HOME_LAT, (float) lat)
                .putFloat(KEY_HOME_LON, (float) lon)
                .apply();
    }

    /** Called if geocoding the entered address fails -- never keeps a
      * stale/wrong lat-lon paired with a newly-typed address that didn't
      * actually resolve. */
    static void clearHomeLocation(Context context) {
        prefs(context).edit().remove(KEY_HOME_LAT).remove(KEY_HOME_LON).apply();
    }

    /** Null if no home address has ever been successfully geocoded. */
    static double[] getHomeLatLon(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.contains(KEY_HOME_LAT) || !p.contains(KEY_HOME_LON)) {
            return null;
        }
        return new double[]{p.getFloat(KEY_HOME_LAT, 0f), p.getFloat(KEY_HOME_LON, 0f)};
    }

    static boolean hasThreshold(Context context) {
        return prefs(context).contains(KEY_THRESHOLD);
    }

    /** Only meaningful if hasThreshold() is true -- 0 otherwise, not a real value. */
    static double getThreshold(Context context) {
        return prefs(context).getFloat(KEY_THRESHOLD, 0f);
    }

    static void setThreshold(Context context, double dollarsPerHour) {
        prefs(context).edit().putFloat(KEY_THRESHOLD, (float) dollarsPerHour).apply();
    }

    /** True only once BOTH a geocoded home address and a threshold are set. */
    static boolean isConfigured(Context context) {
        return getHomeLatLon(context) != null && hasThreshold(context);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
