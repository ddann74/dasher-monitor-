package com.drivingefficiency.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;

/**
 * Logs device reboots and app updates to the diagnostic log -- directly
 * relevant to OEM battery-killer investigation: some phones (including
 * some Oppo/OnePlus/Realme ColorOS/OxygenOS devices) silently reset
 * battery-optimization exemptions after a reboot or system update.
 * A logged "device just rebooted" or "app was just updated" entry lets
 * you correlate "did monitoring stop right after this" instead of
 * guessing from memory.
 */
public class BootAndUpdateReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        String message = Intent.ACTION_BOOT_COMPLETED.equals(action)
                ? "Device rebooted"
                : Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                    ? "App was updated/reinstalled"
                    : "Unknown broadcast: " + action;

        try {
            PyObject engine = PythonBridge.getEngine(context);
            engine.callAttr("log_diagnostic", "SYSTEM", message);
        } catch (RuntimeException e) { // RuntimeException alone also catches PyException (Chaquopy PyException extends RuntimeException -- Java disallows both in one multi-catch since one is a subclass of the other)
            FallbackLogger.log(context, "SYSTEM", message + " (engine unavailable: "
                    + android.util.Log.getStackTraceString(e) + ")");
        }
    }
}
