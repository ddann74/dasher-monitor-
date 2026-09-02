# Progress log — fix deadhead measurement for a stacked/batch-order pickup

## Investigation (2026-08-31)

Investigated a driver question ("how is deadhead calculation treated,
can this be improved") against the real code, not a diagnostic log.
Found `add_pickup()` never baselines `_cumulative_distance_km`, and
`_evaluate_pickup`'s arrival branch used the raw cumulative trip total
directly as the deadhead value - correct for a fresh trip (baseline
would always be 0), wrong for a pickup added mid-trip (a stacked/batch
order, which this app supports elsewhere). Confirmed this was
inconsistent with the same function's own delivery-leg calculation two
lines later (`actual_delivery_km = actual_total_km -
self._distance_at_departure_km`), which already does the correct
baseline-subtraction. Wrote `docs/deadhead_stacked_order_baseline/PRD.md`
and `RALPH_PROMPT.md`.

## Implementation (2026-08-31)

Three changes to `TripManager` in `drive_monitor.py`:

- `__init__`: added `self._deadhead_baseline_km = 0.0`, alongside the
  existing `_deadhead_distance_km`/`_distance_at_departure_km` fields it
  mirrors.
- `add_pickup`: snapshots `self._deadhead_baseline_km =
  self._cumulative_distance_km` - for a fresh trip this is `0.0`
  (matching prior behavior exactly); for a stacked order added mid-trip,
  this captures however much was already driven for an earlier pickup so
  it can be excluded.
- `_evaluate_pickup`'s arrival branch: changed
  `self._deadhead_distance_km = self._cumulative_distance_km` to
  `self._cumulative_distance_km - self._deadhead_baseline_km` - the
  isolated leg, not the raw total.
- `_start_trip`: resets `self._deadhead_baseline_km = 0.0` alongside its
  other per-trip state.

No change to `deadhead_score`, `WEIGHT_DEADHEAD`, or
`_estimate_deadhead_km`'s fallback logic - confirmed via diff review,
matching the PRD's own scope.

## Verification (2026-08-31) — ACTUALLY EXECUTED, not just reviewed

Wrote `test_deadhead_stacked_order.py` (scratchpad, not committed - a
throwaway verification script, same as `safety_score_speeding_debounce`'s
own test) and ran it directly against the real, modified
`drive_monitor.py` via plain `python3` (`Database(":memory:")` +
`TripManager`, no Android/Chaquopy involved). Drove the trip through the
real `on_gps_update` entry point (not the lower-level
`_process_point_during_trip` alone, which doesn't include arrival
detection) so both `_cumulative_distance_km` and `_evaluate_pickup`'s
arrival logic ran for real, not simulated. Real output:

```
Case 1 (single pickup, deadhead measured after driving ~2km post-acceptance): 1.95 km
PASS: single-pickup deadhead measures only the post-acceptance leg
Case 2 (stacked order -- trip already 8.97km in when pickup #2 was accepted): deadhead measured = 0.95 km
PASS: stacked-order deadhead measures only the second pickup's own leg
For comparison, the OLD pre-fix code would have recorded 9.97 km (the trip's entire cumulative distance, including pickup #1's driving) for pickup #2's deadhead.

ALL ASSERTIONS PASSED
```

The "old pre-fix value" comparison was reconstructed from the same real
tracking data the fixed code produced (`tm._cumulative_distance_km` at
the moment of arrival is exactly what the old, unpatched code path would
have read directly) rather than checking out and running a separate
unpatched copy - mathematically identical verification, since the value
being compared against is the literal expression the old code used.

One real bug in the test itself was found and fixed while writing it: the
first version placed each pickup at an arbitrary offset (e.g. `lat +
0.05`, ~5.6km away) but only drove a shorter distance toward it (~2km),
so arrival never actually triggered and `_deadhead_distance_km` stayed
`None`. Fixed by computing each pickup's coordinates as exactly
`current_position + (intended_leg_km / km_per_degree)`, so driving that
same intended distance lands within the real 50m arrival geofence
(`ARRIVAL_GEOFENCE_METERS`).

Verified by direct review too: `ast.parse(drive_monitor.py)` confirmed
clean after each edit.

Final PRD §6 box: user sign-off.

## Part 2A: design pass + implementation (2026-09-02)

Per the RALPH_PROMPT's own guardrail ("needs a proper design pass...
before any code is written"), re-read `add_pickup`, `_evaluate_pickup`,
and `_persist_distance_accuracy` together with
`docs/hourly_rate_actual_vs_estimated/PRD.md` §4.B (needing the exact
same per-job data) rather than each PRD in isolation - see PRD §7.4 for
the full design writeup. No driver override was given by the time the
ralph-loop continuation reached this design pass, so it proceeds on its
own recommendation, documented in the PRD rather than assumed silently.

### A real, previously-undocumented bug found while designing this (§7.4.1)

`_persist_distance_accuracy` only ever reads `self.pickup` (a single
dict) and single scalar fields - all of which get silently overwritten
by a SECOND `add_pickup` call in a stacked order. Confirmed directly:
`offer_distance_accuracy` gets exactly ONE row per TRIP, not one per
JOB. For a 2+ job trip, job #1's claimed distance, real deadhead, and
payout/accepted-time data were never persisted anywhere at all once
job #2 was added - not measured wrong (as Part 1 alone left it),
genuinely lost. This is worse than what Part 1 fixed, and was only
found by reading these methods together, not separately.

### Implementation

- `offer_distance_accuracy` gained `accepted_ts`, `payout`,
  `estimated_hourly_rate`, `actual_hourly_rate` REAL columns (both
  `CREATE TABLE IF NOT EXISTS` for fresh installs and an `ALTER TABLE`
  migration for existing databases, matching the `trips` phase-timing
  migration's own established pattern).
- `TripManager.add_pickup` gained a `payout=None` parameter, appended
  LAST (not inserted into the middle) so `DeveloperTestingActivity`'s
  existing 4-positional-arg call site keeps working unchanged. Sets
  `self.pickup["accepted_ts"] = time.time()` unconditionally.
- **The core fix**: right before `add_pickup` overwrites `self.pickup`,
  if the OUTGOING job already completed its own pickup arrival+
  departure, its `offer_distance_accuracy` row is persisted THEN,
  via a new shared helper `TripManager._persist_pickup_job_row(job,
  deadhead_km, now_ts, distance_at_departure_km=None,
  cumulative_km_now=None)`. Called from two places: `add_pickup`
  (delivery leg NOT known yet - `actual_delivery_km`/`actual_total_km`/
  `actual_hourly_rate` left NULL rather than fabricated) and
  `_persist_distance_accuracy` at trip end for the current/last job
  (delivery leg IS known - computed for real, unchanged from before
  this refactor).
- `estimated_hourly_rate` is read out of the job's own
  `score_snapshot_json` (already stored, already has a real
  `"hourly_rate"` key from `SmartScoreEngine.calculate()`) - no new
  parameter needed for it.
- `actual_hourly_rate = payout / ((now_ts - accepted_ts) / 3600.0)`,
  guarded against `payout` being the Java side's established `-1`
  "unknown" sentinel (`DasherAccessibilityService.lastSeenPayout`) and
  against a non-positive elapsed time - both left NULL rather than a
  nonsensical negative/divide-by-zero rate.
- Deliberately did NOT restructure `self.pickup` into a multi-job list
  (the bigger alternative) - see PRD §7.4.2 for why the single-slot-
  plus-snapshot-on-overwrite approach is enough for this PRD's scope.

### Two more real production bugs found and fixed as a DIRECT consequence of this change

Both existing methods assumed every `offer_distance_accuracy` row
always had non-NULL `actual_delivery_km`/`actual_total_km` - true
before this change (every row was only ever inserted at trip end, with
the delivery leg always known), no longer true once an earlier job's
row can exist with those fields NULL:

1. `DriveMonitorEngine.get_distance_accuracy_summary()` did
   `abs(claimed - row["actual_delivery_km"])` with no None guard -
   would `TypeError` the instant a stacked-order trip's data existed.
   Fixed: rows with either field NULL are now skipped, same as the
   existing `claimed is None` skip right above.
2. `DriveMonitorEngine.export_full_report()`'s DISTANCE ACCURACY
   section did `f"{r['actual_delivery_km']:.2f}"` with no None guard -
   same crash. Fixed: formats "N/A" for a NULL value instead.

Both were caught by actually RUNNING the real code against a
stacked-order scenario, not by reading it - the first surfaced as a
live `TypeError` traceback while running the verification script below;
the second was found by proactively checking every other place these
two columns are read, once the first crash showed the class of bug was
real.

### `get_hourly_rate_accuracy_summary()` (hourly_rate PRD's §6 item)

Added to `DriveMonitorEngine`, mirroring
`get_distance_accuracy_summary()` immediately above it: average signed/
absolute error between `estimated_hourly_rate` and `actual_hourly_rate`
across every row where BOTH are known, plus a bias-direction label.
Rows with only one of the two (an earlier stacked-order job) are
correctly excluded by the `WHERE ... IS NOT NULL` on both columns.

### UI: a real "View Hourly Rate Accuracy" button, not just a callable-but-unused method

Added to `TripHistoryActivity` (button + string resource + layout
entry), mirroring the existing "View Distance Accuracy" button/dialog
exactly - the PRD's own §4 design listed this method as part of the
design; leaving it reachable only via a raw Chaquopy call would make
this PRD's own stated purpose (a driver-visible accuracy report)
invisible in the app.

## Verification (2026-09-02) — ACTUALLY EXECUTED, not just reviewed

Wrote `test_per_job_accuracy.py` (scratchpad, throwaway) and ran it
directly against the real, modified `drive_monitor.py` via plain
`python3` (`DriveMonitorEngine` backed by a real sqlite file, no
Android/Chaquopy involved). `drive_monitor`'s own `time.time()` was
monkeypatched to a controllable fake clock so `actual_hourly_rate`
reflects the SIMULATED drive duration, not the real wall-clock
milliseconds the script takes to run (confirmed this matters: an
earlier version of the test, without the monkeypatch, produced a
`avg_signed_error` of ~9.6 million from a real-clock elapsed time of a
fraction of a second).

Simulated: a 2-job stacked-order trip (job #1's pickup+departure, THEN
job #2 added mid-trip, THEN job #2's own full pickup->delivery->trip-end)
plus a normal single-job trip as a regression check. All 28 assertions
passed, including:

- The §7.4.1 bug fix directly: TWO `offer_distance_accuracy` rows exist
  for the 2-job trip (would have been ONE before this fix) - job #1's
  row has real `actual_deadhead_km`/`payout`/`accepted_ts`/
  `estimated_hourly_rate` but NULL delivery/hourly-rate fields; job #2's
  (trip-end) row has every field populated correctly.
- `actual_hourly_rate` matches the `payout / elapsed-hours` formula
  exactly against the row's own persisted `timestamp`/`accepted_ts`.
- `get_hourly_rate_accuracy_summary()` and `get_distance_accuracy_summary()`
  both return real numbers without crashing, correctly using
  `sample_count == 1` in each case (only the fully-known row counts).
- Regression: a fresh trip's `add_pickup` does NOT persist a phantom
  row (nothing to snapshot yet), and a normal single-job trip still
  gets `actual_delivery_km`/`actual_hourly_rate` populated exactly as
  before this change.

Separately verified `export_full_report()` directly against a
hand-inserted NULL-delivery-leg row: confirmed it now renders "N/A"
instead of crashing.

`ast.parse(drive_monitor.py)` confirmed clean after every edit.

Remaining: §7.5's final box (user sign-off) is never mine to check.
§7.6 (Part 2B, full dropoff-side per-job phase timing) remains blocked
- no per-job dropoff-linkage design exists yet, a real, separate
design question this pass did not resolve.
