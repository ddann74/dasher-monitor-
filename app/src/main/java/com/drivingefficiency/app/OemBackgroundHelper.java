package com.drivingefficiency.app;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Confirmed via a real diagnostic log: accessibility can be silently
 * revoked mid-session (9 occurrences in ~4 hours) with screenOn=true and
 * doze=false every time, and with install/update timestamps never
 * changing -- ruling out both simple screen-off/Doze killing and the
 * "Restricted Settings after reinstall" theory for that specific
 * incident. The remaining, most likely explanation is an OEM-proprietary
 * background-management sweep (separate from Android's standard battery
 * optimization API, which was already confirmed exempted at the time).
 *
 * This class centralizes best-effort deep-links into each OEM's own
 * autostart / "protected apps" settings screen, since standard
 * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS does not reach these
 * OEM-specific controls at all. HONEST LIMIT: these component names are
 * undocumented, OEM-internal, and have changed across OS versions and
 * regions before -- every attempt is wrapped so a missing/renamed
 * activity falls back to the app's own details settings screen rather
 * than crashing.
 */
final class OemBackgroundHelper {

    private OemBackgroundHelper() {
    }

    private static String manufacturer() {
        return Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The manufacturers with a documented history of proprietary
     * background-killing beyond standard Android battery optimization.
     * Used to decide whether to proactively show guidance at all --
     * showing this on a Pixel or a stock-Android device would just be
     * confusing noise with no matching settings screen to send anyone to.
     */
    static boolean isKnownAggressiveOem() {
        String m = manufacturer();
        return m.contains("xiaomi") || m.contains("oppo") || m.contains("vivo")
                || m.contains("huawei") || m.contains("honor") || m.contains("samsung")
                || m.contains("oneplus") || m.contains("meizu") || m.contains("letv")
                || m.contains("asus") || m.contains("lenovo");
    }

    /** Human-readable name for display, since Build.MANUFACTURER casing/values vary ("OPPO", "samsung", "Xiaomi"). */
    static String displayName() {
        String m = manufacturer();
        if (m.contains("xiaomi")) return "Xiaomi";
        if (m.contains("oppo")) return "Oppo";
        if (m.contains("vivo")) return "Vivo";
        if (m.contains("huawei")) return "Huawei";
        if (m.contains("honor")) return "Honor";
        if (m.contains("samsung")) return "Samsung";
        if (m.contains("oneplus")) return "OnePlus";
        if (m.contains("meizu")) return "Meizu";
        if (m.contains("letv")) return "LeEco";
        if (m.contains("asus")) return "Asus";
        if (m.contains("lenovo")) return "Lenovo";
        return Build.MANUFACTURER == null ? "this device" : Build.MANUFACTURER;
    }

    /**
     * Manufacturer-specific explanation of where the setting lives and
     * what it's usually called, shown alongside the deep-link button
     * since the button itself may land on a generic settings screen if
     * the OEM has changed the activity name.
     */
    static String guidanceText() {
        String m = manufacturer();
        if (m.contains("xiaomi")) {
            return "MIUI's \"Autostart\" permission and \"No restrictions\" battery saver setting "
                    + "both need to be enabled for this app, or MIUI can silently stop it in the background.";
        }
        if (m.contains("oppo") || m.contains("oneplus")) {
            return "ColorOS/OxygenOS \"Startup Manager\" (Protected Apps) and \"Allow background activity\" "
                    + "both need to be enabled. \"Restricted Settings\" can also block re-enabling "
                    + "Accessibility after a reinstall -- if the button below opens the wrong screen, go to "
                    + "Settings > Apps > See all apps > Dasher Monitor > three-dot menu > \"Allow restricted settings\".";
        }
        if (m.contains("vivo")) {
            return "Vivo's \"High background app power consumption\" / autostart permission needs to be "
                    + "enabled in the Background App Management screen, or the OS can freeze this app.";
        }
        if (m.contains("huawei") || m.contains("honor")) {
            return "Huawei/Honor's \"App Launch\" settings need Manage Manually enabled with Auto-launch, "
                    + "Secondary launch, and Run in background all switched on.";
        }
        if (m.contains("samsung")) {
            return "Samsung's \"Put unused apps to sleep\" and \"Deep sleeping apps\" lists (in Battery > "
                    + "Background usage limits, aka Device Care) need to exclude this app, or Samsung will "
                    + "put it to sleep after periods of inactivity.";
        }
        if (m.contains("meizu")) {
            return "Meizu's Security Center app permission for background/autostart needs to be enabled.";
        }
        if (m.contains("letv")) {
            return "LeEco's autostart/background permission for this app needs to be enabled.";
        }
        if (m.contains("asus")) {
            return "Asus's Mobile Manager \"Auto-start Manager\" needs this app enabled.";
        }
        if (m.contains("lenovo")) {
            return "Lenovo's background/autostart permission for this app needs to be enabled.";
        }
        return "This manufacturer's battery/background management settings should exclude this app.";
    }

    /**
     * Best-effort list of known OEM settings activities to try, most
     * specific/current first. Ordered so the first one that actually
     * resolves on this device wins.
     */
    private static List<Intent> candidateIntents() {
        List<Intent> candidates = new ArrayList<>();
        String m = manufacturer();

        if (m.contains("xiaomi")) {
            candidates.add(componentIntent("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            candidates.add(componentIntent("com.miui.securitycenter",
                    "com.miui.powercenter.PowerSettings"));
        } else if (m.contains("oppo") || m.contains("oneplus")) {
            candidates.add(componentIntent("com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"));
            candidates.add(componentIntent("com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"));
            candidates.add(componentIntent("com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"));
        } else if (m.contains("vivo")) {
            candidates.add(componentIntent("com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
            candidates.add(componentIntent("com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"));
        } else if (m.contains("huawei") || m.contains("honor")) {
            candidates.add(componentIntent("com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
            candidates.add(componentIntent("com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"));
        } else if (m.contains("samsung")) {
            candidates.add(componentIntent("com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"));
        } else if (m.contains("meizu")) {
            candidates.add(componentIntent("com.meizu.safe",
                    "com.meizu.safe.security.SHOW_APPSEC"));
        } else if (m.contains("letv")) {
            candidates.add(componentIntent("com.letv.android.letvsafe",
                    "com.letv.android.letvsafe.AutobootManageActivity"));
        } else if (m.contains("asus")) {
            candidates.add(componentIntent("com.asus.mobilemanager",
                    "com.asus.mobilemanager.autostart.AutoStartActivity"));
        } else if (m.contains("lenovo")) {
            candidates.add(componentIntent("com.lenovo.security",
                    "com.lenovo.security.purebackground.PureBackgroundActivity"));
        }
        return candidates;
    }

    private static Intent componentIntent(String pkg, String cls) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(pkg, cls));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /**
     * Tries each known candidate screen for this manufacturer in order,
     * falling back to the app's own details settings page (always
     * resolvable) if none of them exist on this device/OS version. Never
     * throws -- ActivityNotFoundException is a real, expected outcome
     * here (OEMs rename/remove these undocumented activities across
     * releases) rather than a bug.
     *
     * @return true if an OEM-specific screen was opened, false if it fell
     *         back to the generic app details screen.
     */
    static boolean openAutostartSettings(Context context) {
        for (Intent candidate : candidateIntents()) {
            try {
                context.startActivity(candidate);
                return true;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // Try the next candidate, or fall through to the generic fallback below.
            }
        }
        Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.getPackageName()));
        fallback.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(fallback);
        return false;
    }
}
