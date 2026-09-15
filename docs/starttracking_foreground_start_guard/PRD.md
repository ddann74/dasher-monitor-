# PRD: Guard startTracking()'s own foreground-start call against SecurityException

Status: IMPLEMENTED (2026-09-15), scouting-pass finding (round 10, #2,
directed audit of monitoring auto-correction).

## 1. What was wrong

`TripForegroundService.onCreate()` already guards its own
`startForegroundLocationOnly()` call with `try/catch (SecurityException e)`
-- a documented, real Android 14 FGS-location background-start
eligibility rejection. On that failure it logs, alerts, records a
restart failure, sets `serviceExists = false`, and calls `stopSelf()`.

But `stopSelf()` called from inside `onCreate()` does not prevent
Android from still delivering the `onStartCommand()` that was already
queued for the original `startForegroundService()` call that created
this instance -- a well-documented Android service-lifecycle behavior
(the `onStartCommand` dispatch for that intent is independent of what
`onCreate()` does internally, since `onCreate()` didn't itself throw).
`onStartCommand()` has no check of any failure flag before dispatching
`ACTION_START_TRACKING` to `startTracking()`, and `startTracking()`
made a SECOND call to `startForegroundLocationOnly()` with **no
try/catch at all**. Since nothing about the environment changed in the
few milliseconds between the two calls, the same eligibility condition
still holds, so this second call throws the identical `SecurityException`
-- uncaught this time, crashing the entire app process immediately
after `onCreate()`'s own guard was supposed to have already degraded
this gracefully.

This affects every background auto-start path that fires
`ACTION_START_TRACKING` in one shot -- `BootAndUpdateReceiver`,
`MonitoringWatchdogReceiver`'s restart branch, and
`DrivingDetectionReceiver` -- exactly the paths most likely to hit this
eligibility rejection in the first place, since a genuinely foreground,
user-initiated "Start Monitoring" tap from `MainActivity` does not hit
this restriction at all.

## 2. Design

Wrapped `startTracking()`'s own `startForegroundLocationOnly()` call in
the same `try/catch (SecurityException e)` shape as `onCreate()`'s,
mirroring its exact recovery: log, `raiseMonitoringNotActiveAlert`,
`MonitoringWatchdogReceiver.recordRestartFailure(this)`, `serviceExists
= false`, `stopSelf()`, and `return` -- with one addition specific to
this call site: `monitoringActive` and `isRunning` are set to `true`
just BEFORE this call (so the rest of `startTracking()` can proceed on
success), so the catch block explicitly rolls both back to `false` on
failure -- otherwise the app would incorrectly believe tracking was
genuinely active when the foreground promotion never actually
succeeded. Returning immediately on failure also means
`markIntendedActive`, `recordRestartSuccess`, and `scheduleWatchdog`
(all further down in `startTracking()`) correctly never run for a
failed attempt.

The OTHER existing `startForegroundLocationOnly()` call site
(`TripForegroundService.java`, inside the mode-change/recording-demote
handling) was deliberately left unguarded -- that call happens while the
service is already confirmed to be in the foreground (it runs right
after a successful `startForegroundWithRecording()` moments earlier in
the same call chain), so it's a safe "already foreground, updating
type" operation, not a fresh promotion into the foreground state, and
isn't subject to this same background-eligibility restriction.

## 3. Verification

`startTracking()` depends on live Android `Service`/`NotificationManager`
APIs and can't be compiled/run outside a device or emulator in this
environment. Verified instead by:

- `python3`-based brace/paren balance check on the edited file: final
  depth 0.
- A standalone, compiled-and-run Java program (`javac`/`java`) mirroring
  the exact sequence of state transitions around the guarded call
  (flags set, guarded call, catch rolls back and returns early vs.
  success falls through to the rest of the method's work), structurally
  matching the shipped source (confirmed by direct read immediately
  before writing the test):
  - **The bug scenario**: a throwing foreground-start call correctly
    rolls `monitoringActive`/`isRunning` back to `false` (previously
    this call wasn't even guarded, so there was no rollback -- the
    process crashed instead).
  - The failure path records a restart failure for the circuit breaker,
    marks `serviceExists` false, and calls `stopSelf()`.
  - The failure path does NOT proceed to `markIntendedActive`/
    `scheduleWatchdog` -- confirms a failed attempt is never recorded as
    a false "success."
  - A successful foreground-start still completes normally (flags stay
    true, the rest of `startTracking()`'s work runs) -- confirms this
    fix doesn't change behavior for the ordinary, working case.

  8 checks, all passed on first run.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  "onCreate() catches the exception, stopSelf() is called, but
  onStartCommand() still fires and hits the same exception again"
  sequence has not been observed on a device. This fix is built on
  documented Android service-lifecycle behavior (an already-queued
  `onStartCommand` dispatch is independent of `onCreate()`'s internal
  exception handling) rather than something reproduced here.
- Does not address the other three auto-start call sites
  (`DrivingDetectionReceiver`, `DasherAccessibilityService`'s 3
  `attemptAutoStartMonitoring` call sites) bypassing the restart circuit
  breaker entirely and continuing to hit this same underlying rejection
  independently -- a separate, related finding from the same scouting
  round, not yet addressed. This fix stops the CRASH those paths could
  also trigger (since they all ultimately call into the same
  `startTracking()`), but doesn't stop them from repeatedly attempting
  and failing to start monitoring in the first place.
- Does not change `raiseMonitoringNotActiveAlert`'s own behavior (still
  fires a full sound+vibration alert on every call, with no de-
  duplication against an already-showing alert for the same underlying
  cause) -- out of scope for this fix, which is specifically about
  preventing the crash.

## 5. Success criteria

- [x] `startTracking()`'s own foreground-start call no longer crashes
      the process on the same `SecurityException` `onCreate()` already
      guards against
- [x] A failed attempt correctly rolls back `monitoringActive`/
      `isRunning` rather than leaving the app believing tracking is
      active
- [x] A failed attempt records a restart failure for the existing
      circuit breaker and does not proceed to mark a false success
- [x] A successful foreground-start is completely unaffected by this
      change
- [x] Standalone compiled Java test (8 checks) of the exact state-
      transition logic, fully passed
- [x] `python3` brace/paren balance check clean
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver/QA confirms on a real Android 14 device that a boot-time or
      watchdog-triggered restart hitting this rejection now degrades
      gracefully (alert + clean stop) instead of crashing the app
      process.
- [ ] Driver sign-off.
