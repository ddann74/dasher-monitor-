package com.drivingefficiency.app;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Lightweight, Android-native logging that works even before the Python
 * engine is initialized -- a crash or hang in the very first moment of
 * startup (before PythonBridge.getEngine() has returned) was previously
 * invisible, since every logDiagnostic() call across the app checks
 * "if (engine != null)" and silently no-ops otherwise. This writes
 * directly to a plain text file, bypassing Python and SQLite entirely,
 * so it works in that narrow but real gap.
 *
 * Not meant to replace the main diagnostic log (which is richer, and
 * viewable in-app) -- this is purely a fallback for the one window where
 * that system genuinely can't be used yet.
 */
public final class FallbackLogger {

    private static final String FILE_NAME = "fallback_log.txt";
    private static final long MAX_FILE_SIZE_BYTES = 100 * 1024; // 100KB cap

    private FallbackLogger() {}

    public static void log(Context context, String category, String message) {
        try {
            File file = new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
            if (file.exists() && file.length() > MAX_FILE_SIZE_BYTES) {
                file.delete(); // simple cap -- this is a rarely-used fallback, not the main log
            }
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date());
            FileWriter writer = new FileWriter(file, true);
            writer.write("[" + timestamp + "] " + category + ": " + message + "\n");
            writer.close();
        } catch (IOException e) {
            // This is already the fallback -- nowhere left to fall back to.
        }
    }

    public static String read(Context context) {
        File file = new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
        if (!file.exists()) {
            return "";
        }
        StringBuilder content = new StringBuilder();
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            reader.close();
        } catch (IOException e) {
            // Best effort -- return whatever was read so far.
        }
        return content.toString();
    }
}
