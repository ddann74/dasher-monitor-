# PRD: Stop Developer Testing from contaminating real trips and calibration

Status: IMPLEMENTED (2026-09-14), scouting-pass finding (round 3).

## 1. What was wrong

`DeveloperTestingActivity` shares the exact same live `DriveMonitorEngine`/
`TripManager` singleton as real monitoring (`PythonBridge.getEngine`).
Its `simulateDriveAndArrival()` feeds synthetic GPS ticks through the
real, public `on_gps_update` -- which really calls `_start_trip()` and
inserts a genuine row into `trips`.

Two real, confirmed bugs, verified end-to-end against the actual
engine:

1. **The simulation never cleaned up.** The simulate loop only spans
   ~77s of simulated time, far short of `TRIP_END_PARK_SECONDS` (300s)
   needed for the trip to end naturally, and nothing ever called
   `force_end_trip()` or reset state afterward. The engine was left
   sitting in `TRIP_ACTIVE` indefinitely. Reproduced: after running the
   simulation, `get_state()` was still `TRIP_ACTIVE`; a REAL GPS tick an
   hour later from a real, distant location was silently appended onto
   the SAME fake trip instead of starting a fresh one.
2. **No way, even in principle, to exclude a test-contaminated trip
   from calibration.** Unlike `offer_outcomes` (Source 2 of
   `recalculate_personal_calibration`, which already filters
   `is_test_data = 0`), the `trips`/`trip_feedback` tables had no
   `is_test_data` column at all. Source 1's query read EVERY rated trip
   unconditionally -- any trip whose `offer_score_snapshot_json`
   originated from Developer Testing had no possible way to be
   excluded from what personal calibration learns from.

## 2. Design

### 2.1 `trips.is_test_data` (new column)

`ALTER TABLE trips ADD COLUMN is_test_data INTEGER DEFAULT 0` -- same
migration pattern as every other `trips` column added over time.
Threaded through the real call chain: `DriveMonitorEngine.on_gps_update`
→ `TripManager.on_gps_update` → `_evaluate_trip_start` → `_start_trip`,
each gaining a new `is_test_data=False` parameter (backward compatible
-- every real production call site, `TripForegroundService`, omits it
and gets the default). `_start_trip` stores it as
`self._trip_is_test_data` and writes it into the trip's `INSERT`.

### 2.2 `DeveloperTestingActivity.simulateDriveAndArrival` (Java)

Every `on_gps_update` call in the simulation now passes `true` for
`is_test_data`, marking any trip it starts. A new `finally` block calls
`engine.callAttr("force_end_trip")` unconditionally after the
simulation -- including on an exception, so a failure mid-simulation
can't leave the engine dangling mid-trip either.
`force_end_trip()` (`TripManager.force_end_trip`) already no-ops
safely when no trip is active (confirmed by reading its own guard,
`if self.state != self.STATE_ACTIVE: return None`), so this is always
safe to call unconditionally, whether or not the simulation actually
reached real trip-start speed/duration.

### 2.3 `recalculate_personal_calibration`'s Source 1 query

Added `AND t.is_test_data = 0` to the existing query -- the exact same
protection Source 2 (`offer_outcomes`) already had, now genuinely
possible for trips too.

## 3. Verification

- Real, executable Python test (real `DriveMonitorEngine`, not
  reimplemented logic): reproduced the exact scouting-pass scenario --
  a simulated trip (marked `is_test_data = 1`), the new `force_end_trip`
  cleanup returning the engine to `IDLE`, then a real GPS tick an hour
  later from a real, distant location correctly starting a SEPARATE,
  fresh trip (not merged into the fake one, confirmed by row id and
  `is_test_data = 0`). Separately, confirmed Source 1's exact query
  returns only the real trip's rating when a fake (`is_test_data = 1`)
  trip and a real one are both present and both rated. 9 checks, all
  passed.
- `python3 -m py_compile` clean; brace/paren balance confirmed on
  `DeveloperTestingActivity.java`.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  Developer Testing screen's behavior (including the new cleanup
  running correctly on a real device) has not been observed on a
  device.
- `trip_feedback` itself still has no `is_test_data` column of its own
  -- the fix works by joining through `trips.is_test_data`, which is
  sufficient for Source 1's actual query shape (always joins `trips` to
  `trip_feedback`), but a hypothetical future query that reads
  `trip_feedback` without joining `trips` would not automatically
  inherit this protection.
- Existing, ALREADY-contaminated trips from before this fix (if any
  were created via Developer Testing on a real device previously)
  remain unmarked (`is_test_data` defaults to 0 for pre-existing rows)
  -- this fix prevents NEW contamination going forward; it does not
  retroactively identify or clean up anything that may have already
  happened.
- `offer_outcomes`'s own `is_test_data` marking (used by Developer
  Testing's separate "Simulate Offer Outcomes" button) was already
  correct before this fix and is unrelated/unchanged here -- this PRD
  is scoped specifically to the `trips` table gap the scouting pass
  found.

## 5. Success criteria

- [x] `trips.is_test_data` column added via the established migration
      pattern
- [x] Every real production call site (`TripForegroundService`)
      continues to work unchanged via the new parameter's default
- [x] Developer Testing's simulation marks every trip it starts
- [x] Developer Testing properly ends/resets its simulated trip after
      use, including on an exception (`finally` block)
- [x] A real trip after a simulation starts fresh, never merges into
      the leftover fake one
- [x] `recalculate_personal_calibration`'s Source 1 excludes
      test-marked trips, mirroring Source 2's existing protection
- [x] Real executable Python test (9 checks, reproducing the actual
      scouting-pass finding end to end) fully passed
- [x] `python3 -m py_compile` clean; brace/paren balance confirmed
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver confirms in real use: using Developer Testing no longer
      affects a subsequent real trip, and calibration learning stays
      accurate.
- [ ] Driver sign-off.
