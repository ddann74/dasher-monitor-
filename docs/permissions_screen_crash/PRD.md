# PRD — Permissions & Setup screen crashes (driver-reported, critical)

Status: IMPLEMENTED. Driver reported "the app crashes during setup,"
separately from the earlier database-restore/closed-connection report
(`docs/database_restore_safety/PRD.md` §7, real, already fixed, but not
the cause of THIS crash). §2's first diagnosis (Activity Recognition,
below) was a real, worthwhile hardening fix but WRONG about being the
actual cause -- the driver then ran a real experiment (disabling
location permission) that redirected this to the real root cause in
§3: a foreground-service-type mismatch that fires on every single app
launch, unrelated to Activity Recognition. See PROGRESS.md for the
full course-correction, not just the final answer.

## 1. What was reported

"the app crashes during setup, not when i load the backup database" --
explicitly ruling out the restore flow (`DataManagementActivity`) as the
cause of THIS crash, redirecting investigation to `PermissionsActivity`
(the screen labeled "Permissions & Setup" in the UI, per
`R.string.nav_permissions`).

## 2. First investigation (real fix, but NOT the actual cause -- see §3)

Read `PermissionsActivity.onCreate()` and `onResume()` in full. Every
other Python/Java call site on this screen is defensively wrapped in a
`try/catch` -- `get_fuel_cost_settings`, `set_fuel_cost_settings`,
`logDiagnostic` itself, `buildInstallTimingNote` -- matching this
codebase's own consistent discipline everywhere else. ONE exception:

`subscribeToDrivingDetectionIfPermitted()`, called unconditionally from
`onResume()` (i.e. every single time this screen becomes visible) once
`ACTIVITY_RECOGNITION` permission has already been granted, had **zero**
exception handling around a real Google Play Services call chain:
`ActivityRecognition.getClient(this).requestActivityTransitionUpdates(...)`.
Both of those can throw SYNCHRONOUSLY (not just fail asynchronously via
the `addOnFailureListener` this method already had) -- e.g. if Play
Services is missing, outdated, mid-update, or otherwise unavailable on
a specific device. `DrivingDetectionReceiver`, which touches a very
similar Activity-Recognition API surface, already wraps its own call in
a `try/catch` for exactly this reason -- this one call site was the
odd one out.

This matches the reported symptom precisely: crashes specifically on
this screen (not the restore flow), and would recur on every visit,
matching "keeps crashing."

**Honest limit, confirmed wrong by real evidence**: this was the most
plausible candidate found by code review at the time, not a confirmed
diagnosis -- no stack trace or diagnostic log was available. The driver
then reported "i just turned off the app permission for location to
not allow and the app doesnt crash" -- a real experiment, and direct
evidence this whole theory was wrong: `ACTIVITY_RECOGNITION` and
`ACCESS_FINE_LOCATION` are different permissions entirely, so this
can't be about Activity Recognition. Kept the fix below anyway (it's a
real, separate hardening improvement -- this call site was still the
only unguarded Google-Play-Services call in the file), but redirected
investigation per §3.

### Fix (kept, real hardening, not the actual crash)

Wrapped the entire body of `subscribeToDrivingDetectionIfPermitted()`
in a `try/catch (RuntimeException e)`, logging the failure via the same
`logDiagnostic` pattern every other guarded call site in this file
already uses. No behavior change on the success path.

## 3. Real root cause, found from the driver's location-permission experiment

Disabling location permission stops `MainActivity.onCreate()`'s own
`startForegroundService(new Intent(this, TripForegroundService.class))`
call from ever firing at all (gated by `hasForegroundLocationPermission()`)
-- meaning the crash lives inside `TripForegroundService`'s startup,
not `PermissionsActivity` at all. "Crashes during setup" meant the
app's own initial setup/launch sequence, not the "Permissions & Setup"
screen specifically.

### 3.1 Root cause, confirmed by re-reading the manifest against Android 14's documented behavior

`AndroidManifest.xml` declares `TripForegroundService` with
`android:foregroundServiceType="location|mediaProjection"` -- both
types, unconditionally, since the service handles both plain GPS
tracking AND (opt-in) screen recording. But **every** `startForeground()`
call in `TripForegroundService.java` (four call sites: `onCreate()`'s
idle start, `startTracking()`, the screen-recording promotion, and
`stopTracking()`'s return-to-idle) used the plain 2-argument overload.
Per Android 14's documented behavior, that overload implicitly requests
**every** type declared in the manifest, not just the ones relevant to
that specific call -- meaning even the very first, plain idle
`startForeground()` in `onCreate()`, which runs on EVERY app launch
whether or not screen recording is enabled at all, was implicitly
asking Android to start a `mediaProjection`-type foreground service
with no active `MediaProjection` grant. Android 14 can reject that.

This exactly matches the evidence: happens on every app launch (that
plain idle call fires unconditionally in `onCreate()`), and disappears
when location permission is off (since then `TripForegroundService`
never starts at all, and the buggy call is never reached).

The manifest's own PRE-EXISTING comment ("declaring the type here is
what Android 14+ requires to allow that grant to be used at all, it
doesn't request or imply the grant itself") was an untested assumption
from the `docs/screen_recording/PRD.md` work -- never confirmed on a
real device, exactly the kind of gap real driver evidence exists to
close.

### 3.2 Fix

Two new helper methods on `TripForegroundService`:
- `startForegroundLocationOnly(Notification)` -- explicitly requests
  ONLY `ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION` via the 3-arg
  `startForeground(int, Notification, int)` overload (API 29+; below
  that, foreground service types don't exist as a concept, so the
  plain 2-arg call is correct and used as a fallback). Used by the
  three routine call sites: `onCreate()`'s idle start, `startTracking()`,
  and `stopTracking()`'s return-to-idle.
- `startForegroundWithRecording(Notification)` -- explicitly requests
  BOTH `FOREGROUND_SERVICE_TYPE_LOCATION` and
  `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` together. Called ONLY at
  the one moment `mediaProjection` is genuinely about to be used --
  right before `ScreenRecordingController.start(this)`, replacing the
  disproven assumption that merely being in a `location`-type
  foreground service (started elsewhere, earlier) was already enough.

Manifest comment updated to describe the real, confirmed behavior
instead of the disproven assumption.

## 4. Non-goals

- Not a fix for `docs/database_restore_safety/PRD.md` §7 (the closed-
  database bug) -- that was real and already fixed, just not the cause
  of THIS crash.
- Not touching `DrivingDetectionReceiver` itself -- unrelated to this
  root cause, already has its own guard for a different reason.
- Not adding retry/backoff logic for a failed Activity Recognition
  subscription or a failed recording start -- out of scope for both
  fixes in this PRD.

## 5. Verification

Brace/paren balance confirmed (62/62, 352/352 in `PermissionsActivity.java`;
171/171, 762/762 in `TripForegroundService.java`) after the edits. Not
verified on-device -- no Android emulator/device available in this
environment, and this specific foreground-service-type failure mode
can't be reproduced in a plain `python3` sandbox (pure Java/Android
platform behavior, no Python involved at all). Verified by code review
only: confirmed all four real `startForeground()` call sites route
through the correct helper (three location-only, one recording-inclusive,
right before the one place `createVirtualDisplay()` actually needs the
`mediaProjection` type to be active), and that the API-level guard
(`Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q`) correctly falls back
to the plain 2-arg call below API 29, where this app's `minSdk` 26
still needs to run.

## 6. Success criteria

- [x] `subscribeToDrivingDetectionIfPermitted()` wrapped in a
      `try/catch`, matching this file's own established pattern (§2,
      real hardening, kept even though it wasn't the actual crash)
- [x] §3.1's root cause confirmed by tracing the driver's own
      location-permission experiment through the real code, not
      guessed
- [x] `startForegroundLocationOnly()`/`startForegroundWithRecording()`
      implemented; all four real `startForeground()` call sites in
      `TripForegroundService` route through the correct one
- [x] Manifest comment corrected to match the real, confirmed behavior
- [ ] Driver confirms this actually resolves the crash on-device.
- [ ] Driver sign-off.
