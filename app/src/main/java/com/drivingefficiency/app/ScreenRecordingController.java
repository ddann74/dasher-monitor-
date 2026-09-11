package com.drivingefficiency.app;

import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import androidx.core.app.ActivityCompat;

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
 *   SECOND trip within the same still-alive process -- Android can
 *   reject the mediaProjection foreground-service-type DECLARATION
 *   ITSELF (TripForegroundService.startForegroundWithRecording) when
 *   the process doesn't currently hold what Android considers a live
 *   grant. That call is wrapped in its own try/catch specifically so
 *   this degrades to "no recording this trip" instead of crashing the
 *   whole foreground service. See startForegroundWithRecording()'s own
 *   doc (TripForegroundService.java) for the full mechanism.
 * - CONFIRMED on a real device (2026-09-03, a SECOND real driver
 *   diagnostic log, on a freshly granted first-use token): the order
 *   matters the OTHER way too. getMediaProjection() must be called
 *   AFTER the foreground service already declares the mediaProjection
 *   type, not before -- calling it first is accepted by
 *   getMediaProjection() itself (returns non-null, no exception) but
 *   the resulting MediaProjection object is then rejected later, at
 *   createVirtualDisplay() time, with "Media projections require a
 *   foreground service of type ... MEDIA_PROJECTION". An earlier fix in
 *   this same session had this backwards (acquireProjection() called
 *   before the type was promoted) -- it stopped the crash but, as a
 *   side effect nobody noticed until this second log, made recording
 *   fail 100% of the time instead. See acquireProjection()'s own doc.
 * - Captures the ENTIRE device screen, not just this app -- there is no
 *   API to scope it narrower. See PRD ss1.3.
 * - A trip's recording is NOT one continuous file -- see rotateSegment()
 *   and SEGMENT_DURATION_MS. It's split into fixed-length chunks, each
 *   independently finalized, specifically so a crash mid-trip loses at
 *   most one chunk's worth of footage instead of the whole trip (PRD
 *   ss11, "crash-recovery gap"). Most real trips still produce exactly
 *   one file, since they run under SEGMENT_DURATION_MS.
 * - Also records MICROPHONE audio (PRD ss27), driver-requested -- a real
 *   privacy step up from video-only, since it captures whatever's
 *   audible in the car (voice, passengers, phone calls), not just
 *   Dasher's own screen. RECORD_AUDIO is a SEPARATE runtime permission
 *   from the MediaProjection consent grant; denying it never blocks
 *   recording, only its audio track -- decided once per trip in
 *   beginCapture() via hasAudioPermission(), not re-checked mid-trip.
 */
class ScreenRecordingController {

    private static final String PREFS_NAME = "screen_recording_prefs";
    private static final String KEY_ENABLED = "enabled";
    // docs/screen_recording/PRD.md ss7/ss8 ("capture by default"): whether
    // MainActivity's own first-run explanation dialog has already been
    // shown and resolved (Enable or Not Now), so it's a true ONE-TIME
    // nudge, never a repeat nag on later app opens.
    private static final String KEY_DEFAULT_PROMPT_SHOWN = "default_prompt_shown";
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

    // docs/screen_recording/PRD.md §23 -- driver-requested full automation:
    // re-granting consent should need zero manual interaction, not just a
    // one-tap notification (§21). Android itself still always shows the
    // real OS consent dialog (no API lets an app silently grant this to
    // itself -- confirmed, not a gap in this codebase); this instead lets
    // DasherAccessibilityService auto-tap THAT dialog on the driver's
    // behalf. Armed for a short window immediately before, and only
    // immediately before, this app's own call to createScreenCaptureIntent()
    // (see PermissionsActivity.requestScreenRecordingConsent()) -- this
    // timing window, not a package-name check, is what keeps the
    // accessibility service from ever touching any OTHER app's dialogs;
    // see that class's own doc for why a package check alone isn't used.
    private static volatile long expectingConsentDialogUntilMs = 0;
    private static final long CONSENT_DIALOG_AUTO_TAP_WINDOW_MS = 20_000;

    static void armConsentDialogAutoTap() {
        expectingConsentDialogUntilMs = System.currentTimeMillis() + CONSENT_DIALOG_AUTO_TAP_WINDOW_MS;
    }

    static boolean isExpectingConsentDialog() {
        return System.currentTimeMillis() < expectingConsentDialogUntilMs;
    }

    static void disarmConsentDialogAutoTap() {
        expectingConsentDialogUntilMs = 0;
    }

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /** docs/screen_recording/PRD.md §27 -- RECORD_AUDIO is a dangerous
      * permission (Android 6+), requested at runtime by
      * PermissionsActivity right before the MediaProjection consent
      * dialog. Checked fresh, not cached at the class level -- unlike
      * the MediaProjection grant, a plain permission check is cheap and
      * the OS can revoke it independently at any time. */
    static boolean hasAudioPermission(Context context) {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * "capture by default" (docs/screen_recording/PRD.md ss7/ss8):
     * whether the driver has EVER made a real, explicit choice about
     * this toggle -- deliberately NOT "isEnabled() == true," which
     * can't distinguish "explicitly turned off" from "never touched."
     * SharedPreferences.contains() answers exactly that. This, not a
     * flipped isEnabled() default, is what MainActivity gates its
     * first-run explanation on: flipping the raw getBoolean default to
     * true was considered and rejected -- it would make isEnabled()
     * read true everywhere ELSE in the codebase (most importantly
     * TripForegroundService.startTracking()'s recording-attempt check)
     * before the driver had ever seen an explanation or granted real
     * consent, which for a driver whose monitoring auto-starts without
     * ever opening MainActivity first (a real, already-documented
     * scenario -- docs/dash_monitoring_awareness/PRD.md) would raise a
     * confusing "no consent held" alert for a feature they never asked
     * about. isEnabled() only ever becomes true the same way it always
     * has: an explicit setEnabled(true) once real consent is granted.
     */
    static boolean hasEverBeenConfigured(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains(KEY_ENABLED);
    }

    static boolean isDefaultPromptShown(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_DEFAULT_PROMPT_SHOWN, false);
    }

    static void setDefaultPromptShown(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DEFAULT_PROMPT_SHOWN, true).apply();
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

    /** Every recording file, newest first -- driver-requested "view/share
      * recordings" (PRD ss17): needed the actual file list, not just the
      * count/total-size summary the Setup screen already showed. Sorted
      * by lastModified() descending rather than filename, since a
      * segment's "_partN" suffix would otherwise sort "trip_X_part10"
      * before "trip_X_part2" alphabetically. */
    static File[] listRecordingsNewestFirst(Context context) {
        File[] files = recordingsDir(context).listFiles();
        if (files == null) {
            return new File[0];
        }
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return files;
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

    // Segment length for the crash-safety rotation below -- short enough
    // that a crash mid-trip loses at most this much footage, long enough
    // not to spam the recordings folder with tiny files on a normal trip.
    private static final int SEGMENT_DURATION_MS = 5 * 60 * 1000;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder mediaRecorder;
    private File currentFile;
    private Service activeService;
    private String tripTimestamp;
    private int segmentIndex;

    // Every segment this recording SESSION has produced, in order (a
    // session can span many deliveries -- see PRD ss4a P7's own disclosed
    // gap that recordings aren't linked to individual trips). Reset in
    // beginCapture() since that's called once per session, not per
    // delivery. verifiedSegmentCount tracks how many of these have
    // already been checked by verifyNewlyFinishedSegments() so a long
    // session with many deliveries never re-verifies the same finalized
    // segment twice.
    private final java.util.List<File> segmentFiles = new java.util.ArrayList<>();
    private int verifiedSegmentCount;

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
        /**
         * unexpectedReason is null for a normal, caller-initiated stop()
         * call (TripForegroundService already logs its own "Stopped
         * recording for this trip" line right after calling stop(), so
         * nothing here would add anything). Non-null when recording
         * ended some OTHER way mid-trip -- a segment-rotation failure
         * (rotateSegment()'s two catch blocks) or Android itself
         * revoking the grant externally (projectionCallback.onStop()
         * below) -- CONFIRMED REAL GAP, found auditing this for the
         * field-test checklist: neither case ever surfaced anywhere
         * before this, not even to logcat in the external-revoke case.
         * A driver would just see recording quietly stop mid-delivery
         * with no explanation anywhere.
         */
        void onRecordingStopped(String unexpectedReason);
    }

    private final StopListener stopListener;

    // Set true only for the duration of stop()'s own body -- lets
    // releaseInternal() tell "this teardown was the caller's own
    // explicit stop() call" apart from "something else tore this down"
    // without needing a separate flag at every one of rotateSegment()'s
    // internal call sites.
    private boolean expectingStop;

    // True only once mediaRecorder.start() has actually succeeded this
    // session (set in beginCapture(), reset at the top of each new
    // session). Gates releaseInternal()'s unexpectedReason: an initial
    // acquire/begin-capture failure (start()/beginCapture() returning
    // false before recording ever began) is already logged explicitly by
    // TripForegroundService.startTracking()'s own check right after that
    // call returns -- without this flag, that same single failure would
    // ALSO surface through the StopListener callback below, duplicating
    // one real event into two confusing log lines.
    private boolean wasEverRecordingThisSession;

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
            lastFailureReason = "Android revoked the screen-recording permission mid-trip "
                    + "(system \"Stop\" notification action tapped, or the grant expired)";
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
     * Split from the original single-method start() into this plus
     * beginCapture() below, across two real diagnostic-log-driven fixes:
     *
     * 1. (2026-09-02) A real driver hit an uncaught SecurityException --
     *    "Starting FGS with type mediaProjection ... requires
     *    permissions" -- thrown from TripForegroundService's own
     *    startForeground() call. A stale/already-consumed consent token
     *    can make Android reject that FOREGROUND SERVICE TYPE
     *    DECLARATION itself, and that call used to be unguarded, so it
     *    crashed the whole foreground service.
     * 2. (2026-09-03) A SECOND real diagnostic log, on a freshly granted
     *    consent token, showed recording failing on literally the first
     *    attempt. Root cause: this method (acquireProjection ->
     *    getMediaProjection()) was being called BEFORE
     *    startForegroundWithRecording() promoted the foreground-service
     *    type -- the opposite of what Android actually requires.
     *    getMediaProjection() itself doesn't reject that (returns
     *    non-null, no exception here), but the resulting MediaProjection
     *    object is then rejected later, when beginCapture() actually
     *    tries to use it via createVirtualDisplay().
     *
     * Current (corrected) order, matching Android's real requirement:
     * TripForegroundService.startForegroundWithRecording() (the type
     * declaration, now wrapped in its own try/catch so fix #1 still
     * holds) runs FIRST; only if that succeeds does the caller call
     * THIS method; only if this succeeds does the caller call
     * beginCapture(). Returns false (setting lastFailureReason) on the
     * toggle being off, no consent held, or acquisition failing --
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

    private int capturedWidth;
    private int capturedHeight;
    private int capturedDensity;
    /** docs/screen_recording/PRD.md §27 -- decided once per trip, in
      * beginCapture(), not re-checked on every segment rotation: a trip
      * shouldn't silently gain or lose its audio track mid-recording if
      * the OS revokes the permission a few minutes in (an edge case, but
      * a confusing file to review if it happened) -- rotateSegment()
      * reuses whatever beginCapture() decided for the whole trip. */
    private boolean audioEnabledForThisTrip;

    /**
     * Actually starts capturing -- MUST be called only after
     * acquireProjection() returned true AND the caller has already
     * promoted the service to the mediaProjection foreground service
     * type (see this class's own doc and acquireProjection()'s doc for
     * why that order is required). Returns false (setting
     * lastFailureReason) on any MediaRecorder/MediaProjection setup
     * failure -- never throws.
     *
     * Crash-recovery fix (2026-09-02, docs/screen_recording/PRD.md ss11):
     * recording is now split into SEGMENT_DURATION_MS chunks (see
     * rotateSegment() below) rather than one file for the whole trip. A
     * MediaRecorder-produced MP4 only gets its 'moov' index box -- the
     * part that makes it playable at all -- written when stop() completes
     * cleanly; if the process dies before that, the file is left
     * unplayable (see hasMoovBox()'s own doc). Segmenting bounds the
     * damage from a crash to at most one segment's worth of footage
     * instead of the entire trip, since every completed segment before
     * the crash was already finalized by its own rotation.
     */
    boolean beginCapture(Service service) {
        try {
            activeService = service;
            WindowManager windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            capturedWidth = metrics.widthPixels;
            capturedHeight = metrics.heightPixels;
            capturedDensity = metrics.densityDpi;

            tripTimestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            segmentIndex = 1;
            currentFile = segmentFile();
            segmentFiles.clear();
            segmentFiles.add(currentFile);
            verifiedSegmentCount = 0;
            wasEverRecordingThisSession = false;
            audioEnabledForThisTrip = hasAudioPermission(service);

            mediaRecorder = newRecorder(currentFile);
            mediaRecorder.prepare();

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "DasherMonitorScreenRecording", capturedWidth, capturedHeight, capturedDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mediaRecorder.getSurface(), null, null);

            mediaRecorder.start();
            // Only set true once capture has GENUINELY started -- gates
            // releaseInternal()'s unexpectedReason (see StopListener's
            // own doc): an initial acquire/begin failure here (never
            // successfully started) is already logged explicitly by
            // TripForegroundService.startTracking()'s own check right
            // after this call returns false; without this flag, that
            // same single failure would ALSO surface through the
            // StopListener callback below, duplicating one real event
            // into two confusing log lines.
            wasEverRecordingThisSession = true;
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

    /** First segment keeps the original "trip_<timestamp>.mp4" naming
      * (most trips run under SEGMENT_DURATION_MS and produce exactly one
      * file, unchanged from before this fix); later segments get a
      * "_partN" suffix so they sort together with the trip they belong
      * to. */
    private File segmentFile() {
        String suffix = segmentIndex == 1 ? "" : ("_part" + segmentIndex);
        return new File(recordingsDir(activeService), "trip_" + tripTimestamp + suffix + ".mp4");
    }

    /**
     * docs/screen_recording/PRD.md §27 -- driver asked for microphone
     * audio alongside video. Real Android platform requirement, not a
     * style choice: setAudioSource()/setVideoSource() MUST both be
     * called before setOutputFormat(), or MediaRecorder throws
     * IllegalStateException -- setAudioEncoder() is the opposite, it
     * has to come after setOutputFormat(), same as the existing
     * setVideoEncoder() call right below it.
     */
    private MediaRecorder newRecorder(File file) {
        MediaRecorder recorder = new MediaRecorder();
        if (audioEnabledForThisTrip) {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        }
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        if (audioEnabledForThisTrip) {
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128 * 1000);
            recorder.setAudioSamplingRate(44100);
        }
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(capturedWidth, capturedHeight);
        recorder.setVideoFrameRate(30);
        recorder.setVideoEncodingBitRate(8 * 1000 * 1000);
        recorder.setOutputFile(file.getAbsolutePath());
        recorder.setMaxDuration(SEGMENT_DURATION_MS);
        // Callback fires on this thread's Looper (the service's main
        // thread, which has one) once the segment hits its time limit --
        // the documented Android pattern for bounded-duration recording,
        // not a novel/unverified mechanism.
        recorder.setOnInfoListener((mr, what, extra) -> {
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                rotateSegment(mr);
            }
        });
        return recorder;
    }

    /**
     * Cleanly finalizes the just-completed segment (the actual
     * crash-safety boundary -- this is where 'moov' gets written) and
     * starts the next one, redirecting the existing VirtualDisplay's
     * output rather than tearing down and recreating the MediaProjection
     * grant itself (VirtualDisplay.setSurface() is the supported API for
     * this -- no need to reacquire consent mid-trip).
     */
    private void rotateSegment(MediaRecorder finishedRecorder) {
        if (finishedRecorder != mediaRecorder) {
            return; // stale callback from an already-superseded recorder
        }
        try {
            finishedRecorder.stop();
            finishedRecorder.release();
        } catch (Exception e) { // same reasoning as beginCapture()'s catch -- a rotation failure
            // ends this trip's recording, it must never crash the service.
            lastFailureReason = "segment rotation (finalizing previous segment) failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage();
            android.util.Log.e("ScreenRecordingController", "rotateSegment() failed to finalize previous segment", e);
            releaseInternal();
            return;
        }

        // Separate try/catch from the block above -- a failure here means
        // nextRecorder was created (and possibly prepared) but never
        // reached mediaRecorder/started, so it must be released explicitly
        // rather than left as a leaked native codec instance; the
        // already-finalized previous segment above is unaffected either
        // way.
        MediaRecorder nextRecorder = null;
        try {
            segmentIndex++;
            File nextFile = segmentFile();
            nextRecorder = newRecorder(nextFile);
            nextRecorder.prepare();

            virtualDisplay.setSurface(nextRecorder.getSurface());
            nextRecorder.start();

            mediaRecorder = nextRecorder;
            currentFile = nextFile;
            segmentFiles.add(nextFile);
        } catch (Exception e) {
            lastFailureReason = "segment rotation (starting next segment) failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage();
            android.util.Log.e("ScreenRecordingController", "rotateSegment() failed to start next segment", e);
            if (nextRecorder != null) {
                try {
                    nextRecorder.release();
                } catch (RuntimeException ignored) {
                }
            }
            releaseInternal();
        }
    }

    /** Simple top-level ISO-BMFF box scan -- checks whether a 'moov' box
      * is present without parsing its contents. A MediaRecorder-produced
      * MP4 only gets one written when stop() completes cleanly (see
      * rotateSegment()/stop() above); a process killed before that (a
      * crash mid-segment) leaves a file that looks like a normal .mp4 but
      * that no standard player can open. Genuinely REPAIRING such a file
      * -- reconstructing a valid 'moov' from the raw frame data already
      * written -- is a much harder, OEM/version-specific problem this app
      * does not attempt (no Android SDK/device in this environment to
      * verify a repair actually works, and a repair that looks successful
      * but silently produces a still-broken file would be worse than
      * doing nothing). This only detects the gap, so cleanUpOrphanedSegments()
      * can remove it rather than leaving an unplayable file for the driver
      * to discover by trying to open it later. */
    private static boolean hasMoovBox(File file) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            long length = raf.length();
            long pos = 0;
            while (pos + 8 <= length) {
                raf.seek(pos);
                long boxSize = readUnsignedInt32(raf);
                byte[] type = new byte[4];
                raf.readFully(type);
                if ("moov".equals(new String(type, "US-ASCII"))) {
                    return true;
                }
                if (boxSize == 1) { // 64-bit extended size follows the type field
                    boxSize = raf.readLong();
                } else if (boxSize == 0) {
                    break; // box extends to EOF and it's not moov -- nothing left to scan
                }
                if (boxSize < 8) {
                    break; // malformed -- stop rather than looping forever
                }
                pos += boxSize;
            }
            return false;
        } catch (Exception e) {
            return false; // unreadable file -- treated as broken by the caller below
        }
    }

    private static long readUnsignedInt32(java.io.RandomAccessFile raf) throws java.io.IOException {
        byte[] b = new byte[4];
        raf.readFully(b);
        return ((long) (b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    /**
     * A REAL playability check, not just hasMoovBox()'s container-only
     * scan -- confirmed real gap (found auditing this feature, per an
     * explicit ask to "verify there is a playable file"): a file can have
     * a closed moov box and still fail to actually play if the frame data
     * inside is corrupt. MediaMetadataRetriever is Android's own media
     * framework actually attempting to open the file, not a heuristic --
     * a genuinely broken file fails setDataSource() or extractMetadata()
     * here the same way a real video player would fail to open it.
     * Returns false for a missing/empty file, one MediaMetadataRetriever
     * can't open at all, or one that opens but reports no duration
     * (present but content-free).
     */
    static boolean isPlayable(File file) {
        if (file == null || !file.exists() || file.length() == 0) {
            return false;
        }
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String duration = retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            return duration != null && Long.parseLong(duration) > 0;
        } catch (Exception e) { // exactly the class of corruption hasMoovBox() alone would miss
            android.util.Log.w("ScreenRecordingController", "isPlayable() -- " + file.getName()
                    + " failed to open: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        } finally {
            // Real Android API quirk, confirmed by CI's own javac error
            // (unlike MediaRecorder.release() elsewhere in this class):
            // MediaMetadataRetriever.release() is a CHECKED
            // java.io.IOException, not a RuntimeException -- catching
            // only RuntimeException here left it unreported and failed
            // the build outright, not silently.
            try {
                retriever.release();
            } catch (java.io.IOException | RuntimeException ignored) {
            }
        }
    }

    /**
     * Verifies every segment that has ACTUALLY finished (fully closed,
     * moov box written) since the last call -- deliberately never
     * touches whichever segment is still open for writing right now (a
     * mid-write MP4 legitimately has no moov box yet; checking it would
     * always report broken, which is not a real defect). Safe to call
     * after EVERY individual delivery within a session, not just at
     * session end -- see TripForegroundService.verifyScreenRecordingAfterDelivery()
     * -- since only segments finalized SINCE the previous call are
     * examined, nothing already-confirmed-playable is re-checked.
     *
     * Whether the LAST entry in segmentFiles counts as "finalized" or
     * "still open" depends on isRecording(): true (called mid-session,
     * a delivery just ended but recording keeps running for the rest of
     * the dash) excludes it; false (called after stop() has already
     * finalized the final segment too) includes it.
     *
     * Returns the segments that failed isPlayable() -- empty means every
     * segment examined this call is genuinely playable, not merely that
     * MediaRecorder.stop() didn't throw. lastVerifiedSegmentCount()
     * exposes how many were actually examined THIS call, so a caller can
     * tell "checked N, all playable" apart from "nothing new to check
     * yet" -- both return an empty broken list, but only the first is
     * worth a positive confirmation in the visible diagnostic log
     * (field-test checklist: "no alert fired" alone can't distinguish a
     * real pass from the check never having run at all).
     */
    java.util.List<File> verifyNewlyFinishedSegments() {
        int finalizedCount = isRecording() ? segmentFiles.size() - 1 : segmentFiles.size();
        java.util.List<File> broken = new java.util.ArrayList<>();
        lastVerifiedSegmentCount = 0;
        while (verifiedSegmentCount < finalizedCount) {
            File f = segmentFiles.get(verifiedSegmentCount);
            if (!isPlayable(f)) {
                broken.add(f);
            }
            verifiedSegmentCount++;
            lastVerifiedSegmentCount++;
        }
        return broken;
    }

    private int lastVerifiedSegmentCount;

    int lastVerifiedSegmentCount() {
        return lastVerifiedSegmentCount;
    }

    /**
     * Called once at service startup (TripForegroundService.onCreate) --
     * the first point this app's own code runs again after a possible
     * crash, "next launch" in the PRD's own words. Removes any leftover
     * recording missing a 'moov' box: a real segment a crash interrupted
     * before rotateSegment()/stop() could finalize it, and not
     * recoverable by this app (see hasMoovBox()'s own doc for why genuine
     * repair isn't attempted). Returns how many were removed so the
     * caller can log it -- silently leaving a broken-looking .mp4 around,
     * or silently deleting one with no record of it happening, are both
     * exactly the kind of silent failure this PRD's third-pass audit
     * (see PROGRESS.md) already flagged as worse than a visible one.
     */
    static int cleanUpOrphanedSegments(Context context) {
        File[] files = recordingsDir(context).listFiles();
        if (files == null) {
            return 0;
        }
        int removed = 0;
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".mp4") && !hasMoovBox(f)) {
                if (f.delete()) {
                    removed++;
                }
            }
        }
        return removed;
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
        expectingStop = true;
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
        activeService = null; // held only while a trip's capture is active -- see beginCapture()
        // Always fired, even if nothing was actually torn down above (a
        // setup failure inside start() before recording truly began) --
        // harmless in that case (the caller's own "active" flag was never
        // set true to begin with), and guarantees the ONE real case that
        // matters -- an external stop via the projectionCallback above --
        // can never be missed the way three separately-maintained call
        // sites at the caller were (see class doc).
        //
        // unexpectedReason is null exactly when THIS teardown was
        // triggered by stop()'s own body (expectingStop was set right
        // before it ran) -- never based on lastFailureReason's own
        // staleness, since that field is never cleared between sessions
        // and would otherwise leak a PREVIOUS trip's failure into a
        // perfectly normal stop() of a later, successful one. Also
        // requires wasEverRecordingThisSession, so an initial acquire/
        // begin-capture failure (recording never actually started) isn't
        // ALSO reported here -- startTracking() already logs that case
        // explicitly on its own.
        String unexpectedReason = (expectingStop || !wasEverRecordingThisSession) ? null : lastFailureReason;
        expectingStop = false;
        if (stopListener != null) {
            stopListener.onRecordingStopped(unexpectedReason);
        }
    }

    boolean isRecording() {
        return mediaRecorder != null;
    }

    /** docs/screen_recording/PRD.md §27 -- only meaningful after a
      * successful beginCapture(); lets the caller log whether this
      * trip's recording actually has an audio track, since a driver has
      * no other way to know that without opening a file in a player. */
    boolean isAudioEnabledForThisTrip() {
        return audioEnabledForThisTrip;
    }

    File currentFile() {
        return currentFile;
    }
}
