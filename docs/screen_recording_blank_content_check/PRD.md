# PRD: Detect blank/FLAG_SECURE screen recordings, not just a valid file

Status: IMPLEMENTED (2026-09-15), scouting-pass finding (round 8, #2,
directed audit of screen recording reliability).

## 1. What was wrong

`ScreenRecordingController.isPlayable(File)` -- the "REAL playability
check" this app uses to verify a finished recording segment, called from
`verifyNewlyFinishedSegments()` after every delivery -- only confirmed
two things: that `MediaMetadataRetriever` could open the file at all,
and that it reported a real (`> 0`) duration. It never inspected whether
the decoded video actually showed anything.

Android's `WindowManager.LayoutParams.FLAG_SECURE` (set by many apps
that handle payments or account data) makes `MediaProjection` render
that window's content as solid black on the virtual display this app
records from -- `MediaRecorder` still writes a perfectly valid,
correctly-timed MP4 of that black frame stream. `isPlayable()` would
report such a file as fully playable (opens cleanly, real duration),
and `verifyScreenRecordingAfterDelivery()` would log the strongest
positive confirmation this code produces: `"Verified N recording
segment(s) ... playable."` -- while the footage is entirely useless as
delivery evidence, with nothing anywhere ever telling the driver.
`grep -i "FLAG_SECURE"` across this whole class previously returned zero
matches -- unlike almost every other real platform gotcha in this
codebase, which is otherwise unusually thorough about disclosing exactly
this kind of thing, this one simply hadn't been considered.

(Whether the real Dasher app actually sets `FLAG_SECURE` on the
recorded screens is not verifiable without a device/APK -- this fix
closes the gap in the VERIFICATION regardless of whether that specific
trigger turns out to be real, since a blank recording could plausibly
also result from other capture failures this same content-blind check
would equally have missed.)

## 2. Design

Extended `isPlayable()` -- rather than adding a separate, easy-to-forget
second check -- since its own name and contract is exactly what every
caller already trusts to mean "verified usable." After confirming a real
duration, it now also calls a new `hasVisibleContent(retriever,
durationMs)`:

- Samples ONE frame from the MIDDLE of the clip (`durationMs / 2`), not
  frame 0 -- a real, genuine recording's very first frame can
  legitimately still be black for an instant while the virtual display's
  surface finishes its first real composite, which isn't itself evidence
  of a problem.
- Checks a coarse 12x12 grid of sampled pixels against a reference pixel
  (the frame's own top-left corner), with a tolerance of 10 (out of 255)
  per RGB channel -- loose enough that real video-compression noise in a
  genuinely solid-color source frame doesn't false-negative, tight
  enough that any frame with actual on-screen content (which varies far
  more than compression noise) is virtually certain to differ from the
  reference somewhere in the grid.
- Not black-specific: checks for ANY uniform color, since some OEMs/
  capture paths could plausibly render a secured window differently
  (solid white or gray, for instance) -- the underlying problem
  ("nothing real was captured") is the same either way.
- A frame that can't be decoded at all is treated as NOT visible content
  (conservative -- "unknown" is not "verified good").

Every existing downstream consumer (`verifyNewlyFinishedSegments()`,
`reportBrokenRecordingSegments()`, `raiseRecordingVerificationFailedAlert()`)
needed zero changes -- a blank segment now simply fails `isPlayable()`
the same way a corrupt one always did, reusing 100% of the existing
alerting infrastructure.

## 3. Verification

`ScreenRecordingController` depends on live `MediaProjection`/
`MediaRecorder`/`MediaMetadataRetriever` decoding real video, and can't
be compiled/run outside a device or emulator in this environment.
Verified instead by:

- `python3`-based brace/paren balance check on the edited file: final
  depth 0.
- A standalone, compiled-and-run Java program (`javac`/`java`) containing
  a **verbatim copy** of `hasVisibleContent()`'s core pixel-sampling
  algorithm (confirmed identical to the shipped source via `grep`
  immediately before writing the test) plus minimal fake
  `android.graphics.Bitmap`/`Color` stand-ins (`Color.red/green/blue`
  use Android's real, documented, stable bit-shift formula, not a
  reimplementation guess) -- since the real classes require a device to
  decode an actual video frame, but the blank-detection ALGORITHM itself
  is pure pixel-comparison logic that runs identically once a frame's
  pixels exist:
  - **The bug scenario**: a uniformly solid-black frame (the real
    `FLAG_SECURE` failure mode) is correctly detected as blank.
  - A frame with genuine, varied content is correctly detected as
    visible.
  - A uniformly solid-WHITE frame is also correctly detected as blank --
    confirms this isn't a black-specific check.
  - A solid-black frame with realistic compression-noise-level variation
    (random 0-6 per channel, well under the tolerance of 10) still reads
    as blank -- confirms the tolerance absorbs real codec noise without
    false-negatives.
  - A mostly-blank frame with one small, genuinely different 10x10-pixel
    region is still detected as visible -- confirms the sparse 12x12
    sampling grid reliably catches a real, localized UI element rather
    than needing full-frame content to register.

  5 checks, all passed on first run.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  end-to-end scenario (recording a genuinely `FLAG_SECURE`-protected
  window and confirming the resulting MP4 is now correctly flagged) has
  not been observed on a device. This fix is built on well-documented
  Android platform behavior (`FLAG_SECURE` forcing black frames in
  `MediaProjection`/screenshot capture) rather than something reproduced
  here, and verified via the pixel-sampling algorithm in isolation, not
  the full `MediaMetadataRetriever.getFrameAtTime()` decode path.
- Samples only ONE frame at the clip's midpoint. A recording that starts
  or ends blank but has real content only in the middle (or vice versa)
  isn't specifically covered -- a deliberate scope choice (matching
  `isPlayable()`'s own existing "one signal, not exhaustive frame-by-
  frame analysis" scope for corruption detection) rather than an
  oversight; sampling every frame would be far more expensive for a
  check that already runs after every delivery.
- The 10-per-channel tolerance and 12x12 sample grid are judgment calls,
  not derived constants -- same honesty status as this codebase's other
  tuned thresholds. Real compression noise characteristics for the
  actual codec/bitrate this app records at have not been measured on a
  device to confirm 10 is the right margin.
- Does not attempt to determine WHY a segment is blank (FLAG_SECURE vs.
  some other capture failure) -- the existing
  `raiseRecordingVerificationFailedAlert` message stays generic
  ("failed playability verification"), which is honest either way (the
  segment isn't usable) without overclaiming a specific diagnosed cause.

## 5. Success criteria

- [x] A uniformly blank (black or otherwise solid-color) recording
      segment now fails `isPlayable()` instead of being reported as
      verified
- [x] A genuinely playable, content-bearing segment is unaffected --
      still passes
- [x] Realistic compression-noise-level variation in a truly blank
      recording doesn't cause a false "visible content" result
- [x] A small, real, localized visible region is still reliably caught
      by the sample grid
- [x] Every existing downstream alert/log consumer required zero changes
- [x] Standalone compiled Java test (5 checks) of the exact algorithm,
      verified against the shipped source, fully passed
- [x] `python3` brace/paren balance check clean
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver/QA confirms on a real device that a `FLAG_SECURE`-protected
      (or otherwise genuinely blank) recording now triggers the
      "failed playability verification" alert instead of being silently
      reported as fine.
- [ ] Driver sign-off.
