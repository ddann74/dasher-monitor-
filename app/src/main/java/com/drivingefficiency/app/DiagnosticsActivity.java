package com.drivingefficiency.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class DiagnosticsActivity extends AppCompatActivity {

    private PyObject engine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);
        engine = PythonBridge.getEngine(this);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Diagnostics");
        }

        Button viewDiagnosticLogButton = findViewById(R.id.viewDiagnosticLogButton);
        Button viewDiagnosticArchivesButton = findViewById(R.id.viewDiagnosticArchivesButton);
        Button copyDiagnosticLogButton = findViewById(R.id.copyDiagnosticLogButton);
        Button shareDiagnosticLogButton = findViewById(R.id.shareDiagnosticLogButton);
        Button exportFullHistoryButton = findViewById(R.id.exportFullHistoryButton);
        Button testApiConnectionButton = findViewById(R.id.testApiConnectionButton);
        TextView apiStatusText = findViewById(R.id.apiStatusText);

        // Visible immediately, no need to wait for a real offer to trigger
        // a geocode/traffic attempt and check the log afterward -- this
        // answers "is my Google Maps API key even configured" right away.
        // Covers geocoding + live traffic specifically -- weather uses
        // Open-Meteo separately, which needs no API key at all.
        boolean hasKey = GoogleApiHelper.hasApiKey(this);
        apiStatusText.setText(hasKey
                ? "Google Maps API key (geocoding + traffic): configured"
                : "Google Maps API key (geocoding + traffic): not configured (see README)");

        viewDiagnosticLogButton.setOnClickListener(v -> showDiagnosticLog());
        viewDiagnosticArchivesButton.setOnClickListener(v -> showDiagnosticArchives());
        copyDiagnosticLogButton.setOnClickListener(v -> copyDiagnosticLog());
        shareDiagnosticLogButton.setOnClickListener(v -> shareDiagnosticLog());
        exportFullHistoryButton.setOnClickListener(v -> exportFullHistory());
        testApiConnectionButton.setOnClickListener(v -> testApiConnection());
    }

    /**
     * IMPORTANT DISTINCTION: "configured" above only means a key string is
     * present -- it does NOT prove the key is valid, or that the
     * Geocoding/Distance Matrix APIs are actually enabled and working.
     * This makes a REAL live call and shows Google's actual response, so
     * "does it work" is answered with real evidence, not just "is
     * something typed in the box." Tests Geocoding first, then (only if
     * that succeeds) Distance Matrix using the resolved coordinates --
     * these are separately-enabled APIs on your Google Cloud project, so
     * one can work while the other doesn't.
     */
    private void testApiConnection() {
        if (!GoogleApiHelper.hasApiKey(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Test Google Maps Connection")
                    .setMessage("No API key configured yet -- add one in Permissions & Setup first.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        Toast.makeText(this, "Testing... (needs a real network call, may take a few seconds)",
                Toast.LENGTH_SHORT).show();

        String testAddress = "Sydney Opera House, Sydney NSW";
        GoogleApiHelper.geocodeAddress(this, testAddress, new GoogleApiHelper.GeocodeCallback() {
            @Override
            public void onResult(double lat, double lon) {
                // Geocoding proven working -- now test Distance Matrix too,
                // since it's a separate API that could be disabled
                // independently even with Geocoding working fine.
                GoogleApiHelper.getTrafficDelayRatio(DiagnosticsActivity.this, lat, lon, lat, lon,
                        new GoogleApiHelper.TrafficCallback() {
                            @Override
                            public void onResult(double trafficDelayRatio, int durationInTrafficSeconds,
                                                  int typicalDurationSeconds) {
                                showTestResult(true, true,
                                        String.format("Geocoding: SUCCESS (resolved to %.4f, %.4f)\n"
                                                        + "Distance Matrix: SUCCESS (real live traffic data received)\n\n"
                                                        + "Both APIs are genuinely working.",
                                                lat, lon));
                            }

                            @Override
                            public void onError(String message) {
                                showTestResult(true, false,
                                        String.format("Geocoding: SUCCESS (resolved to %.4f, %.4f)\n"
                                                        + "Distance Matrix: FAILED -- %s\n\n"
                                                        + "Geocoding works, but check that the Distance Matrix "
                                                        + "API specifically is enabled on your Google Cloud project.",
                                                lat, lon, message));
                            }
                        });
            }

            @Override
            public void onError(String message) {
                showTestResult(false, false,
                        "Geocoding: FAILED -- " + message + "\n\n"
                                + "This is Google's actual response, not a guess -- check that the key is "
                                + "correct, the Geocoding API is enabled, and billing is attached on your "
                                + "Google Cloud project.");
            }
        });
    }

    private void showTestResult(boolean geocodeOk, boolean trafficOk, String message) {
        logDiagnostic("API_TEST", (geocodeOk && trafficOk ? "PASSED" : "FAILED") + ": " + message.replace("\n", " "));
        new AlertDialog.Builder(this)
                .setTitle("Test Google Maps Connection")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    /**
         * Shows the most recent diagnostic log entries (capped at 200 for
         * display) -- newest first, with a relative "how long ago" timestamp.
         * This is what makes debugging an unexpected stop actually possible
         * out in the field: previously, caught exceptions were silently
         * swallowed with nothing but a code comment, and Android's own system
         * logs (logcat) are ephemeral and need a computer connected via ADB
         * to view at all.
         */
        private String buildDiagnosticLogText(JSONArray entries) throws JSONException {
            long nowSeconds = System.currentTimeMillis() / 1000L;
            java.text.SimpleDateFormat timeFormat =
                    new java.text.SimpleDateFormat("h:mm:ss a", java.util.Locale.getDefault());
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                double ts = entry.optDouble("timestamp", 0);
                long agoSeconds = nowSeconds - (long) ts;
                String agoLabel = agoSeconds < 60 ? agoSeconds + "s ago"
                        : agoSeconds < 3600 ? (agoSeconds / 60) + "m ago"
                        : (agoSeconds / 3600) + "h ago";
                // Real clock time alongside the relative duration --
                // "5m ago" alone loses context once you're looking back
                // at a log from a while ago, or trying to line events up
                // against something else (like an adb logcat timestamp).
                String clockTime = timeFormat.format(new java.util.Date((long) ts * 1000));
                body.append("[").append(clockTime).append(", ").append(agoLabel).append("] ")
                        .append(entry.optString("category", ""))
                        .append(": ").append(entry.optString("message", ""))
                        .append("\n\n");
            }
            return body.toString();
        }

        private void showDiagnosticLog() {
            try {
                JSONObject log = new JSONObject(engine.callAttr("get_diagnostic_log").toString());
                JSONArray entries = log.optJSONArray("entries");
                if (entries == null || entries.length() == 0) {
                    new AlertDialog.Builder(this)
                            .setTitle("Diagnostic Log")
                            .setMessage("No log entries yet.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }

                String body = buildDiagnosticLogText(entries);

                new AlertDialog.Builder(this)
                        .setTitle("Diagnostic Log (newest first)")
                        .setMessage(body)
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Clear Log", (dialog, which) -> {
                            try {
                                engine.callAttr("clear_diagnostic_log");
                                Toast.makeText(this, "Log cleared.", Toast.LENGTH_SHORT).show();
                            } catch (RuntimeException e) { // covers PyException too
                                Toast.makeText(this, "Could not clear log: " + e.getMessage(),
                                        Toast.LENGTH_LONG).show();
                            }
                        })
                        .show();
            } catch (JSONException | PyException e) {
                Toast.makeText(this, "Could not load diagnostic log: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        }

        /**
         * Copies the full current diagnostic log to the clipboard --
         * useful for pasting straight into a message/email when reporting
         * a problem, without needing to first go through the share sheet.
         */
        private void copyDiagnosticLog() {
            try {
                JSONObject log = new JSONObject(engine.callAttr("get_diagnostic_log").toString());
                JSONArray entries = log.optJSONArray("entries");
                if (entries == null || entries.length() == 0) {
                    Toast.makeText(this, "No log entries yet.", Toast.LENGTH_SHORT).show();
                    return;
                }
                String body = buildDiagnosticLogText(entries);
                // Previously written but never actually surfaced anywhere
                // -- FallbackLogger.read() existed but nothing ever called
                // it. Appended here so a real crash (see
                // DasherMonitorApplication's handler) actually becomes
                // visible in the exact log you already copy and share.
                String fallbackContent = FallbackLogger.read(this);
                if (!fallbackContent.isEmpty()) {
                    body += "\n\n--- FALLBACK LOG (crashes and pre-engine events) ---\n" + fallbackContent;
                }
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Diagnostic Log", body));
                    Toast.makeText(this, "Log copied to clipboard.", Toast.LENGTH_SHORT).show();
                }
            } catch (JSONException | PyException e) {
                Toast.makeText(this, "Could not copy log: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        /**
         * Shares the full current diagnostic log as a real file via the
         * standard Android share sheet (email, messages, wherever) --
         * same FileProvider pattern already used for CSV export, so the
         * app's storage isn't broadly exposed. Meant for handing the log
         * over for problem-solving without needing to manually copy/paste
         * a long dialog's contents.
         */
        private void shareDiagnosticLog() {
            try {
                JSONObject log = new JSONObject(engine.callAttr("get_diagnostic_log").toString());
                JSONArray entries = log.optJSONArray("entries");
                if (entries == null || entries.length() == 0) {
                    Toast.makeText(this, "No log entries yet.", Toast.LENGTH_SHORT).show();
                    return;
                }
                String body = buildDiagnosticLogText(entries);
                // Previously missing entirely from Share Log (only Copy
                // Log had this) -- a real inconsistency where a genuine
                // crash's details could be silently absent from what
                // actually gets sent, depending only on which button was
                // tapped.
                String fallbackContent = FallbackLogger.read(this);
                if (!fallbackContent.isEmpty()) {
                    body += "\n\n--- FALLBACK LOG (crashes and pre-engine events) ---\n" + fallbackContent;
                }

                // Saved to the app's external files directory rather than
                // internal cache -- genuinely browsable via a normal
                // Files app and not subject to Android silently clearing
                // it at any time, unlike the previous internal-cache
                // location which was reachable only in the single moment
                // the share sheet was open.
                java.io.File externalDir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
                java.io.File dir = externalDir != null ? externalDir : getCacheDir();
                java.io.File file = new java.io.File(dir, "dasher_monitor_diagnostic_log.txt");
                java.io.FileWriter writer = new java.io.FileWriter(file);
                writer.write(body);
                writer.close();

                android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", file);

                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(shareIntent, "Share Diagnostic Log"));
            } catch (JSONException | PyException | java.io.IOException e) {
                Toast.makeText(this, "Could not share log: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        /**
         * Genuine full history -- previously Copy/Share were hard-capped
         * at 200 recent entries. Combines every archived file with the
         * complete live log into one export, saved the same way as the
         * fixed Share Log (external files dir, genuinely browsable/
         * persistent, not internal cache).
         */
        private void exportFullHistory() {
            try {
                JSONObject result = new JSONObject(engine.callAttr("export_full_diagnostic_history").toString());
                if (!result.optBoolean("found", false)) {
                    Toast.makeText(this, "No diagnostic history yet.", Toast.LENGTH_SHORT).show();
                    return;
                }
                String body = result.optString("content", "");
                String fallbackContent = FallbackLogger.read(this);
                if (!fallbackContent.isEmpty()) {
                    body += "\n\n--- FALLBACK LOG (crashes and pre-engine events) ---\n" + fallbackContent;
                }

                java.io.File externalDir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
                java.io.File dir = externalDir != null ? externalDir : getCacheDir();
                java.io.File file = new java.io.File(dir, "dasher_monitor_full_history.txt");
                java.io.FileWriter writer = new java.io.FileWriter(file);
                writer.write(body);
                writer.close();

                android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", file);

                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(shareIntent, "Share Full Diagnostic History"));
            } catch (JSONException | PyException | java.io.IOException e) {
                Toast.makeText(this, "Could not export full history: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

    /**
         * Lists archived diagnostic log files (newest first) -- created
         * automatically whenever the live log fills up (currently every 500
         * entries) and gets rotated to a plain-text file instead of having
         * its oldest entries deleted. Tapping one shows its full contents.
         */
        private void showDiagnosticArchives() {
            try {
                JSONObject result = new JSONObject(engine.callAttr("list_diagnostic_archives").toString());
                JSONArray files = result.optJSONArray("files");
                if (files == null || files.length() == 0) {
                    new AlertDialog.Builder(this)
                            .setTitle("Diagnostic Log Archives")
                            .setMessage("No archived logs yet -- these are created automatically "
                                    + "once the live log fills up (currently every 500 entries).")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }

                String[] filenames = new String[files.length()];
                for (int i = 0; i < files.length(); i++) {
                    filenames[i] = files.optString(i, "");
                }

                new AlertDialog.Builder(this)
                        .setTitle("Diagnostic Log Archives (tap to view)")
                        .setItems(filenames, (dialog, which) -> showDiagnosticArchiveContent(filenames[which]))
                        .setNegativeButton("Close", null)
                        .show();
            } catch (JSONException | PyException e) {
                Toast.makeText(this, "Could not load archive list: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        }

    private void showDiagnosticArchiveContent(String filename) {
            try {
                JSONObject result = new JSONObject(engine.callAttr("read_diagnostic_archive", filename).toString());
                if (!result.optBoolean("found", false)) {
                    Toast.makeText(this, "Could not read that archive.", Toast.LENGTH_SHORT).show();
                    return;
                }
                // Fixed a real, confirmed bug: this previously used a
                // plain AlertDialog.setMessage() for up to 500 full log
                // entries' worth of text -- well beyond what a simple
                // dialog message can properly render, very likely
                // explaining reported archived logs appearing blank or
                // unreadable. Matches the same scrollable-view pattern
                // already proven working elsewhere in this app (e.g. the
                // feedback dialog).
                TextView contentView = new TextView(this);
                contentView.setText(result.optString("content", ""));
                contentView.setTextIsSelectable(true);
                int pad = (int) (16 * getResources().getDisplayMetrics().density);
                contentView.setPadding(pad, pad, pad, pad);

                android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
                scrollView.addView(contentView);

                new AlertDialog.Builder(this)
                        .setTitle(filename)
                        .setView(scrollView)
                        .setPositiveButton("OK", null)
                        .show();
            } catch (JSONException | PyException e) {
                Toast.makeText(this, "Could not read archive: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        /**
         * Wrapper so a logging call itself can never crash the app (same
         * defensive pattern as every other Activity/Service). This class
         * never needed its own copy before -- none of its earlier methods
         * called logDiagnostic -- until showTestResult (Test Google Maps
         * Connection) started calling it, exposing the gap as a real
         * compile error.
         */
        private void logDiagnostic(String category, String message) {
            try {
                if (engine != null) {
                    engine.callAttr("log_diagnostic", category, message);
                } else {
                    FallbackLogger.log(this, category, message);
                }
            } catch (RuntimeException e) { // RuntimeException alone also catches PyException
                FallbackLogger.log(this, category, message);
            }
        }
}
