# Progress log — "the app crashes during setup"

## First attempt (2026-09-02) — Activity Recognition, real fix, wrong cause

Driver reported "the app crashes during setup." No diagnostic log was
available (the app kept crashing before the driver could reach
Diagnostics). Read `PermissionsActivity.onCreate()`/`onResume()` in
full and found `subscribeToDrivingDetectionIfPermitted()` was the only
call site in that file with zero exception handling around a real
Google Play Services call (`ActivityRecognition.getClient(this)
.requestActivityTransitionUpdates(...)`), which can throw
synchronously. Wrapped it in a `try/catch`, matching every other call
site in the file. This was a real gap and a worthwhile fix, kept in
the codebase -- but it turned out not to be the actual crash.

## Course correction (2026-09-02) — driver ran a real experiment

Driver reported: "i just turned off the app permission for location to
not allow and the app doesnt crash." This is direct, decisive evidence
against the Activity Recognition theory -- `ACTIVITY_RECOGNITION` and
`ACCESS_FINE_LOCATION` are unrelated permissions. Immediately
re-investigated rather than assuming the first fix was right.

Traced it: `MainActivity.onCreate()` only calls
`startForegroundService(new Intent(this, TripForegroundService.class))`
when `hasForegroundLocationPermission()` is true. With location denied,
`TripForegroundService` never starts at all -- so the crash has to live
inside that service's own startup path.

Read `TripForegroundService.onCreate()`/`startTracking()`/`stopTracking()`
and the manifest's `<service>` declaration together. Found:
`android:foregroundServiceType="location|mediaProjection"` declares
BOTH types on the service, but all four real `startForeground()` calls
in the Java code used the plain 2-argument overload -- which, per
Android 14's documented behavior, implicitly requests EVERY type
declared in the manifest, not just the one relevant to that specific
call. This meant even the very first, completely routine
`startForeground()` in `onCreate()` (fires on every single app launch)
was implicitly asking Android for a `mediaProjection`-type foreground
service with no active `MediaProjection` grant -- something Android 14
can legitimately reject, regardless of whether the driver has ever
touched screen recording. Confirmed the manifest's own existing
comment ("declaring the type here is what Android 14+ requires... it
doesn't request or imply the grant itself") was an untested assumption
from the original `docs/screen_recording/PRD.md` work, not something
ever confirmed on a real device.

This matches the evidence precisely: happens on every launch (routine
call, not conditional on anything the driver did), and stops when
location is denied (service never starts, buggy call never reached).

### Fix

- `TripForegroundService.startForegroundLocationOnly(Notification)`
  (new): requests only `ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION`
  via the 3-arg `startForeground` overload (API 29+, falls back to the
  plain 2-arg call below that, since this app's `minSdk` is 26). Used
  by the three routine call sites: `onCreate()`'s idle start,
  `startTracking()`, `stopTracking()`'s return-to-idle.
- `TripForegroundService.startForegroundWithRecording(Notification)`
  (new): requests BOTH `FOREGROUND_SERVICE_TYPE_LOCATION` and
  `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` together. Called exactly
  once, right before `ScreenRecordingController.start(this)` -- the one
  real moment the `mediaProjection` type needs to be genuinely active,
  replacing the previous (disproven) assumption that an earlier,
  location-only `startForeground()` call already covered it.
- Manifest comment corrected to describe the real, confirmed behavior.

## Verification (2026-09-02)

Brace/paren balance confirmed: `PermissionsActivity.java` 62/62 braces,
352/352 parens; `TripForegroundService.java` 171/171 braces, 762/762
parens -- both after all edits. Code-reviewed all four real
`startForeground()` call sites to confirm each routes through the
correct helper, and that the screen-recording promotion happens BEFORE
`screenRecordingController.start(this)` (order matters -- Android needs
the type active before `createVirtualDisplay()` runs, not after).

Not verified on-device -- no Android emulator/device available in this
environment, and this is pure Android platform behavior (foreground
service type enforcement) with no Python involved at all, so it can't
be reproduced in a `python3` sandbox the way most of this session's
other fixes were. This is the strongest, most evidence-backed diagnosis
found so far (a real driver-run experiment, not just code reading), but
still needs the driver's own confirmation that it actually resolves the
crash.
