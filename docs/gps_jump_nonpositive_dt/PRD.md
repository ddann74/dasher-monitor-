# PRD: GPS-jump plausibility check missed duplicate/out-of-order timestamps

Status: IMPLEMENTED (2026-09-14), scouting-pass finding (round 6, #1).

## 1. What was wrong

The round-5 GPS-jump plausibility fix
(`docs/gps_jump_plausibility_check/PRD.md`,
`GPS_JUMP_MAX_PLAUSIBLE_SPEED_KMH`) rejects a GPS fix whose implied speed
since the last accepted fix is physically impossible -- but only ran
that check inside `if dt_seconds > 0:`. When a new fix arrived with a
timestamp equal to or earlier than the previous accepted fix
(`dt_seconds <= 0`), the plausibility check was skipped entirely and
`TripManager._process_point_during_trip`
(`app/src/main/python/drive_monitor.py`) still added the point's full
distance to `self._cumulative_distance_km` unconditionally.

This is exactly the case that most needs scrutiny -- a duplicate or
out-of-order fix -- left as the one unchecked path. It's also a
realistic scenario, not just a theoretical one: `TripForegroundService`
stamps each processed GPS point with the wall-clock processing time
(`System.currentTimeMillis()`), computed inside the loop over
`LocationResult.getLocations()`, rather than each location's own fix
time. `LocationResult` can legitimately deliver several queued fixes in
one callback (most commonly after a Doze-mode wakeup releases a batch at
once -- the exact scenario this app's own watchdog/wake-lock code
exists to handle). If two locations in that batch get processed within
the same millisecond, `dt_seconds` comes out to `0.0`, bypassing the
plausibility check regardless of how far apart the two fixes actually
are.

A single such bogus tick permanently corrupts `_cumulative_distance_km`,
which feeds directly into the persisted `trips.distance_km`,
`fuel_cost_estimate`, `offer_distance_accuracy.actual_total_km` /
`actual_delivery_km` (used to correct future distance estimates on the
offer screen), and personal calibration -- a single bad GPS tick can
permanently poison a driver's real trip history and learned
distance/fuel numbers.

## 2. Design

Treat `dt_seconds <= 0` the same as a failed plausibility check: reject
the point (don't add its distance to `_cumulative_distance_km`, don't
append it to `self.gps_points`, don't run harsh-event/delay detection on
it), and return immediately -- exactly the same "reject entirely, keep
the anchor at the last known-good point" behavior the round-5 fix
already uses for an implausible-speed rejection. This is the minimal
change: reorder the existing `if dt_seconds > 0:` guard into an early
`if dt_seconds <= 0: return` followed by the existing implied-speed
check unconditionally (since it's now only reached when `dt_seconds >
0`, division by a non-positive value can't happen).

`_detect_harsh_events`/`_detect_major_delay` (which read `self._last_point`,
set unconditionally at the end of `on_gps_update` regardless of whether
`_process_point_during_trip` accepted the point) already have their own
independent `dt <= 0: return` guards, so they're unaffected by this
change and continue to work correctly.

## 3. Verification

Real, executable Python test against the actual `drive_monitor.py`
engine (`TripManager._process_point_during_trip` called directly on a
fresh `DriveMonitorEngine` instance):

- Two normal, plausible driving ticks accumulate real distance (sanity
  check the trip mechanics are exercised correctly).
- A same-timestamp fix ~120km away from the last accepted point (the
  exact bug scenario) is rejected -- cumulative distance unchanged.
- An earlier-timestamp fix, also ~120km away, is likewise rejected.
- A genuine, plausible point immediately after the rejected ones still
  applies normally with a small/plausible delta -- confirms the
  comparison anchor correctly stayed at the last known-good point
  rather than either rejected fix.
- The original round-5 positive-`dt_seconds` implausible-speed rejection
  still works unchanged (regression check).

6 checks, all passed on first run. `python3 -m py_compile` clean.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  batched-`LocationResult`-after-Doze-wakeup scenario that produces
  duplicate processing timestamps has not been observed on a device,
  only reproduced by directly calling the engine method with the
  timestamp pattern that scenario would produce.
- Does not change how `TripForegroundService` stamps GPS points
  (`System.currentTimeMillis()` inside the batch loop, rather than each
  `Location`'s own `getTime()`) -- that's a second, independent
  improvement noted by the scouting pass but out of scope for this fix,
  which addresses the engine-side blind spot directly regardless of
  which timestamp source the Java side ends up using.
- A fix with `dt_seconds` exactly `0` but a small, physically plausible
  distance (e.g. GPS noise while stationary) is now also rejected rather
  than silently adding a few meters -- an intentional, conservative
  choice consistent with treating a non-positive interval as
  unmeasurable, not a regression in the numbers that matter (a few
  meters of GPS noise was never meaningful signal).

## 5. Success criteria

- [x] A same-or-earlier-timestamp fix no longer bypasses the
      plausibility check
- [x] Such a fix is rejected the same way an implausible-speed fix
      already is (no distance added, anchor stays at last known-good
      point)
- [x] The original positive-`dt_seconds` implausible-speed rejection
      still works
- [x] Real executable Python test (6 checks) against the actual engine,
      fully passed
- [x] `python3 -m py_compile` clean
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver confirms in real use that trip distance stays accurate
      across Doze-mode wakeups with batched location deliveries.
- [ ] Driver sign-off.
