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

## Second-pass premortem against the real implementation (2026-08-31)

Requested explicitly by the driver, after the PRD's own design-phase
premortem (§4a, written before any code existed). Re-traced the actual
implementation rather than re-stating what was already there - found two
real, confirmed issues, both fixed immediately as part of reporting them
(see PRD.md §4a's new "Second pass" subsection for the full writeup):

- **P4 (real bug)**: `PermissionsActivity`'s "Delete All Recordings" had
  no guard against a currently-in-progress recording - could silently
  destroy it (unlinking an open file's directory entry). Fixed via a new
  `TripForegroundService.isScreenRecordingActive` static flag, checked
  before the delete dialog appears.
- **P5 (real gap)**: the Setup screen couldn't tell the driver "toggle
  is on, but the consent grant is actually gone" (a process restart
  invalidates it silently) until a trip already tried and failed. Fixed
  by having `refreshScreenRecordingStatus()` check `hasPendingConsent()`
  directly and surface the mismatch on the same screen as the toggle.

Fixing P4 correctly required a small refactor: my first attempt set
`isScreenRecordingActive = false` at each of `TripForegroundService`'s
two explicit `stop()` call sites individually - which missed the THIRD
way recording can stop (Android itself revoking the grant externally,
e.g. the driver tapping the system "Stop recording" notification, routed
through `ScreenRecordingController`'s own `MediaProjection.Callback`).
Caught this while double-checking the fix, before committing it -
refactored to a `ScreenRecordingController.StopListener` callback fired
from inside `releaseInternal()` itself, the one place all three stop
paths actually converge, rather than three independently-maintained call
sites that could drift out of sync (exactly the class of bug P4 itself
was).

P6 (noted, not fixed): whether a delay between granting consent and
actually starting a trip risks the grant going stale is still genuinely
unconfirmed - added to `ScreenRecordingController`'s existing
unconfirmed-behavior disclosure rather than guessed at.

Verified the fix itself the same way as the rest of this PRD: brace-
balance checks on all three touched files (all balanced), confirmed
`ScreenRecordingController`'s only instantiation site was updated to
match its new constructor signature.

## Third pass (2026-08-31): "does anything fail silently?"

Driver asked this directly. Full audit of every catch block and
early-return in `ScreenRecordingController`/its callers - found real
issues, all fixed:

- **Actively misleading, not just silent**: `start()`'s two early
  `return false` paths (`manager == null`, `getMediaProjection() == null`)
  bypassed the catch block entirely, so nothing was ever logged for them
  - not even to logcat. The caller's own diagnostic message claimed to
  point at "the preceding ERROR-level Android log," which for those two
  paths never existed. Fixed via a new `lastFailureReason()` (set on
  EVERY failure path, not just the ones that throw) written directly into
  the app's own visible diagnostic log, not just logcat - which a driver
  has no way to read without a computer and ADB in the first place, the
  same underlying gap `stop()`'s next issue shares.
- **Fully silent**: `MediaRecorder.stop()`'s expected-failure catch
  (already-understood edge case: stopped before any real data recorded)
  had no log line at all, anywhere. Fixed via `lastStopWasLikelyEmpty()`,
  surfaced in `stopTracking()`'s existing log line so a 0-byte/near-empty
  recording has an explanation instead of an unexplained file size.
- **Misleading success message**: `deleteAllRecordings()` ignored
  `File.delete()`'s own return value - a partial failure (a locked file,
  a permission hiccup) still showed a plain "Recordings deleted" success
  toast. Fixed: returns a failure count, surfaced in the toast.

**Reviewed and deliberately left alone**: `mediaRecorder.release()` and
`mediaProjection.unregisterCallback()`'s swallowed exceptions in
`releaseInternal()`. These are the standard, well-documented Android
no-op-on-already-released-object pattern - not a "did the recording
actually work" question the way the three fixes above are, and logging
every one would mostly add noise. Judgment call, not an oversight -
flagged here so it's clear this was considered, not missed.

## Fourth pass (2026-08-31): "are there any gaps" - documented, not fixed

Driver asked a broader question than the previous two passes (which
targeted failure paths and silent logging specifically). Found two real
scope gaps, neither a bug - written into `PRD.md` §4a as P7/P8 rather
than fixed, since both are real design/scope decisions, not
straightforward code fixes:

- **P7**: recordings have no link to this app's own trip database -
  reviewing a specific delivery has no way to find its recording, or
  vice versa, beyond comparing timestamps by hand.
- **P8**: the `VirtualDisplay`'s capture dimensions are fixed at whatever
  the screen's orientation was the moment recording started - a
  mid-trip physical rotation would not be reflected, likely producing a
  squished/misoriented recording for whatever happens afterward. Low
  real-world likelihood for a phone mounted for driving, but genuinely
  unconfirmed, and a different KIND of gap than the first two passes
  checked for (steady-state correctness, not a failure/logging path).

## §7 design pass (2026-09-02) — driver asked to "capture screen recording by default," design only, no code

Driver asked two things: where the video files are stored (answered
directly, see PRD §7.1 - `getExternalFilesDir()/ScreenRecordings/`,
app-private external storage, no in-app player/export exists today),
and to design "capture by default," explicitly instructed to add it to
the PRD without writing any code from it yet.

Re-read §1.1 (the OS's MediaProjection consent dialog can never be
silently bypassed - a real platform constraint, not a design choice)
and §1.3 (the real whole-screen privacy exposure this feature already
carries) before designing anything, since "by default" has to respect
both rather than re-litigate them. Confirmed from the real code
(`ScreenRecordingController.isEnabled`, L72-74) that the setup toggle
currently defaults to `false` via `getBoolean(KEY_ENABLED, false)`,
and that the consent flow only ever fires from a driver's own tap
inside `PermissionsActivity` today - nothing proactively surfaces it.

Wrote PRD §7 with: what "by default" can/can't mean given the OS
constraint (the toggle default and proactive prompting ARE within this
app's control; the OS dialog itself is not); the real tradeoff a
default-on toggle creates (removes §1.3's own "opt-in mitigates this"
reasoning for any driver who never visits Settings); explicit
non-goals (not attempting to bypass the OS dialog, not building an
export/share feature as a side effect, not silently fixing the
already-flagged no-storage-cap gap); and one genuinely open question
(§7.5: should a new in-app first-run explanation screen be shown
before the OS dialog, given the OS dialog's own wording is generic
across every app) with a stated recommendation (yes) that is NOT acted
on without the driver's own answer or explicit go-ahead.

Updated the Status header and RALPH_PROMPT.md to mark this an
explicitly-deferred addition - a real, deliberate "design only" ask
from the driver, not an unanswered open question a future "continue"
instruction should resolve on its own initiative the way other PRDs'
stated recommendations get used.

No code changed. §8's checklist (all boxes) is the tracking mechanism
for §7's eventual implementation, once approved.

## §9 fix (2026-09-02): real crash loop, CONFIRMED by a real diagnostic log

Driver uploaded a real diagnostic log (`dasher_monitor_full_history16.txt`)
saying "the app is still crashing and maybe stopping monitoring for some
reason." The log showed three identical `SecurityException` stack traces,
each at `TripForegroundService.startForegroundWithRecording
(TripForegroundService.java:238)`, each followed shortly by a fresh process
start - a crash loop, not a one-off. This is exactly P6 from the "second
pass" section above coming true: "whether a delay between granting consent
and actually starting a trip risks the grant going stale" - except the real
trigger turned out to be simpler than a delay: **reusing an already-granted
MediaProjection consent token for a SECOND trip in the same still-alive
process.**

**Root cause, precisely**: PR #12 (the foreground-service-type fix earlier
this session) made `startForegroundWithRecording()` declare the
`mediaProjection` type via the 3-arg `startForeground()` overload -
correctly scoped to only fire when the recording toggle is on, but called
*unconditionally* whenever the toggle was on, *before*
`ScreenRecordingController` ever got a chance to check whether a currently
LIVE `MediaProjection` grant actually existed. `FOREGROUND_SERVICE_
MEDIA_PROJECTION` (the manifest permission) is necessary but not
sufficient - Android's foreground-service-type validator also requires the
calling process to currently hold a live grant, obtained via
`MediaProjectionManager.getMediaProjection()`, before the type can be
declared. A driver's first trip after granting consent worked fine (fresh
token, `getMediaProjection()` succeeds); the SECOND trip in the same
process reused the same stored `pendingResultCode`/`pendingResultData`
without re-prompting (by design - re-prompting every trip would defeat the
point of "grant once"), and Android rejected the type declaration itself
- not inside `ScreenRecordingController` where the old `start()` method
could catch and report it via `lastFailureReason()`, but one level up, at
the `startForeground()` call in `TripForegroundService` that fired before
`ScreenRecordingController` was ever invoked. That threw an uncaught
`SecurityException` straight out of `startForegroundWithRecording()`,
crashing the whole service (killing GPS tracking, not just recording) -
matching the driver's own words, "maybe stopping monitoring for some
reason."

**Fix**: reordered to match Android's documented MediaProjection
foreground-service sequence - acquire the projection FIRST, and only
promote the foreground-service type (and only then attempt the actual
capture) if that acquisition succeeds:

- `ScreenRecordingController.start(Service)` split into two methods:
  `acquireProjection(Service)` (calls `getMediaProjection()`, registers the
  stop callback, returns false with `lastFailureReason` set on any failure
  - safe to fail, throws nothing) and `beginCapture(Service)` (the actual
  `MediaRecorder`/`VirtualDisplay` setup, unchanged from the old `start()`
  body past the projection-acquisition step).
- `TripForegroundService.startTracking()`'s recording block now calls
  `acquireProjection()` BEFORE `startForegroundWithRecording()`, and only
  calls `startForegroundWithRecording()` + `beginCapture()` if acquisition
  succeeded. If it fails (stale token, no consent, or any other reason),
  the service falls back to `startForegroundLocationOnly()` instead -
  GPS tracking continues normally, recording is skipped for that trip, and
  the existing "no consent held" / "enabled but failed" alert paths (see
  the original §7 implementation notes above) still fire to tell the
  driver why.
- `ScreenRecordingController`'s class-level doc updated: the "UNCONFIRMED
  on a real device" note for cross-trip reuse (originally written 2026-08-
  31, flagged again as P6 on 2026-08-31) is now "CONFIRMED on a real
  device (2026-09-02, a real driver's diagnostic log)" - the assumption
  was wrong, and the fix no longer depends on it being right: a stale
  token now degrades to "no recording this trip," not "no monitoring this
  trip."

**Verification**: same disclosed limitation as the rest of this PRD - no
Android SDK/emulator/device in this environment, so this is code review
against the real stack trace plus brace/paren-balance checks, not a live
repro. `TripForegroundService.java`: 172/172 braces, 765/765 parens.
`ScreenRecordingController.java`: 47/47 braces, 184/184 parens. Grepped
for any other call site of the old `start()` method - none found; the
single call site in `TripForegroundService` was the only caller.

Remaining PRD §10 boxes: driver confirmation that the crash loop stops
(the direct test - toggle recording on, complete two trips back to back
in the same app session without force-closing between them) and driver
sign-off. Both outstanding until reported back.

## §11 fix (2026-09-02): "fix the crash-recovery gap so recordings finalize on next launch"

Driver asked what happens to the video if the app crashes mid-recording
(a follow-up question after §9/§10 shipped). Answered directly first: a
graceful `onDestroy()` teardown finalizes the file (writes its `moov`
index box via `MediaRecorder.stop()`); an actual process crash skips
that, leaving a file with recorded frame data but no index - unplayable
in any standard player, though not literally deleted. Driver then asked
to fix it.

**Investigated and rejected**: genuinely repairing a `moov`-less MP4
(reconstructing the frame-offset/timing tables from the raw `mdat` data
already on disk) is a hard, OEM/codec/Android-version-specific problem -
real third-party tools exist for exactly this (`untrunc` and similar),
and none of them work universally. With no Android SDK/emulator/device
in this environment to verify a repair actually produces a playable
file, shipping one risked exactly the failure mode this PRD's earlier
audits kept finding and fixing: something that LOOKS successful (a
renamed/"recovered" file) but is quietly still broken - worse than
admitting the limitation, since the driver would trust footage that
isn't actually there. Documented in PRD §11.1 rather than silently
skipped.

**What was actually fixed** (PRD §11.2):

1. **`ScreenRecordingController.java`** - recording is no longer one
   file per trip. Added `SEGMENT_DURATION_MS` (5 minutes) and rewired
   `beginCapture()`/a new `newRecorder()` helper to use
   `MediaRecorder.setMaxDuration()` + `setOnInfoListener` (Android's own
   documented pattern for bounded-duration recording). A new
   `rotateSegment()` method fires when a segment hits its time limit:
   `stop()`s the finishing recorder (finalizing ITS `moov` box - the
   real crash-safety boundary) and starts a fresh `MediaRecorder` on the
   same `VirtualDisplay` via `VirtualDisplay.setSurface()` (no
   re-acquiring the `MediaProjection` grant mid-trip). A crash now loses
   at most one segment instead of the whole trip. First segment keeps
   the original `trip_<timestamp>.mp4` naming (most trips stay under 5
   minutes and still produce exactly one file, unchanged from before);
   later segments get `_part2`, `_part3`, etc.
2. Added `hasMoovBox(File)` - a from-scratch top-level ISO-BMFF box scan
   (checks for the `moov` box's PRESENCE only, never parses its
   contents - a simple, fully-verifiable-by-reading operation, unlike
   the rejected repair approach) - and `cleanUpOrphanedSegments(Context)`,
   which scans the recordings folder for `.mp4` files missing one and
   deletes them.
3. **`TripForegroundService.java`** - `onCreate()` (the first point this
   app's own code runs again after any crash - literally "next launch")
   now calls `cleanUpOrphanedSegments()` and logs how many orphaned
   segments were removed, rather than leaving broken-looking files for
   the driver to discover by trying to open them.

**Self-caught bug during review**: `rotateSegment()`'s original single
try/catch meant a failure starting the NEXT segment (after the previous
one had already been successfully finalized) left the half-created
`MediaRecorder` for that next segment un-released - a leaked native
codec instance. Caught this before considering the fix done, split the
method into two separate try/catch blocks (finalize-previous vs.
start-next) so a failure in the second explicitly releases whatever was
created.

**Also fixed while reviewing existing callers**: `currentFile()`'s
meaning changed (now "the current/last segment," not "the whole trip's
one file"). Its one call site - `TripForegroundService`'s stop-tracking
diagnostic log line, which reported a byte count implying it was the
whole trip's size - was corrected to say "final segment" explicitly
rather than leaving a now-misleading log message in place.

**Verification**: same disclosed limitation as the rest of this PRD - no
Android SDK/emulator/device, so code review plus static checks:
brace/paren balance (`ScreenRecordingController.java` 74/74 braces,
290/290 parens; `TripForegroundService.java` 173/173 braces, 774/774
parens); traced every existing caller of `ScreenRecordingController`'s
public methods against the changed internals; confirmed `moov` is always
a top-level (never nested) box, so a shallow scan correctly detects
presence/absence; confirmed `cleanUpOrphanedSegments()` can only ever
run before any `beginCapture()` call in a process's lifetime (called
once, in `onCreate()`), so it can never race with or delete a
currently-in-progress recording.

Remaining PRD §12 boxes: driver confirmation (a >5-minute trip with
recording on produces multiple playable files; a real or forced crash
mid-trip leaves only the current segment missing, not the whole trip)
and driver sign-off.
