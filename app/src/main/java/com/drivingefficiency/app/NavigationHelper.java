package com.drivingefficiency.app;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

/**
 * One-Tap Instant Pinpoint (report section 3): opens a delivery address in
 * RoadWarrior so it's identified there without retyping it.
 *
 * There is no documented public deep-link/URI API for RoadWarrior to
 * pre-fill a single stop -- their real "API integration" (per their own
 * site) is a business-level bulk-manifest API (e.g. importing FedEx
 * manifests), aimed at fleet accounts with a developer relationship, not a
 * lightweight consumer Intent scheme. So this uses a standard Android
 * geo: intent targeted at RoadWarrior's package first; if RoadWarrior
 * isn't installed or doesn't handle it, it falls back to a normal
 * (unrestricted) geo: intent so whatever maps app is available can open
 * it instead.
 *
 * Your current location is NOT passed in this intent -- there's no need
 * to: RoadWarrior (or whichever app opens) shows your live position itself
 * via its own GPS permission, exactly like it already does when you open
 * it directly.
 */
public final class NavigationHelper {

    // RoadWarrior Route Planner's real Android package name -- reconfirmed
    // correct against the current Google Play Store listing on 2026-08-22
    // during the RoadWarrior-icon fix (docs/road_warrior_icon/PRD.md), so
    // this is NOT the cause of "the RoadWarrior icon doesn't work" and
    // doesn't need re-checking next time that's reported.
    private static final String DEFAULT_ROADWARRIOR_PACKAGE = "com.roadwarrior.android";

    // Premortem finding, fixed here (docs/road_warrior_icon/PRD.md ss4a,
    // P5): a future RoadWarrior update, regional variant, or old sideloaded
    // APK could genuinely use a different package name than the one
    // reconfirmed above -- indistinguishable from "RoadWarrior doesn't
    // handle geo: intents at all" (both silently fall back to the generic
    // chooser). Runtime-overridable the same way GoogleApiHelper's API key
    // is, so an affected driver has a way out without a code change.
    private static final String PREFS_NAME = "navigation_prefs";
    private static final String KEY_ROADWARRIOR_PACKAGE = "roadwarrior_package_override";

    private NavigationHelper() {}

    /** Runtime-entered override takes priority; falls back to the reconfirmed default. */
    public static String getRoadWarriorPackage(Context context) {
        String override = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_ROADWARRIOR_PACKAGE, "");
        if (override != null && !override.isEmpty()) {
            return override;
        }
        return DEFAULT_ROADWARRIOR_PACKAGE;
    }

    public static void setRoadWarriorPackage(Context context, String packageName) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ROADWARRIOR_PACKAGE, packageName == null ? "" : packageName.trim())
                .apply();
    }

    public static void openAddress(Context context, String address, double lat, double lon) {
        // Confirmed real bug, fixed here: (0.0, 0.0) is the placeholder
        // sentinel DasherAccessibilityService uses for "not geocoded yet"
        // (its add_stop_to_buffer calls document this explicitly). Tapping
        // the icon before geocoding resolves -- no API key configured
        // (GoogleApiHelper.hasApiKey()), or the async callback just hasn't
        // returned yet -- used to silently open geo:0.0,0.0, a pin in the
        // Gulf of Guinea, with no indication anything was wrong.
        if (lat == 0.0 && lon == 0.0) {
            Toast.makeText(context, unresolvedAddressReason(context), Toast.LENGTH_LONG).show();
            return;
        }

        String uriString = "geo:" + lat + "," + lon + "?q=" + Uri.encode(address);

        Intent roadWarriorIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(uriString));
        roadWarriorIntent.setPackage(getRoadWarriorPackage(context));
        try {
            context.startActivity(roadWarriorIntent);
            // Confirmed real bug, fixed here: this branch previously gave
            // zero on-screen confirmation that RoadWarrior specifically
            // opened -- the entire point of this feature per the class doc
            // above -- versus the silent fallback below. From the driver's
            // seat the two outcomes were indistinguishable.
            Toast.makeText(context, "Opening \"" + address + "\" in RoadWarrior.",
                    Toast.LENGTH_SHORT).show();
            return;
        } catch (ActivityNotFoundException e) {
            // RoadWarrior isn't installed, or doesn't register for geo:
            // intents -- fall through to the generic chooser below.
        }

        Intent genericIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(uriString));
        try {
            context.startActivity(genericIntent);
            // Same fix as above, fallback side: makes it visible that
            // RoadWarrior specifically was NOT used, instead of leaving the
            // driver to assume it was.
            Toast.makeText(context, "RoadWarrior not available -- opening \"" + address
                    + "\" in your default maps app instead.", Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, "No maps app found to open \"" + address + "\".",
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Premortem finding (docs/road_warrior_icon/PRD.md ss4a, P1/P2/P3):
     * "Address not resolved yet -- try again in a moment" is only true for
     * a genuine race against the async geocode callback. If there's no API
     * key configured, or accessibility access has been revoked, coordinates
     * are stuck at (0.0, 0.0) PERMANENTLY -- trying again does nothing, and
     * the driver deserves to be told which real, fixable thing is wrong
     * rather than sent looping on "try again" forever. Checked in order:
     * a missing API key blocks every stop's geocoding outright, so it's
     * checked first regardless of accessibility state.
     */
    private static String unresolvedAddressReason(Context context) {
        if (!GoogleApiHelper.hasApiKey(context)) {
            return "No Google Maps API key configured -- navigation can't resolve real "
                    + "addresses until one is set up.";
        }
        String enabledServices = android.provider.Settings.Secure.getString(context.getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean hasAccessibility = enabledServices != null
                && enabledServices.contains(context.getPackageName() + "/"
                        + context.getPackageName() + ".DasherAccessibilityService");
        if (!hasAccessibility) {
            return "Accessibility permission is off -- turn it back on in Settings to capture this address.";
        }
        return "Address not resolved yet -- try again in a moment.";
    }

    /**
     * Deliberately SEPARATE from openAddress above, per explicit request:
     * RoadWarrior stays exclusively for pinpointing the actual delivery
     * address -- this uses Waze specifically instead, only ever called
     * for the return-to-sweet-spot icon, never for delivery navigation.
     * Uses Waze's own URI scheme directly (not a generic geo: intent,
     * which wouldn't guarantee Waze specifically gets chosen), with a
     * fallback to a generic maps chooser if Waze isn't installed.
     */
    public static void openAddressWithWaze(Context context, double lat, double lon) {
        Uri wazeUri = Uri.parse("waze://?ll=" + lat + "," + lon + "&navigate=yes");
        Intent wazeIntent = new Intent(Intent.ACTION_VIEW, wazeUri);
        wazeIntent.setPackage("com.waze");
        try {
            context.startActivity(wazeIntent);
            return;
        } catch (ActivityNotFoundException e) {
            // Waze isn't installed -- fall through to a generic chooser
            // rather than silently doing nothing.
        }

        Intent genericIntent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("geo:" + lat + "," + lon + "?q=" + lat + "," + lon));
        try {
            context.startActivity(genericIntent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, "No maps app found to navigate there.", Toast.LENGTH_LONG).show();
        }
    }
}
