# Audit — all mathematical calculations in drive_monitor.py

Status: DONE. One real bug found and fixed (verified with a real
executable test). One real, separate gap found and documented as
out-of-scope for this audit (it's data loss, not a wrong formula).

Requested: "conduct an audit of all mathematical calculations." Scope
was every formula in `app/src/main/python/drive_monitor.py` (the only
pure-Python file, zero Android/Chaquopy dependency) -- `SmartScoreEngine`'s
six-factor scoring, `TripManager`'s distance/time/safety math, the
learning/incremental-average functions, and every reporting-layer
aggregate (weekly/CSV/feedback/distance-accuracy summaries).

## Bug found and fixed: `time_score` was inverted (SmartScoreEngine.calculate)

`drive_monitor.py`, `SmartScoreEngine.calculate()`, the "Time of day /
traffic" factor (originally line 821):

```python
is_high_risk, traffic_risk_source = self._get_traffic_risk(hour_24, current_lat, current_lon)
time_score = 100.0 if is_high_risk else 70.0
```

Present since the initial commit (confirmed via `git log -L`), never
touched since.

Every other factor in `calculate()` scores a worse real-world condition
lower and a better one higher:

- `deadhead_score = max(0.0, 100.0 - deadhead_km * 10.0)` -- more
  deadhead, lower score.
- `wait_score = max(0.0, min(100.0, 100.0 - (avg_wait - 3.0) * 8.0))` --
  longer wait, lower score.
- `weather_score` -- more rain/wind, lower score.
- `base_score` / `hourly_score` -- more pay, higher score.

`is_high_risk` means "this hour/zone has historically (or right now,
via live traffic) been notably slower than usual" -- confirmed
unambiguous by reading all three of its sources (`_is_peak_hour`,
`_get_traffic_risk_by_zone`, the live-traffic branch in
`_get_traffic_risk`: `live_ratio >= LIVE_TRAFFIC_HIGH_RISK_RATIO`, a
delay ratio). It is a bad condition, exactly like heavy rain or a long
restaurant wait. `_synthesize_verdict` already treats `is_high_risk` as
a downside: `if "High" in traffic_risk_label: parts.append("heavier
traffic than usual for this time/area")`.

But the code gave `is_high_risk=True` the MAXIMUM possible score (100)
and `is_high_risk=False` a LOWER score (70) -- the opposite of every
other factor, and the opposite of what the app's own verdict sentence
says about the same condition in the same response. Weighted at
`WEIGHT_TIME_OF_DAY = 0.09` (9% of the composite `final_score`), this
meant every offer scored during a driver's own historically slower
hours -- or with live traffic actively reported as delayed -- was
silently boosted, not penalized, nudging acceptance decisions toward
worse times to drive.

### Fix

Swapped the two branches, keeping the existing magnitude (100/70, a
gentler swing than the other factors use):

```python
time_score = 70.0 if is_high_risk else 100.0
```

No other line touched. `WEIGHT_TIME_OF_DAY`, `_get_traffic_risk`,
`_is_peak_hour`, `_get_traffic_risk_by_zone`, and the live-traffic
branch are all untouched -- this was a one-line direction fix, not a
change to how risk is detected.

### Verification -- ACTUALLY EXECUTED, not just reviewed

Wrote and ran `test_time_score_direction.py` (scratchpad, throwaway,
same pattern as every other executable test this session) directly
against the real, modified `drive_monitor.py` via plain `python3`
(`Database(":memory:")` + `SmartScoreEngine`, no Android/Chaquopy
involved). Called `calculate()` twice with identical inputs except
`hour_24` -- noon (inside the generic lunch-window fallback, since
under 5 trips exist yet, so `_is_peak_hour` returns the fallback
`is_high_risk=True`) vs. 3am (`is_high_risk=False`). Real output:

```
time_score during a historically riskier/slower period (noon): 70.0
time_score during a historically calmer period (3am): 100.0
PASS: time_score now correctly scores the riskier period lower
final_score: 83.9 (risky) vs 86.6 (calm) -- riskier now scores lower overall, as expected

ALL ASSERTIONS PASSED
```

Also confirmed `ast.parse(drive_monitor.py)` clean after the edit.

## Other areas checked -- confirmed correct, no changes made

- `haversine_meters` -- standard great-circle formula, correct.
- `_estimate_deadhead_km` / `_restaurant_wait_info` / `_learned_delivery_speed_kmh`
  -- three-tier fallback (specific -> overall -> hardcoded default) and
  the incremental-average update (`new_avg = ((avg*count)+new_val)/new_count`)
  are the standard correct formula in each.
- `_record_accel_sample_in_memory` -- genuine Welford's online
  algorithm, correct.
- `_merge_accel_samples_into_history` -- genuine Chan et al. parallel-
  variance combine formula for merging two independent Welford
  summaries, correct.
- `_pearson_correlation` -- standard formula, correctly guards `var_x`/
  `var_y` <= 0 to avoid a division by zero.
- `_get_calibrated_weights` -- bounded adjustment
  (`* (1.0 + adjustment_pct)`, clamped to `max(0.0, ...)`), then
  renormalized so the 6 weights still sum to 1.0. `WEIGHT_BASE_RATE +
  WEIGHT_HOURLY_RATE + WEIGHT_DEADHEAD + WEIGHT_RESTAURANT_WAIT +
  WEIGHT_TIME_OF_DAY + WEIGHT_WEATHER` = 0.36+0.225+0.135+0.09+0.09+0.10
  = 1.00 exactly, confirmed.
- `_compute_summary`'s `composite_score = 0.4*time_eff + 0.3*safety +
  0.3*geofence` -- weights sum to 1.0, correct.
- `_safety_score`, `_geofence_hit_ratio`, `_get_fuel_cost_per_km`
  (`(l_per_100km / 100) * price_per_liter` = $/km, correct unit
  conversion) -- all correct, all guard their division by zero.
- `_persist_distance_accuracy`'s `actual_delivery_km = actual_total_km
  - self._distance_at_departure_km` and the deadhead fix from
  `docs/deadhead_stacked_order_baseline/` (`_cumulative_distance_km -
  self._deadhead_baseline_km`) -- both re-confirmed correct and
  untouched by this audit.
- Every reporting aggregate (`get_feedback_summary`,
  `get_offer_outcome_stats`, `get_rejected_offers_report`,
  `get_pickup_sweet_spot_zone`, `get_parking_difficulty_rating`,
  `get_distance_accuracy_summary`) -- all guard `len(rows) == 0` /
  `if not values` before dividing; no bare division-by-zero risk found
  anywhere in the file.

## Real gap found, NOT fixed (data loss, not a wrong calculation -- out of scope for this audit)

`_persist_distance_accuracy` (and therefore every `offer_distance_accuracy`
row) is only ever called once per trip, from `_end_trip` -> `_persist_trip`,
using whatever single pickup is currently in `self.pickup` at that
moment. For a stacked/batch order with 2+ pickups in the same trip,
`add_pickup` overwrites `self.pickup` (and `_deadhead_distance_km` /
`_distance_at_departure_km`) each time a new pickup is registered --
so only the LAST pickup's real deadhead/delivery-distance numbers are
ever persisted to `offer_distance_accuracy`. Earlier pickups' real
measured data is silently discarded, never written anywhere.

This isn't a wrong formula -- every number that IS recorded is now
correct (per the Part 1 deadhead fix). It's a missing capture point:
`offer_distance_accuracy` was designed to be one-row-per-pickup (its
own schema supports it, and `get_distance_accuracy_summary`'s
restaurant-specific averaging already assumes it), but the code only
ever writes the final pickup of a stacked trip. Flagging this rather
than fixing it here -- it needs its own scoped fix (probably: persist
a row inside `_evaluate_pickup`'s departure branch, per pickup, instead
of once at trip end) and its own verification, the same
investigate-before-implement discipline as every other PRD in this
repo. Not implementing it silently as a side effect of a math audit.
