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
 * - CONFIRMED on a real device (2026-09-02, a real driver's diagnostic
 *   log): a single granted consent canNOT be reliably reused for a
 *   SECOND trip within the same still-alive process. Worse than just
 *   getMediaProjection() returning null (which acquireProjection()
 *   below already handled gracefully) -- Android can reject the
 *   mediaProjection foreground-service-type DECLARATION ITSELF when
 *   the process doesn't currently hold a live grant, which used to
 *   crash the whole foreground service before acquireProjection() was
 *   split out to run (and be allowed to fail cleanly) before that
 *   declaration is ever attempted. See acquireProjection()'s own doc.
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

    /** Returns how many files could NOT be deleted (0 = fully successful) --
      * File.delete()'s own return value was previously ignored entirely,
      * so a partial failure (a locked/in-use file, a permission hiccup)
      * would still show a plain "Recordings deleted" success message with
      * no indication anything was left behind. Found and fixed auditing
      * this feature for "does anything fail silently?" */
    static int deleteAllRecordings(Context context) {
        File[] files = recordingsDir(context).listFiles();
        if (files == null) {
            return 0;
        }
        int failures = 0;
        for (File f : files) {
            if (!f.delete()) {
                failures++;
            }
        }
        return failures;
    }

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder mediaRecorder;
    private File currentFile;

    // Notified from releaseInternal() -- the ONE place all three ways
    // recording can stop (an explicit stop() call, this class's own
    // onStop() callback below, or a mid-setup exception in start())
    // actually converge. Found while reviewing this implementation for a
    // premortem: TripForegroundService originally set its own
    // isScreenRecordingActive static flag to false at each of its two
    // explicit stop() call sites individually, which missed the THIRD
    // path -- Android itself revoking the grant externally (e.g. the
    // driver tapping the system "Stop" notification) -- leaving that flag
    // wrongly stuck true after a recording had, in fact, already stopped.
    // A single callback fired from inside cleanup itself can't be missed
    // by construction, unlike three independently-maintained call sites.
    interface StopListener {
        void onRecordingStopped();
    }

    private final StopListener stopListener;

    ScreenRecordingController(StopListener stopListener) {
        this.stopListener = stopListener;
    }

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

    // CONFIRMED REAL GAP, closed by this field: start() had two early
    // `return false` paths (manager/mediaProjection null) that bypassed
    // the catch block below entirely, so NOTHING was ever logged for
    // them -- not even to logcat -- while the caller's own diagnostic
    // message claimed to point at "the preceding ERROR-level Android
    // log," which for those two paths didn't exist at all. Every failure
    // path now sets this before returning false, and it's ALWAYS visible
    // in this app's own diagnostic log (not just logcat, which a driver
    // has no way to read without a computer and ADB) via lastFailureReason().
    private String lastFailureReason;

    String lastFailureReason() {
        return lastFailureReason;
    }

    /**
     * CONFIRMED REAL BUG, closed by splitting start() into this method
     * plus beginCapture() below: a real driver hit an uncaught
     * SecurityException -- "Starting FGS with type mediaProjection ...
     * requires permissions" -- thrown from TripForegroundService's own
     * startForeground() call, BEFORE this class ever got a chance to
     * call getMediaProjection() at all. Root cause, confirmed against
     * the real crash log: this class's own class-doc "UNCONFIRMED...
     * whether a single granted consent can be reused for a SECOND trip"
     * note turned out to matter in a way the original start() design
     * didn't anticipate -- a stale/already-consumed consent token
     * doesn't just make getMediaProjection() return null (handled
     * gracefully below); Android can reject the FOREGROUND SERVICE TYPE
     * DECLARATION ITSELF if the calling process doesn't currently hold
     * a live MediaProjection grant, independent of whatever this class
     * does internally afterward. Since the app's own
     * startForegroundWithRecording() call (which requests that type)
     * used to run BEFORE this class ever attempted getMediaProjection(),
     * a stale consent crashed the whole foreground service instead of
     * just failing this one recording.
     *
     * Fix: acquire the projection FIRST (this method, callable while
     * the service is still only in its plain location-type foreground
     * state -- getMediaProjection() itself has no foreground-service-
     * type precondition). The caller only promotes to the mediaProjection
     * type (see TripForegroundService.startForegroundWithRecording)
     * AFTER this succeeds, then calls beginCapture() to actually start
     * recording -- matching Android's own documented order:
     * getMediaProjection() -> startForeground(..., MEDIA_PROJECTION) ->
     * createVirtualDisplay(). Returns false (setting lastFailureReason)
     * on the toggle being off, no consent held, or acquisition failing --
     * never throws.
     */
    boolean acquireProjection(Service service) {
        if (!isEnabled(service)) {
            lastFailureReason = "toggle is off";
            return false;
        }
        if (!hasPendingConsent()) {
            lastFailureReason = "no consent held (process restart since it was last granted?)";
            return false;
        }
        try {
            MediaProjectionManager manager = (MediaProjectionManager)
                    service.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (manager == null) {
                lastFailureReason = "MEDIA_PROJECTION_SERVICE unavailable on this device";
                return false;
            }
            mediaProjection = manager.getMediaProjection(pendingResultCode, pendingResultData);
            if (mediaProjection == null) {
                lastFailureReason = "getMediaProjection() returned null (consent token may already "
                        + "be consumed/invalid -- see class doc's unconfirmed reuse-across-trips note)";
                return false;
            }
            // Required on Android 10+ before createVirtualDisplay, or it
            // throws IllegalStateException -- must be registered before
            // the virtual display is created, not after.
            mediaProjection.registerCallback(projectionCallback, null);
            return true;
        } catch (Exception e) { // covers a stale/invalid consent token throwing instead of
            // returning null, on some Android versions/OEMs -- confirmed real risk,
            // same reasoning as the try/catch in beginCapture() below.
            lastFailureReason = e.getClass().getSimpleName() + ": " + e.getMessage();
            android.util.Log.e("ScreenRecordingController", "acquireProjection() failed", e);
            releaseInternal();
            return false;
        }
    }

    /**
     * Actually starts capturing -- MUST be called only after
     * acquireProjection() returned true AND the caller has already
     * promoted the service to the mediaProjection foreground service
     * type (see this class's own doc and acquireProjection()'s doc for
     * why that order is required). Returns false (setting
     * lastFailureReason) on any MediaRecorder/MediaProjection setup
     * failure -- never throws.
     */
    boolean beginCapture(Service service) {
        try {
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
            lastFailureReason = e.getClass().getSimpleName() + ": " + e.getMessage();
            android.util.Log.e("ScreenRecordingController", "beginCapture() failed", e);
            releaseInternal();
            return false;
        }
    }

    /** Set true when stop() catches MediaRecorder throwing on an
      * effectively-empty recording (see stop()'s own comment) -- the
      * resulting file is likely invalid/empty. Exposed so the caller can
      * mention it in the visible diagnostic log rather than this being a
      * fully silent, logcat-only event as it was before this audit
      * ("does anything fail silently?" - answer: this did). Reset at the
      * start of every stop() call. */
    private boolean lastStopWasLikelyEmpty;

    boolean lastStopWasLikelyEmpty() {
        return lastStopWasLikelyEmpty;
    }

    /** Safe to call even if acquireProjection()/beginCapture() never
      * succeeded -- releaseInternal() itself null-checks everything. */
    void stop() {
        lastStopWasLikelyEmpty = false;
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
            }
        } catch (RuntimeException e) {
            // MediaRecorder.stop() can throw if start() never actually got
            // far enough to record any real data (e.g. stopped within the
            // same instant it started) -- the output file may be
            // invalid/empty in that case, not something to crash the
            // foreground service over, but previously not logged ANYWHERE
            // (not even logcat) either -- fully silent. Now recorded via
            // lastStopWasLikelyEmpty() and still logged to logcat for a
            // developer debugging with ADB.
            lastStopWasLikelyEmpty = true;
            android.util.Log.w("ScreenRecordingController",
                    "stop() -- MediaRecorder.stop() threw, output file may be empty/invalid", e);
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
        // Always fired, even if nothing was actually torn down above (a
        // setup failure inside start() before recording truly began) --
        // harmless in that case (the caller's own "active" flag was never
        // set true to begin with), and guarantees the ONE real case that
        // matters -- an external stop via the projectionCallback above --
        // can never be missed the way three separately-maintained call
        // sites at the caller were (see class doc).
        if (stopListener != null) {
            stopListener.onRecordingStopped();
        }
    }

    boolean isRecording() {
        return mediaRecorder != null;
    }

    File currentFile() {
        return currentFile;
    }
}
