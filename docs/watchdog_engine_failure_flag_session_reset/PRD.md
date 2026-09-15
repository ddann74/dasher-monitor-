# PRD: Reset the engine-failure flag at session start

Status: IMPLEMENTED (2026-09-15). Round-13 scouting finding #2 -- the
verification pass run after `docs/monitoring_uptime_guarantee/PREMORTEM.md`'s
7-item Ralph loop closed, specifically auditing this session's own R7
fix (`docs/watchdog_engine_failure_aware_reacquire/PRD.md`) for
regressions. Added to the premortem as R21.

## 1. What was wrong

This session's own R7 fix added `MonitoringWatchdogReceiver.KEY_ENGINE_FAILURE_ACTIVE`,
a durable `SharedPreferences` flag written by
`TripForegroundService.writeWatchdogHeartbeatIfEngineHealthy` -- `true`
when the engine/DB is failing (heartbeat withheld), `false` on every
normal, healthy heartbeat write.

`consecutiveEngineFailures` (the in-memory field driving that write) is
a plain instance field on `TripForegroundService` -- it implicitly resets
to 0 for every fresh `Service` instance, including a normal Stop→Start
cycle (no OEM kill needed). But the durable flag it drives does **not**
reset the same way: it's only ever overwritten from inside
`writeWatchdogHeartbeatIfEngineHealthy`, which requires a GPS callback to
have arrived and `HEARTBEAT_INTERVAL_MS` (15s) to have passed since the
last one.

If a session ended (`stopTracking()`, or the process being killed) while
the engine genuinely was failing (`KEY_ENGINE_FAILURE_ACTIVE == true`),
that value stayed on disk. The **next** session's `TripForegroundService`
instance starts with `consecutiveEngineFailures == 0` in memory, but
`MonitoringWatchdogReceiver` reads the stale `true` from
`SharedPreferences` regardless. If that new session then hit a genuine
GPS/pipeline stall before its own first successful heartbeat tick
managed to overwrite the flag, the watchdog would wrongly attribute the
**new** session's stall to the **old**, already-resolved failure --
skipping `ACTION_REACQUIRE_LOCATION`, the real self-heal action R7's fix
exists to correctly gate.

Bounded impact: `MonitoringWatchdogReceiver.raiseAlert()` still fires
unconditionally before this branch is ever reached, so the driver is
never left with zero signal (invariant property 1 still holds) -- but
the wrong diagnostic cause gets logged, and the correct auto-recovery
action for a genuine GPS stall would be skipped for the wrong reason
(undermining property 2, "observable recovery... attempted and worked
vs. attempted and failed... tell apart").

## 2. Design

Added an explicit reset of `KEY_ENGINE_FAILURE_ACTIVE` to `false` inside
`startTracking()`'s success path, immediately after
`MonitoringWatchdogReceiver.recordRestartSuccess(this)` -- the exact
point that call's own comment already identifies as "a genuine recovery,
not merely an attempt," i.e. the same "a real new session has just
begun" moment the circuit breaker's own success-tracking already keys
off. This is reached exactly once per genuine session start (whether the
very first start, or a restart after a prior failure), matching
`consecutiveEngineFailures`'s own implicit in-memory reset for a fresh
`Service` instance.

## 3. Verification

`writeWatchdogHeartbeatIfEngineHealthy`, `startTracking()`, and
`SharedPreferences` all depend on a live Android runtime and can't be
exercised outside a device. Verified instead by:

- `python3`-based brace/paren balance check on the edited file: final
  depth 0.
- A standalone, compiled-and-run Java program
  (`EngineFailureFlagSessionResetTest.java`, `javac`/`java`), extending
  the same fake-`SharedPreferences` model already used for R7's own
  verification test, with the new reset line's exact placement
  (immediately after `recordRestartSuccess()`, before `scheduleWatchdog()`)
  confirmed identical to the real source via a fresh re-read immediately
  before writing the test:
  - **The pre-fix starting condition**: a prior session ends with the
    flag stuck `true` (engine was genuinely failing).
  - **THE ACTUAL BUG, shown for contrast**: without the reset, a genuine
    new stall in the next session is wrongly suppressed by the stale
    flag.
  - **THE FIX**: the new session's `startTracking()` success path resets
    the flag to `false`, and the same genuine stall now correctly
    triggers the real reacquire.
  - **Regression check**: a genuine engine failure arising *within* the
    new session (after the reset) still correctly sets the flag and
    skips the pointless reacquire -- confirms the reset doesn't mask a
    real, fresh failure, only a stale leftover one.

  5 checks, all passed on first run.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  `SharedPreferences` write/read timing across a genuine app restart has
  not been observed on a device.
- Does not address the underlying asymmetry (in-memory state resets
  implicitly per-instance, durable state doesn't) as a general pattern --
  this fix closes the one instance of it found for this specific flag;
  if a future scouting pass finds another durable flag with the same
  shape, it would need its own, separate fix following this same
  reasoning.

## 5. Success criteria

- [x] The engine-failure flag no longer persists stale across a session
      boundary
- [x] A genuine GPS stall early in a new session (before its own first
      heartbeat) correctly triggers the real reacquire, not wrongly
      suppressed by a prior session's resolved failure
- [x] A genuine engine failure arising within the new session itself is
      still correctly detected and still correctly skips the pointless
      reacquire
- [x] `python3` brace/paren balance check clean
- [x] Standalone compiled Java test (5 checks) of the exact reset
      placement and resulting decision logic, verified against the
      shipped source, fully passed
- [ ] HONEST LIMIT: no Android device/emulator available -- see §4.
- [ ] Driver/QA confirms on a real device that stopping monitoring while
      the engine is in a failing state, then restarting, does not
      suppress a genuine GPS-stall reacquire in the new session.
- [ ] Driver sign-off.
