package com.drivingefficiency.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Screen recording during a trip - docs/screen_recording/PRD.md. Wraps
 * Android's official MediaProjection API, the same mechanism third-party
 * screen recorders use.
 *
 * HONEST LIMITS, not implementation shortcuts (see PRD ss1.1/ss1.3):
 * - The consent grant (obtained via PermissionsActivity, see
 *   setPendingConsent) is held ONLY in memory, deliberately never
 *   persisted -- Android does not allow a MediaProjection grant to be
 *   silently reused after the process that received it dies. A restart
 *   (the exact thing docs/watchdog_reliability/PRD.md exists to recover
 *   from) invalidates it; the driver has to grant it again from Setup.
 *   TripForegroundService's caller is responsible for handling that
 *   visibly (see its own startTracking() comment), not this class.
 * - UNCONFIRMED on a real device: whether a single granted consent can be
 *   reused for a SECOND trip within the same still-alive process (calling
 *   getMediaProjection again with the same result data), or whether
 *   Android requires a fresh per-trip consent tap even without a process
 *   restart. No emulator/device is available in the environment this was
 *   written in to verify either way -- start() below assumes reuse works
 *   and reports failure honestly (returns false) if it doesn't, rather
 *   than crashing.
 * - Captures the ENTIRE device screen, not just this app -- there is no
 *   API to scope it narrower. See PRD ss1.3.
 */
class ScreenRecordingController {

    private static final String PREFS_NAME = "screen_recording_prefs";
    private static final String KEY_ENABLED = "enabled";
    private static final String RECORDINGS_DIR_NAME = "ScreenRecordings";

    // In-memory only, deliberately never persisted -- see class doc. Set by
    // PermissionsActivity's consent-result callback, read by
    // TripForegroundService when a trip starts. A process restart clears
    // this back to null/0, which is the CORRECT behavior (matches the real
    // grant's own lifetime), not a bug to work around.
    private static int pendingResultCode = 0;
    private static Intent pendingResultData = null;

    static void setPendingConsent(int resultCode, Intent data) {
        pendingResultCode = resultCode;
        pendingResultData = data;
    }

    static boolean hasPendingConsent() {
        return pendingResultData != null;
    }

    static void clearPendingConsent() {
        pendingResultCode = 0;
        pendingResultData = null;
    }

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /** Private app storage, not a shared/public gallery -- same reasoning
      * as tiktok-feed-filter's AudioExtractor (a sibling repo this
      * session) for privacy-sensitive on-device media: visible to a file
      * manager, cleared on uninstall, never auto-shared anywhere. */
    static File recordingsDir(Context context) {
        File dir = new File(context.getExternalFilesDir(null), RECORDINGS_DIR_NAME);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    static int recordingsCount(Context context) {
        File[] files = recordingsDir(context).listFiles();
        return files == null ? 0 : files.length;
    }

    static long recordingsTotalSizeBytes(Context context) {
        File[] files = recordingsDir(context).listFiles();
        if (files == null) {
            return 0;
        }
        long total = 0;
        for (File f : files) {
            total += f.length();
        }
        return total;
    }

    static void deleteAllRecordings(Context context) {
        File[] files = recordingsDir(context).listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            f.delete();
        }
    }

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder mediaRecorder;
    private File currentFile;

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            // Android itself can revoke the grant at any time (e.g. the
            // driver taps the persistent "screen being recorded" system
            // notification's own Stop action) -- this is the only
            // reliable signal that happened, so clean up rather than
            // leaving a half-torn-down recorder/display around.
            releaseInternal();
        }
    };

    /**
     * Starts recording for the current trip. Returns false (and does NOT
     * start anything) if the toggle is off, no consent is currently held
     * (a process restart clears it -- see class doc), or setup throws for
     * any other reason -- callers must treat false as "not recording,"
     * never assume success just because this was called.
     */
    boolean start(Service service) {
        if (!isEnabled(service) || !hasPendingConsent()) {
            return false;
        }
        try {
            MediaProjectionManager manager = (MediaProjectionManager)
                    service.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (manager == null) {
                return false;
            }
            mediaProjection = manager.getMediaProjection(pendingResultCode, pendingResultData);
            if (mediaProjection == null) {
                return false;
            }
            // Required on Android 10+ before createVirtualDisplay, or it
            // throws IllegalStateException -- must be registered before
            // the virtual display is created, not after.
            mediaProjection.registerCallback(projectionCallback, null);

            WindowManager windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            int width = metrics.widthPixels;
            int height = metrics.heightPixels;
            int density = metrics.densityDpi;

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            currentFile = new File(recordingsDir(service), "trip_" + timestamp + ".mp4");

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setVideoSize(width, height);
            mediaRecorder.setVideoFrameRate(30);
            mediaRecorder.setVideoEncodingBitRate(8 * 1000 * 1000);
            mediaRecorder.setOutputFile(currentFile.getAbsolutePath());
            mediaRecorder.prepare();

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "DasherMonitorScreenRecording", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mediaRecorder.getSurface(), null, null);

            mediaRecorder.start();
            return true;
        } catch (Exception e) { // MediaRecorder/MediaProjection setup has many real, non-exotic
            // failure modes (codec unavailable, disk full, a device that
            // doesn't support this combination of size/format) -- none of
            // them should crash the always-on foreground service.
            android.util.Log.e("ScreenRecordingController", "start() failed", e);
            releaseInternal();
            return false;
        }
    }

    /** Safe to call even if start() never succeeded -- releaseInternal()
      * itself null-checks everything. */
    void stop() {
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
            }
        } catch (RuntimeException e) {
            // MediaRecorder.stop() can throw if start() never actually got
            // far enough to record any real data (e.g. stopped within the
            // same instant it started) -- the output file may be
            // invalid/empty in that case, not something to crash the
            // foreground service over.
        }
        releaseInternal();
    }

    private void releaseInternal() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.release();
            } catch (RuntimeException ignored) {
            }
            mediaRecorder = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (mediaProjection != null) {
            try {
                mediaProjection.unregisterCallback(projectionCallback);
            } catch (RuntimeException ignored) {
            }
            mediaProjection.stop();
            mediaProjection = null;
        }
    }

    boolean isRecording() {
        return mediaRecorder != null;
    }

    File currentFile() {
        return currentFile;
    }
}
