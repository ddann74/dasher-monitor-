# PRD — hourly-rate estimate vs. actual result

Status: DRAFT. Investigation only, grounded in the real code. Nothing
implemented yet — do not start coding from this PRD until the driver
says "yes implement it" (same convention as every other PRD in this
repo).

## 1. What the code actually does today

`SmartScoreEngine.calculate()`, `app/src/main/python/drive_monitor.py`:

```python
hourly_rate = (payout / est_minutes) * 60 if est_minutes > 0 else 0
hourly_score = min(100.0, (hourly_rate / 60.0) * 100.0)
```

`est_minutes` is computed once, before `calculate()` is called, via:

```python
def estimate_minutes_from_distance(self, distance_km):
    if not distance_km or distance_km <= 0:
        return None
    speed_kmh, _, _ = self._learned_delivery_speed_kmh()
    return max(5.0, (distance_km / speed_kmh) * 60.0)
```

`_learned_delivery_speed_kmh()` returns this driver's own learned
average speed for the pickup-departure -> trip-end leg (falls back to
a fixed 25 km/h until at least one real delivery has completed). So
`est_minutes` is **pure drive-time**: `distance_km / speed`, nothing
else, floored at 5 minutes.

## 2. Two real gaps, found by comparing this against what the app already knows

### 2.1 The estimate omits restaurant wait time, which the same function call already has

`calculate()` computes `avg_wait, wait_samples, wait_is_restaurant_specific
= self._restaurant_wait_info(restaurant_name)` a few lines below, to
build `wait_score`. That's a real, learned, restaurant-specific average
wait time (parking + waiting for the order — see `record_restaurant_wait`'s
own docstring on why the two can't be split). It is available in the
exact same function call that computes `hourly_rate`, but `est_minutes`
never adds it in. A restaurant with a genuinely long learned wait (the
code's own threshold: `if wait_is_restaurant_specific and avg_wait > 12:
restaurant_warning = "Long wait here historically"`) still gets its
`hourly_rate` computed as if pickup took zero minutes — the displayed
$/hr is a best case that assumes an instant pickup, for every offer,
even ones this app has already learned are slow.

### 2.2 There is no comparison against the actual result — and, more fundamentally, it currently CANNOT be computed

This is already honestly disclosed in the code itself —
`export_trips_csv`'s docstring: *"Per-trip earnings/accept-decline
tracking doesn't exist (offers are scored before acceptance, but
nothing currently links an accepted offer's payout to the trip that
follows it)."*

Verified this is true all the way down, not just at the CSV-export
layer:

- `trips.offer_score_snapshot_json` is the only thing carried from an
  accepted offer into the trip that follows it (set once, at
  `_start_trip`, from `self.pickup["score_snapshot_json"]`).
- That snapshot is exactly `calculate()`'s return dict — which does
  **not** include the raw `payout` or `distance_km` it was computed
  from, only derived numbers (`base_rate_per_km`, `hourly_rate`,
  `deadhead_km`, etc.). `base_rate_per_km = payout / distance_km`, but
  neither factor survives independently, so payout can't even be
  reconstructed after the fact.
- Nothing else in `TripManager` or the `trips` table stores payout at
  all.

So today, there is no code path — not a missing report, an actually
missing capability — that could answer "was the $/hr this offer showed
me at accept-time close to what I actually made?" The data needed to
answer that question is never captured in the first place.

This is the same shape of problem `docs/deadhead_stacked_order_baseline/`
just fixed for distance (claimed vs. actual), and the same shape
`offer_distance_accuracy` / `get_distance_accuracy_summary` already
solve for distance specifically — but for **money and time**, not
distance, that machinery doesn't exist yet.

## 3. Non-goals

- Not touching `deadhead_score`, `wait_score`, or any other factor's
  formula — this is scoped to `hourly_rate`/`est_minutes` and the new
  actual-vs-estimated capture, nothing else in `calculate()`.
- Not changing `WEIGHT_HOURLY_RATE` or the calibration mechanism.
- Not solving the stacked/batch-order payout-attribution problem in
  full here (see open question in §5) — flagging it, not designing a
  complete answer, the same way §2 non-goals were handled in the
  deadhead PRD before that got its own follow-up.

## 4. Proposed design (for review, not yet approved)

Two independent pieces:

**A. Fix the estimate itself (small, self-contained):**
`estimate_minutes_from_distance` (or `calculate()`, wherever the
restaurant name is available at estimate time) should add the learned
`avg_wait` minutes for that restaurant on top of the drive-time
estimate, the same way `_restaurant_wait_info` is already called for
`wait_score`. This alone would make the displayed `hourly_rate` a
realistic estimate instead of a best-case one, with zero new schema.

**B. Actually capture the real result, so it can be compared (larger,
needs a schema change):**
1. Store `payout` (and ideally `distance_km`) directly on `self.pickup`
   at `add_pickup` time — not just inside the JSON snapshot — the same
   way `claimed_distance_km` already is, so it survives into the trip.
2. Add `accepted_payout REAL`, `accepted_est_hourly_rate REAL` (or
   similar) columns to `trips`, populated from `self.pickup` at
   `_start_trip`, mirroring how `offer_score_snapshot_json`/
   `deadline_text`/`pickup_address` already flow through today.
3. At trip end (`_persist_distance_accuracy` is the natural home, or a
   sibling method next to it), if payout was captured, compute
   `actual_hourly_rate = accepted_payout / real_elapsed_hours` and
   persist it — either as new `trips` columns or, more consistent with
   the existing `offer_distance_accuracy` precedent, a new
   `offer_earnings_accuracy` table (`trip_id, restaurant_name,
   estimated_hourly_rate, actual_hourly_rate, estimated_minutes,
   actual_minutes, timestamp`).
4. A `get_hourly_rate_accuracy_summary()` mirroring
   `get_distance_accuracy_summary()` — average estimate error,
   direction of bias (over- or under-estimating), sample count.

## 5. Open questions (need a driver decision before B is implemented)

- **What time span counts as "actual"?** `self.pickup` has no
  "accepted_ts" field today — only `arrived_at` (pickup arrival) and
  the trip's own `start_time` (first sustained-speed detection, which
  can be well after an offer is accepted while parked). The honest
  candidates: (a) trip `start_time` -> `end_time` — biased toward
  looking better than reality, since real time spent deciding/idle
  between accept and driving off isn't counted; (b) a new real
  "offer accepted" timestamp, captured at `add_pickup` time, ->
  `end_time` — closer to "was this offer worth it," but a bigger
  change (needs a new field threaded through `add_pickup` ->
  `_start_trip` -> the persisted row). Recommend (b), since (a) would
  make every actual result look artificially better than it was,
  undermining the whole point of the comparison — but this is a real
  product decision, not a coding one.
- **Stacked/batch orders**: same problem `docs/deadhead_stacked_order_baseline/`
  §7 already flagged for phase timing — a single trip can carry
  multiple accepted offers/payouts, and `self.pickup` is a single dict
  overwritten by each `add_pickup` call. A real per-job answer needs
  per-pickup payout capture, not per-trip — likely the same underlying
  fix as `docs/deadhead_stacked_order_baseline/` §7 (per-job rows
  instead of per-trip columns), so these two probably want to be
  designed together rather than as two separate schemas.
- Should A (the wait-time fix to the live estimate) ship on its own,
  independent of B? It's fully self-contained and doesn't need a
  driver decision the way B's open questions do.

## 6. Success criteria (not started — nothing here is implemented yet)

- [ ] §4.A: `est_minutes` includes the restaurant's learned average
      wait time, not just drive time.
- [ ] Real executable test proving A: same distance/speed, two
      restaurants with different learned `avg_wait`, confirms the one
      with the longer wait produces a lower `hourly_rate`/`hourly_score`.
- [ ] §4.B (only after the open questions in §5 are answered by the
      driver): payout captured at accept time, actual hourly rate
      computed and persisted at trip end.
- [ ] `get_hourly_rate_accuracy_summary()` implemented and returns real
      numbers from a real recorded trip in a test.
- [ ] Driver sign-off.
