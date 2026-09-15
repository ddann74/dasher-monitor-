# PRD: Circuit breaker for the watchdog's auto-restart loop

Status: IMPLEMENTED (2026-09-15), scouting-pass finding (round 9, #1,
directed audit of monitoring auto-correction).

## 1. What was wrong

`TripForegroundService.onCreate()` unconditionally calls
`startForegroundLocationOnly(buildIdleNotification())` before
`onStartCommand()`/`startTracking()` ever runs. This can throw
`SecurityException` -- a documented, real Android 14 restriction on
starting a foreground location service from the background, which this
class's own existing comment already says has "no known way to make
this specific auto-start path itself Android-14-eligible without a user
tap." The failure path (catch block) logs, raises an alert, and calls
`stopSelf()` -- but never sets `isRunning = true` (it was already
`false`) and never clears `intendedActive`.

Since `MonitoringWatchdogReceiver.onReceive()`'s restart branch is only
gated on `!TripForegroundService.isRunning`, and that stays `false`
after this exact failure, the NEXT watchdog cycle (45s-2min later) sees
the same staleness, fires the same alert, and attempts the exact same
`startForegroundService(ACTION_START_TRACKING)` call -- which hits the
identical `SecurityException` again. This repeats indefinitely for as
long as the underlying eligibility condition holds, with no attempt
counter, no backoff, and identical notification text every cycle, so
the driver has no way to distinguish "just started failing" from
"silently retried and failed 40 times this hour." Each cycle is a real
process spin-up/teardown plus a duplicate alert -- genuine battery and
notification cost for a repair attempt the app's own code already knows
cannot succeed this way.

## 2. Design

Added a consecutive-failure counter, persisted in the same durable
SharedPreferences file the watchdog already uses for its heartbeat/
intended-active state (survives process death):

- `MonitoringWatchdogReceiver.recordRestartFailure(Context)` -- called
  from `TripForegroundService.onCreate()`'s `SecurityException` catch
  block, the exact point a restart (automatic OR manual) is confirmed
  to have failed. Increments and returns the new count.
- `MonitoringWatchdogReceiver.recordRestartSuccess(Context)` -- called
  from `startTracking()`, right alongside the existing
  `markIntendedActive(true)` call. Reaching `startTracking()` at all
  means `onCreate()`'s foreground-service start just succeeded -- a
  genuine recovery, not merely an attempt -- so any prior run of
  failures is reset.
- `onReceive()`'s restart branch now checks
  `getConsecutiveRestartFailures(context) >=
  RESTART_CIRCUIT_BREAKER_THRESHOLD` (3) before attempting another
  restart. Below the threshold: behavior is completely unchanged
  (attempts the restart, same as before). At or above it: skips the
  `startForegroundService` call entirely (breaking the churn) and
  raises a NEW, distinctly-worded, distinctly-channeled
  `raiseEscalatedAlert()` instead of the generic staleness alert --
  "Monitoring couldn't restart itself... tap to open Dasher Monitor and
  start it manually," deep-linking to `MainActivity` (a real, foreground,
  user-initiated open, which does NOT hit the same background-eligibility
  restriction -- the one action that actually resolves this).

The escalated alert still fires every watchdog cycle while the breaker
stays tripped (consistent with how the existing base alert already
behaves, rather than a one-shot notification easy to miss), but its
text is honest about what's actually happening ("failed Nx in a row")
instead of repeating the same generic "no activity detected" message
alongside an invisible, silently-failing retry behind it.

## 3. Verification

`MonitoringWatchdogReceiver`/`TripForegroundService` depend on live
`AlarmManager`/`NotificationManager`/Chaquopy `PyObject` and can't be
compiled/run outside a device or emulator in this environment. Verified
instead by:

- `python3`-based brace/paren balance check on both edited files: final
  depth 0 for each.
- A standalone, compiled-and-run Java program (`javac`/`java`)
  containing a **verbatim copy** of `recordRestartFailure`,
  `recordRestartSuccess`, `getConsecutiveRestartFailures`, and the
  threshold constant (confirmed identical to the shipped source via
  `grep` immediately before writing the test), plus a small function
  mirroring the exact circuit-breaker decision structure from
  `onReceive()`'s restart branch, run against a minimal fake
  `SharedPreferences` (in-memory, matching real Android's per-file-name
  persistence semantics):
  - With zero prior failures, the watchdog still attempts a normal
    restart (unaffected by this change for the common case).
  - Below the threshold (2 failures), still attempts a normal restart.
  - **The bug scenario**: at exactly 3 consecutive failures (the
    threshold), the circuit breaker trips -- decides "escalate" instead
    of another silent restart attempt.
  - A 4th consecutive failure keeps the breaker tripped (no flapping
    back to attempting once past the threshold).
  - A genuine restart success resets the failure counter to zero.
  - After that reset, the watchdog resumes attempting normal restarts
    again -- confirms a past failure streak doesn't permanently block
    future genuine recovery.

  6 checks, all passed on first run.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  Android 14 `SecurityException` restart-loop scenario has not been
  observed on a device, only reproduced by tracing the exact control
  flow this codebase's own prior comments already document as a real,
  confirmed platform restriction.
- The threshold (3 consecutive failures) is a judgment call, not a
  derived constant -- same honesty status as this codebase's other
  tuned thresholds. Chosen to distinguish "one transient failure" from
  "this is genuinely stuck," but not validated against real-device
  timing data.
- Does not address every possible cause of a restart failure -- only
  the specific `SecurityException` path this codebase's own comments
  already identify as un-fixable-from-code. A DIFFERENT, transient
  failure that happens to also throw at this exact call site would also
  increment the counter and eventually trip the breaker, escalating to
  "open the app manually" even if a later automatic retry might
  actually have succeeded -- an accepted, disclosed tradeoff (a false
  escalation costs one extra manual open; a true restart loop costs
  ongoing battery/notification spam) rather than distinguishing failure
  causes with more precision.
- The escalated alert still depends on the driver noticing and tapping
  it -- there is no way to automate the actual fix here, consistent with
  the same platform restriction the underlying `SecurityException`
  represents.

## 5. Success criteria

- [x] Repeated restart failures no longer silently loop forever with
      identical alerts and no escalation
- [x] The circuit breaker trips at a defined, counted threshold, not an
      arbitrary or unbounded point
- [x] A genuine restart success always resets the counter, so past
      failures never permanently block future recovery
- [x] The escalated alert is distinct (channel, text, tap target) from
      the generic staleness alert, and deep-links to the one action that
      actually works (opening the app)
- [x] Below the threshold, behavior is completely unchanged from before
      this fix
- [x] Standalone compiled Java test (6 checks) of the exact counter and
      decision logic, fully passed
- [x] `python3` brace/paren balance check clean on both edited files
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver/QA confirms on a real Android 14 device that a background
      auto-start rejection now stops looping after 3 attempts and
      raises the escalated, tappable alert instead.
- [ ] Driver sign-off.
