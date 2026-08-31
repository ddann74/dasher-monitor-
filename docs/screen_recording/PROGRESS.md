# Progress log — in-app screen recording during a trip

## Open question resolved (2026-08-31)

Driver answered PRD §5.1 directly: "Record everything while the trip is
active." Confirms the whole-screen, full-trip-duration design already
drafted in §3 - not scoped to Dasher's own foreground app. §5.2
(retention/cap) and §5.3 (confirmation model) were not separately asked;
implemented with the PRD's own stated defaults (no cap for v1;
toggle-once-then-automatic) rather than silently deciding differently -
both flagged in the PRD as real, disclosed gaps, not settled.

## Implementation (2026-08-31)

**`AndroidManifest.xml`**: added
`FOREGROUND_SERVICE_MEDIA_PROJECTION` permission; added `mediaProjection`
to `TripForegroundService`'s existing `foregroundServiceType="location"`
(now `"location|mediaProjection"`, Android's pipe-separated multi-type
syntax). Hit and fixed the same XML-comment-double-hyphen bug from
earlier in this session (` -- ` inside an XML `<!-- -->` comment body is
invalid XML, not just at the closing delimiter) - caught by
`xml.etree.ElementTree` before committing, same as before.

**`strings.xml`** / **`activity_permissions.xml`**: new "Screen
Recording" section mirroring this file's existing heading/subtext/
control pattern (see the Fuel Cost section for the template followed) -
a `Switch` (this app's first use of that widget; every other
toggle-shaped control here is a settings-intent `Button`, but an actual
in-app on/off state is what a `Switch` is for), a status `TextView`
(count + total size), and a "Delete All Recordings" button.

**New `ScreenRecordingController.java`**: wraps `MediaProjection` +
`VirtualDisplay` + `MediaRecorder`. Static helpers for the persisted
enabled/disabled preference (`SharedPreferences`), the in-memory-only
consent holder (deliberately never persisted - see its own class doc for
why), and recordings-directory bookkeeping (count, total size,
delete-all). Instance methods `start(Service)`/`stop()` own the actual
capture lifecycle, with `MediaProjection.Callback.onStop()` wired to
clean up if Android itself revokes the grant (e.g. the driver taps the
system "stop recording" notification action) rather than only ever
cleaning up on an explicit `stop()` call.

**`PermissionsActivity.java`**: registered a
`registerForActivityResult(ActivityResultContracts.StartActivityForResult())`
launcher (this file's existing pattern is the older
`onRequestPermissionsResult`-based one, for real runtime permissions;
`MediaProjection`'s consent is a different, result-returning Activity
flow that API doesn't fit, so this is a deliberate, disclosed deviation,
not an inconsistency - flagged in the PRD itself before writing any
code). Toggling the Switch on launches the real
`MediaProjectionManager.createScreenCaptureIntent()` system dialog every
time (not just the first time) - see "still open" note below for why.
Toggling off clears both the persisted preference and the in-memory
consent. Added `refreshScreenRecordingStatus()`, called from `onCreate`
and `onResume` so the count/size updates after a trip adds a new file.

**`TripForegroundService.java`**: new
`screenRecordingController` field. `startTracking()` attempts to start
recording (gated on both the toggle and a currently-held consent) as the
last step, after the service is already running in the foreground with
the manifest's `mediaProjection` type active. Three distinct outcomes,
each logged/handled separately per the PRD's own requirement that
invalidated consent be visible, not silent:
- started successfully → logged
- enabled but no consent held (the expected shape of a process restart
  since consent was last granted) → logged AND a loud alert notification
  via the existing `raisePermissionRevokedAlert` mechanism, naming
  exactly what to do (re-grant in Setup)
- enabled, consent held, but `start()` still failed for some other
  reason → logged, pointing at the Android-level exception logged
  separately by `ScreenRecordingController`

`stopTracking()` and `onDestroy()` both stop any in-progress recording
(the latter as a final safety net, same shape as this file's other
`onDestroy()` cleanup, in case the service is torn down through a path
that didn't already call `stopTracking()`).

## Honest gap, disclosed rather than hidden

`ScreenRecordingController`'s own class doc flags this directly:
**whether a single granted `MediaProjection` consent can be reused for a
SECOND trip within the same still-alive process, or whether Android
requires a fresh per-trip consent tap even without a process restart, is
UNCONFIRMED** - no emulator/device available in this environment to test
either way. `PermissionsActivity`'s toggle sidesteps the *first*
activation of this uncertainty (always re-asks when the Switch is turned
on, rather than assuming an old grant is still valid), but
`TripForegroundService.startTracking()` still assumes the held consent
can be reused across however many trips happen before the next process
restart. If that assumption is wrong on a real device, the symptom would
be: recording works for the first trip after enabling, then silently (no
error currently distinguishes this case from a genuine failure) stops
working for the next trip in the same session. Flagged here explicitly so
it's the first thing checked if that's reported.

## Verification (2026-08-31)

Same disclosed limitation as `docs/screen_recording/PRD.md` §4: no
Android SDK/emulator/device in this environment - `MediaProjection` has
essentially zero pure-logic surface to unit-test, so this is code review
only, the least independently-verifiable PRD implemented in this repo so
far.

- `xml.etree.ElementTree` validation on `AndroidManifest.xml`,
  `activity_permissions.xml`, `strings.xml` - all three well-formed
  (caught and fixed one real mistake, the double-hyphen XML comment bug,
  before this).
- Brace-balance check: `TripForegroundService.java` 160/160,
  `ScreenRecordingController.java` 37/37, `PermissionsActivity.java`
  57/57 - all balanced.
- Confirmed every `id` referenced in `PermissionsActivity.java`'s new
  code (`screenRecordingSwitch`, `screenRecordingStatusText`,
  `deleteAllRecordingsButton`) exists exactly once in
  `activity_permissions.xml`.
- Confirmed `androidx.activity`'s `registerForActivityResult` API is
  available without a new Gradle dependency - it's provided by
  `ComponentActivity`, an ancestor of `AppCompatActivity`
  (`androidx.appcompat:appcompat:1.7.0`, already a dependency), not a new
  library.
- No change to any existing GPS/accessibility/notification code path when
  the toggle is off - every new call site is gated behind
  `ScreenRecordingController.isEnabled()`/`hasPendingConsent()` checks
  that both default to false/null on a fresh install.

Remaining PRD §6 box: user sign-off. Real-device verification (does the
consent dialog actually appear, does recording actually produce a valid
MP4, does the reuse-across-trips assumption above actually hold) is
entirely outstanding - by far the most consequential set of unknowns of
any PRD implemented in this repo this session.
