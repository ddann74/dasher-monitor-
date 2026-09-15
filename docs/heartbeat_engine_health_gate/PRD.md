# PRD: Gate the watchdog heartbeat on engine/DB health, not just GPS arrival

Status: IMPLEMENTED (2026-09-15), scouting-pass finding (round 9, #2,
directed audit of monitoring auto-correction).

## 1. What was wrong

`TripForegroundService`'s location callback called `maybeLogHeartbeat(tsMs)`
-- which writes `MonitoringWatchdogReceiver.KEY_LAST_HEARTBEAT_MS`, the
exact SharedPreferences value the watchdog reads for staleness -- BEFORE
`engine.callAttr("on_gps_update", ...)` ever ran. So heartbeat freshness
only ever proved `FusedLocationProviderClient` delivered a location fix;
it said nothing about whether the actual engine/DB pipeline (offer
detection, trip state, every DB write) was working.

`drive_monitor.py`'s sqlite3 connection is a single long-lived connection
created once, with no reconnect/recovery path anywhere in the live
`on_gps_update` call path (only in separate export/backup code). If that
connection ever became unusable mid-session (locked, a corrupted
handle), every `on_gps_update` call would raise an exception -- caught,
logged as "GPS tick exception," and otherwise completely silent: no
counter, no escalation, no user-visible alert anywhere. Meanwhile GPS
ticks kept arriving normally, so the heartbeat this file writes stayed
perpetually fresh, meaning `MonitoringWatchdogReceiver` never saw
staleness, never alerted, and never attempted a restart. The entire
delivery-tracking pipeline could go completely dead for the rest of a
shift while every liveness signal in the app reported healthy.

## 2. Design

Two changes, both scoped to the location callback and
`maybeLogHeartbeat`:

- Added `consecutiveEngineFailures` (int) and `engineFailureAlertRaised`
  (boolean) instance fields. The success path of the `on_gps_update`
  try/catch resets both; the catch (failure) path increments the
  counter.
- Split the watchdog-visible heartbeat write out of `maybeLogHeartbeat`
  into its own method, `writeWatchdogHeartbeatIfEngineHealthy`, which
  skips the write entirely once `consecutiveEngineFailures` reaches
  `ENGINE_FAILURE_ALERT_THRESHOLD` (3). `checkAndLogPermissions` (also
  called from `maybeLogHeartbeat`) is deliberately NOT gated the same
  way -- permission state is independent of engine/DB health and should
  keep being checked regardless.
- Once the failure streak crosses the threshold, an edge-triggered
  (fires once per streak, not every tick) `raiseEngineFailureAlert`
  notification fires -- a distinct, correctly-worded alert ("Delivery
  tracking has failed Nx in a row (internal error) -- restart the app to
  reset it"), NOT a reuse of `raisePermissionRevokedAlert`'s "turned
  off... until re-enabled" wording, which would be misleading for an
  internal-error condition rather than a permission toggle.

With the heartbeat write gated this way, the EXISTING watchdog
infrastructure this session already built -- the staleness alert and the
circuit-breaker-guarded auto-restart
(`docs/watchdog_restart_circuit_breaker/PRD.md`) -- now also applies to
this failure mode automatically: once staleness accumulates (because the
write stopped), the watchdog's own alert fires as a second, independent
signal, and if the driver later force-stops/restarts the app, a fresh
process gets a fresh (working) engine/DB connection.

## 3. Verification

`TripForegroundService`'s location callback depends on live
`FusedLocationProviderClient`/Chaquopy `PyObject`/`NotificationManager`
and can't be compiled/run outside a device or emulator in this
environment. Verified instead by:

- `python3`-based brace/paren balance check on the edited file: final
  depth 0.
- A standalone, compiled-and-run Java program (`javac`/`java`) mirroring
  the exact counter/gating/alert-edge-trigger state machine (confirmed
  identical in structure and threshold to the shipped source via `grep`
  immediately before writing the test):
  - Normal healthy ticks write the heartbeat every time, no alert.
  - **The bug scenario**: simulated engine-call failures (GPS ticks
    still "arriving," engine calls failing) -- confirmed the heartbeat
    write still happens below the threshold (1-2 failures, matching the
    old, buggy behavior for a merely transient blip) but is correctly
    SKIPPED once the failure streak reaches the threshold (3) -- the
    watchdog can now see real staleness despite GPS ticks continuing.
  - The specific engine-failure alert fires exactly once at the
    threshold, not on every failing tick, and not a second time while
    still broken (edge-triggered, matching this codebase's established
    pattern for every other repeating-condition alert).
  - A genuine recovery (a successful engine call) resumes heartbeat
    writes immediately and correctly resets the alert-raised flag, so a
    LATER, independent failure streak can alert again rather than being
    permanently silenced by one past alert.

  10 checks, all passed on first run.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  locked/corrupted-sqlite3-connection scenario has not been observed on
  a device, only reasoned about from this codebase's own confirmed
  absence of any reconnect path in the live engine call path (verified
  by reading the relevant code, not by triggering a real database
  lock).
- The threshold (3 consecutive failures) is a judgment call, not a
  derived constant -- same honesty status as this codebase's other
  tuned thresholds, chosen to avoid alerting/gating on one transient
  exception while still catching a genuinely stuck pipeline promptly.
- Does not add any reconnect/self-heal logic to `drive_monitor.py`'s
  sqlite3 connection itself -- this fix is purely about DETECTING and
  surfacing the failure (closing the "silent, invisible" gap), not
  making the connection self-repair. A driver still needs to restart the
  app to get a fresh connection, per the alert's own text.
- `checkAndLogPermissions` and the rest of `maybeLogHeartbeat`'s
  existing behavior (diagnostic logging, permission re-checks) are
  unaffected by this change and keep running on their normal 15s cadence
  regardless of engine health -- intentional, since those checks are
  independent of the DB/engine pipeline's own health.

## 5. Success criteria

- [x] The watchdog-visible heartbeat no longer advances once the engine/
      DB pipeline has been failing repeatedly, even while GPS ticks keep
      arriving
- [x] A distinct, correctly-worded alert fires once per failure streak,
      not spammed every tick
- [x] A genuine recovery immediately resumes normal heartbeat writes and
      resets the alert state for future streaks
- [x] Permission checks and other heartbeat-adjacent logging are
      unaffected by engine health
- [x] Standalone compiled Java test (10 checks) of the exact state
      machine, fully passed
- [x] `python3` brace/paren balance check clean
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver/QA confirms on a real device that a simulated repeated
      engine failure now produces both the specific "Delivery tracking
      error" alert and, if left unresolved, the watchdog's own staleness
      alert shortly after.
- [ ] Driver sign-off.
