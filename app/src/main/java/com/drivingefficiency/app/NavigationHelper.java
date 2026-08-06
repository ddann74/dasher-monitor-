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

    // RoadWarrior Route Planner's real Android package name.
    private static final String ROADWARRIOR_PACKAGE = "com.roadwarrior.android";

    private NavigationHelper() {}

    public static void openAddress(Context context, String address, double lat, double lon) {
        String uriString = "geo:" + lat + "," + lon + "?q=" + Uri.encode(address);

        Intent roadWarriorIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(uriString));
        roadWarriorIntent.setPackage(ROADWARRIOR_PACKAGE);
        try {
            context.startActivity(roadWarriorIntent);
            return;
        } catch (ActivityNotFoundException e) {
            // RoadWarrior isn't installed, or doesn't register for geo:
            // intents -- fall through to the generic chooser below.
        }

        Intent genericIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(uriString));
        try {
            context.startActivity(genericIntent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, "No maps app found to open \"" + address + "\".",
                    Toast.LENGTH_LONG).show();
        }
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
