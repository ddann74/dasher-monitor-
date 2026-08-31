package com.drivingefficiency.app;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

/**
 * RoadWarrior icon tap action (docs/road_warrior_icon/PRD.md, requirement
 * change 2026-08-30): copies the stop's address to the clipboard so the
 * driver can paste it into RoadWarrior -- or whatever app they actually
 * use -- themselves.
 *
 * This REPLACES the original auto-launch-navigation design. That design
 * existed because there is no documented public deep-link/URI API for
 * RoadWarrior to pre-fill a single stop -- their real "API integration"
 * (per their own site) is a business-level bulk-manifest API aimed at
 * fleet accounts, not a lightweight consumer Intent scheme -- and whether
 * RoadWarrior even handles a generic geo: intent was never confirmed on a
 * real device (see the PRD's s1 for the full history). Copying the address
 * sidesteps that uncertainty entirely instead of continuing to guess at
 * it: the driver pastes it into whatever actually works for them.
 */
public final class NavigationHelper {

    // Kept for DasherAccessibilityService's two real geocode onError
    // callbacks (handleDropoffScreen's fullAddress,
    // geocodePickupAndCheckTraffic's restaurantName) -- that file is out of
    // scope for this PRD to touch. The failure info recorded here is no
    // longer READ by anything in this class: the copy-to-clipboard guard
    // below is address-TEXT-based, not coordinate-based, so a failed
    // geocode (which only ever affected lat/lon, never the address string
    // itself) doesn't block a copy the way it used to block navigation.
    // Write side kept so those two out-of-scope call sites keep compiling;
    // nothing currently reads this back.
    private static final String PREFS_NAME = "navigation_prefs";
    private static final String KEY_LAST_GEOCODE_FAILURE_TARGET = "last_geocode_failure_target";
    private static final String KEY_LAST_GEOCODE_FAILURE_MESSAGE = "last_geocode_failure_message";
    private static final String KEY_LAST_GEOCODE_FAILURE_AT = "last_geocode_failure_at";

    private NavigationHelper() {}

    /**
     * REAL GAP, closed here: every outcome in this class was only ever a
     * Toast -- visible in the moment, but leaving zero durable record.
     * Combined with the FLAG_ACTIVITY_NEW_TASK crash (now fixed
     * separately), this meant NO real diagnostic log uploaded so far has
     * ever shown whether a RoadWarrior tap actually succeeded, fell back
     * to another maps app, or found nothing at all -- the crash always
     * happened first, and even once it doesn't, nothing here was ever
     * logged for later review. Mirrors the Toast text at each real
     * outcome so the next uploaded diagnostic log can finally show which
     * one happened, without requiring anyone to have been watching the
     * screen at the exact moment.
     */
    private static void logDiagnostic(Context context, String category, String message) {
        try {
            PythonBridge.getEngine(context).callAttr("log_diagnostic", category, message);
        } catch (RuntimeException e) { // covers PyException too
            FallbackLogger.log(context, category, message);
        }
    }

    /**
     * Called from DasherAccessibilityService's geocode onError callbacks
     * (dropoff's fullAddress, pickup's restaurantName) -- the only two real
     * geocode call sites that feed NavigationHelper's tap coordinates.
     */
    public static void recordGeocodeFailure(Context context, String target, String message) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_GEOCODE_FAILURE_TARGET, target == null ? "" : target)
                .putString(KEY_LAST_GEOCODE_FAILURE_MESSAGE, message == null ? "" : message)
                .putLong(KEY_LAST_GEOCODE_FAILURE_AT, System.currentTimeMillis())
                .apply();
    }

    /**
     * Requirement change (2026-08-30, docs/road_warrior_icon/PRD.md):
     * copies `address` to the clipboard instead of launching a navigation
     * intent. Guards on the address TEXT being present, not on
     * coordinates -- this action no longer uses lat/lon at all, since the
     * driver only needs something to paste, and the address string is
     * often known well before geocoding would have resolved it.
     */
    public static void copyAddressToClipboard(Context context, String address) {
        if (address == null || address.trim().isEmpty()) {
            Toast.makeText(context, "Address not available yet -- try again in a moment.",
                    Toast.LENGTH_LONG).show();
            logDiagnostic(context, "NAV_TAP", "Refused to copy -- address not available yet");
            return;
        }

        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            // Not expected on a real device, but the service lookup can
            // theoretically return null -- fail visibly rather than
            // silently doing nothing if it ever does.
            Toast.makeText(context, "Could not access the clipboard.", Toast.LENGTH_LONG).show();
            logDiagnostic(context, "NAV_TAP", "Clipboard service unavailable for \"" + address + "\"");
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("Delivery address", address));
        Toast.makeText(context, "Copied to clipboard: " + address, Toast.LENGTH_LONG).show();
        logDiagnostic(context, "NAV_TAP", "Copied \"" + address + "\" to clipboard");
    }

    /**
     * Deliberately SEPARATE from copyAddressToClipboard above, out of scope
     * for the 2026-08-30 requirement change: RoadWarrior/clipboard-copy is
     * exclusively for pinpointing the actual delivery address -- this uses
     * Waze specifically instead, only ever called for the
     * return-to-sweet-spot icon, never for delivery navigation. Uses
     * Waze's own URI scheme directly (not a generic geo: intent, which
     * wouldn't guarantee Waze specifically gets chosen), with a fallback
     * to a generic maps chooser if Waze isn't installed.
     */
    public static void openAddressWithWaze(Context context, double lat, double lon) {
        Uri wazeUri = Uri.parse("waze://?ll=" + lat + "," + lon + "&navigate=yes");
        Intent wazeIntent = new Intent(Intent.ACTION_VIEW, wazeUri);
        wazeIntent.setPackage("com.waze");
        // Called from TripForegroundService (a Service), so
        // FLAG_ACTIVITY_NEW_TASK is required or Android throws and kills
        // the whole app process.
        wazeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(wazeIntent);
            logDiagnostic(context, "NAV_TAP", "Opened Waze navigation to " + lat + "," + lon
                    + " -- geo: intent accepted without exception");
            return;
        } catch (ActivityNotFoundException e) {
            // Waze isn't installed -- fall through to a generic chooser
            // rather than silently doing nothing.
            logDiagnostic(context, "NAV_TAP", "Waze not available for " + lat + "," + lon
                    + " -- falling back to generic maps chooser");
        }

        Intent genericIntent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("geo:" + lat + "," + lon + "?q=" + lat + "," + lon));
        genericIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); // same fix as wazeIntent above
        try {
            context.startActivity(genericIntent);
            logDiagnostic(context, "NAV_TAP", "Opened " + lat + "," + lon + " via the generic maps chooser (Waze fallback)");
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, "No maps app found to navigate there.", Toast.LENGTH_LONG).show();
            logDiagnostic(context, "NAV_TAP", "No maps app at all found to navigate to " + lat + "," + lon);
        }
    }
}
