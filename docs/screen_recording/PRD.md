# PRD: In-app screen recording during a trip

Status: DRAFT - awaiting sign-off before implementation begins. This
introduces a genuinely new, privacy-sensitive capability this app has
never had before - read §1.3 and §5 before signing off, not just the
checklist.
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

## 5. Open questions - genuinely blocking, not just disclosed

1. **Given §1.3, should this feature exist as designed at all, or with a
   narrower scope** (e.g. only recording while Dasher/DoorDash itself is
   the foreground app, auto-pausing when the driver switches to anything
   else - technically achievable by combining this with the existing
   `DasherAccessibilityService`'s own foreground-app tracking, at the
   cost of gaps in the recording whenever the driver legitimately checks
   another app mid-trip)? This changes §3's design meaningfully depending
   on the answer.
2. **Storage cap/retention**: keep every trip's recording forever (until
   manually deleted), auto-delete after N days, or cap total size with
   oldest-first eviction (same pattern `tiktok-feed-filter`'s
   `RepeatViewRepository` uses for its own capped history, a sibling
   repo's already-proven approach to bounding unbounded local storage)?
3. **Should recording require BOTH the toggle on AND a per-trip
   confirmation**, or is toggle-once-then-automatic (as designed in §3)
   the right default? An always-record-once-enabled design is more
   convenient but has a higher accidental-capture risk than a
   per-trip prompt.

None of these are implementation details - they materially change what
gets built. Recommend resolving at least #1 before any code is written.

## 6. Success criteria (implementation-phase checklist)

- [ ] Open questions (§5) resolved with the driver
- [ ] Setup toggle added, off by default
- [ ] `MediaProjection` consent flow wired via `ActivityResultLauncher`
- [ ] `ScreenRecordingController` (or equivalent) added, start/stop tied
      to `startTracking()`/`stopTracking()`
- [ ] `foregroundServiceType="mediaProjection"` added to
      `TripForegroundService`'s manifest entry
- [ ] Process-restart invalidation handled visibly (not silent)
- [ ] Recordings written to private app storage, not shared/public
- [ ] Basic in-app review (count + total size) of stored recordings
- [ ] No change to existing GPS/accessibility/notification behavior when
      the toggle is off (diff-reviewed)
- [ ] User sign-off
