# PRD: Make every auto-start path respect the restart circuit breaker

Status: IMPLEMENTED (2026-09-15), scouting-pass finding (round 10, #3,
directed audit of monitoring auto-correction).

## 1. What was wrong

The restart circuit breaker added in `docs/watchdog_restart_circuit_breaker/PRD.md`
was only ever CHECKED inside `MonitoringWatchdogReceiver.onReceive()`'s
own restart branch. `recordRestartFailure()` (called from
`TripForegroundService.onCreate()`'s `SecurityException` catch block)
increments a single, SHARED SharedPreferences counter regardless of
which caller's restart attempt actually failed -- but two other
independent auto-start paths had zero awareness that counter, or the
breaker built around it, existed at all:

- `DrivingDetectionReceiver.onReceive()` -- fires `startForegroundService()`
  directly on every detected "entering vehicle" transition.
- `DasherAccessibilityService.attemptAutoStartMonitoring()` -- used by 3
  separate call sites (service-connect detection, a debounced foreground
  transition, and Dash-Paused auto-resume).

On a device that consistently fails the Android 14 FGS-location
eligibility check (the exact class of device the breaker's own doc
cites, "17 occurrences over ~30 hours"), the watchdog correctly trips
its breaker after 3 failures and stops auto-restarting, raising its
escalated alert. But a driver getting in and out of the car all day
keeps re-triggering `DrivingDetectionReceiver`, which has no idea the
breaker exists, retries `startForegroundService()` again, hits the
identical failure, increments the same shared counter further, and
fires its OWN separate `raiseMonitoringNotActiveAlert()` -- with no
de-duplication, replaying sound+vibration on every call. The exact
"battery-draining spin-up/teardown loop with indistinguishable repeat
alerts" the breaker's own doc says it exists to eliminate still happens,
just through the paths it didn't know about.

## 2. Design

Added `MonitoringWatchdogReceiver.isRestartCircuitBreakerTripped(Context)`
-- a public wrapper around the existing private
`getConsecutiveRestartFailures` + `RESTART_CIRCUIT_BREAKER_THRESHOLD`
check, so other components can consult the SAME shared state without
duplicating the threshold constant or the counter's storage details.

Both other auto-start paths now check it before attempting anything:

- `DrivingDetectionReceiver.onReceive()`: the existing
  `if (!TripForegroundService.isRunning) { ... }` became
  `if (breakerTripped) { log only } else if (!isRunning) { ...
  existing attempt ... }` -- a quiet log line when tripped, not a
  duplicate alert (the watchdog's own escalated alert already told the
  driver this needs a manual app open).
- `DasherAccessibilityService.attemptAutoStartMonitoring()`: an early
  `if (breakerTripped) { log; return; }` guard at the top, covering all
  3 call sites at once since they all funnel through this one method.

The reset path is unaffected: none of these three gated call sites are
how the counter ever gets reset. A driver manually opening the app and
tapping "Start Monitoring" goes through `MainActivity`'s own direct
`startForegroundService()` call (a genuinely foreground, user-initiated
start, which doesn't hit the same background-eligibility restriction in
the first place) -- untouched by this fix -- and a successful
`startTracking()` from that path still calls
`MonitoringWatchdogReceiver.recordRestartSuccess()`, resetting the
counter to 0 and letting all three background auto-start paths resume
normally on their next trigger.

## 3. Verification

`MonitoringWatchdogReceiver`/`DrivingDetectionReceiver`/
`DasherAccessibilityService` depend on live `AlarmManager`/
`AccessibilityService`/`Context` and can't be compiled/run outside a
device or emulator in this environment. Verified instead by:

- `python3`-based brace/paren balance check on all three edited files:
  final depth 0 for each.
- A standalone, compiled-and-run Java program (`javac`/`java`)
  containing a verbatim copy of `isRestartCircuitBreakerTripped`'s exact
  logic (confirmed identical to the shipped source via `grep`
  immediately before writing the test) and the shared gating decision
  now applied at all three (plus the watchdog's own, pre-existing)
  call sites:
  - With zero prior failures, an auto-start path still attempts
    normally -- unaffected by this fix for the common, healthy case.
  - Below the threshold (2 failures), still attempts normally.
  - **The bug scenario**: at the threshold (3 failures, matching the
    watchdog's own breaker), another auto-start path now correctly
    skips instead of blindly retrying the identical doomed call.
  - Well past the threshold (5 failures), still skips -- doesn't flap
    back to attempting.
  - The decision function agrees with the watchdog's own threshold
    check at every boundary tested -- confirms all auto-start paths
    now agree on whether the breaker is tripped, so none can retry
    while another has correctly gone quiet.

  5 checks, all passed on first run.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  "repeated vehicle entries while the breaker is tripped" scenario has
  not been observed on a device, only reasoned about from this
  codebase's own already-documented Android 14 eligibility restriction
  and control-flow tracing.
- `DrivingDetectionReceiver.raiseMonitoringNotActiveAlert` and
  `DasherAccessibilityService`'s own copy of the same alert are
  themselves still NOT de-duplicated against each other or against the
  watchdog's own alerts if they fire in the narrow window before the
  breaker trips (i.e. failures 1 and 2, before the 3rd crosses the
  threshold) -- this fix stops the alerts once the breaker is tripped,
  it doesn't add cross-component alert de-duplication for the earlier,
  below-threshold failures. That's a separate, narrower gap than the one
  this fix closes.
- Does not change `MainActivity`'s own manual "Start Monitoring" path in
  any way -- confirmed by design (see §2) rather than by editing that
  file.

## 5. Success criteria

- [x] `DrivingDetectionReceiver` and `DasherAccessibilityService`'s
      auto-start paths now check the same shared circuit breaker state
      the watchdog uses
- [x] Once the breaker is tripped, these paths skip quietly (log only)
      instead of retrying and firing a duplicate alert
- [x] Below the threshold, all paths continue to behave exactly as
      before this fix
- [x] The reset path (a manual, foreground "Start Monitoring" tap)
      remains completely unaffected, so a genuine recovery still un-trips
      the breaker for all auto-start paths at once
- [x] Standalone compiled Java test (5 checks) of the exact shared
      decision logic, verified against the shipped source, fully passed
- [x] `python3` brace/paren balance check clean on all three edited
      files
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver/QA confirms on a real Android 14 device that repeated
      driving-detected/Dasher-foreground triggers no longer produce
      repeat alerts once the watchdog's own circuit breaker has tripped.
- [ ] Driver sign-off.
