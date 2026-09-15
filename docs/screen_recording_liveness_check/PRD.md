# PRD: Real liveness detection for screen recording's health check

Status: IMPLEMENTED (2026-09-15), scouting-pass finding (round 10, #4,
directed audit of monitoring auto-correction).

## 1. What was wrong

`ScreenRecordingController.isRecording()` was a bare
`mediaRecorder != null` reference check -- the exact "lifecycle flag
mistaken for liveness" blind spot round 9 fixed for the GPS pipeline
(`TripForegroundService.isRunning`), left unfixed here. `newRecorder()`
registered an `OnInfoListener` for segment rotation but never an
`OnErrorListener`.

Per documented Android `MediaRecorder` behavior, the recorder can hit an
encoder/codec error mid-session (e.g. `MEDIA_RECORDER_ERROR_UNKNOWN`)
without being released and without `mediaRecorder` ever becoming null --
it simply stops writing real frames to disk. Since nothing listened for
that error, `isRecording()` kept returning `true` indefinitely, and
`TripForegroundService.checkTripCaptureHealth()` (called every 15s from
the existing accessibility heartbeat loop) kept logging "running" and
never fired its own `raisePermissionRevokedAlert("Trip Capture", ...)`
for a trip whose capture had actually died.

## 2. Design

- Added `recorderErrored`/`recorderErrorDetail` fields, reset inside
  `newRecorder()` -- the single choke point both `beginCapture()` and
  `rotateSegment()` go through to create a fresh recorder, so a new
  instance always starts with a clean health state, never inheriting a
  stale error from whatever it's replacing.
- Registered a real `MediaRecorder.OnErrorListener` in `newRecorder()`,
  right alongside the existing `OnInfoListener`. It only acts when the
  callback fires for the CURRENTLY active recorder (`mr == mediaRecorder`)
  -- mirroring the existing `finishedRecorder != mediaRecorder`
  identity-check pattern already used elsewhere in this class
  (`finalizeSegment`) for the same "don't act on a stale/already-replaced
  instance's own async callback" reason, since a genuine race (the old
  instance's callback still in flight right after rotation) is possible.
- `isRecording()` now returns `mediaRecorder != null && !recorderErrored`.
- `checkTripCaptureHealth()`'s existing alert-firing logic (a true->false
  transition of `isRecording()` already triggered
  `raisePermissionRevokedAlert("Trip Capture", ...)`) needed NO new alert
  machinery -- fixing the underlying signal is enough for that already-
  existing consumer to correctly fire on this failure mode too. The
  alert text now distinguishes a genuine recorder error (new
  `recorderErrorDetail()` getter) from the previous, generic "re-grant
  consent" wording, which wouldn't have been the right suggestion for a
  codec/encoder failure.

## 3. Verification

`ScreenRecordingController` depends on live `MediaRecorder`/
`MediaProjection` and can't be compiled/run outside a device or emulator
in this environment. Verified instead by:

- `python3`-based brace/paren balance check on both edited files: final
  depth 0 for each.
- A standalone, compiled-and-run Java program (`javac`/`java`) mirroring
  the exact state machine (reset-on-new-recorder, identity-checked error
  callback, `isRecording()`'s new condition), confirmed structurally
  identical to the shipped source via direct read immediately before
  writing the test, using a lightweight fake object in place of the
  real `MediaRecorder` (identity is all this logic needs -- the real
  class's many setup calls aren't part of what this fix changes):
  - A freshly started recorder with no errors reports `isRecording()`
    true.
  - **The bug scenario**: a genuine error firing for the CURRENT
    recorder makes `isRecording()` correctly report `false` -- confirmed
    the error detail (code/extra) is captured.
  - Rotating to a new recorder resets the error state, and the new
    recorder correctly reports healthy.
  - A stale error callback from an already-replaced (old) recorder
    instance does NOT affect the current, healthy recorder's state --
    confirms the identity check protects against exactly the race it's
    meant to.
  - `mediaRecorder == null` (never started, or a clean stop) still
    correctly reports not recording, unaffected by this fix.

  7 checks, all passed on first run.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  mid-session encoder/codec error scenario has not been observed on a
  device, only reasoned about from documented Android `MediaRecorder`
  error-callback behavior.
- Does not attempt to auto-recover from a detected recorder error (e.g.
  restarting capture with a fresh recorder immediately) -- this fix is
  purely about DETECTING and surfacing the failure through the existing
  alert path, matching the same scope choice made for the analogous
  engine/DB health-gate fix (`docs/heartbeat_engine_health_gate/PRD.md`).
  A driver still needs to act (re-grant consent, or the specific error
  detail now gives a more targeted starting point) to actually resume
  capture.
- The `OnErrorListener` callback signature/constants (`what`, `extra`)
  are recorded as raw integers in the alert text rather than translated
  to human-readable Android error-code names -- matches this
  codebase's existing style of surfacing raw diagnostic detail (e.g.
  `MediaMetadataRetriever`/`Log.getStackTraceString` usage elsewhere)
  rather than adding a lookup table for a rarely-seen error path.

## 5. Success criteria

- [x] A genuine mid-session `MediaRecorder` error is now detected, not
      silently missed by a bare null-check
- [x] The existing 15s health-check/alert machinery correctly fires for
      this failure mode with zero new alert code needed
- [x] A stale error from an already-rotated-out recorder instance never
      marks the current, healthy recorder broken
- [x] The alert text distinguishes a genuine recorder error from a
      consent-loss failure
- [x] Standalone compiled Java test (7 checks) of the exact state
      machine, fully passed
- [x] `python3` brace/paren balance check clean on both edited files
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver/QA confirms on a real device that a simulated recorder
      error (or a genuine codec failure, if reproducible) now triggers
      the "Trip Capture" alert instead of silently going unnoticed.
- [ ] Driver sign-off.
