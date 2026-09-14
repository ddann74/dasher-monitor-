package com.drivingefficiency.app;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Single source of truth for the Dasher (DoorDash driver) app's package
 * name -- CONFIRMED REAL GAP, fixed here (2026-09-14, docs/
 * dasher_package_verification/PRD.md): this was previously duplicated as
 * two independently-hardcoded `DASHER_PACKAGE` constants
 * (DasherAccessibilityService, AppNotificationListenerService), one of
 * which carried its own "Update with the real Dasher app package name"
 * comment -- a live signal this value was never independently confirmed
 * against a real installed app, and a classic drift risk if either copy
 * were ever updated without the other.
 *
 * EVERY detection path in this app -- offer parsing, screen recognition,
 * mode switching (DASHER vs. GENERAL), notification-based offer/message
 * detection -- depends entirely on this exact string matching a real
 * app's getPackageName(). If DoorDash ever changes it (a rebrand, a
 * regional variant, a different build channel) or it was simply wrong to
 * begin with, detection fails completely and silently, with nothing
 * anywhere telling the driver why. isInstalled() exists so that failure
 * can be made loud (see TripForegroundService.checkAndLogPermissions)
 * instead of silent.
 */
final class DasherAppInfo {

    static final String PACKAGE_NAME = "com.doordash.driverapp";

    private DasherAppInfo() {}

    /**
     * Whether a package by this exact name is actually installed on the
     * device right now -- checked, not assumed. False doesn't
     * necessarily mean "this driver doesn't have DoorDash installed" --
     * it could just as easily mean PACKAGE_NAME itself is wrong or
     * outdated, which is exactly the case this exists to catch.
     */
    static boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(PACKAGE_NAME, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
