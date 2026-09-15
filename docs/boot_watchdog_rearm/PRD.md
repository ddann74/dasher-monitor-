# PRD: Re-arm the watchdog directly from BootAndUpdateReceiver

Status: IMPLEMENTED (2026-09-15), scouting-pass finding (round 10, #1,
directed audit of monitoring auto-correction).

## 1. What was wrong

`MonitoringWatchdogReceiver.scheduleWatchdog()` was only ever called
from two places, both inside a *successful* run of
`TripForegroundService.startTracking()`: directly, and via
`watchdogRearmRunnable` (itself only scheduled inside `startTracking()`
too). `BootAndUpdateReceiver.onReceive()` never called it directly -- it
only fired `ACTION_START_TRACKING` at the service and relied entirely on
`startTracking()` getting far enough to arm the watchdog itself.

AlarmManager alarms do NOT survive a device reboot -- the watchdog's
alarm from before the reboot is already gone by the time this receiver
runs. Concrete scenario: device reboots (the exact trigger this receiver
exists for), `wasIntendedActive()` is true, `BootAndUpdateReceiver`
fires the restart intent. If `TripForegroundService.onCreate()`'s
foreground-start throws `SecurityException` (the documented Android 14
FGS-location eligibility rejection this codebase already guards
elsewhere, and boot-triggered starts are not obviously exempt from that
specific type-eligibility check) -- `startTracking()` is never reached,
so `scheduleWatchdog()` is never called either. Result: no watchdog
alarm exists at all after that reboot. Every mechanism rounds 8-9 built
(heartbeat staleness alert, the restart circuit breaker, the escalated
alert, GPS reacquire) is silently inert for the rest of the shift, with
zero indication to the driver that the safety net itself is gone --
worse than before those fixes existed, because "the device just
rebooted" is precisely the moment a fresh alarm most needs re-arming.

## 2. Design

`BootAndUpdateReceiver.onReceive()` now calls
`MonitoringWatchdogReceiver.scheduleWatchdog(context)` directly,
unconditionally, immediately after confirming `wasIntendedActive()` is
true -- BEFORE attempting the restart, not after, and independent of
whether that restart attempt ends up succeeding. This guarantees a
watchdog check gets armed after every reboot where monitoring was
supposed to resume, regardless of what happens to the restart attempt
itself (including a failure that happens asynchronously, inside
`TripForegroundService.onCreate()`, well outside this receiver's own
try/catch reach).

If the restart also succeeds (the common case),
`startTracking()` schedules the watchdog again moments later -- safe and
harmless, since `AlarmManager.setExactAndAllowWhileIdle` with
`FLAG_UPDATE_CURRENT` simply replaces any still-pending alarm, the same
idempotent re-arm pattern already established elsewhere in this
codebase (e.g. `watchdogRearmRunnable`).

**Incidental fix found and corrected while working in this area:**
`BootAndUpdateReceiver.notifyResumed()`'s notification used hardcoded id
`9300` -- the same id this session's own round-9 fix
(`raiseEngineFailureAlert`) had used in `TripForegroundService.java`.
Notification IDs are keyed per-package, not per-channel, so whichever of
these two completely unrelated notifications posted second would
silently replace the other in the notification shade (e.g. a genuine
"Delivery tracking error" alert could be silently wiped from view by an
unrelated "monitoring resumed after reboot" notification, or vice
versa). Changed `raiseEngineFailureAlert`'s id to `9500` (confirmed
against every other notification id in the codebase to avoid a repeat
collision).

## 3. Verification

`BootAndUpdateReceiver`/`MonitoringWatchdogReceiver` depend on live
`AlarmManager`/`Context`/`PyObject` and can't be compiled/run outside a
device or emulator in this environment. Verified instead by:

- `python3`-based brace/paren balance check on both edited files: final
  depth 0 for each.
- A standalone, compiled-and-run Java program (`javac`/`java`) mirroring
  the exact call order in `onReceive()` after the `isBootOrUpdate` check
  (confirmed structurally identical to the shipped source via direct
  read immediately before writing the test): the `wasIntendedActive`
  guard, then the (now unconditional) `scheduleWatchdog` call, then the
  try/catch restart attempt:
  - When monitoring wasn't intended active, the watchdog is correctly
    NOT re-armed (nothing to resume).
  - When monitoring was intended active and the restart succeeds, the
    watchdog is armed (unaffected by this change for the common case).
  - **The bug scenario**: when monitoring was intended active but the
    restart attempt fails, the watchdog is STILL armed -- confirms the
    safety net now survives even a failed boot-time restart, which it
    previously would not have.

  4 checks, all passed on first run.
- Manually confirmed via `grep` across every `manager.notify(...)` call
  site in the Java sources that `9500` (the new engine-failure-alert id)
  doesn't collide with any other notification id currently in use.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  reboot-then-failed-restart scenario has not been observed on a device,
  only reasoned about from documented AlarmManager-does-not-survive-
  reboot behavior and this codebase's own already-confirmed
  `SecurityException` risk on background auto-starts.
- Does not address the three OTHER auto-start call sites
  (`DrivingDetectionReceiver`, `DasherAccessibilityService`'s
  `attemptAutoStartMonitoring` call sites) that bypass the restart
  circuit breaker entirely and could still independently retry and fail
  -- a separate, related finding from the same scouting round, not yet
  addressed here.
- The `9200 + tripId` notification id pattern elsewhere in
  `TripForegroundService.java` (delivery-rating notifications) remains a
  pre-existing, unbounded-id design that could theoretically still
  collide with a fixed id for a large enough `tripId` -- out of scope
  for this fix, which only addressed the concrete, confirmed collision
  found while working in this specific area.

## 5. Success criteria

- [x] The watchdog is re-armed after a reboot whenever monitoring was
      supposed to resume, regardless of whether the restart attempt
      itself succeeds
- [x] A failed boot-time restart no longer leaves the entire fail-safe
      mechanism (staleness alert, circuit breaker, escalated alert, GPS
      reacquire) silently disarmed for the rest of the shift
- [x] Monitoring that wasn't intended active before the reboot correctly
      does not get a re-armed watchdog (no false positive)
- [x] The notification id collision between the boot-resume alert and
      the engine-failure alert is resolved
- [x] Standalone compiled Java test (4 checks) of the exact call-order
      logic, fully passed
- [x] `python3` brace/paren balance check clean on both edited files
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver/QA confirms on a real device that a reboot followed by a
      failed restart attempt still results in a working watchdog alert
      within the normal detection window.
- [ ] Driver sign-off.
