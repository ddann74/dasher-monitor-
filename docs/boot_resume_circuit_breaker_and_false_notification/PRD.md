# PRD: Circuit-breaker-aware boot/update auto-resume, and no false "resumed" notification

Status: IMPLEMENTED (2026-09-15). Round-13 scouting finding #1 -- the
verification pass run after `docs/monitoring_uptime_guarantee/PREMORTEM.md`'s
7-item Ralph loop closed, per that PRD's own acceptance criteria (a fresh
scouting pass must find nothing new before the invariant is considered
"held" rather than just "improved"). Not itself one of R1-R7; added to
the premortem as a new entry.

## 1. What was wrong

Two related bugs in `BootAndUpdateReceiver.onReceive()`'s auto-resume
path (fires after a device reboot or app update, if monitoring was
intended to be active):

**Missing circuit breaker.** `docs/circuit_breaker_other_autostart_paths/PRD.md`
(round 10) gave every *other* background auto-start path
(`DrivingDetectionReceiver`, and all 3 call sites inside
`DasherAccessibilityService.attemptAutoStart`) a check against
`MonitoringWatchdogReceiver.isRestartCircuitBreakerTripped()` before
attempting a restart -- specifically so a proven-doomed restart (e.g. the
Android 14 foreground-service-location `SecurityException`) doesn't get
re-attempted forever across every trigger. `BootAndUpdateReceiver`'s own
restart call was the one path left out, despite that same PRD's own
parent doc (`docs/monitoring_uptime_guarantee/PRD.md`) claiming this
class of gap was "closed for every other auto-start path too." A device
stuck in that failure mode would re-attempt the identical doomed start
on every single reboot or app update, indefinitely.

**False "resumed" notification.** `context.startForegroundService()`
dispatches asynchronously -- it only throws for an immediate, synchronous
rejection, not for a failure inside `TripForegroundService.onCreate()`
itself (the same `SecurityException` this file's own comment already
names). `notifyResumed(context)` used to be called unconditionally right
after that dispatch call returned without throwing, regardless of
whether the service actually finished starting. So on a device where the
restart genuinely fails downstream (`onCreate()`'s own catch block
correctly rolls back, records the failure, and raises
`raiseMonitoringNotActiveAlert`), the driver got **two contradicting
notifications** after the exact same reboot: the correct, urgent failure
alert, and a reassuring, false "Dasher Monitor resumed" notification. A
driver who trusts the reassuring one is left believing monitoring is
running when it silently is not -- directly against invariant properties
1 (no silent staleness) and 2 (observable recovery -- attempted-and-
worked vs. attempted-and-failed must be tell-apart-able) in
`docs/monitoring_uptime_guarantee/PRD.md`'s §3.

## 2. Design

**Circuit breaker.** Added the identical guard used at every other call
site: `MonitoringWatchdogReceiver.isRestartCircuitBreakerTripped(context)`
checked before attempting the start at all. When tripped, logs and
returns -- no duplicate alert, since the watchdog's own escalated alert
already told the driver a manual app open is needed.

**No false notification.** Replaced the unconditional
`notifyResumed(context)` call with `goAsync()` (the standard
`BroadcastReceiver` mechanism for deferring completion past `onReceive()`
returning) plus a short delayed re-check -- the exact same pattern
already established by `DasherAccessibilityService.attemptAutoStart`'s
own `monitoringVerifyHandler`/`MONITORING_VERIFY_DELAY_MS` (5 seconds).
After the delay:
- `TripForegroundService.isRunning == true`: the restart genuinely
  succeeded -- post the reassuring notification.
- `TripForegroundService.isRunning == false`: log the mismatch as an
  error, but do **not** raise a duplicate driver-facing alert --
  `TripForegroundService.onCreate()`'s own catch block already raised
  `raiseMonitoringNotActiveAlert` for this exact failure, and firing a
  second one would just be redundant noise.

`pendingResult.finish()` is called in a `finally` block covering both
branches, so the `PendingResult` is always released regardless of which
path executes.

## 3. Verification

`BroadcastReceiver.goAsync()`, `Handler.postDelayed`, and
`startForegroundService()` all depend on a live Android runtime and
can't be exercised outside a device. Verified instead by:

- `python3`-based brace/paren balance check on the edited file: final
  depth 0.
- A standalone, compiled-and-run Java program
  (`BootResumeCircuitBreakerTest.java`, `javac`/`java`), replicating the
  two decision points as pure functions (confirmed identical in shape to
  the real source via a fresh re-read immediately before writing the
  test):
  - **Breaker tripped**: confirms the start is skipped entirely, with no
    notification of either kind.
  - **Genuine success**: confirms the start is attempted and the
    reassuring notification correctly fires.
  - **THE ACTUAL BUG SCENARIO**: dispatch "succeeds" (no synchronous
    throw) but the service never actually ends up running -- confirms
    the false "resumed" notification is **not** posted, and an error is
    logged instead of a duplicate alert.

  7 checks, all passed on first run.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  `goAsync()`/`Handler` timing relative to `TripForegroundService.onCreate()`'s
  actual completion, and the real 5-second delay's sufficiency across
  slower devices, have not been observed on a device. If `onCreate()`
  genuinely takes longer than 5 seconds to either succeed or roll back
  on some device, this could theoretically log a false "still not
  running" error for a restart that succeeds moments later -- the same
  honest limit `DasherAccessibilityService`'s own copy of this exact
  pattern already carries, not a new one introduced here.
- Does not add its own escalated/duplicate alert on the "still not
  running" branch -- deliberately, since `TripForegroundService.onCreate()`'s
  own catch block is expected to have already raised one; if a future
  scouting pass finds a path where `onCreate()`'s failure handling itself
  doesn't reliably alert, that would be a separate, more fundamental gap
  than this fix's scope.

## 5. Success criteria

- [x] `BootAndUpdateReceiver`'s auto-resume now respects the same restart
      circuit breaker every other auto-start path already does
- [x] The "Dasher Monitor resumed" notification only fires when the
      restart is confirmed to have genuinely succeeded
- [x] No duplicate driver-facing alert on a confirmed failure --
      `TripForegroundService`'s own alert still covers it
- [x] `python3` brace/paren balance check clean
- [x] Standalone compiled Java test (7 checks) of both decision points,
      verified against the shipped source, fully passed
- [ ] HONEST LIMIT: no Android device/emulator available -- see §4.
- [ ] Driver/QA confirms on a real device (ideally one with a documented
      Android 14 FGS-location eligibility issue) that a reboot no longer
      produces both a failure alert AND a false "resumed" notification.
- [ ] Driver sign-off.
