# PRD: Dash-Pause auto-detection was finalizing mid-delivery trips as complete

Status: IMPLEMENTED (2026-09-15), scouting-pass finding (round 7, #2).

## 1. What was wrong

`TripManager.force_end_trip` (`app/src/main/python/drive_monitor.py`)
has no guard against ending a trip mid-delivery -- unlike
`_evaluate_trip_end`, which already refuses to naturally end a DASHER
trip while a dropoff stop is still unmatched or a pickup is active and
not yet departed. `TripForegroundService.stopTracking()` calls
`force_end_trip()` unconditionally for every stop, including an
auto-pause stop (`isAutoPauseStop=true`) -- fired whenever
`DasherAccessibilityService` detects the "Dash Paused" screen
(`DasherAccessibilityService.java:976-989`), which briefly appears and
clears again within seconds, GPS tracking pausing and resuming
automatically around it.

Pausing a Dash mid-route to the customer -- a normal, ordinary workflow
(a short break, or the auto-pause firing on the Dash Paused screen) --
therefore permanently finalized the in-progress trip right at the pause
point, as if the delivery were complete. That:
- Scored the interrupted trip as a bad/incomplete delivery
  (`geofence_hit_ratio`/`composite_score` tanked purely because the
  dropoff stop looked "unmatched," not because of any real driving or
  safety issue).
- Wrote a truncated distance/time into the `offer_distance_accuracy`
  learning table (the actual delivery leg was only partially driven at
  the pause point), with no flag distinguishing a genuinely-completed
  job from a pause-truncated one -- permanently polluting the data this
  app uses to learn whether an offer's claimed distance includes the
  drive to the restaurant, and actual-vs-estimated hourly rate.
- Split what should be ONE continuous delivery into two separate trip
  rows: the truncated one ending at the pause, and a new one starting
  fresh once GPS resumes (losing the original pickup's context, since
  `_start_trip` clears `self.pickup` once it's already `recorded`).

## 2. Design

Added an `allow_mid_delivery_end` parameter (default `True`, preserving
the exact original unconditional behavior for every existing caller) to
`TripManager.force_end_trip` and its `DriveMonitorEngine` wrapper. When
`False`, it applies the SAME guard `_evaluate_trip_end` already uses --
a DASHER-mode trip with a pending unmatched stop or an active,
not-yet-departed pickup is left alone (returns `None`, trip stays
`STATE_ACTIVE`) instead of being finalized.

`TripForegroundService.stopTracking()` now passes
`!isAutoPauseStop` for this parameter: an auto-pause stop
(`isAutoPauseStop=true`) passes `false`, so a genuinely mid-delivery
trip is left ACTIVE through the brief pause -- GPS resumes within
seconds (per the existing `EXTRA_AUTO_PAUSE_STOP` comment) and the SAME
trip continues and completes naturally once the driver actually reaches
the dropoff. A manual "Stop Monitoring" tap (`isAutoPauseStop=false`)
keeps passing `true` (the original, unconditional behavior) -- there,
finalizing a truncated trip is still far better than leaving it orphaned
forever, which is the original bug `force_end_trip` exists to fix. The
`onDestroy()` crash-safety-net call site is untouched (still calls with
no argument, defaulting to `True`) for the same reason.

An auto-pause with NO delivery in progress (no pending stop, no active
pickup -- e.g. the driver is just browsing offers, not on a delivery)
still ends the trip normally either way, since the guard only triggers
when a delivery is genuinely in progress.

## 3. Verification

Real, executable Python test against the actual `drive_monitor.py`
engine:

- **The bug scenario**: a DASHER-mode trip with a pending, unmatched
  dropoff stop, hit with `force_end_trip(allow_mid_delivery_end=False)`
  (the auto-pause call) -- confirmed it's a no-op (`None` returned),
  the trip stays `STATE_ACTIVE`, and the trip's DB row is NOT persisted
  as ended (`end_time` still `NULL`).
- **Continuity**: after that no-op, GPS ticks resuming through the real
  public `on_gps_update` entry point (not internal methods) drive the
  SAME trip to the dropoff and park there long enough -- confirmed the
  dropoff stop becomes matched and the trip completes naturally under
  its ORIGINAL `trip_id`, not a second, split trip.
- **Regression check**: the same mid-delivery scenario with the default
  `allow_mid_delivery_end=True` (a manual-stop-style call) still
  finalizes the trip exactly as before this fix -- confirms the
  orphaned-trip bug `force_end_trip` originally fixed is not
  reintroduced for manual stops/crash recovery.
- **No-overreach check**: an auto-pause call with no delivery in
  progress (no pending stop, no pickup) still ends the trip normally --
  confirms the guard doesn't block legitimate auto-pause endings outside
  an actual delivery.

11 checks, all passed on first run. `python3 -m py_compile` clean.
Java-side brace/paren balance verified via script (both final depths 0).

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  Dash-Pause-screen-appears-and-clears-within-seconds scenario has not
  been observed on a device, only reproduced by directly driving the
  engine's public API in the sequence that scenario would produce.
- Does not address a pause that lasts much LONGER than the "seconds"
  the existing code comments describe (e.g. a driver who pauses Dash
  and then genuinely stops driving for a long break mid-delivery, GPS
  tracking off the whole time). In that case the trip now stays
  ACTIVE indefinitely in memory until GPS resumes and it can complete
  or be recovered normally -- better than the prior silent truncation,
  but a very long real-world gap between the last pre-pause GPS point
  and the first post-resume one could still produce an odd-looking
  gap in the trip's own GPS point history (not a data-corruption risk,
  since `_process_point_during_trip`'s plausibility/timestamp guards
  already reject an implausible jump across that gap -- just a
  cosmetic one).
- Does not add any explicit "paused" state or flag to the trip itself
  (as the scouting finding's fix direction also suggested as an
  alternative) -- the simpler fix of leaving the trip `ACTIVE` and
  letting it resume naturally was judged sufficient and lower-risk than
  introducing a new state to every consumer of trip state.

## 5. Success criteria

- [x] An auto-pause mid-delivery no longer finalizes the trip at the
      pause point
- [x] The trip continues under the same `trip_id` once GPS resumes and
      completes naturally
- [x] Manual "Stop Monitoring" and the crash-safety-net path still
      finalize a truncated trip unconditionally (original behavior
      preserved)
- [x] An auto-pause with no delivery in progress still ends normally
- [x] Real executable Python test (11 checks) against the actual
      engine, fully passed
- [x] `python3 -m py_compile` clean; Java brace/paren balance verified
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver confirms in real use that pausing a Dash mid-delivery no
      longer produces a truncated, badly-scored trip.
- [ ] Driver sign-off.
