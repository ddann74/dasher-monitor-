package com.drivingefficiency.app;

import android.app.Application;

/**
 * Confirmed real gap: no crash handler existed anywhere in the app.
 * Every diagnostic log investigated this whole project could only ever
 * show the SYMPTOM of a process dying (silence, then a fresh
 * onCreate()) -- never whether the actual cause was a genuine uncaught
 * Java exception, as opposed to the OS killing the process for memory
 * or battery-management reasons, which this can't see (only a real
 * adb logcat capture can see an OS-level kill decision).
 *
 * Set as early as possible (Application.onCreate() runs before any
 * Activity or Service), so every thread in the process is covered from
 * the earliest possible moment, not just the ones started later.
 */
public class DasherMonitorApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                // Deliberately does NOT go through the normal
                // log_diagnostic (Python/SQLite) path -- if the crash
                // happened WITHIN Python itself, or left the database in
                // a bad state, that path could itself fail or hang right
                // when it matters most. FallbackLogger writes directly to
                // a plain text file, bypassing both entirely, so this is
                // the one logging path that should still work even when
                // everything else might not.
                String stackTrace = android.util.Log.getStackTraceString(throwable);
                FallbackLogger.log(getApplicationContext(), "CRASH",
                        "Uncaught exception on thread \"" + thread.getName() + "\": " + stackTrace);
            } catch (RuntimeException loggingFailure) {
                // Even the fallback failed -- nothing further to do here,
                // and definitely not worth blocking the crash over.
            }
            // Deliberately re-throws to the previous handler (Android's
            // own default) rather than swallowing the crash -- the goal
            // is to log it before dying, not to try to keep running in
            // a genuinely broken state, which could behave far less
            // predictably than just letting the crash proceed normally.
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable);
            } else {
                System.exit(1);
            }
        });
    }
}
