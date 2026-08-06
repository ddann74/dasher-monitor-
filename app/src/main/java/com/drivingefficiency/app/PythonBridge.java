package com.drivingefficiency.app;

import android.content.Context;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

/**
 * Thin singleton wrapper around the Python "drive_monitor" module so every
 * Android component (service, activity) shares one engine instance.
 */
public final class PythonBridge {

    private static PyObject engine;

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
        }
        return engine;
    }
}
