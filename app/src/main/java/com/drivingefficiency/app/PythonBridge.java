package com.drivingefficiency.app;

import android.content.Context;
import android.content.SharedPreferences;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

/**
 * Thin singleton wrapper around the Python "drive_monitor" module so every
 * Android component (service, activity) shares one engine instance.
 */
public final class PythonBridge {

    private static PyObject engine;

    // "Force Dasher Mode" manual override (docs/manual_dasher_override) --
    // confirmed via a real diagnostic log that DasherAccessibilityService's
    // automatic foreground detection never once reported Dasher active
    // across three real dashing sessions, even while offers were actively
    // being processed. The Python engine's in-memory state doesn't survive
    // a process death, so SharedPreferences here is the durable source of
    // truth -- reapplied to the engine every time it's (re)created, the
    // same pattern MonitoringWatchdogReceiver uses for its own durable flag.
    private static final String PREFS_NAME = "manual_dasher_override_prefs";
    private static final String KEY_MANUAL_DASHER_OVERRIDE = "manual_dasher_override";

    private PythonBridge() {}

    public static synchronized PyObject getEngine(Context context) {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(context.getApplicationContext()));
        }
        if (engine == null) {
            Python py = Python.getInstance();
            PyObject module = py.getModule("drive_monitor");
            engine = module.callAttr("get_engine",
                    context.getApplicationContext().getFilesDir().getAbsolutePath());
            engine.callAttr("set_manual_dasher_override", getManualDasherOverride(context));
        }
        return engine;
    }

    public static boolean getManualDasherOverride(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_MANUAL_DASHER_OVERRIDE, false);
    }

    public static synchronized void setManualDasherOverride(Context context, boolean enabled) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_MANUAL_DASHER_OVERRIDE, enabled).apply();
        if (engine != null) {
            engine.callAttr("set_manual_dasher_override", enabled);
        }
    }
}
