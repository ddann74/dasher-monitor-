package com.drivingefficiency.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.app.PendingIntent;
import android.provider.Settings;
import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class PermissionsActivity extends AppCompatActivity {

    private PyObject engine;
    private TextView permissionStatusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permissions);
        engine = PythonBridge.getEngine(this);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Permissions & Setup");
        }

        permissionStatusText = findViewById(R.id.permissionStatusText);

        Button notifAccessButton = findViewById(R.id.notifAccessButton);
        Button accessibilityButton = findViewById(R.id.accessibilityButton);
        Button overlayPermissionButton = findViewById(R.id.overlayPermissionButton);
        Button batteryOptimizationButton = findViewById(R.id.batteryOptimizationButton);
        Button activityRecognitionButton = findViewById(R.id.activityRecognitionButton);
        Button trustedContactsButton = findViewById(R.id.trustedContactsButton);
        Button cannedRepliesButton = findViewById(R.id.cannedRepliesButton);
        EditText apiKeyInput = findViewById(R.id.apiKeyInput);
        Button saveApiKeyButton = findViewById(R.id.saveApiKeyButton);

        // Pre-filled with whatever key is currently active (runtime-entered
        // takes priority, falling back to local.properties/BuildConfig) --
        // previously the only way to set this was editing local.properties
        // directly, which didn't survive re-extracting the generated
        // project into a new folder.
        apiKeyInput.setText(GoogleApiHelper.getApiKey(this));

        saveApiKeyButton.setOnClickListener(v -> {
            String key = apiKeyInput.getText().toString().trim();
            GoogleApiHelper.setApiKey(this, key);
            Toast.makeText(this, key.isEmpty() ? "API key cleared." : "API key saved.",
                    Toast.LENGTH_SHORT).show();
        });

        // Notification access is required to capture Dasher offers/messages
        notifAccessButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        // Accessibility access is required to read the accepted-offer address
        accessibilityButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        // Overlay permission is required for the arrival-instruction bubble
        // (report: "Zero Interaction While Driving").
        overlayPermissionButton.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });

        // Without this, aggressive OEM battery managers (Samsung, Xiaomi,
        // etc.) can kill the always-on foreground service despite it
        // being a foreground service -- this was a known, previously
        // unaddressed gap.
        batteryOptimizationButton.setOnClickListener(v -> {
            android.os.PowerManager powerManager =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            boolean alreadyIgnoring = powerManager != null
                    && powerManager.isIgnoringBatteryOptimizations(getPackageName());
            if (alreadyIgnoring) {
                Toast.makeText(this, "Already exempt from battery optimization.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });

        // For general-driving auto-start -- lets monitoring start from
        // genuine driving motion alone, without ever needing to open
        // Dasher first. A true runtime-requestable permission (like
        // location), unlike the settings-intent-based ones above.
        activityRecognitionButton.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Toast.makeText(this, "Not needed on this Android version.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                    == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Already granted.", Toast.LENGTH_SHORT).show();
                return;
            }
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, 2002);
        });

        // Moved here from the main screen -- grouped with setup/config
        // screens rather than sitting alongside the always-visible
        // Start/Stop controls.
        trustedContactsButton.setOnClickListener(v ->
                startActivity(new Intent(this, TrustedContactsActivity.class)));
        cannedRepliesButton.setOnClickListener(v ->
                startActivity(new Intent(this, CannedRepliesActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refreshes automatically when you come back from Settings after
        // granting (or not granting) something -- previously there was no
        // way to tell which of the 4 were actually still missing without
        // checking each one yourself.
        updatePermissionStatusText();
        subscribeToDrivingDetectionIfPermitted();
    }

    /**
     * Genuine general-driving auto-start -- registers for Activity
     * Recognition transition updates (in-vehicle detection) if the
     * permission is granted. Safe to call repeatedly (e.g. every
     * onResume): requestActivityTransitionUpdates replaces any existing
     * subscription rather than stacking duplicates, so this doesn't need
     * to track whether it already subscribed this session.
     *
     * HONEST LIMIT: this only confirms the subscription request itself
     * didn't throw -- it cannot confirm Android's motion classifier will
     * actually fire IN_VEHICLE transitions reliably. That can only be
     * confirmed by real driving with this installed, checking the log
     * for DRIVING_DETECTION entries afterward.
     */
    private void subscribeToDrivingDetectionIfPermitted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return; // ACTIVITY_RECOGNITION as a runtime permission doesn't exist before Android 10
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        java.util.List<com.google.android.gms.location.ActivityTransition> transitions = new java.util.ArrayList<>();
        transitions.add(new com.google.android.gms.location.ActivityTransition.Builder()
                .setActivityType(com.google.android.gms.location.DetectedActivity.IN_VEHICLE)
                .setActivityTransition(com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build());
        com.google.android.gms.location.ActivityTransitionRequest request =
                new com.google.android.gms.location.ActivityTransitionRequest(transitions);

        Intent intent = new Intent(this, DrivingDetectionReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 3001, intent, flags);

        com.google.android.gms.location.ActivityRecognition.getClient(this)
                .requestActivityTransitionUpdates(request, pendingIntent)
                .addOnSuccessListener(unused ->
                        logDiagnostic("DRIVING_DETECTION", "Subscribed to Activity Recognition transition updates"))
                .addOnFailureListener(e ->
                        logDiagnostic("ERROR", "Activity Recognition subscription failed: " + e.getMessage()));
    }

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

    /**
     * Real, direct evidence for whether accessibility dropping correlates
     * with a fresh install/update -- queries Android's own record of it,
     * rather than relying on memory. Duplicated in other classes for the
     * same reason.
     */
    private String buildInstallTimingNote() {
        try {
            android.content.pm.PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            long nowMs = System.currentTimeMillis();
            long sinceInstallMin = (nowMs - info.firstInstallTime) / (60 * 1000);
            long sinceUpdateMin = (nowMs - info.lastUpdateTime) / (60 * 1000);
            return "App installed " + sinceInstallMin + " min ago, last updated " + sinceUpdateMin + " min ago.";
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException e) {
            return "Could not read install timing: " + e.getMessage();
        }
    }

    private void updatePermissionStatusText() {
        boolean hasNotificationAccess = isNotificationAccessGranted();
        boolean hasAccessibility = isAccessibilityServiceGranted();
        boolean hasOverlay = OverlayHelper.hasPermission(this);
        boolean hasBatteryExemption = isBatteryExemptionGranted();
        boolean hasActivityRecognition = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                        == PackageManager.PERMISSION_GRANTED;

        android.text.SpannableStringBuilder status = new android.text.SpannableStringBuilder();
        appendPermissionLine(status, hasNotificationAccess, "Notification Access");
        appendPermissionLine(status, hasAccessibility, "Accessibility Access");
        appendPermissionLine(status, hasOverlay, "Overlay Permission");
        appendPermissionLine(status, hasBatteryExemption, "Battery Optimization Exempt");
        appendPermissionLine(status, hasActivityRecognition, "Activity Recognition (general-driving auto-start)");
        permissionStatusText.setText(status);

        // Previously this ONLY updated the on-screen display -- a real,
        // confirmed gap: checking this screen directly (exactly what
        // just confirmed accessibility was off) never got recorded to
        // the persistent log at all. This is often the ONLY moment
        // accessibility gets checked while monitoring itself isn't
        // active (the continuous heartbeats only run during active
        // monitoring), so it's a genuinely important data point that was
        // being silently lost every single time.
        logDiagnostic("PERMISSIONS", "Setup screen checked -- location="
                + (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED)
                + " overlay=" + hasOverlay + " notificationAccess=" + hasNotificationAccess
                + " batteryExempt=" + hasBatteryExemption + " accessibility=" + hasAccessibility
                + ". " + buildInstallTimingNote());

        // Previously only ever discussed conversationally -- never
        // actually built into the app. Only shows for confirmed Oppo
        // devices (the actual device this whole investigation has been
        // about), not speculative guidance for other manufacturers with
        // no evidence they're relevant here. Only shown when
        // accessibility is genuinely off, and only once per visit to
        // this screen (not nagging on every single permission refresh).
        if (!hasAccessibility && "OPPO".equalsIgnoreCase(Build.MANUFACTURER)) {
            showOppoAccessibilityGuidance();
        }
    }

    /**
     * Oppo's "Restricted Settings" security feature can silently block
     * re-enabling accessibility for sideloaded apps, especially after a
     * reinstall -- this walks through the specific, confirmed steps to
     * work around it, rather than the generic "go re-enable it in
     * Settings" guidance that doesn't mention this Oppo-specific gate.
     */
    private void showOppoAccessibilityGuidance() {
        new AlertDialog.Builder(this)
                .setTitle("Oppo Device Detected")
                .setMessage("Accessibility access is off. On Oppo devices, this is often caused by "
                        + "\"Restricted Settings\" silently blocking it, especially after a reinstall.\n\n"
                        + "To fix this:\n"
                        + "1. Settings > Apps > See all apps > Dasher Monitor\n"
                        + "2. Tap the three-dot menu > \"Allow restricted settings\"\n"
                        + "3. Then re-enable accessibility for this app as usual\n\n"
                        + "Also worth checking: Settings > Battery > App battery management > "
                        + "allow background activity for this app.")
                .setPositiveButton("Open App Settings", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .setNegativeButton("Not Now", null)
                .show();
    }

    /**
     * Appends one line, colored green if granted or red if not -- a plain
     * TextView with a single android:textColor can't show different
     * colors per line, so this needs a SpannableStringBuilder with a
     * ForegroundColorSpan applied to just that line's range.
     */
    private void appendPermissionLine(android.text.SpannableStringBuilder builder, boolean granted, String label) {
        int start = builder.length();
        builder.append(granted ? "\u2713 " : "\u2717 ").append(label).append("\n");
        int end = builder.length();
        // Resource lookup (not a hardcoded hex literal) specifically so
        // the values-night variants of these colors -- brighter, for
        // contrast against a dark background -- actually take effect.
        int color = androidx.core.content.ContextCompat.getColor(this,
                granted ? R.color.score_green : R.color.score_red);
        builder.setSpan(new android.text.style.ForegroundColorSpan(color),
                start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private boolean isNotificationAccessGranted() {
        String enabledListeners = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        return enabledListeners != null && enabledListeners.contains(getPackageName());
    }

    private boolean isAccessibilityServiceGranted() {
        String enabledServices = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabledServices != null
                && enabledServices.contains(getPackageName() + "/" + getPackageName() + ".DasherAccessibilityService");
    }

    private boolean isBatteryExemptionGranted() {
        android.os.PowerManager powerManager =
                (android.os.PowerManager) getSystemService(POWER_SERVICE);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName());
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }


}
