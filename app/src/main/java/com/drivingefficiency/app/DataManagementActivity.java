package com.drivingefficiency.app;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.net.Uri;
import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class DataManagementActivity extends AppCompatActivity {

    private PyObject engine;
    private static final int REQUEST_BACKUP_DATABASE_FILE = 4001;
    private static final int REQUEST_RESTORE_DATABASE_FILE = 4002;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_data_management);
        engine = PythonBridge.getEngine(this);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Data Management");
        }

        Button exportCsvButton = findViewById(R.id.exportCsvButton);
        Button exportFullReportButton = findViewById(R.id.exportFullReportButton);
        Button exportFullReportPdfButton = findViewById(R.id.exportFullReportPdfButton);
        Button backupDatabaseButton = findViewById(R.id.backupDatabaseButton);
        Button restoreDatabaseButton = findViewById(R.id.restoreDatabaseButton);
        Button resetAllDataButton = findViewById(R.id.resetAllDataButton);

        exportCsvButton.setOnClickListener(v -> exportTripsCsv());
        exportFullReportButton.setOnClickListener(v -> exportFullReport());
        exportFullReportPdfButton.setOnClickListener(v -> exportFullReportAsPdf());

        // Uses the SYSTEM file picker, same reasoning as trusted-contacts
        // save/load: internal app storage gets wiped on reinstall, which
        // would defeat the entire point of a backup. This covers the
        // WHOLE database -- every table, not just trips -- since months
        // of learned data (restaurant wait times, deadhead accuracy,
        // personal calibration, everything) previously lived ONLY in this
        // app's local storage with no way to recover it if the phone was
        // lost, reset, or the app's data cleared.
        backupDatabaseButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, "dasher_monitor_backup.db");
            startActivityForResult(intent, REQUEST_BACKUP_DATABASE_FILE);
        });

        // Destructive -- confirm before replacing everything currently
        // recorded with whatever's in the chosen backup file.
        restoreDatabaseButton.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Restore Database")
                    .setMessage("This replaces ALL current data -- every trip, learned average, "
                            + "and setting -- with whatever's in the backup file you choose. "
                            + "This cannot be undone. The app will need a full restart "
                            + "afterward. Continue?")
                    .setPositiveButton("Choose Backup File", (dialog, which) -> {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("application/octet-stream");
                        startActivityForResult(intent, REQUEST_RESTORE_DATABASE_FILE);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        // Destructive -- confirm before wiping everything. Previously
        // there was no way to start fresh short of manually clearing app
        // storage from Android's own Settings screen.
        resetAllDataButton.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Reset All Data")
                    .setMessage("This permanently deletes every trip, stop, safety event, "
                            + "customer message, learned restaurant wait time, learned "
                            + "deadhead/delivery speed data, and trusted contact. This "
                            + "cannot be undone. Continue?")
                    .setPositiveButton("Reset Everything", (dialog, which) -> {
                        logDiagnostic("BUTTON", "Reset All Data confirmed");
                        try {
                            engine.callAttr("reset_all_data");
                            Toast.makeText(this, "All data reset.", Toast.LENGTH_SHORT).show();
                        } catch (RuntimeException e) { // covers PyException too
                            Toast.makeText(this, "Reset failed: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        android.net.Uri uri = data.getData();
        if (requestCode == REQUEST_BACKUP_DATABASE_FILE) {
            backupDatabaseToUri(uri);
        } else if (requestCode == REQUEST_RESTORE_DATABASE_FILE) {
            restoreDatabaseFromUri(uri);
        }
    }

    /**
     * Writes a safe, consistent snapshot of the ENTIRE database (see
     * backup_database_to's use of SQLite's own online-backup API, not a
     * raw file copy) to a temp file, then streams that temp file to
     * wherever the user chose via the system file picker.
     */
    private void backupDatabaseToUri(android.net.Uri uri) {
        try {
            java.io.File tempFile = new java.io.File(getCacheDir(), "backup_temp.db");
            engine.callAttr("backup_database_to", tempFile.getAbsolutePath());
            try (java.io.InputStream in = new java.io.FileInputStream(tempFile);
                 java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out != null) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
            }
            tempFile.delete();
            logDiagnostic("BACKUP", "Full database backed up successfully");
            Toast.makeText(this, "Backup saved successfully.", Toast.LENGTH_LONG).show();
        } catch (RuntimeException | java.io.IOException e) {
            logDiagnostic("ERROR", "Database backup exception: " + android.util.Log.getStackTraceString(e));
            Toast.makeText(this, "Backup failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Reads the chosen backup file and replaces the live database file
     * with it -- but ONLY after cleanly closing the current connection
     * first (see close_database_for_restore's reasoning: replacing the
     * file underneath a still-open connection risks corruption). Requires
     * a full app restart afterward for the restored data to actually be
     * picked up, rather than trying to hot-swap a live connection.
     */
    private void restoreDatabaseFromUri(android.net.Uri uri) {
        try {
            String dbPath = engine.callAttr("get_database_file_path").toString();
            engine.callAttr("close_database_for_restore");
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 java.io.OutputStream out = new java.io.FileOutputStream(dbPath)) {
                if (in == null) {
                    Toast.makeText(this, "Could not open that backup file.", Toast.LENGTH_LONG).show();
                    return;
                }
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            new AlertDialog.Builder(this)
                    .setTitle("Restore Complete")
                    .setMessage("The database has been replaced. Please FULLY CLOSE this app now "
                            + "(swipe it away from recent apps) and reopen it for the restored "
                            + "data to take effect.")
                    .setPositiveButton("OK", null)
                    .setCancelable(false)
                    .show();
        } catch (RuntimeException | java.io.IOException e) {
            Toast.makeText(this, "Restore failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    /**
         * Writes the CSV content (built in Python from real tracked fields --
         * see export_trips_csv()'s honest scope note on what columns are and
         * aren't included) to a file in the cache directory, then shares it
         * via a standard Android share sheet (email, Google Sheets, Drive,
         * etc.) using FileProvider so the app's storage isn't broadly exposed.
         */
        private void exportTripsCsv() {
            try {
                String csv = engine.callAttr("export_trips_csv").toString();
                java.io.File cacheDir = getCacheDir();
                java.io.File file = new java.io.File(cacheDir, "dasher_monitor_history.csv");
                java.io.FileWriter writer = new java.io.FileWriter(file);
                writer.write(csv);
                writer.close();

                android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", file);

                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/csv");
                shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(shareIntent, "Export Trip History"));
                logDiagnostic("BUTTON", "CSV export shared");
            } catch (RuntimeException | java.io.IOException e) { // RuntimeException covers PyException too
                Toast.makeText(this, "Could not export CSV: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        /**
         * Exports EVERY metric this app records, not just trips -- safety
         * events, delays, messages, restaurant wait history, distance
         * accuracy, delivery speed history, and accept/decline/timeout
         * outcomes. Previously none of these had any export path at all,
         * only one-screen-at-a-time in-app viewing.
         */
        private void exportFullReport() {
            try {
                String report = engine.callAttr("export_full_report").toString();
                java.io.File cacheDir = getCacheDir();
                java.io.File file = new java.io.File(cacheDir, "dasher_monitor_full_report.txt");
                java.io.FileWriter writer = new java.io.FileWriter(file);
                writer.write(report);
                writer.close();

                android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", file);

                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(shareIntent, "Export Full Report"));
                logDiagnostic("BUTTON", "Full report exported and shared");
            } catch (RuntimeException | java.io.IOException e) { // RuntimeException covers PyException too
                Toast.makeText(this, "Could not export full report: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        /**
         * Same full report content, rendered as an actual PDF instead of
         * plain text -- uses Android's built-in PdfDocument (no external
         * library needed), drawing the report line by line onto pages
         * with proper pagination (a real report can be many pages long;
         * this creates a new page automatically rather than clipping or
         * cramming everything onto one page).
         */
        private void exportFullReportAsPdf() {
            try {
                String report = engine.callAttr("export_full_report").toString();

                android.graphics.pdf.PdfDocument document = new android.graphics.pdf.PdfDocument();
                android.graphics.Paint paint = new android.graphics.Paint();
                paint.setTextSize(10f);
                paint.setTypeface(android.graphics.Typeface.MONOSPACE);

                int pageWidth = 595;  // A4 at 72dpi
                int pageHeight = 842;
                int marginX = 32;
                int marginTop = 40;
                float lineHeight = paint.getTextSize() + 4f;
                int maxLinesPerPage = (int) ((pageHeight - marginTop * 2) / lineHeight);

                String[] allLines = report.split("\n", -1);
                int pageNumber = 1;
                int lineIndex = 0;
                while (lineIndex < allLines.length) {
                    android.graphics.pdf.PdfDocument.PageInfo pageInfo =
                            new android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create();
                    android.graphics.pdf.PdfDocument.Page page = document.startPage(pageInfo);
                    android.graphics.Canvas canvas = page.getCanvas();

                    float y = marginTop;
                    int linesOnThisPage = 0;
                    while (lineIndex < allLines.length && linesOnThisPage < maxLinesPerPage) {
                        // Long lines (e.g. a wide CSV row) are truncated per page-width
                        // rather than wrapped -- keeps this simple; the full, untruncated
                        // text is still available via the plain-text export for anything
                        // that needs every character.
                        String line = allLines[lineIndex];
                        canvas.drawText(line, marginX, y, paint);
                        y += lineHeight;
                        lineIndex++;
                        linesOnThisPage++;
                    }

                    document.finishPage(page);
                    pageNumber++;
                }

                java.io.File cacheDir = getCacheDir();
                java.io.File file = new java.io.File(cacheDir, "dasher_monitor_full_report.pdf");
                java.io.FileOutputStream out = new java.io.FileOutputStream(file);
                document.writeTo(out);
                out.close();
                document.close();

                android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", file);

                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("application/pdf");
                shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(shareIntent, "Export Full Report (PDF)"));
                logDiagnostic("BUTTON", "Full report exported as PDF (" + (pageNumber - 1) + " pages)");
            } catch (RuntimeException | java.io.IOException e) {
                Toast.makeText(this, "Could not export PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

    /**
         * Wrapper so a logging call itself can never crash the app (same
         * defensive pattern as the background services). Falls back to
         * FallbackLogger if the engine isn't ready yet.
         */
        private void logDiagnostic(String category, String message) {
            try {
                if (engine != null) {
                    engine.callAttr("log_diagnostic", category, message);
                } else {
                    FallbackLogger.log(this, category, message);
                }
            } catch (RuntimeException e) { // RuntimeException alone also catches PyException (Chaquopy PyException extends RuntimeException -- Java disallows both in one multi-catch since one is a subclass of the other)
                FallbackLogger.log(this, category, message);
            }
        }
}
