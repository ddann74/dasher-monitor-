# PRD: Recover a stalled GPS pipeline even when the service reports "running"

Status: IMPLEMENTED (2026-09-15), scouting-pass finding (round 9, #3,
directed audit of monitoring auto-correction).

## 1. What was wrong

`MonitoringWatchdogReceiver.onReceive()`'s only recovery action was
gated entirely on `!TripForegroundService.isRunning`. `isRunning` has
exactly three write sites -- set `true` in `startTracking()`, cleared in
`stopTracking()` and `onDestroy()` -- all tied to service LIFECYCLE, never
to functional health. So any failure mode where the service object
survives but GPS ticks silently stop arriving (a stalled
`FusedLocationProviderClient` callback chain, or the system Location
toggle being cycled off and back on) correctly made the heartbeat go
stale and correctly fired the existing staleness alert (`raiseAlert`) --
but then hit `if (!TripForegroundService.isRunning)`, found it `true`,
and skipped the restart branch entirely. Every subsequent watchdog cycle
repeated the same alert with zero recovery ever attempted -- not even a
safe one.

## 2. Design

Added a genuinely safe recovery action for exactly this case, and wired
it into the `else` branch of the existing `isRunning` check:

- New `TripForegroundService.ACTION_REACQUIRE_LOCATION` action, handled
  in `onStartCommand()`, dispatching to a new `reacquireLocationUpdates()`
  method: no-ops if `!monitoringActive` (nothing to reacquire), otherwise
  calls `fusedLocationClient.removeLocationUpdates(locationCallback)`
  followed by `startLocationUpdatesAtInterval(...)` at whatever GPS tier
  (`currentGpsIntervalTier`) was already active -- NOT forced back to the
  fastest moving-tier interval, so a genuinely deep-parked driver doesn't
  see a battery-cost spike just because a recovery attempt ran.
  `requestLocationUpdates()` replacing an existing registration is a
  normal, safe, idempotent Fused Location Provider operation -- this
  can't make a genuinely healthy registration worse, only possibly fix a
  stalled one.
- `MonitoringWatchdogReceiver.onReceive()`'s restart branch now has a
  real `else`: when `isRunning` is `true` (so the restart path doesn't
  apply), it sends `ACTION_REACQUIRE_LOCATION` to the already-running
  instance via `startForegroundService` -- which, unlike the restart
  path, does NOT risk Android 14's background-FGS-eligibility
  `SecurityException`, since the service is already in the foreground
  state, not being newly promoted into it. This action therefore doesn't
  need its own circuit breaker the way `docs/watchdog_restart_circuit_breaker/PRD.md`'s
  restart path does -- it's lightweight (no process spin-up/teardown)
  and safe to simply retry every watchdog cycle if the underlying cause
  persists; the driver is kept informed regardless via the existing,
  unconditional `raiseAlert` that already fires every stale cycle.

## 3. Verification

`MonitoringWatchdogReceiver`/`TripForegroundService` depend on live
`AlarmManager`/`FusedLocationProviderClient`/`Context` and can't be
compiled/run outside a device or emulator in this environment. Verified
instead by:

- `python3`-based brace/paren balance check on both edited files: final
  depth 0 for each.
- A standalone, compiled-and-run Java program (`javac`/`java`) mirroring
  the exact outer `if (!isRunning) {...} else {...}` dispatch structure
  from `onReceive()`, and the exact tier-to-interval mapping from
  `reacquireLocationUpdates()` (confirmed identical to the shipped
  source via `grep` immediately before writing the test):
  - `isRunning=false` still dispatches to the existing restart/circuit-
    breaker path, unaffected by this change.
  - **The bug scenario**: `isRunning=true` (service alive, heartbeat
    stale) now dispatches to the new reacquire-location recovery action
    -- previously this combination triggered no recovery action of any
    kind.
  - Tier 0 (moving) maps to the 1s interval, tier 1 (stationary) to 5s,
    tier 2 (deep-park) to 30s -- confirms the re-registration reuses
    whatever tier was already active rather than resetting to the
    fastest, most battery-hungry rate.

  5 checks, all passed on first run.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  stalled-`FusedLocationProviderClient`-callback or Location-toggle-
  cycled scenario has not been observed on a device, only reasoned about
  from Android's documented API behavior and this codebase's own control
  flow.
- Does not specifically detect or distinguish WHY the pipeline stalled
  (e.g. the system Location toggle being off entirely, which
  `LocationManager.isProviderEnabled()` could detect -- a separate,
  not-yet-implemented finding from the same scouting round, out of
  scope for this fix). If Location is off system-wide,
  `requestLocationUpdates()` will simply keep failing to deliver
  callbacks after this re-registration too -- this fix does not claim to
  fix that specific case, only to ensure SOME recovery attempt happens
  instead of none, for the broader class of "isRunning=true but stale."
- No circuit breaker was added for this specific action, deliberately --
  see §2's reasoning (it's lightweight/idempotent, and the driver is
  already kept informed via the existing unconditional staleness alert
  regardless of whether this recovery attempt succeeds). If real-world
  evidence later shows this assumption wrong (e.g. it turns out to have
  a real cost or a failure mode worth circuit-breaking), that would be a
  targeted follow-up.
- Does not change `raiseAlert`'s own text to reflect that a recovery
  attempt was made behind the scenes -- the driver-facing message stays
  the same generic "monitoring may have stopped, open the app to check"
  regardless of which internal recovery path (if any) is being
  attempted, consistent with how the restart path's own alert already
  behaves before this round's circuit-breaker escalation kicks in.

## 5. Success criteria

- [x] A service that reports `isRunning=true` but has a stale heartbeat
      now triggers a real recovery attempt, not silence
- [x] The recovery action never forces GPS polling back to the fastest
      tier -- reuses whatever tier was already active
- [x] The recovery action cannot trigger the Android 14 background-FGS
      `SecurityException` the restart path is exposed to
- [x] The existing `isRunning=false` restart/circuit-breaker path is
      completely unaffected by this change
- [x] Standalone compiled Java test (5 checks) of the exact dispatch and
      tier-mapping logic, verified against the shipped source, fully
      passed
- [x] `python3` brace/paren balance check clean on both edited files
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver/QA confirms on a real device that a stalled/stuck location
      callback chain (simulated, e.g., by toggling Location off and back
      on while monitoring is active) recovers without a full app
      restart.
- [ ] Driver sign-off.
