# PRD: In-app screen recording during a trip

Status: IMPLEMENTED (all §6 boxes checked except sign-off) -- this
PRD's own status line was stale, found and corrected during a
2026-09-02 ralph-loop continuation pass. Went through two additional
premortem/silent-failure audit passes after initial implementation
(see PROGRESS.md) that found and fixed a real delete-race and a silent
consent-staleness gap. This introduces a genuinely new, privacy-
sensitive capability this app has never had before - read §1.3 and §5
before signing off, not just the checklist.
§7 (added 2026-09-02, DRAFT, NOT implemented): the driver asked to
capture screen recording by default. Investigated and designed, with
an open question (§7.5) - explicitly NOT coded, per the driver's own
instruction to add this to the PRD without implementing it yet.
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

- [ ] A new **Setup** toggle ("Record screen during trips") is off by
      default - recording never starts without the driver explicitly
      opting in first.
- [ ] Enabling the toggle immediately triggers the one-time
      `MediaProjection` consent flow (from an Activity, not silently from
      the background service) - if declined, the toggle reverts to off
      and nothing records.
- [ ] With the toggle on and consent granted, recording starts
      automatically when a trip starts (`startTracking()`) and stops
      automatically when it ends (`stopTracking()`) - no separate
      manual start/stop button needed for the common case.
- [ ] If the granted projection has been invalidated (process restart -
      see §1.1.2) when a new trip starts, this is handled visibly (an
      Activity log line and/or notification saying recording could not
      resume and consent is needed again) rather than silently recording
      nothing while appearing to work.
- [ ] Recorded files are written to this app's own private external
      storage (not a shared/public gallery) - same reasoning
      `tiktok-feed-filter`'s `AudioExtractor` already used for
      privacy-sensitive on-device media in a sibling repo.
- [ ] A way to review/delete recordings exists in-app (at minimum: see
      how many exist and their total size, matching the existing
      Diagnostic Log's size-visibility pattern; full playback UI is a
      stretch goal, not required for this PRD - see §5.2).
- [ ] No change to `TripForegroundService`'s existing GPS/accessibility/
      notification lifecycle - recording is additive, gated entirely
      behind the new toggle, and its absence (toggle off, the default)
      must leave every existing behavior completely unchanged.

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

## 8. Success criteria for §7 (NOT started - explicitly not to be coded without a follow-up "yes implement it")

- [ ] §7.5's open question answered by the driver (or their explicit
      go-ahead to build it per this section's own recommendation)
- [ ] Toggle default flipped from `false` to `true`
      (`ScreenRecordingController.isEnabled`)
- [ ] Consent prompt proactively surfaced (exact trigger point - first
      app launch? first `startTracking()` with no consent held? -
      still needs a specific decision, not just "proactively" left
      vague)
- [ ] §7.5's first-run explanation screen, if that's the chosen answer
- [ ] §7.1's in-app export/share gap addressed, IF the driver confirms
      that's wanted alongside this (separate ask, not assumed)
- [ ] Executable/reviewed verification, same standard as §6
- [ ] User sign-off

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
