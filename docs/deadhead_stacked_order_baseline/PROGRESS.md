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
