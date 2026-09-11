# PRD: In-app screen recording during a trip

Status: CODE COMPLETE PER §6 -- NOT CONFIRMED WORKING IN THE FIELD.
CORRECTED (2026-09-11, driver's own feature audit): this line used to
read plain "IMPLEMENTED (all §6 boxes checked except sign-off)," which
was true of the checklist but not of reality -- the only real field
evidence this feature has ever had (§21's 2026-09-09/09-11 diagnostic
log) showed **zero successful recordings across the entire 2+ day
window**, and §21.3 documents a genuine, still-open Android platform
limitation with no code fix. §21/§23/§25 are real fixes for the causes
that log found, but those fixes are themselves unconfirmed in the
field too -- nothing in this file should be read as "confirmed working
for the driver," anywhere, until a NEW real log or explicit driver
confirmation says otherwise. Every individual section's own
"IMPLEMENTED"/"FIXED" label below means "this specific code change is
complete and reasoned through," never "confirmed working end-to-end."
This PRD's status line was ALSO previously found stale once before,
during a 2026-09-02 ralph-loop continuation pass. Went through two
additional premortem/silent-failure audit passes after initial
implementation (see PROGRESS.md) that found and fixed a real
delete-race and a silent consent-staleness gap. This introduces a
genuinely new, privacy-sensitive capability this app has never had
before - read §1.3 and §5 before signing off, not just the checklist.
§7 (added 2026-09-02, DRAFT; IMPLEMENTED 2026-09-09 per the driver's
explicit go-ahead): the driver asked to capture screen recording by
default. §7.6/§7.7 - a proactive first-run explanation dialog, not a
flipped `isEnabled()` default (investigated and rejected as a real
regression risk - see §7.6).
§9 (added 2026-09-02, CRITICAL, FIXED): a real diagnostic log from the
driver showed a crash loop - screen recording surviving past the first
trip in a session crashed the whole app on every subsequent trip start,
confirmed by three identical stack traces. Root cause and fix
documented in §9; see PROGRESS.md.
§11 (added 2026-09-02, FIXED): driver asked to fix the "video lost on a
crash" gap. Full binary repair was investigated and explicitly rejected
(unverifiable without a real device); fixed instead via segmented
recording (bounds crash loss to one ≤5-minute segment) plus startup
detection/cleanup of orphaned segments. See §11/§12; PROGRESS.md.
§15 (added 2026-09-09, FIXED): driver asked to actually verify a
playable file exists after each delivery, or vibrate an alert -- direct
follow-up to an audit that found nothing anywhere checked a produced
recording was actually playable, only that MediaRecorder.stop() didn't
throw. See §15/§16.
§17 (added 2026-09-09, FIXED): driver asked how to actually access a
recording, then to add a share button -- closes §7.1's own long
-disclosed "no in-app player, no export" gap. See §17/§18.
§13 (added 2026-09-03, CRITICAL, self-correcting §9's own fix): a THIRD
real diagnostic log showed recording had never once actually started
since §9's fix shipped - not crashing, but silently failing on every
attempt, including a freshly granted first-use consent token. §9's
reordering (acquire projection before promoting the foreground-service
type) had the order backwards per Android's real requirement. Fixed by
restoring the correct order (type first) while keeping the original
crash fixed via a new try/catch around the type-declaration call
itself, independent of ordering. "Doesn't crash" and "actually
produces a playable recording" are now treated as two separate claims
needing separate confirmation - see §13.4. See §13/§14; PROGRESS.md.
§21 (added 2026-09-11, CRITICAL, FIXED): driver asked "there don't
appear to be any screen recordings, could you explain why," with a
real 2026-09-09/09-11 diagnostic log attached. That log showed ZERO
successful recordings across 2+ real days: one process crash (OEM
kill, confirmed knownAggressiveOem=true) cleared the in-memory consent
grant early on 09-09, and every trip after that logged "no consent
held" and fired a "Trip Capture revoked" alert notification -- which
had no `setContentIntent` at all, so tapping it did nothing. The
driver had no way to discover, from the alert itself, that the fix was
"open Setup, toggle the switch off and back on." Fixed by deep-linking
that specific alert straight to PermissionsActivity with a new extra
that re-fires the real OS consent dialog immediately. See §21/§22;
also documents a SEPARATE, NOT fixed, genuine Android platform
limitation found in the same log (§21.3): the very first recording
attempt, on a never-before-used consent token, also failed --
apparently from the token going stale after sitting unused for the ~8
minutes between being granted in Setup and the trip actually starting,
not from reuse. No code fix for that exists yet; see §21.3 for why.
§23 (added 2026-09-11, FIXED, with real disclosed limits): driver
followed up on §21 -- "I don't want to have to tap anything ... done
automatically." Auto-launches Setup with zero interaction at trip
start, and auto-taps the real OS consent dialog itself via the
existing accessibility service (a narrow, time-boxed, documented
exception to its "Dasher content only" rule). Android still always
shows the real dialog -- no app can remove that -- this only answers
it automatically. Deliberately NOT extended to mid-trip drops (a
disclosed safety trade-off, see §23.3). See §23/§24.
§25 (added 2026-09-11, FIXED): driver asked whether the diagnostic log
actually confirms the field-test checklist's items. An audit found it
mostly does, with real visual-only exceptions, plus one closable gap
that directly undermined §21-§23: the checklist's own n1 (tapped
recovery) and n2 (zero-tap recovery) items logged identically, so the
log alone couldn't tell them apart. Every consent-recovery entry point
now tags which path opened Setup; also fixed a real bug found while
building this (one shared, racily-mutated `Intent` across three launch
paths). See §25/§26.
§27 (added 2026-09-11, IMPLEMENTED): driver asked for audio alongside
video, confirmed as microphone (ambient car sound), not app/device
audio. `RECORD_AUDIO` declared and requested at runtime, decided once
per trip so it can't drift mid-recording, real MediaRecorder
source/encoder ordering followed, both proactive-consent surfaces
(first-run dialog, Setup subtext) updated to disclose it before the
driver grants anything, and denying it degrades to video-only rather
than blocking recording. See §27/§28.
Scope: this one feature only. Not a general codebase pass.

## 0. What this is / isn't

A new, opt-in feature: record the device screen during a trip using
Android's official `MediaProjection` API - the same mechanism third-party
screen recorders (e.g. AZ Screen Recorder) use, brought in-app instead of
relying on a separate app.

**Motivation**: earlier in this session, the driver reported AZ Screen
Recorder crashing specifically during dashing - the most likely cause
being resource contention between two simultaneous foreground services
(this app's own GPS/accessibility/notification-listener foreground
service, plus a separate app's screen-capture foreground service) on the
same device, compounded by OEM battery-management aggressiveness already
documented elsewhere in this app's own `docs/watchdog_reliability/PRD.md`.
Bringing recording in-app, sharing this app's own already-proven
foreground-service lifecycle instead of running a second competing one,
directly addresses that.

This is **not** a small, low-risk addition like most other PRDs in this
repo - `MediaProjection` captures the ENTIRE device screen, not just this
app's own UI, and Android enforces a real, non-negotiable consent dialog
every time a capture session starts. §1.3 and §5 are load-bearing, not
boilerplate.

## 1. Why / design constraints (investigation, 2026-08-31)

### 1.1 Real platform constraints, not design choices

1. **A user consent dialog is unavoidable, every time a NEW capture
   session starts.** `MediaProjectionManager.createScreenCaptureIntent()`
   must be launched from an `Activity` (not a background service) and
   Android shows its own system dialog ("Start recording or casting with
   Dasher Monitor?") that only a human tap can dismiss - there is no API
   to silently pre-grant or auto-approve this, by OS design (it is one of
   Android's most sensitive permissions for exactly the reason in §1.3
   below). This means "start recording automatically the instant a trip
   starts, every time, with zero taps" is not achievable on Android,
   full stop - not a limitation of this app's design.
2. **The granted projection is invalidated whenever the process dies.**
   If `TripForegroundService`'s process is killed (an OS/OEM kill - see
   `docs/watchdog_reliability/PRD.md`'s own evidence this happens for
   real) and restarts, the previously-granted `MediaProjection` token is
   gone; recording cannot silently resume, the consent dialog is needed
   again. A trip that survives a process kill (which the watchdog work
   exists specifically to recover from) would have its screen recording
   end at the kill and NOT resume automatically - the watchdog can
   restart GPS tracking, it cannot re-grant a user consent dialog on its
   own.
3. **Android 14 (this app's `targetSdk`) requires a specific foreground
   service type** (`FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`) declared
   for whichever service actually calls
   `MediaProjection.createVirtualDisplay()`, and requires that call to
   happen promptly after consent is granted - a stale/unused grant can be
   rejected.

### 1.2 Where this fits this app's existing architecture

- `TripForegroundService` already owns the trip lifecycle
  (`startTracking`/`stopTracking`) and is already a foreground service -
  the natural home for recording start/stop, sharing its lifecycle rather
  than adding a second, separately-managed service (which would
  reintroduce a version of the exact resource-contention problem this
  feature exists to solve).
- `PermissionsActivity` is this app's existing pattern for permission-gated
  toggles (battery exemption, accessibility, notification access, fuel
  cost settings) - the natural place for a "Record screen during trips"
  toggle and the one-time consent-request tap, following the existing
  `onRequestPermissionsResult`-based pattern already used there (this
  codebase does not currently use the newer `ActivityResultContracts`
  API - worth adopting for this specific flow, since `MediaProjection`'s
  own consent intent is exactly the kind of one-shot result-returning
  flow that API was built for, but noted as a deviation from existing
  code style, not a silent one).
- No existing code in this repo writes video/media files anywhere -
  this is new ground, unlike (for example) `tiktok-feed-filter`'s
  `AudioExtractor`/`DownloadedVideoLocator` in a sibling repo this
  session, which already established a private-app-storage pattern for
  exactly this class of privacy-sensitive on-device media. This PRD
  follows that same shape (private app storage, not the shared/public
  MediaStore) by default - see §5.1.

### 1.3 The real privacy exposure this feature has - read before signing off

**`MediaProjection` captures the entire device screen, not just this
app's own UI, for as long as the session is active - there is no API to
scope it to "only while Dasher is in the foreground."** If recording is
running and the driver switches to their banking app, a personal
messaging app, or anything else, that content is captured into the same
video file. This is fundamentally different from every other feature in
this app, all of which only ever read Dasher's/DoorDash-adjacent apps'
own on-screen text via the accessibility service, never a raw screen
capture of arbitrary other apps.

This PRD's default design (§3) mitigates but does not eliminate this:
recording is opt-in (off by default), tied tightly to the trip lifecycle
(starts only when a trip actually starts, stops the moment it ends, not
"whenever the app feels like it"), and stored privately (not in a shared
gallery). It does NOT attempt to detect or blank out other apps while
recording is active - no such API exists to do this reliably system-wide.

## 2. Definition of "functional" for this task

Left unchecked since the original 2026-08-31 draft even after
implementation - a real documentation gap, not a code gap: every item
below was actually implemented and is covered by §6's own (checked)
success criteria and by the PROGRESS.md history of everything built and
fixed since. Checked off here on 2026-09-09 after re-verifying each one
directly against the current code (not just trusting the old §6 boxes),
cross-references below point at where.

- [x] A new **Setup** toggle ("Record screen during trips") is off by
      default - recording never starts without the driver explicitly
      opting in first. `ScreenRecordingController.isEnabled()`:
      `getBoolean(KEY_ENABLED, false)`.
- [x] Enabling the toggle immediately triggers the one-time
      `MediaProjection` consent flow (from an Activity, not silently from
      the background service) - if declined, the toggle reverts to off
      and nothing records. `PermissionsActivity`'s
      `screenCaptureConsentLauncher` + `createScreenCaptureIntent()`.
- [x] With the toggle on and consent granted, recording starts
      automatically when a trip starts (`startTracking()`) and stops
      automatically when it ends (`stopTracking()`) - no separate
      manual start/stop button needed for the common case. See §13's
      corrected `acquireProjection()`/`beginCapture()` ordering.
- [x] If the granted projection has been invalidated (process restart -
      see §1.1.2) when a new trip starts, this is handled visibly (an
      Activity log line and/or notification saying recording could not
      resume and consent is needed again) rather than silently recording
      nothing while appearing to work. `raisePermissionRevokedAlert(
      "Trip Capture", ...)`, same mechanism as every other revoked
      -permission alert.
- [x] Recorded files are written to this app's own private external
      storage (not a shared/public gallery) - same reasoning
      `tiktok-feed-filter`'s `AudioExtractor` already used for
      privacy-sensitive on-device media in a sibling repo.
      `recordingsDir()`: `context.getExternalFilesDir(null)`.
- [x] A way to review/delete recordings exists in-app (at minimum: see
      how many exist and their total size, matching the existing
      Diagnostic Log's size-visibility pattern; full playback UI is a
      stretch goal, not required for this PRD - see §5.2). Exceeded, not
      just met: §17 added a real share/view path on top of the original
      count/size/delete-all bar.
- [x] No change to `TripForegroundService`'s existing GPS/accessibility/
      notification lifecycle - recording is additive, gated entirely
      behind the new toggle, and its absence (toggle off, the default)
      must leave every existing behavior completely unchanged. Every
      recording call site is reachable only through code already gated
      on `isEnabled()`/`isRecording()`, re-confirmed while adding §15/§17.

Non-goals:
- Scoping capture to only this app's own UI - not possible on Android
  (§1.3).
- Automatically resuming recording after a process kill without a new
  consent tap - not possible on Android (§1.1.2).
- Uploading/sharing recordings anywhere - purely local, matching this
  app's existing 100%-offline-except-geocoding design (see README's
  Privacy section for the existing precedent).
- Video editing, trimming, or per-delivery splitting - out of scope for
  a first version; one file per trip is the v1 shape (see §5.3).

## 3. Design

### 3.1 Consent flow (`PermissionsActivity`)

New toggle wired to `MediaProjectionManager.createScreenCaptureIntent()`
via `ActivityResultLauncher` (a deliberate, disclosed deviation from this
file's existing `onRequestPermissionsResult` pattern - see §1.2). On
grant, the returned `Intent` (which IS the capture permission - Android's
model, unlike a normal runtime permission) is handed to
`TripForegroundService` and held in memory only for the life of the
process (see §1.1.2 for why it can't be persisted/reused across a
restart). On denial, the toggle is reverted and nothing else changes.

### 3.2 Recording lifecycle (`TripForegroundService`)

New `ScreenRecordingController` (or similar), constructed with the
consent `Intent`/token from 3.1, exposing `start()`/`stop()` called from
`startTracking()`/`stopTracking()` respectively, gated on the Setup
toggle being on AND a valid (non-invalidated) grant being held. Uses
`MediaProjection.createVirtualDisplay()` + `MediaRecorder` to encode to
an MP4 file. Requires declaring
`android:foregroundServiceType="mediaProjection"` alongside the existing
`"location"` type on `TripForegroundService`'s manifest entry (Android
supports multiple types on one service declaration).

### 3.3 Storage

New file per trip, written under this app's own
`getExternalFilesDir()`-scoped directory (e.g.
`Android/data/com.drivingefficiency.app/files/ScreenRecordings/`),
matching `tiktok-feed-filter`'s `AudioExtractor` pattern - visible to a
file manager, cleared on uninstall, never in a shared/public gallery
unless the driver explicitly exports one later (out of scope for v1, see
non-goals).

## 4. Testing / verification approach

Same disclosed limitation as every Java-side PRD in this repo: no Android
SDK/emulator/device in this environment - `MediaProjection` in particular
has zero pure-logic surface to unit-test (it's entirely a system-API/
Activity-result flow), so this PRD's verification is code review only
until a real device is available. Flagged explicitly, not glossed over:
this is the least independently-verifiable PRD in this repo so far.

## 4a. Premortem (2026-08-31): assume this fails after shipping

- **P1 - the exact resource-contention problem this feature was meant to
  solve could still happen, just with one process instead of two.**
  Adding `MediaProjection`'s own CPU/memory/battery cost directly into
  `TripForegroundService` - the same process already running GPS
  polling, accessibility reading, and notification listening - could
  make THAT process more likely to be OEM-killed, not less, even though
  it removes the two-separate-apps contention. Not confirmed either way
  without real-device battery/CPU profiling, which this environment
  can't do.
- **P2 - storage fills up silently.** Screen recordings are large (much
  larger than the Diagnostic Log's capped 512KB) and this PRD's v1 has
  no size cap or auto-cleanup (see §2's review/delete requirement, which
  only surfaces the problem, not solves it automatically). A driver who
  dashes for hours daily with this on could fill device storage within
  days. Flagged as a real, near-term follow-up need, not solved here.
- **P3 - the privacy exposure in §1.3 is the single biggest reason this
  PRD could be the wrong call entirely**, not just a risk to mitigate.
  Every other feature in this app reads only Dasher/DoorDash-adjacent
  on-screen text; this one captures literally anything on screen for the
  whole trip duration. If the driver ever alt-tabs to check a personal
  message, a banking app, or anything else mid-trip with this on, that's
  in the recording. This needs explicit, informed sign-off - not the
  same "reasonable default, flag concerns, proceed" treatment most other
  PRDs in this repo get.

### Second pass (2026-08-31), against the actual implementation, not just the design

- **P4 - CONFIRMED REAL BUG in the original implementation, now fixed:
  "Delete All Recordings" had no guard against a recording actively being
  written.** `PermissionsActivity`'s delete button had no way to know
  whether `TripForegroundService`'s screen recording was currently in
  progress - that state lived only in a service-local field, never
  shared. Deleting while a file is open for writing typically "succeeds"
  on Android (unlinks the directory entry) while the write continues
  into now-unreferenced storage - the in-progress recording would
  silently vanish with no error anywhere. Fixed: new
  `TripForegroundService.isScreenRecordingActive` static flag, checked
  before the delete confirmation dialog even appears.
- **P5 - CONFIRMED REAL GAP in the original implementation, now fixed:
  the Setup screen couldn't distinguish "toggle on, actually able to
  record" from "toggle on, but consent silently invalidated by a process
  restart."** The Switch reflects the PERSISTED preference (survives a
  restart); the actual consent grant is memory-only (does not). A driver
  checking Setup after, say, a watchdog-recovered process kill would see
  the Switch ON and reasonably assume the next trip would record - the
  only evidence otherwise was an alert notification that fires later,
  only once a trip actually tries and fails. Fixed:
  `refreshScreenRecordingStatus()` now checks
  `hasPendingConsent()` directly and surfaces the mismatch right on this
  screen, not just after the fact.
- **P6 - noted, not confirmed either way: whether the delay between
  granting consent (in Setup) and actually using it (whenever the next
  trip starts, which could be hours later) risks the grant going stale
  on some Android versions**, independent of the already-flagged
  process-restart case. No documented hard timeout is known to exist,
  but this is genuinely unconfirmed without a real device - flagged
  alongside the existing reuse-across-trips uncertainty in
  `ScreenRecordingController`'s own class doc, not treated as resolved.

### Third pass (2026-08-31): "are there any gaps" - not bugs, real scope gaps

- **P7 - no link between a stored recording and the trip it belongs to.**
  Recordings are timestamped files (`trip_YYYYMMDD_HHMMSS.mp4`) in a flat
  directory with zero connection to this app's own trip database or
  "View Last Trip Summary"/"Trip History" screens. Reviewing a specific
  past delivery has no way to jump to its recording (or vice versa) -
  matching them requires comparing timestamps by hand. Not a bug (§2's
  "basic review" requirement - count/size - was met as scoped), but a
  real, previously-undisclosed usability gap once the feature is looked
  at as a whole rather than one requirement at a time.
- **P8 - screen rotation during a trip is not handled.** `start()`
  captures `width`/`height`/`density` once, from
  `windowManager.getDefaultDisplay().getRealMetrics()` at the moment
  recording begins, and creates the `VirtualDisplay` at that fixed size
  for the rest of the trip. If the device actually rotates mid-trip, the
  capture surface does not follow - the likely result is a squished or
  incorrectly-oriented recording for whatever happens after the
  rotation, not a crash. Real-world likelihood is low for this
  specific app (a phone mounted for driving is unlikely to physically
  rotate mid-trip), which is why this wasn't caught in the first two
  audit passes (both focused on failure paths and logging, not this kind
  of steady-state correctness question) - low probability is not the
  same as confirmed-fine, and no device is available here to check
  either way.

## 5. Open questions - genuinely blocking, not just disclosed

1. **Given §1.3, should this feature exist as designed at all, or with a
   narrower scope** (e.g. only recording while Dasher/DoorDash itself is
   the foreground app, auto-pausing when the driver switches to anything
   else - technically achievable by combining this with the existing
   `DasherAccessibilityService`'s own foreground-app tracking, at the
   cost of gaps in the recording whenever the driver legitimately checks
   another app mid-trip)? This changes §3's design meaningfully depending
   on the answer.
   **RESOLVED (2026-08-31, driver): "Record everything while the trip is
   active."** Whole-screen capture for the full trip duration, as
   originally designed in §3 - not scoped to Dasher's own foreground.
2. **Storage cap/retention**: keep every trip's recording forever (until
   manually deleted), auto-delete after N days, or cap total size with
   oldest-first eviction (same pattern `tiktok-feed-filter`'s
   `RepeatViewRepository` uses for its own capped history, a sibling
   repo's already-proven approach to bounding unbounded local storage)?
   **Not resolved - implemented with NO cap for v1** (manual delete-all
   only, per §3). Flagged as real, near-term follow-up in the premortem
   (§4a-P2), not silently deferred.
3. **Should recording require BOTH the toggle on AND a per-trip
   confirmation**, or is toggle-once-then-automatic (as designed in §3)
   the right default? An always-record-once-enabled design is more
   convenient but has a higher accidental-capture risk than a
   per-trip prompt.
   **Not explicitly resolved - implemented as toggle-once-then-automatic**
   (§3's original default), since #1's answer confirmed the driver wants
   full trip-duration coverage without narrower scoping, which reads as
   the same intent (convenience over a per-trip prompt). Worth confirming
   explicitly if this turns out to be the wrong read.

## 6. Success criteria (implementation-phase checklist)

- [x] Open question §5.1 (the one flagged as actually blocking)
      resolved with the driver; §5.2/§5.3 implemented with their
      documented defaults, not silently skipped
- [x] Setup toggle added, off by default
- [x] `MediaProjection` consent flow wired via `ActivityResultLauncher`
- [x] `ScreenRecordingController` added, start/stop tied to
      `startTracking()`/`stopTracking()`
- [x] `foregroundServiceType="mediaProjection"` added to
      `TripForegroundService`'s manifest entry (alongside the existing
      `location` type)
- [x] Process-restart invalidation handled visibly (not silent) - the
      same `raisePermissionRevokedAlert` mechanism already used for a
      revoked permission
- [x] Recordings written to private app storage
      (`getExternalFilesDir()/ScreenRecordings/`), not shared/public
- [x] Basic in-app review (count + total size) of stored recordings, plus
      a delete-all action with a confirmation dialog
- [x] No change to existing GPS/accessibility/notification behavior when
      the toggle is off (diff-reviewed - every new code path is gated
      behind `ScreenRecordingController.isEnabled()`/`hasPendingConsent()`)
- [ ] Confirmed working in real field use -- explicitly NOT true as of
      2026-09-11: the only real diagnostic log this feature has ever
      had (§21) showed zero successful recordings across 2+ real days.
      §21/§23/§25 fix the causes that log found, but those fixes are
      themselves unconfirmed; this box stays unchecked until a NEW real
      log, or explicit driver confirmation, shows an actual recording
      was produced.
- [ ] User sign-off

## 7. Driver request (2026-09-02, DRAFT - NOT implemented, not approved): capture by default

Driver asked: "capture screen recording by default" and "where are the
videos located." The second question is answered directly - see below;
this section is the investigation and design for the first, written up
per the driver's own explicit instruction to add it to the PRD but NOT
write any code from it yet.

### 7.1 Where the videos are located today (answers the driver's second question)

`context.getExternalFilesDir(null)/ScreenRecordings/trip_<timestamp>.mp4`
(`ScreenRecordingController.recordingsDir`/`RECORDINGS_DIR_NAME`) - on a
real device this resolves to app-private EXTERNAL storage, e.g.
`/storage/emulated/0/Android/data/com.drivingefficiency.app/files/ScreenRecordings/`.
Per §1.2's own design note, this is deliberately private app storage,
not the shared/public `MediaStore` gallery - confirmed still true,
nothing about this has changed. Practically, this means:

- The videos do **not** appear in the Photos/Gallery app on the phone.
- No in-app player or export/share button exists (§6's own checklist:
  "basic in-app review" is COUNT + TOTAL SIZE + delete-all only, not
  playback) - confirmed by re-reading `PermissionsActivity`'s screen-
  recording section in full, not assumed.
- The only ways to actually retrieve a file today: a file manager app
  that can browse `Android/data/...` (Android 11+ restricts this for
  many file managers unless the user explicitly grants "All files
  access"), or a computer connected over USB with file transfer/adb.
  **This is itself a real, disclosed gap this PRD has never closed** -
  worth a separate, explicit driver decision (an in-app "share/export"
  action?) if watching the recordings is something the driver actually
  wants to do, independent of the "by default" request below.

### 7.2 What "by default" can and cannot mean here

§1.1 point 1 already established, as a real Android platform
constraint (not a design choice): **the OS's own MediaProjection
consent dialog cannot be silently bypassed, ever, by any app, for any
reason** - "start recording automatically, every time, with zero
taps" is not achievable on this OS, full stop. That has not changed
and this section does not attempt to re-litigate it. What CAN
change, entirely within this app's own control:

1. **The setup toggle's default value** - currently
   `isEnabled()` reads `getBoolean(KEY_ENABLED, false)`
   (`ScreenRecordingController.java` L72-74) - off unless the driver
   has explicitly turned it on. Flipping this default to `true` means
   a driver who never opens Settings still gets prompted for the
   (unavoidable) one-time consent dialog the first time monitoring
   starts, instead of screen recording silently never happening at
   all because they never found the toggle.
2. **Proactively surfacing the consent prompt**, rather than requiring
   the driver to remember to visit `PermissionsActivity` and tap
   "Enable" themselves. Today, per §1.1/§3, the consent flow only ever
   fires from a driver's own tap inside `PermissionsActivity` - there
   is no code path today that surfaces it from anywhere else (e.g. on
   first app launch, or the first time `startTracking()` runs with the
   toggle on but no consent yet held).

### 7.3 The real tradeoff this creates - directly extends §1.3, not a new concern

§1.3 already discloses screen recording's core privacy exposure (whole
device screen, not just this app, for the full trip duration) and
names "opt-in, off by default" as part of how this PRD's original
design mitigates it. **Flipping the default to on removes exactly that
mitigation for every driver who installs the app and never visits
Settings** - they would get the OS consent dialog (which they must
still affirmatively tap "Start now" on for anything to actually
record - Android does not allow a default-accepted state, per §1.1),
but would arrive at that dialog without ever having made an
affirmative, in-app choice to want this feature at all. A driver who
taps through an unexpected system dialog without reading it closely is
a real, known UX pattern this PRD hasn't had to reckon with while the
feature was opt-in - it does have to now, since "by default" only
narrows the OS's own consent gate, it does not add a NEW gate this app
controls to soften that risk (unless one is deliberately designed - see
non-goals below).

### 7.4 Non-goals for this addition

- Not attempting to bypass or auto-accept the OS consent dialog itself
  - confirmed impossible (§1.1/§7.2), not a design choice being
    declined.
- Not (yet) designing an in-app viewer/export for existing recordings
  (§7.1's own disclosed gap) - a real, related but separate ask, not
  bundled into "capture by default" without being asked to.
- Not changing §5.2's storage-cap decision (still no cap, still
  manual delete-all only) - a default-on toggle would mean MORE
  drivers accumulating recordings with no cap by default, which makes
  §5.2's already-flagged near-term follow-up more urgent, not
  something to silently fix as a side effect here.

### 7.5 Open question - genuinely blocking, matching §5's own pattern

Should flipping the toggle's default to `true` be paired with a NEW,
explicit first-run explanation screen ("This app can record your
screen during trips to help you review deliveries later - screen
recording captures your ENTIRE screen, not just this app, for the
whole trip") shown BEFORE the OS's own consent dialog ever appears -
so the driver's first encounter with this feature is an in-app
explanation they control, not a system dialog they might tap through
on reflex? Or is the OS's own consent dialog (which does show Android's
standard "this will let Dasher Monitor record everything displayed
on your screen" warning) considered sufficient disclosure on its own,
matching how §1.1/§3's original opt-in design already relied on it? This
is a real driver preference about how much additional friction to add
in front of an OS gate that already exists either way - not purely a
coding call, and not decided here. **Recommend the first-run
explanation screen** - the OS dialog's own wording is generic across
every app that ever requests this permission, while a driver
proactively defaulted INTO this by an app update deserves to know
specifically why, before being asked to tap through it - but this is
disclosed as a recommendation only, not built.

### 7.6 Implemented (2026-09-09): driver's explicit go-ahead ("implement the screen recording by default feature")

Went with §7.5's own recommendation (first-run explanation screen)
rather than blocking on a re-ask, since the driver's go-ahead came
after that recommendation was already on record - flagged here as a
judgment call, not silently assumed.

**A real regression risk found while designing the trigger, not just
"pick first-launch vs. first-trip"**: the checklist below originally
planned to literally flip `ScreenRecordingController.isEnabled()`'s
`getBoolean(KEY_ENABLED, false)` default to `true`. Investigated first
and REJECTED: `isEnabled()` is read in multiple places, most
importantly `TripForegroundService.startTracking()`'s recording
-attempt block - and monitoring can auto-start without the driver ever
opening `MainActivity` first (a real, already-documented scenario, see
`docs/dash_monitoring_awareness/PRD.md`). A flipped raw default would
have made `isEnabled()` read `true` for such a driver BEFORE they ever
saw an explanation or granted real consent, firing the existing
"enabled but no consent held" alert for a feature they never asked
about - confusing, not helpful, and exactly the kind of "fixed one
thing, silently broke another" this PRD's own §13 already lived
through once on this exact subsystem.

**What was actually built instead**, same goal, safer mechanism:

- New `ScreenRecordingController.hasEverBeenConfigured()` -
  `SharedPreferences.contains(KEY_ENABLED)`, not `isEnabled() ==
  false` - correctly distinguishes "never touched this" from
  "explicitly turned off." `isEnabled()`'s own raw default stays
  `false`, unchanged; it only ever becomes `true` the same way it
  always has, via a real granted consent.
- New `MainActivity.maybeShowScreenRecordingDefaultPrompt()` - runs
  once per `onCreate` (the app's real, guaranteed entry point), shows
  the recommended explanation dialog ONLY when
  `!hasEverBeenConfigured() && !isDefaultPromptShown()`. Both buttons
  record a real, explicit choice (`setCancelable(false)`, no
  dismiss-without-choosing case to reason about separately): "Enable
  Screen Recording" launches `PermissionsActivity` with a new
  `EXTRA_AUTO_REQUEST_RECORDING_CONSENT` extra; "Not Now" explicitly
  persists `enabled=false` (a real opt-out, not a soft skip).
- `PermissionsActivity.onCreate()`: that extra triggers
  `screenRecordingSwitch.setChecked(true)`, which fires the SAME
  already-attached listener a real manual tap would - the real OS
  consent dialog appears immediately, no second tap needed. The
  switch-on branch's own consent-request logic was extracted into
  `requestScreenRecordingConsent()` specifically so this reuses it
  rather than duplicating it.
- New `ScreenRecordingController.isDefaultPromptShown()`/
  `setDefaultPromptShown()` - a plain persisted flag so the prompt is a
  true ONE-TIME nudge, never a repeat nag on later launches.

**Self-caught bug during implementation**: the first version called
`screenRecordingSwitch.setChecked(true)` AND explicitly called
`requestScreenRecordingConsent()` right after it, in
`PermissionsActivity`. Since the `OnCheckedChangeListener` is already
attached by that point in `onCreate` (unlike the switch's OWN initial
`setChecked()` a few lines earlier, which deliberately runs BEFORE the
listener is attached), `setChecked(true)` already fires the listener -
which itself calls `requestScreenRecordingConsent()`. The explicit
second call would have launched the real OS consent dialog TWICE, back
to back. Caught on re-read before verifying brace/paren balance, fixed
by removing the redundant explicit call and relying on the listener
alone.

§7.1's in-app export/share gap was NOT bundled into this - it was
already addressed separately (a real driver ask in its own right, see
this PRD's §17/§18), not assumed as part of "by default."

### 7.7 Success criteria for §7

- [x] §7.5's open question answered (driver's go-ahead + this section's
      own recommendation)
- [x] Proactive first-run trigger implemented -
      `MainActivity.onCreate()`, gated on `hasEverBeenConfigured()`, NOT
      a flipped `isEnabled()` default (see §7.6 for why that would have
      been a real regression)
- [x] §7.5's first-run explanation screen built, `setCancelable(false)`,
      both outcomes persisted explicitly
- [x] §7.1's in-app export/share gap - already addressed separately
      (§17/§18), not part of this ask
- [x] Executable/reviewed verification: brace/paren balance --
      `ScreenRecordingController.java` 89/89 braces, 381/381 parens;
      `PermissionsActivity.java` 83/83, 465/465;
      `MainActivity.java` 132/132, 571/571 (all after the double-launch
      fix) -- plus `python3 -m py_compile drive_monitor.py` re-confirmed
      clean (Python side untouched) and cross-referenced
      `EXTRA_AUTO_REQUEST_RECORDING_CONSENT`/`hasEverBeenConfigured`/
      `isDefaultPromptShown`/`setDefaultPromptShown` across every call
      site
- [ ] Driver confirms in real use: the explanation appears on first
      launch for a never-configured install, "Enable" leads straight to
      the real OS consent dialog, "Not Now" leaves recording off and
      never asks again, and an EXISTING driver who already has an
      opinion about this toggle (on or off) never sees the prompt at
      all
- [ ] Driver sign-off

## 9. Driver-reported (2026-09-02, CRITICAL): real crash loop, confirmed by a real diagnostic log

### 9.1 Real evidence

A real diagnostic log (`dasher_monitor_full_history16.txt`) showed the
exact same crash three times, at the exact same line, each one killing
the app process outright:

```
java.lang.SecurityException: Starting FGS with type mediaProjection
callerApp=... targetSDK=34 requires permissions: all of the permissions
allOf=true [android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION]
any of the permissions allOf=false [android.permission.CAPTURE_VIDEO_OUTPUT,
android:project_media]
	at android.app.Service.startForeground(Service.java:863)
	at com.drivingefficiency.app.TripForegroundService.startForegroundWithRecording(...)
	at com.drivingefficiency.app.TripForegroundService.startTracking(...)
	at com.drivingefficiency.app.TripForegroundService.onStartCommand(...)
```

The log shows a genuine crash LOOP: the first trip (with recording)
completed successfully (a real 944MB recording). The moment that trip
ended and monitoring auto-restarted for the next one, it crashed. The
app process (or the watchdog) restarted it; the toggle was still on,
the same underlying condition still held, and it crashed again --
twice more, once only 5 seconds after the previous restart.

### 9.2 Root cause, confirmed against the real crash trace

`FOREGROUND_SERVICE_MEDIA_PROJECTION` IS already declared in the
manifest (`<uses-permission>`) -- this is not a missing-declaration
bug. The real issue: Android's `mediaProjection` foreground-service-
type validation requires the calling process to currently hold a LIVE
`MediaProjection` grant, independent of the manifest declaration. This
codebase's own `ScreenRecordingController` class doc already flagged
this as a real, "UNCONFIRMED on a real device" risk -- a MediaProjection
consent token is very likely single-use, not safely reusable for a
second trip within the same process. What wasn't anticipated: a stale/
consumed token doesn't just make `getMediaProjection()` return null
(handled gracefully) -- it can make Android reject the foreground-
service-TYPE DECLARATION ITSELF, and
`TripForegroundService.startForegroundWithRecording()` (added in the
`docs/permissions_screen_crash/PRD.md` fix) was being called
UNCONDITIONALLY whenever the recording toggle was on, before this
class ever got a chance to check whether a live projection actually
existed. Worse: this was true even of the OLD (pre-that-fix) code too
-- `isEnabled()` only checks the persisted toggle preference, never
whether a valid consent is actually currently held, so the underlying
race existed either way; the crash simply became visible once the
type was requested explicitly.

### 9.3 Fix

Split `ScreenRecordingController.start()` into two phases, matching
Android's own documented order (`getMediaProjection()` ->
`startForeground(..., MEDIA_PROJECTION)` -> `createVirtualDisplay()`):

- `acquireProjection(Service)` (new): checks the toggle, checks
  `hasPendingConsent()`, and calls `getMediaProjection()` -- all of
  which can fail cleanly (returns false, sets `lastFailureReason`)
  while the service is still only in its plain location-type
  foreground state, before the mediaProjection type is ever requested.
- `beginCapture(Service)` (new): the rest of the old `start()` --
  `MediaRecorder` setup and `createVirtualDisplay()`. Only ever called
  after `acquireProjection()` has already succeeded AND the caller has
  already promoted to the mediaProjection foreground type.
- `TripForegroundService.startTracking()`: now calls
  `acquireProjection()` FIRST; only if that succeeds does it call
  `startForegroundWithRecording()` (promoting the type) and THEN
  `beginCapture()`. If `acquireProjection()` fails for any reason
  (toggle off, no consent, stale/consumed token), the mediaProjection
  type is never requested at all -- the existing failure-logging/alert
  branches (unchanged) still fire with an accurate reason, and the
  service stays safely in its already-active location-only state.

### 9.4 Verification

Brace/paren balance confirmed (`ScreenRecordingController.java` 47/47,
184/184; `TripForegroundService.java` 172/172, 765/765) after all
edits. Verified by code review: confirmed every existing call site of
the old `start()` method was updated (only one real call site
existed), confirmed the failure-branch logging/alert logic is
unchanged in meaning (still distinguishes "no consent held" from "held
but failed"), and confirmed `beginCapture()`'s internals are byte-for-
byte the same as the old `start()` method's second half, just moved,
not altered. Not verified on-device -- no Android emulator/device
available in this environment, and this is exactly the class of bug
that only reveals itself against real Android platform enforcement,
which is precisely how the ORIGINAL bug was found (a real driver log),
not from code review alone.

## 10. Success criteria for §9

- [x] Root cause confirmed against the real crash trace (three
      identical occurrences, same line, same exception)
- [x] `ScreenRecordingController.acquireProjection()`/`beginCapture()`
      implemented, matching Android's documented
      getMediaProjection -> startForeground -> createVirtualDisplay order
- [x] `TripForegroundService.startTracking()` updated to the two-phase
      call, mediaProjection type never requested unless a live
      projection was already confirmed
- [x] Existing failure-logging/alert behavior preserved (diff-reviewed)
- [ ] Driver confirms a second/third trip in the same session with
      recording enabled no longer crashes.
- [ ] Driver sign-off.

## 11. Driver-asked (2026-09-02): "fix the crash-recovery gap so recordings finalize on next launch"

Follow-up to a plain question the driver asked right after §9/§10 shipped:
"what happens to the video in the event of a crash." Answered directly
first (a graceful `onDestroy()` teardown finalizes the file via
`MediaRecorder.stop()`; an actual process crash skips that entirely,
leaving the file's `moov` index box - the part that makes an MP4
playable at all - never written, so the file exists but is unplayable
in a standard player), then the driver asked to fix it.

### 11.1 Why "repair the file after the fact" was rejected

The literal ask ("finalize on next launch") most naturally reads as
"reconstruct the broken file into a playable one after a crash."
Investigated this first and rejected it: genuinely repairing an
MP4 with a missing `moov` box means reconstructing the frame-offset/
timing tables (`stco`/`stts`/`stsz`) from the raw `mdat` data that's
already on disk - a hard, codec/OEM/Android-version-specific problem
(this is what dedicated tools like `untrunc` exist to do, imperfectly,
for exactly this failure mode). With no Android SDK/emulator/device in
this environment to verify a repair actually produces a valid,
playable file, shipping one would risk the worst outcome this PRD's own
audits have repeatedly flagged: a **confidently wrong result** - a
renamed/"recovered" file that LOOKS fixed but still doesn't play,
misleading the driver into thinking footage was saved when it wasn't.
Not attempted, for the same reason §4a's premortem and the third-pass
audit (see PROGRESS.md) never shipped anything unverifiable dressed up
as working.

### 11.2 What was fixed instead: bound the loss, then clean up what's left

Two real, implementable, independently-reasoned-about changes:

1. **Segmented recording** (the actual crash-safety fix): a trip's
   recording is no longer one file capturing the whole trip. It's split
   into 5-minute chunks (`ScreenRecordingController.SEGMENT_DURATION_MS`),
   each independently finalized via `MediaRecorder.setMaxDuration()` +
   `setOnInfoListener(MEDIA_RECORDER_INFO_MAX_DURATION_REACHED)` -
   Android's own documented pattern for bounded-duration recording, not
   a novel mechanism. On each rotation, the finishing segment is
   `stop()`'d (writing its `moov` box) and a new `MediaRecorder` takes
   over the SAME `VirtualDisplay`'s output via `VirtualDisplay.setSurface()`
   (no need to re-acquire the `MediaProjection` grant mid-trip - it's
   still the one obtained at trip start). A crash now loses at most one
   segment (≤5 minutes) instead of however much of the trip had been
   recorded so far. Most real trips still run under 5 minutes and
   produce exactly one file, same filename as before this fix
   (`trip_<timestamp>.mp4`); longer trips get `_part2`, `_part3`, etc.
2. **Startup detection and cleanup** (the literal "next launch" ask,
   honestly scoped): `TripForegroundService.onCreate()` - the first
   point this app's own code runs again after any crash - now calls
   `ScreenRecordingController.cleanUpOrphanedSegments()`, which scans the
   recordings folder for `.mp4` files missing a top-level `moov` box (a
   simple ISO-BMFF container box scan - checking for the box's
   PRESENCE, not parsing or reconstructing its contents, which is a much
   simpler and fully verifiable-by-code-review operation) and deletes
   them, logging how many. This is honestly framed as cleanup/detection,
   not repair: an orphaned segment from a crash is gone either way: the
   fix is that it no longer sits there silently looking like a normal
   recording until the driver tries to open it and finds it broken.

### 11.3 Verification

Same disclosed limitation as the rest of this PRD - no Android SDK/
emulator/device available, so this is code review plus static checks,
not a live repro:

- Brace/paren balance: `ScreenRecordingController.java` 74/74 braces,
  290/290 parens. `TripForegroundService.java` 173/173 braces, 774/774
  parens (both fully balanced after this change).
- Traced every existing caller of `ScreenRecordingController` (`isRecording()`,
  `currentFile()`, `stop()`, `lastStopWasLikelyEmpty()`, `lastFailureReason()`)
  - all still compile against the unchanged public method signatures;
    only `beginCapture()`'s internals changed.
- Found and fixed a real gap while reviewing `rotateSegment()`'s own
  failure path before considering this done: if starting the NEXT
  segment failed partway through (`prepare()`/`start()` throwing), the
  half-created `MediaRecorder` for that segment was never released -
  a leaked native codec instance. Split the method's try/catch into two
  separate blocks (finalize-previous vs. start-next) so a failure in the
  second explicitly releases whatever was created before falling back to
  `releaseInternal()`.
- `currentFile()`'s meaning changed (now "the current/last SEGMENT," not
  "the whole trip's one file") - traced its one call site
  (`TripForegroundService`'s stop-tracking log line) and corrected the
  log wording from implying a whole-trip byte count to explicitly saying
  "final segment," rather than leaving a now-inaccurate log message.
- Confirmed `moov` is always a top-level (never nested) ISO-BMFF box, so
  a shallow top-level scan is suffient to detect its presence/absence -
  no deeper parsing needed for detection purposes.
- Confirmed `cleanUpOrphanedSegments()` can only ever run before any
  `beginCapture()` call in a given process's lifetime (it's called once,
  in `onCreate()`, before any trip has started) - no risk of it deleting
  a currently-in-progress recording out from under an active trip.

## 12. Success criteria for §11

- [x] Root cause of "video lost on crash" identified and explained
      (missing `moov` box on an unclean process death)
- [x] Full binary repair investigated and explicitly rejected, with
      reasoning, rather than silently skipped
- [x] Segmented recording implemented - crash loss bounded to one
      segment (≤5 min) instead of the whole trip
- [x] Startup orphan detection/cleanup implemented and logged
- [x] Existing callers/behavior re-verified against the changed
      `beginCapture()` internals; one real resource-leak gap found and
      fixed during that review
- [ ] Driver confirms: a multi-segment trip (>5 min with recording on)
      produces multiple playable files, and a forced crash mid-trip (or
      the next real crash, whichever comes first) leaves only the
      current segment missing rather than the whole trip.
- [ ] Driver sign-off.

## 13. Driver-reported (2026-09-03, CRITICAL, self-correcting a prior fix): recording never actually started, on ANY attempt, including a freshly granted first-use consent

A third real diagnostic log (`dasher_monitor_full_history17.txt`) from
this same driver, covering a fresh install/rebuild (the log's own
`App installed 4 min ago` line on its very first entry), showed screen
recording failing on its ONE genuine attempt with consent actually
held - and that attempt was the very first, on a token that had just
been granted moments earlier, never previously used. Every other
attempt in the multi-day log simply had no consent held at all (process
restarts, expected/already-alerted behavior). Searched the whole log
for "Started recording" - zero matches, anywhere, across several days
of use.

### 13.1 What the log showed

```
[2026-09-02 22:59:59] SCREEN_RECORDING: Enabled and consent held, but
starting the recorder failed: SecurityException: Media projections
require a foreground service of type
ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
```

### 13.2 Root cause: §9/§10's own fix had the call order backwards

§9's fix (2026-09-02) reordered `acquireProjection()` (calling
`getMediaProjection()`) to run BEFORE `startForegroundWithRecording()`
(the foreground-service type declaration), on the documented
assumption that `getMediaProjection()` "has no foreground-service-type
precondition." That assumption is what this new log disproves.

Tracing the exact exception through the code (`beginCapture()`'s
`createVirtualDisplay()` is the only call that can throw this specific
message, and the log shows no crash/uncaught exception at the point
`startForegroundWithRecording()` itself runs - confirming that call
succeeded without throwing) shows: `getMediaProjection()` itself did
NOT reject the call (no exception logged from `acquireProjection()`,
which sets its own distinct `lastFailureReason` text on failure) - it
returned a `MediaProjection` object without complaint. That object was
then rejected LATER, when `beginCapture()` actually tried to use it via
`createVirtualDisplay()`, with exactly this message.

This matches Android's real (and, on reflection, better-documented)
requirement for API 34+: `startForeground(..., FOREGROUND_SERVICE_TYPE_
MEDIA_PROJECTION)` must be called BEFORE `MediaProjectionManager
#getMediaProjection()`, not after. A `MediaProjection` obtained before
its foreground-service type is live is accepted at acquisition time but
invalid at actual use. §9's fix had this backwards - it correctly
stopped the ORIGINAL crash (a stale-token type-declaration rejection,
thrown as an uncaught `SecurityException` from `startForeground()`
itself), but as an unnoticed side effect of the reordering, broke
recording entirely: it went from "crashes on a stale second-trip token"
to "never works, on any token, including a fresh first-use one" - and
because the new failure mode fails gracefully (logged, not crashed),
nothing in §9/§10's own verification (which checked "does it still
crash" via code review and brace/paren balance, not "does it actually
produce a working recording," since there's no device here to check
that) caught it.

### 13.3 Fix: restore the correct order, but keep the original crash fixed too

The order is reversed back (type declaration first, then acquire
projection, then begin capture) - but NOT simply back to the
pre-§9 code, because that WOULD reintroduce the original crash. The
real, durable fix is that `startForegroundWithRecording()` (the call
that used to throw uncaught) is now itself wrapped in a `try/catch
(SecurityException)`, changed from `void` to `boolean`, so a stale
token is caught right where the original crash happened - independent
of ordering - and `TripForegroundService.startTracking()` calls it
FIRST, only proceeding to `acquireProjection()` -> `beginCapture()` if
it succeeds, with a fallback to `startForegroundLocationOnly()` if
recording setup fails after the type was promoted (so the service's
declared type doesn't stay mismatched with reality for the rest of a
trip that isn't actually recording).

Both `ScreenRecordingController`'s class doc and `acquireProjection()`'s
own doc were rewritten to describe this corrected order and both real
diagnostic logs (2026-09-02 crash, 2026-09-03 silent-failure) that
shaped it, rather than leaving the previous (now-wrong) reasoning in
place.

### 13.4 Honest note on confidence

This is the THIRD real-diagnostic-log-driven iteration on this exact
subsystem this session (§9/§10 fixed a crash; §13 here fixes a
regression that fix silently introduced). No Android SDK/emulator/
device exists in this environment at any point across all three passes
- every conclusion here is inferred from real exception text and log
sequencing, not from running the code. The previous fix's own
"CONFIRMED on a real device" language turned out to be about the
CRASH being fixed, not about recording actually WORKING - a distinction
that matters and that this PRD is now explicit about: "doesn't crash"
and "actually works" are being verified as two separate claims from
here on, not one. §14's checklist reflects that a driver test
confirming an actual PLAYABLE recording file is produced is the only
thing that closes this out - not just "no crash observed."

## 14. Success criteria for §13

- [x] Root cause traced to the specific call (`createVirtualDisplay()`
      inside `beginCapture()`) and the specific ordering requirement
      (type declaration before `getMediaProjection()`) using the real
      exception text and log sequencing
- [x] `startForegroundWithRecording()` changed to `boolean`, wrapped in
      its own `try/catch (SecurityException)` - the original crash stays
      fixed independent of call order
- [x] Call order in `startTracking()` reversed: type declaration ->
      `acquireProjection()` -> `beginCapture()`, with a location-only
      fallback if recording setup fails after the type was promoted
- [x] `ScreenRecordingController`'s class doc and `acquireProjection()`'s
      doc corrected to match, including both real diagnostic logs that
      shaped the current order
- [x] Brace/paren balance re-verified after this change:
      `TripForegroundService.java` 188/188 braces, 853/853 parens;
      `ScreenRecordingController.java` 74/74 braces, 294/294 parens
- [ ] Driver confirms an ACTUAL PLAYABLE recording file is produced on
      the next trip with recording enabled - not just "no crash," which
      is the exact claim §9/§10 made that turned out to be insufficient
- [ ] Driver sign-off.

## 15. Driver-requested (2026-09-09): verify a playable file exists after each delivery, vibrate an alert otherwise

Direct follow-up to an audit (asked "does the code verify there is a
playable file") that found a real, confirmed gap: `hasMoovBox()`
(§11/§12) only ever ran once, at app startup, scanning leftover files
from a PREVIOUS crashed session - nothing checked a NORMAL recording
(one where `MediaRecorder.stop()` didn't throw) actually produced a
playable file. §13.4 had already disclosed "doesn't crash" and
"actually plays" were being wrongly conflated; this closes that gap for
real instead of continuing to disclose it.

**A real design wrinkle surfaced while scoping this**: screen recording
is not, and has never been, scoped one-file-per-delivery.
`startTracking()`/`stopTracking()` bound one whole MONITORING SESSION
(driver taps Start/Stop Monitoring), which can span many deliveries;
recording inside that session is segmented every `SEGMENT_DURATION_MS`
(5 minutes) regardless of delivery boundaries (§4a P7's own disclosed
gap: no link between a recording and the trip it belongs to). So
"verify after a delivery" cannot mean "check that one delivery's file"
- there often isn't one. What it CAN honestly mean, and what this
implements: at each delivery's completion, verify every segment that
has ACTUALLY finished (fully closed) since the previous delivery ended,
skipping whichever segment is still open for writing (a mid-write MP4
legitimately has no `moov` box yet - checking it would always report
broken, which isn't a real defect).

### 15.1 A real check, not `hasMoovBox()` again

`hasMoovBox()` only proves the container was closed - it doesn't prove
the video frames inside are decodable. New `ScreenRecordingController.
isPlayable(File)` uses `MediaMetadataRetriever.setDataSource()` +
`extractMetadata(METADATA_KEY_DURATION)` - Android's own media
framework actually attempting to open the file, the same class of
operation a real player performs, not a heuristic. A genuinely corrupt
file fails `setDataSource()`/`extractMetadata()` here the same way it
would fail to open in a video player. Returns false for a missing/empty
file, one that throws opening, or one that opens but reports zero
duration.

### 15.2 Tracking which segments to check

`ScreenRecordingController` gained `segmentFiles` (every segment this
recording SESSION has produced, appended in `beginCapture()` and
`rotateSegment()`, cleared at the start of each new session) and
`verifiedSegmentCount` (how many have already been checked, so a
session with many deliveries never re-verifies an already-confirmed
segment). New `verifyNewlyFinishedSegments()`: examines
`segmentFiles[verifiedSegmentCount .. finalizedCount)`, where
`finalizedCount` is `segmentFiles.size() - 1` while still recording
(the last entry is presumably still open) or `segmentFiles.size()` once
recording has actually stopped (the last entry is now finalized too).
Returns whichever of those failed `isPlayable()`.

### 15.3 Where it's called

`TripForegroundService.verifyScreenRecordingAfterDelivery()`, called
from BOTH places `notifyRateThisDelivery()` already fires (the manual
-stop path's own guarded call, and the automatic `TRIP_ACTIVE -> IDLE`
transition) - reusing their exact existing guard conditions rather than
re-deriving "did a delivery genuinely just complete," since that dedup
logic already had one real bug found and fixed (the auto-pause
double-prompt, see PROGRESS.md) that this piggybacks on rather than
risks re-introducing independently. No-ops entirely if recording isn't
enabled. If recording is enabled but not actually `isRecording()` at
that moment (consent lost mid-session, setup failed) - itself a real
integrity gap, distinct from "a file exists but is corrupt," and just
as worth the driver knowing - alerts immediately with
`lastFailureReason()`'s text. Otherwise checks `verifyNewlyFinishedSegments()`
and alerts on anything broken.

A second call, directly in `stopTracking()`'s existing recording-stop
block (right after `screenRecordingController.stop()`), catches the
ONE segment that can only become checkable once the session truly ends
- the final segment, only finalized by that `stop()` call itself.

### 15.4 The alert itself

New `TripForegroundService.raiseRecordingVerificationFailedAlert(reason)`
- same "explain why, don't just buzz with no context" shape as the
existing `raisePermissionRevokedAlert` immediately above it in the
file, logging to the visible diagnostic log AND raising a
high-priority notification naming exactly which segment(s) failed or
why recording wasn't active. Deliberately does NOT reuse
`startPermissionAlertVibration()`'s repeating/cancellable vibration
state: that pattern exists because a permission can come back
mid-vibration (a live condition to poll and stop early for); a
recording segment that already finished broken has nothing to
self-heal, so a single distinctive pattern is the honest shape, not an
indefinite buzz with no cancellation condition to ever wait for. New
`HapticFeedback.vibrateRecordingVerificationFailed()` - three long
buzzes played once through, distinct from every other pattern already
in that file (the two-pulse/one-pulse/one-long Smart Score cues, the
permission alert's own repeating `{0,800,400}`).

### 15.5 Verification

Same disclosed limitation as the rest of this PRD - no Android
SDK/emulator/device in this environment, so code review plus static
checks, not a live repro of an actual corrupt file being caught:

- Brace/paren balance: `ScreenRecordingController.java` 84/84 braces,
  344/344 parens; `HapticFeedback.java` 17/17, 28/28;
  `TripForegroundService.java` 217/217, 1009/1009 (all balanced after
  every edit in this section).
- `python3 -m py_compile drive_monitor.py` -- unaffected by this
  change, re-confirmed clean anyway (nothing here touches the Python
  side).
- Traced every call site of `verifyScreenRecordingAfterDelivery()` (2,
  matching `notifyRateThisDelivery()`'s own 2) and of
  `verifyNewlyFinishedSegments()` (those 2, plus the one direct call in
  `stopTracking()`) - confirmed the mid-session calls never touch a
  still-open segment (gated on `isRecording()` inside the shared
  helper) and the final `stopTracking()` call only ever examines the
  one segment the mid-session calls structurally couldn't have reached
  yet.
- Confirmed `reportBrokenRecordingSegments`' parameter type
  (`java.util.List<java.io.File>`) matches `verifyNewlyFinishedSegments()`'s
  return type (`java.util.List<File>`, `File` being `java.io.File` via
  that file's own import) - same type, different qualification style
  per each file's own existing convention, not a mismatch.
- Confirmed no `AndroidManifest.xml` change is needed: notification
  channels are created at runtime, not declared in the manifest, and
  `VIBRATE` was already declared for `HapticFeedback`'s existing use.

**Not done, and can't be from here**: on-device confirmation that
`isPlayable()` actually rejects a real corrupted file (or accepts a
real good one) - no Android SDK/emulator/device in this environment to
produce either kind of file to test against. This is honestly the same
class of gap as every other claim in this PRD: reasoned from Android's
documented `MediaMetadataRetriever` contract, not watched working.

## 16. Success criteria for §15

- [x] Real playability check added (`isPlayable()`, via
      `MediaMetadataRetriever`) - distinct from and stronger than
      `hasMoovBox()`'s container-only scan
- [x] Per-segment verification tracked across a whole session
      (`segmentFiles`/`verifiedSegmentCount`), never re-checking an
      already-confirmed segment, never checking a still-open one
- [x] Verification wired to both real per-delivery completion points
      (reusing `notifyRateThisDelivery()`'s own dedup guards) plus
      session-end for the final segment
- [x] Vibration alert on any verification failure, distinct pattern
      from every existing one, plus a notification naming the actual
      reason (not a silent or unexplained buzz)
- [x] Brace/paren balance confirmed on every touched file
- [ ] Driver confirms: a normal delivery with recording enabled
      produces no alert; a deliberately-corrupted recording file (or a
      real corruption, if one occurs) DOES trigger the vibration and
      notification
- [ ] Driver sign-off.

## 17. Driver-requested (2026-09-09): a way to actually access a recording

Direct follow-up to a plain question ("can I access the video from the
app") -- answered honestly first: no, §7.1's disclosed gap ("no in-app
player, no export/share button") was still true, unchanged since it was
first written. Driver then asked to add a share button.

**Design**: mirrors `DiagnosticsActivity.showDiagnosticArchives()`'s own
list-then-act shape exactly, rather than inventing a new pattern - tap a
filename, act on that one file. A bulk "share everything at once" was
considered and rejected: a driver reviewing footage almost always wants
ONE specific delivery's segment, not every recording on the device
bundled into one share action.

New `ScreenRecordingController.listRecordingsNewestFirst()` - the actual
file array, not just the count/total-size summary `recordingsCount()`/
`recordingsTotalSizeBytes()` already provided. Sorted by `lastModified()`
descending rather than filename, since a segment's `_partN` suffix would
otherwise sort `_part10` before `_part2` alphabetically.

New `PermissionsActivity.showRecordingsList()` / `shareRecording(File)`:
lists every recording (name + size) in a dialog; tapping one builds a
`content://` URI via the SAME `FileProvider` authority
(`androidx.core.content.FileProvider`, `<applicationId>.fileprovider`)
already declared in `AndroidManifest.xml` for diagnostic-log export, and
launches `Intent.ACTION_SEND` with `video/mp4` through the standard
share sheet. No `file_paths.xml` change needed:
`external-files-path name="external_exports" path="."` already covers
the entire external-files root, and `ScreenRecordings/` is a direct
subdirectory of it (`recordingsDir()`'s own definition) -- confirmed by
reading that file rather than assumed.

New button: "View/Share Recordings," placed above the existing "Delete
All Recordings" (see it before you can delete it).

### Verification

Same disclosed limitation as the rest of this PRD - no Android SDK
/emulator/device in this environment, so code review plus static
checks, not an actual share-sheet launch or confirmed-openable file:

- Brace/paren balance: `ScreenRecordingController.java` 86/86 braces,
  358/358 parens; `PermissionsActivity.java` 81/81, 452/452.
- XML well-formedness confirmed on `activity_permissions.xml` and
  `strings.xml`.
- `R.id.viewRecordingsButton` / `@string/view_recordings` cross-checked
  between the new Java code, the layout, and `strings.xml` - every
  reference resolves.
- `python3 -m py_compile drive_monitor.py` - unaffected, re-confirmed
  clean anyway.
- Confirmed `getPackageName() + ".fileprovider"` matches the manifest's
  declared authority exactly (`com.drivingefficiency.app.fileprovider`),
  same string `DiagnosticsActivity` already uses successfully.

**Not done, and can't be from here**: on-device confirmation that
tapping a recording actually opens the Android share sheet, and that a
real video player can actually open the resulting file - no emulator
/device available, same limitation as every claim in this PRD.

## 18. Success criteria for §17

- [x] `listRecordingsNewestFirst()` added, real file list (not just
      count/size)
- [x] "View/Share Recordings" button added, lists recordings
      newest-first
- [x] Tapping a recording launches the share sheet via the existing
      FileProvider authority, `video/mp4` MIME type
- [x] No `file_paths.xml` change needed - confirmed the existing
      external-files-root entry already covers `ScreenRecordings/`
- [x] Brace/paren balance and XML well-formedness confirmed on every
      touched file
- [ ] Driver confirms: tapping a recording opens a real share sheet,
      and the shared file actually opens/plays in a chosen video player
- [ ] Driver sign-off.

## 19. Driver-requested (2026-09-09): "build into the log for these check lists if it is necessary"

The field-test checklist built for this app (an interactive artifact
covering every feature from Sections 1-18 plus the zone-map/routing
work) has a recurring shape to its "how do I know it worked" column:
several items could only be judged by *absence* - no crash, no vibration
alert, no error Toast - which can't tell "this ran and passed" apart
from "this code path never ran at all." This section closes that gap at
every checklist trigger point that had no visible trace before, using
the same judgment standard as everywhere else in this PRD: only where a
driver genuinely couldn't otherwise tell from the app's own log.

### 19.1 What was silent before

- `ParkingZoneMapActivity` and `CustomerZoneMapActivity` had **no**
  `logDiagnostic` calls at all - not the empty-state case, not a
  successful zone load, not a zone tap.
- The GENERAL-mode routing-suggestion suppression (§ hotspot/home
  routing PRD) had a comment (`// Nothing to suggest...`) but no log
  line - no way to tell "correctly suppressed" from "this whole block
  didn't run."
- §15's after-delivery and end-of-session recording verification only
  ever logged the *broken* case (`reportBrokenRecordingSegments`) - a
  clean pass produced no line at all, so "no alert" couldn't be told
  apart from "verification never ran."
- `PermissionsActivity.shareRecording()` only ever showed a Toast on
  failure and opened the OS share sheet on success - neither is in this
  app's own diagnostic log.

### 19.2 A genuine bug found by this audit, not just a logging gap

Auditing `ScreenRecordingController`'s `StopListener` callback (fired
from the one place all teardown paths converge, `releaseInternal()`)
for what it actually told the caller turned up a real, previously
unknown silent failure: **if recording stopped mid-trip for any reason
other than the caller's own explicit `stop()` call - a segment-rotation
failure (`rotateSegment()`'s two catch blocks) or Android itself
revoking the grant externally (the driver tapping the system "Stop"
notification, or the grant expiring) - nothing surfaced anywhere,
not even to logcat in the external-revoke case.** A driver would just
see recording quietly stop mid-delivery with zero explanation, and
nothing in this app's own log would show it happened.

Fixed by:
- `MediaProjection.Callback.onStop()` now sets `lastFailureReason`
  before tearing down, instead of leaving it whatever a previous,
  unrelated failure had last set it to.
- `StopListener.onRecordingStopped()` changed from a no-argument
  callback to `onRecordingStopped(String unexpectedReason)` -
  `null` for a normal caller-initiated `stop()`, non-null for anything
  else.
- New `expectingStop` field, set for the duration of `stop()`'s own
  body, lets `releaseInternal()` tell "this was the caller's own
  stop() call" apart from every other teardown path, without adding a
  flag at each of `rotateSegment()`'s internal call sites individually
  (the same "one convergence point, not three separately-maintained
  call sites" reasoning already documented for `StopListener` itself).
- New `wasEverRecordingThisSession` field, set only once
  `mediaRecorder.start()` has actually succeeded in `beginCapture()`
  and reset at the top of each new session, so an initial acquire/
  begin-capture failure (already logged explicitly by
  `TripForegroundService.startTracking()`'s own check) isn't ALSO
  reported through this callback - that would have duplicated one real
  failure into two confusing log lines.
- `TripForegroundService`'s `ScreenRecordingController` instantiation
  now logs `"Recording stopped unexpectedly mid-trip: " + unexpectedReason`
  under the `SCREEN_RECORDING` category whenever `unexpectedReason` is
  non-null.

### 19.3 Positive-confirmation logging added

- `ParkingZoneMapActivity`/`CustomerZoneMapActivity`: log the
  empty-state case, the loaded-zone-count case, and each zone tap
  (`PARKING_ZONE_MAP`/`CUSTOMER_ZONE_MAP` categories), via a new
  `logDiagnostic` wrapper each Activity previously lacked entirely.
- `TripForegroundService`'s GENERAL-mode branch now logs
  `"Skipped hotspot/home/sweet-spot suggestion -- completed trip was
  GENERAL mode, not Dasher"` under `ROUTING_SUGGESTION`.
- `ScreenRecordingController.verifyNewlyFinishedSegments()` gained a
  `lastVerifiedSegmentCount()` getter (segments actually examined this
  call, as opposed to "nothing new to check yet" - both return an empty
  broken list, but only the first is worth a positive log line). Both
  call sites (after-delivery, end-of-session) now log
  `"Verified N recording segment(s) from this delivery/session's end --
  playable."` under `SCREEN_RECORDING` when the check ran and found
  nothing broken.
- `PermissionsActivity.shareRecording()` now logs
  `"Opened share sheet for recording: <name>"` on success and
  `"Could not share recording <name> -- <error>"` on the
  `FileProvider` failure path, both under `SCREEN_RECORDING`.

### 19.4 Verification

- Brace/paren balance confirmed on every touched file:
  `ScreenRecordingController.java`, `TripForegroundService.java`,
  `ParkingZoneMapActivity.java`, `CustomerZoneMapActivity.java`,
  `PermissionsActivity.java`.
- `python3 -m py_compile app/src/main/python/drive_monitor.py` -
  unaffected (Python untouched), re-confirmed anyway per this
  repository's own convention.
- Every new field/method cross-referenced across its call sites:
  `wasEverRecordingThisSession` (declared, set false at the top of
  `beginCapture()`, set true only after `mediaRecorder.start()`
  succeeds, read in `releaseInternal()`); `lastVerifiedSegmentCount`
  (declared, reset and incremented in `verifyNewlyFinishedSegments()`,
  exposed via `lastVerifiedSegmentCount()`, read at both call sites in
  `TripForegroundService`); `StopListener`'s new signature updated at
  its one instantiation site.
- HONEST LIMIT, same as every prior section: no Android
  device/emulator available in this environment. The actual mid-trip
  external-revoke path (tapping the system "Stop" notification) and
  the actual segment-rotation-failure path could not be triggered and
  observed producing this new log line on a real device - the fix
  follows directly from reading `releaseInternal()`'s own control flow,
  not from an observed run.

## 20. Success criteria for §19

- [x] `ParkingZoneMapActivity`/`CustomerZoneMapActivity` log empty
      state, load count, and zone taps
- [x] GENERAL-mode routing-suggestion suppression now logs that it ran
- [x] Recording verification logs a positive "verified, playable" line
      on a clean pass, not just the broken case
- [x] `PermissionsActivity.shareRecording()` logs success and failure
- [x] `StopListener` now reports mid-trip unexpected stops
      (segment-rotation failure or external grant revoke) - previously
      untraced anywhere, including logcat
- [x] `wasEverRecordingThisSession` prevents double-logging the
      initial acquire/begin-capture failure case
- [x] Brace/paren balance and Python compile confirmed on every
      touched file
- [ ] Driver confirms: the field-test checklist's log-based items now
      show a real line for each, including the two previously-silent
      failure paths (would need an actual mid-trip permission-revoke
      or rotation failure to observe directly - unlikely to occur
      naturally during a normal field test)
- [ ] Driver sign-off.

## 21. Driver-asked (2026-09-11): "there don't appear to be any screen recordings, could you explain why"

A real diagnostic log (`dasher_monitor_full_history20.txt`, 6747
lines, 2026-09-09 16:54 through 2026-09-11 22:23) was attached. Every
`SCREEN_RECORDING`-tagged line in it, in order:

1. `16:54:16` Consent granted -- recording will start with the next trip
2. `17:02:22` Enabled and consent held, but starting the recorder
   failed: `SecurityException: Don't re-use the resultData to
   retrieve the same projection instance, and don't use a token that
   has timed out. ...` (the FIRST-EVER attempt to use this token --
   see §21.3)
3. `17:51:26` Removed 1 unfinalized recording segment(s) left over
   from a previous crash (a real process crash/OEM kill happened
   ~17:50 -- `DEVICE: manufacturer=OPPO model=CPH2591
   knownAggressiveOem=true` -- between the two log lines above and
   this one)
4. `22:50:36`, `22:55:16`, 09-09 `23:16:34`, `23:20:56`, 09-10
   `08:19:47`, `09:54:35`, `10:49:05`, 09-11 `21:59:23`, `22:23:49` --
   nine separate trips, every one: "Enabled, but no consent held
   (process likely restarted since it was last granted) - this trip
   will not be recorded"
5. `09:54:50`, `21:59:38` -- two "Periodic capture health check: NOT
   running" lines (the §19 fix, already deployed and working exactly
   as designed -- it correctly detected and logged the ongoing
   failure, it just couldn't fix it)

**Net result across this entire 2+ day, cross-day log: zero
successful recordings.** Two distinct, separately-confirmed causes:

### 21.1 Root cause A (accounts for 9 of the 11 failed trips): the fix notification had no tap action

The consent grant is deliberately held only in memory (see this file's
class-level design in `ScreenRecordingController` -- correct behavior,
matching the real OS grant's own lifetime) and is cleared by any
process restart. The 17:50 OEM kill cleared it once, correctly, per
design. From then on the ONLY way to recover is the driver manually
reopening Setup and toggling the already-"on" switch off then back on
(confirmed in `PermissionsActivity.refreshScreenRecordingStatus()`,
which already prints exactly that instruction -- but only to a driver
who is already looking at that screen).

The actual bug: `TripForegroundService.raisePermissionRevokedAlert()`
built every "Trip Capture revoked" notification with no
`setContentIntent` at all. Confirmed directly in the source --
`Notification.Builder(...).setContentTitle(...).setContentText(...)
...build()`, nothing wiring a `PendingIntent`. Tapping it did nothing.
Across 2+ real days of driving, this alert fired on every single trip
start and, with no tap action and no other cue that the switch itself
needs re-toggling (not just leaving Setup open), was apparently always
dismissed rather than acted on.

**Fix**: `raisePermissionRevokedAlert()` now attaches a
`setContentIntent` specifically when `permissionName` is `"Trip
Capture"`, launching `PermissionsActivity` with a new extra,
`EXTRA_AUTO_REREQUEST_RECORDING_CONSENT`. `PermissionsActivity` was
already able to auto-fire the real OS consent dialog on open for the
first-run case (`EXTRA_AUTO_REQUEST_RECORDING_CONSENT`, driven by
`setChecked(true)` on a real false -> true transition) -- that
mechanism doesn't fire here, since the switch is already checked and
`setChecked(true)` on an unchanged value never calls the listener. The
new extra instead calls `requestScreenRecordingConsent()` directly
when the switch is checked but no consent is currently held, so one
tap on the notification re-opens the exact real OS consent dialog with
no extra navigation.

### 21.2 Root cause B (accounts for the 09-09 22:50 restart itself): a real OEM background kill

`knownAggressiveOem=true` (OPPO), confirmed by
`SCREEN_RECORDING: Removed 1 unfinalized recording segment(s) left
over from a previous crash` at 17:51:26 -- this app's process was
killed by the OS roughly an hour after the driver finished Setup, well
before any trip had successfully recorded anything. This class of kill
is already the documented, known-unsolvable-at-the-app-level subject
of `docs/watchdog_reliability/PRD.md` (the watchdog there recovers GPS
monitoring after exactly this kind of kill -- it was never meant to,
and can't, preserve an in-memory OS consent grant across it). Not a
new finding, not something §21.1's fix changes -- restated here only
because it's the event that triggered the specific 2+ day failure
window this section investigates.

### 21.3 Separate, NOT fixed: the very first attempt, on a token that had never been used before, also failed

Line 2 above is NOT explained by §21.1 or §21.2 -- it happened at
17:02:22, eight minutes after consent was granted at 16:54:16, in the
SAME process (`hasPendingConsent()` returned true; no restart occurred
until ~17:50, confirmed by there being only one `SERVICE: onCreate()`
line before this point). This was the first and only attempted use of
that token. The exception text covers two distinct Android-side
rejections in one message -- reuse of already-consumed `resultData`,
or a token that "has timed out" -- and reuse is ruled out here since
nothing else in this codebase calls `getMediaProjection()` more than
once per granted token (confirmed reading `acquireProjection()`).
That leaves timeout: the ~8 minutes between the driver finishing the
consent dialog and Start Monitoring actually being tapped (spent
working through the OTHER permissions on the same Setup screen --
location, overlay, notification access, accessibility, battery
exemption, visible in the surrounding log) appears to have been enough
for Android to invalidate the grant on its own, independent of any
restart.

This is a genuine constraint of the platform API, not a bug in this
codebase's own logic, and this app's whole "grant once during Setup,
consume whenever the next trip happens to start" design is
structurally exposed to it -- worse, several real trips in this exact
log start from a background trigger with no Activity available at all
(`DRIVING_DETECTION: Auto-started monitoring from detected driving
motion (Dasher was never opened)` at 22:50:36), so consent flatly
cannot be requested at the moment those trips start; it can only ever
be requested ahead of time, in Setup, exactly as today. There is no
code fix proposed for this in this PRD round -- speculatively
shortening the gap (e.g. moving the recording toggle to the end of the
Setup flow) might reduce how often the same driver hits this specific
8-minute-class window, but there is no confirmed real timeout
duration to design against (Android does not document one), and no
device available in this environment to measure it. Flagged here as a
disclosed, open limitation rather than papered over as fixed.

## 22. Success criteria for §21

- [x] `raisePermissionRevokedAlert()`'s "Trip Capture" alert carries a
      `setContentIntent` that opens `PermissionsActivity`
- [x] `PermissionsActivity` re-fires the real OS consent dialog
      directly (not via the no-op `setChecked(true)` path) when opened
      via the new extra with the switch already on and no consent held
- [x] §21.3's first-use-token-timeout finding documented as a genuine
      open platform limitation, not silently folded into "fixed"
- [ ] HONEST LIMIT, same as every prior section: no Android
      device/emulator available in this environment -- the notification
      tap flow and the re-fired consent dialog could not be triggered
      and observed on a real device, only read against Android's
      documented `PendingIntent`/`Notification.Builder` and
      `ActivityResultLauncher` behavior.
- [ ] Driver confirms: after this fix, tapping a "Trip Capture
      revoked" notification opens Setup and immediately shows the real
      OS consent dialog, and granting it makes the next trip record.
- [ ] Driver sign-off.

## 23. Driver-asked (2026-09-11): "I don't want to have to tap anything, I want it done automatically, without me having to do anything"

Direct follow-up to §21/§22: a tappable notification is still one tap.
The driver explicitly asked for zero interaction. Ground truth stated
plainly first: Android does not allow any app to silently grant itself
a screen-recording consent, ever, regardless of what other permissions
it holds -- the real OS dialog is guaranteed to appear, by design, as a
deliberate privacy boundary. This section does not remove that dialog;
it removes the driver's own need to physically answer it.

Two changes, both scoped as narrowly as possible:

**23.1 Auto-launch Setup with zero interaction.** Previously, recovery
still required the driver to tap the §21 notification themselves.
`TripForegroundService.autoLaunchPermissionsActivityForConsentRecovery()`
now fires automatically the moment "no consent held" is detected at
trip start, reusing `AppNotificationListenerService.launchDasherApp()`'s
own already-proven 3-layer Background Activity Launch workaround
verbatim (overlay-exemption + direct `startActivity()`, then a
full-screen-intent notification fallback) rather than inventing a new
one. Deliberately NOT also called from `checkTripCaptureHealth()`'s
mid-trip check -- see §23.3.

**23.2 Auto-tap the real OS dialog itself.** Once Setup opens (auto-
launched or manually), it already re-fires the real consent dialog
(§21/§22). `DasherAccessibilityService.tryAutoTapConsentDialog()` now
watches for that dialog, ONLY for a short armed window immediately
around this app's own call to `createScreenCaptureIntent()`
(`ScreenRecordingController.armConsentDialogAutoTap()` /
`isExpectingConsentDialog()`), and taps the real affirmative button on
the driver's behalf -- handling both a single confirm dialog and
Android 14+'s two-step "Entire screen vs a single app, then Start now"
flow, never tapping anything matching a cancel/deny label.

This is the one deliberate, narrow exception to this class's
documented "only ever reads Dasher's own content" rule -- scoped by
TIME (the arm/disarm window), not by package name, specifically
because the dialog's real owning package is unconfirmed across Android
versions/OEM skins and a wrong package check would silently disable
the whole thing. See `tryAutoTapConsentDialog()`'s own doc and the
updated `accessibility_service_config.xml` comment for the full
reasoning.

**23.3 Deliberately NOT automated: mid-trip drops.** Popping a
full-screen Activity over whatever the driver is looking at (a live
delivery, turn-by-turn navigation) WHILE a trip is already under way
is a real safety trade-off this round chose not to take. Auto-launch
only fires at trip start (before or right as driving begins, matching
this app's own existing risk posture for the new-offer auto-launch
feature); a drop discovered mid-trip by the periodic health check
still only gets the §21 tappable notification, on purpose.

**23.4 HONEST LIMITS, not implementation shortcuts:**
- The consent dialog still genuinely appears on screen, briefly, even
  when everything works -- this automates answering it, not removing
  it. Nothing can remove it; that would require an Android platform
  change, not an app-level one.
- The auto-tap is a real screen-reading heuristic matched against
  English button labels this environment could not confirm against an
  actual device, OS version, or OEM skin (this exact driver's is
  OPPO ColorOS, confirmed `knownAggressiveOem=true` in the §21 log).
  If the real wording doesn't match, this silently does nothing and
  falls back to the still-fully-functional §21/§22 tappable
  notification -- not a silent failure, just a degraded (one-tap)
  recovery instead of a zero-tap one.
- Auto-launching Setup is still subject to the same Background
  Activity Launch restrictions §23.1's reused mechanism has always
  had -- a blocked launch fails silently, same honesty gap already
  documented for the Dasher offer auto-launch.
- No Android device/emulator available in this environment for any of
  §23 -- none of this could be triggered and observed on a real
  device, only read against Android's documented `AccessibilityNodeInfo`,
  `PendingIntent`, and `MediaProjectionManager` behavior.

## 24. Success criteria for §23

- [x] `ScreenRecordingController.armConsentDialogAutoTap()` /
      `isExpectingConsentDialog()` / `disarmConsentDialogAutoTap()`
      added, armed only in `requestScreenRecordingConsent()`, disarmed
      on every real result (success or decline) and on a terminal tap
- [x] `TripForegroundService.autoLaunchPermissionsActivityForConsentRecovery()`
      added, called only from the trip-start "no consent held" branch
- [x] `DasherAccessibilityService.tryAutoTapConsentDialog()` added,
      gated on the armed window, never on package name alone; handles
      the single-dialog and two-step-picker cases; never taps a
      cancel/deny-labeled control
- [x] `PermissionsActivity` auto-finishes after an auto-launched
      recovery's dialog is answered, so Setup doesn't linger in the
      foreground
- [x] `accessibility_service_config.xml`'s comment updated to disclose
      this narrow exception to the "only reads Dasher's content" rule
- [x] Brace/paren balance confirmed on every touched file
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §23.4. Every mechanism here is read directly
      against documented Android platform behavior and this codebase's
      own already-field-tested `launchDasherApp()` precedent, not
      observed running.
- [ ] Driver confirms: after a process restart clears consent, the
      NEXT trip starting recovers with genuinely zero taps -- Setup
      opens itself, the real OS dialog appears and is answered
      automatically, and that trip (or the one after) records.
- [ ] Driver confirms the real button wording on their device (OPPO
      ColorOS) actually matches what the auto-tap looks for -- if not,
      report the exact dialog text so the matcher can be corrected.
- [ ] Driver sign-off.

## 25. Driver-asked (2026-09-11): "will the diagnostic log capture all the checklist items to confirm how they operate"

An audit of the field-test checklist's 19 items against actual
`logDiagnostic` call sites found the coverage genuinely mixed -- most
items are at least partially log-verifiable, several are visual-only
by nature (a video actually playing, a map tile actually rendering),
and a few had real, closable gaps. One gap directly undermined the
§21-§23 work just shipped: checklist items n1 ("tap the Trip Capture
alert") and n2 ("touch nothing, let it auto-recover") both opened
`PermissionsActivity` via the exact same code path and logged
IDENTICAL lines from there on -- there was no way to tell, from the
log alone, whether a given consent recovery actually happened with
zero taps (n2, the thing §23 was built to prove) or required a real
tap (n1, or a degraded fallback).

**Fix**: every caller that can open `PermissionsActivity` for consent
recovery now tags a `EXTRA_CONSENT_RECOVERY_SOURCE` string before
handing off its Intent, and `onCreate()` logs which one fired, with an
explicit note on what that source can and can't prove:

- `alert_notification_tap` (the §21 alert's own tap) and
  `auto_launch_overlay_tap` (§23's overlay tapped) -- both unambiguous
  real taps.
- `auto_launch_direct` -- §23's bare `startActivity()` succeeding
  unblocked; the one genuinely zero-tap case, and the only one that
  actually confirms n2.
- `auto_launch_fullscreen_notification` -- §23's full-screen-intent
  fallback. Deliberately logged as AMBIGUOUS, not claimed as zero-tap:
  Android's own documented behavior is that this either auto-launches
  while locked or silently degrades to an ordinary heads-up
  notification the driver has to tap when unlocked, and there is no
  API that reports back which one actually happened.

A real implementation bug found and fixed while building this: the
original §23 code reused ONE mutable `Intent` object across all three
launch paths (direct call, overlay's tap callback, the notification's
`PendingIntent`), relying on setting its extra right before each use.
The overlay's tap callback can fire arbitrarily late -- whenever the
driver taps it, if ever -- so by the time it ran, the shared Intent's
extra could already have been overwritten by whichever path set it
last, misattributing the source. Fixed by building three separate,
independently-tagged `Intent` instances up front
(`buildConsentRecoveryLaunchIntent(String source)`), one per path.

## 26. Success criteria for §25

- [x] `PermissionsActivity.EXTRA_CONSENT_RECOVERY_SOURCE` added
- [x] All three §23 auto-launch paths (direct, overlay tap, full-screen
      notification) tag a distinct source via separate `Intent`
      instances, not a shared mutated one
- [x] The §21 alert's own tap intent tags `alert_notification_tap`
- [x] `onCreate()` logs the source with an explicit note on what it
      does/doesn't prove, specifically calling out
      `auto_launch_fullscreen_notification` as ambiguous rather than
      claiming it as zero-tap
- [x] Field-test checklist artifact's n1/n2 items updated to reference
      checking this specific log line
- [x] Brace/paren balance confirmed on both touched files
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- the source-tagging itself could not be observed
      firing on a real device, only read against documented Android
      `Intent`/`PendingIntent` extra-passing semantics.
- [ ] Driver confirms: after n1 (tapping the alert), the log shows
      `alert_notification_tap`; after n2 (touching nothing), it shows
      `auto_launch_direct` or, if the direct launch was blocked,
      `auto_launch_fullscreen_notification` (in which case n2 isn't
      fully confirmed either way -- worth noting which one appeared).
- [ ] Driver sign-off.

## 27. Driver-requested (2026-09-11): "I want audio recorded also"

Driver confirmed (asked directly, given the two real options) that this
means the microphone -- ambient sound in the car (voice, passengers,
phone calls), not just whatever Dasher itself plays. That's a genuine
privacy step up from video-only capture, not a minor addition, and is
disclosed as such in both proactive-consent surfaces this app already
has: the first-run explanation dialog (`MainActivity.
maybeShowScreenRecordingDefaultPrompt`) and the Setup screen's own
subtext (`R.string.screen_recording_subtext`).

### 27.1 What changed

- `AndroidManifest.xml`: `RECORD_AUDIO` declared. This is a dangerous
  permission (Android 6+) -- the manifest entry alone grants nothing;
  it has to be requested at runtime too.
- `PermissionsActivity.requestScreenRecordingConsent()`: now requests
  `RECORD_AUDIO` first, via a new `audioPermissionLauncher`
  (`ActivityResultContracts.RequestPermission()`), BEFORE the existing
  `launchScreenCaptureConsent()` step. Proceeds to the real
  screen-capture consent dialog regardless of the driver's answer --
  denying microphone access was deliberately never wired to block
  recording itself, only its audio track. Logged either way
  (`"Microphone permission granted"` / `"...denied -- recording will
  proceed video-only"`).
- `ScreenRecordingController`: new `hasAudioPermission(Context)`
  (plain permission check, not cached at the class level the way the
  MediaProjection grant is -- a permission check is cheap and the OS
  can revoke it independently at any time). New per-trip field
  `audioEnabledForThisTrip`, decided once in `beginCapture()` and
  reused by every segment `rotateSegment()` creates for that same
  trip -- deliberately NOT re-checked mid-trip, so a trip can't
  silently gain or lose its audio track partway through if the OS
  revokes the permission a few minutes in.
- `newRecorder()`: when audio is enabled, adds
  `setAudioSource(MediaRecorder.AudioSource.MIC)` and, after
  `setOutputFormat()`, `setAudioEncoder(AAC)` + a 128kbps/44.1kHz
  encoding config -- ordinary AAC settings, nothing exotic. Real
  Android platform requirement, not a style choice: `setAudioSource()`/
  `setVideoSource()` must both be called BEFORE `setOutputFormat()`,
  while `setAudioEncoder()`/`setVideoEncoder()` must come AFTER it --
  get this backwards and `MediaRecorder` throws
  `IllegalStateException`. Verified by reading Android's own
  documented `MediaRecorder` state machine, not observed on a device.
- `TripForegroundService`'s "Started recording for this trip" log line
  now says explicitly whether that trip has audio or not, via the new
  `ScreenRecordingController.isAudioEnabledForThisTrip()` getter --
  same "never make the driver discover this by opening a file in a
  player" reasoning as everything else logged in this PRD.
- `PermissionsActivity.refreshScreenRecordingStatus()`: same treatment
  as the existing "consent needs to be re-granted" line -- if
  recording is on but microphone permission isn't currently granted,
  that's now visible right on the Setup screen, not just discoverable
  after the fact.

### 27.2 Honest limits

- No Android device/emulator available in this environment, same as
  every other section of this PRD -- none of this (the permission
  request flow, whether `MediaRecorder` actually accepts this
  audio/video source combination together, whether the resulting file
  actually has a playable audio track) has been observed running.
  Verified by reading Android's documented `MediaRecorder` API and
  this class's own existing, already-working video-only pattern.
- If the driver denies `RECORD_AUDIO`, recording proceeds video-only
  for that entire trip (see `audioEnabledForThisTrip`'s own doc for
  why it's not re-checked mid-trip) -- by design, not a bug, but worth
  restating: there's no "ask again mid-trip" path. Re-granting later
  requires reopening Setup and toggling the recording switch off/back
  on, same mechanism as re-granting the MediaProjection consent itself.
- This does NOT touch the separate app/device-audio-playback capture
  API (`AudioPlaybackCaptureConfiguration`) -- that was the other
  option put to the driver and explicitly not chosen. If DoorDash's
  own app audio (notification dings, etc.) is ever wanted too, that's
  a different, additional mechanism, not something this section's
  microphone capture already covers.

## 28. Success criteria for §27

- [x] `RECORD_AUDIO` declared in `AndroidManifest.xml`
- [x] Requested at runtime via `audioPermissionLauncher`, before the
      screen-capture consent dialog, proceeding either way
- [x] `ScreenRecordingController.hasAudioPermission()` added;
      `audioEnabledForThisTrip` decided once per trip in
      `beginCapture()`, reused by every segment rotation
- [x] `newRecorder()` sets audio source/encoder in the correct order
      relative to `setOutputFormat()` (source before, encoder after)
- [x] Diagnostic log states explicitly whether each trip's recording
      has audio, not left to be discovered by opening the file
- [x] Setup screen surfaces a missing-microphone-permission state the
      same way it already surfaces a missing-consent state
- [x] First-run dialog and Setup subtext both updated to disclose
      microphone capture before the driver grants anything
- [x] Brace/paren balance and XML well-formedness confirmed on every
      touched file (a real double-hyphen-in-XML-comment mistake, the
      same class of error §23 already hit once, was caught here before
      commit, not left for CI)
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §27.2. Nothing here has been observed
      recording an actual audio track, only read against documented
      `MediaRecorder`/`ActivityResultContracts` behavior.
- [ ] Driver confirms: granting microphone access on the next
      recording enable actually produces a file with a real, audible
      audio track, not just a bigger-than-before silent video.
- [ ] Driver confirms: denying microphone access still records video
      successfully (doesn't break the existing video-only path).
- [ ] Driver sign-off.
