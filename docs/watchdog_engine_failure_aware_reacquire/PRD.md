# PRD: Make the watchdog's GPS reacquire engine-failure-aware

Status: IMPLEMENTED (2026-09-15). Ralph-loop iteration 7/N of
`docs/monitoring_uptime_guarantee/PREMORTEM.md`, closing risk R7
(round-11 scouting pass finding #4, directed audit of the monitoring
process). This is the last remaining Open item in the register.

## 1. What was wrong

`MonitoringWatchdogReceiver` distinguishes two situations when the
heartbeat goes stale: `TripForegroundService.isRunning == false` (the
service itself is dead, handled by the restart branch) vs. `isRunning ==
true` (the service object is alive but the heartbeat still went stale --
handled by unconditionally sending `ACTION_REACQUIRE_LOCATION`, per
`docs/watchdog_stalled_gps_reacquire/PRD.md`).

But `isRunning == true` + stale heartbeat has two genuinely different
root causes the receiver couldn't tell apart:

1. A real GPS/location pipeline stall (the case the reacquire action
   actually fixes).
2. `TripForegroundService.writeWatchdogHeartbeatIfEngineHealthy`
   deliberately **withholding** the heartbeat write because
   `consecutiveEngineFailures >= ENGINE_FAILURE_ALERT_THRESHOLD` -- an
   engine/DB problem, not a GPS problem at all (`docs/heartbeat_engine_health_gate/PRD.md`'s
   own mechanism, working exactly as designed).

The receiver always guessed case 1 and fired the reacquire regardless --
harmless (idempotent, safe to call), but pointless in case 2: GPS was
never broken, so re-registering location updates does nothing for the
real problem, and the driver is already separately alerted for the real
cause via `raiseEngineFailureAlert`. `MonitoringWatchdogReceiver` had
zero visibility into `consecutiveEngineFailures` -- an in-memory-only
field on `TripForegroundService`, a separate component/process this
`BroadcastReceiver` can't read directly.

## 2. Design

Added a new `SharedPreferences` boolean flag,
`MonitoringWatchdogReceiver.KEY_ENGINE_FAILURE_ACTIVE`, in the exact same
prefs file (`PREFS_NAME = "monitoring_watchdog_prefs"`) and lifecycle
pattern already used for `KEY_LAST_HEARTBEAT_MS` -- durable across
process death, readable by the receiver without needing the live
`TripForegroundService` process, same reasoning that mechanism was
already built on.

`writeWatchdogHeartbeatIfEngineHealthy` now writes this flag on both
paths:
- Withholding (engine failing): writes `KEY_ENGINE_FAILURE_ACTIVE = true`
  (and still withholds `KEY_LAST_HEARTBEAT_MS`, unchanged behavior).
- Writing normally (engine healthy): writes `KEY_ENGINE_FAILURE_ACTIVE =
  false` alongside the heartbeat timestamp, so a later recovery
  correctly clears the flag on the very next healthy heartbeat -- a
  genuine GPS stall that happens AFTER the engine recovers is never
  wrongly attributed to the old engine failure.

`MonitoringWatchdogReceiver`'s `isRunning == true` branch now reads this
flag (defaulting to `false`, so a cold start or a never-triggered engine
failure behaves exactly as before -- sends the reacquire) and branches:
- `true` (engine/DB is the real cause): skips the reacquire entirely,
  logs the actual cause instead of a misleading "asked it to re-register
  location updates" line.
- `false` (genuine GPS stall, or never determined otherwise): unchanged
  -- sends `ACTION_REACQUIRE_LOCATION` exactly as before.

## 3. Verification

Both `writeWatchdogHeartbeatIfEngineHealthy` and
`MonitoringWatchdogReceiver`'s decision logic depend on live
`SharedPreferences`/`Context`, which can't be instantiated outside a
device. Verified instead by:

- `python3`-based brace/paren balance check on both edited files: final
  depth 0 for each.
- A standalone, compiled-and-run Java program
  (`WatchdogEngineFailureAwareReacquireTest.java`, `javac`/`java`) using
  a plain `HashMap` in place of the shared prefs file, with both the
  write method and the receiver's read/branch logic verbatim-copied and
  confirmed identical to the real source via a fresh re-read immediately
  before writing the test:
  - **Normal healthy write**: heartbeat timestamp written, flag set
    `false`.
  - **THE ACTUAL BUG SCENARIO**: engine failing -- heartbeat withheld,
    flag set `true`, and the receiver's decision logic confirmed to skip
    the reacquire (not send it).
  - **Regression check**: a genuine GPS/pipeline stall (engine healthy,
    flag `false` from the last real write) still correctly triggers the
    real reacquire.
  - **Cold-start default**: a never-written flag defaults to sending the
    reacquire, not skipping it -- confirms this fix can't accidentally
    suppress a genuine, first-ever staleness event.
  - **Recovery**: the flag correctly flips back to `false` on the next
    healthy write after an engine failure, and a later genuine stale
    heartbeat after that recovery correctly triggers the real reacquire
    again -- confirms no stuck-`true` state after the engine recovers.

  10 checks, all passed on first run.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  `SharedPreferences` cross-process durability and the real
  `BroadcastReceiver` read timing relative to the service's writes have
  not been observed on a device.
- This is a diagnostic-quality-of-signal fix, not a new recovery
  capability -- the driver was already correctly alerted for the engine/
  DB failure case via `raiseEngineFailureAlert` before this fix; this
  only stops a pointless, harmless-but-misleading reacquire action and
  diagnostic log line from firing alongside it.
- Like every other cross-component `SharedPreferences` signal in this
  app (the heartbeat timestamp itself included), this is eventually
  consistent, not transactional -- a read racing an in-flight write is
  the same accepted, pre-existing tradeoff the heartbeat mechanism
  itself already relies on, not a new one introduced here.

## 5. Success criteria

- [x] The watchdog no longer sends a pointless location reacquire when
      the real cause of a stale heartbeat is a withheld write due to
      engine/DB failure
- [x] A genuine GPS/pipeline stall (engine healthy) still correctly
      triggers the real reacquire, unchanged
- [x] The flag correctly clears on engine recovery, so a later genuine
      stale heartbeat isn't wrongly suppressed
- [x] A cold start or never-triggered engine failure defaults to the
      original (reacquire-sending) behavior, not silently to skipping
- [x] `python3` brace/paren balance check clean on both edited files
- [x] Standalone compiled Java test (10 checks) of the exact write and
      read/branch logic, verified against the shipped source, fully
      passed
- [ ] HONEST LIMIT: no Android device/emulator available -- see §4.
- [ ] Driver/QA confirms on a real device that an engine/DB failure
      produces the "skipping the pointless location reacquire" log line
      instead of a reacquire attempt, and that a genuine GPS stall still
      triggers the real reacquire as before.
- [ ] Driver sign-off.
