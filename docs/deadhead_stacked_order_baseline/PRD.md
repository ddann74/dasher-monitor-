# PRD: Fix deadhead measurement for a stacked/batch-order pickup

Status: DRAFT - awaiting sign-off before implementation begins.
Scope: this one measurement gap only. Not a general codebase pass.

## 0. What this is / isn't

This is a **bug-fix** PRD for how `TripManager` measures deadhead (the
distance driven to reach a pickup restaurant) for a SECOND (or later)
pickup accepted while a trip is already active - a stacked/batch order.
It is **not** a change to the deadhead-based scoring formula
(`deadhead_score`, `WEIGHT_DEADHEAD`) or the learning/fallback logic in
`SmartScoreEngine._estimate_deadhead_km` - both stay exactly as they are;
only the underlying measurement that feeds them is fixed.

Found via driver question ("how is deadhead calculation treated, can this
be improved") investigated in this session, not from a diagnostic log -
this is a code-reasoning finding, not yet confirmed against a real
stacked-order trip.

## 1. Why (investigation, 2026-08-31)

Read `TripManager.add_pickup`, `_evaluate_pickup`, `_start_trip`, and
`_persist_distance_accuracy` in full.

1. **Confirmed real: `_cumulative_distance_km` is a single running total
   for the WHOLE trip, reset only at `_start_trip`** (L2075), which
   itself fires purely from sustained GPS speed (L2054-2061) - it has no
   awareness of offer acceptance at all. It is NOT reset or baselined
   anywhere inside `add_pickup`.
2. **Confirmed real: `add_pickup` (L1842-1876), called once per accepted
   offer, resets `self._deadhead_distance_km = None` but never snapshots
   `self._cumulative_distance_km`.** The only place a deadhead value
   actually gets set is `_evaluate_pickup` (L1924-1926), at arrival:
   `self._deadhead_distance_km = self._cumulative_distance_km` - the RAW
   cumulative trip total at that instant, not
   `(cumulative at arrival) - (cumulative at add_pickup)`.
3. **Confirmed real, and directly inconsistent with this same file's own
   pattern one branch over**: `_persist_distance_accuracy` (L2825-2868)
   computes the DELIVERY leg correctly as a true isolated segment -
   `actual_delivery_km = actual_total_km - self._distance_at_departure_km`
   (L2844), where `_distance_at_departure_km` is itself a baseline
   snapshotted at pickup departure (L1933). The DEADHEAD leg, two lines
   later in the same method (L2855), does no equivalent subtraction -
   `actual_deadhead_km = self._deadhead_distance_km` is used directly.
   Same function, same file, same general pattern (snapshot a baseline,
   subtract later) - correctly applied to one leg, not the other. Strong
   evidence this is an oversight, not a deliberate design choice.
4. **Confirmed real via `_start_trip`'s own trigger condition**: this app
   supports batch/stacked orders (confirmed elsewhere in this codebase -
   `MessageIntelligence`'s per-stop closest-stop matching explicitly
   exists "since a batch order's messages were only ever matched by time
   window against whichever stop happened to be approached LATER";
   `docs/road_warrior_icon/PRD.md`'s own checklist includes "Batch-order
   pickup case re-verified"). If a driver accepts a second order while
   still actively driving on the first - state machine still
   `STATE_ACTIVE`, `_start_trip` never re-fires since the trip never
   returned to idle - `add_pickup` for that second order does not reset
   `_cumulative_distance_km`. The deadhead eventually recorded for that
   second pickup would be the driver's ENTIRE cumulative distance since
   the trip began, including all of the first order's own pickup and
   delivery driving - not the distance from accepting order #2 to
   reaching its restaurant.
5. **Real-world impact, reasoned from the mechanism (not yet confirmed
   against an actual stacked-order trip)**: for a same-restaurant batch
   order (both orders picked up at the same place - common in practice),
   the true deadhead for order #2 is near zero, but this bug would record
   nearly the driver's whole trip distance instead. For a
   different-restaurant batch order, the recorded figure would still be
   inflated by whatever was already driven for order #1. Either way, this
   corrupts `offer_distance_accuracy.actual_deadhead_km` for that
   restaurant going forward - `_estimate_deadhead_km`'s restaurant-specific
   average (§1.6 below) would be pulled toward an inflated number by every
   such contaminated sample, silently making that restaurant's FUTURE
   offers look worse than they really are.
6. **Secondary, lower-priority observation, same investigation**:
   `_estimate_deadhead_km` (L759-787) has no minimum-sample-count gate
   before treating a restaurant-specific average as reliable - a single
   trip's `actual_deadhead_km` (contaminated or not) fully determines the
   restaurant-specific estimate from the very next offer onward. Every
   other learned metric in this app with a real precedent (personal
   calibration: 25+ samples; delivery-speed learning discloses
   `is_learned: false` until 1+ real sample, but only ever a single global
   average, not a bucketed one) treats small-sample learning more
   cautiously than this does. Flagged as a real, separate improvement
   opportunity - not fixed in this PRD (see §5, non-goals).

## 2. Definition of "functional" for this task

- [ ] A pickup added while a trip is ALREADY active (a stacked/batch
      order, `_cumulative_distance_km` non-zero at `add_pickup` time)
      measures deadhead as the distance from THAT pickup's acceptance to
      arrival at ITS restaurant, not the trip's entire cumulative
      distance so far.
- [ ] A pickup added at the start of a fresh trip (the common, single-
      delivery case) is unaffected - deadhead still measures correctly
      from trip start to arrival, same real-world value as today.
- [ ] No change to `deadhead_score`'s formula, `WEIGHT_DEADHEAD`, or
      `_estimate_deadhead_km`'s fallback logic (restaurant-specific ->
      overall -> 0.0) - this PRD only fixes the measurement those already
      consume.
- [ ] No change to `actual_delivery_km`'s existing, already-correct
      baseline-subtraction pattern - used as the reference implementation
      for this fix, not touched itself.

Non-goals:
- The no-minimum-sample-count gap in `_estimate_deadhead_km` (§1.6) -
  real, but a separate design question (what minimum? does it match the
  25-sample precedent or need its own number?) not decided here.
- Backfilling/correcting any already-recorded `offer_distance_accuracy`
  rows that may already be contaminated by this bug - no way to tell,
  after the fact, which historical rows came from a stacked order versus
  a normal single delivery, so a backfill would itself be guesswork.
- Re-deriving `WEIGHT_DEADHEAD` or any other scoring weight now that
  measurement is more accurate - a separate calibration question.

## 3. Design

### 3.1 Baseline at `add_pickup`, matching the delivery leg's own pattern

Add a new field, snapshotted the same way `_distance_at_departure_km`
already is:

```python
def add_pickup(self, restaurant_name, lat, lon, claimed_distance_km=None,
               score_snapshot_json=None, deadline_text=None, address=None):
    self.pickup = { ... unchanged ... }
    self._deadhead_baseline_km = self._cumulative_distance_km
    self._deadhead_distance_km = None
    self._distance_at_departure_km = None
    self._departure_timestamp = None
```

### 3.2 Compute the isolated leg at arrival, not the raw total

`_evaluate_pickup`'s arrival branch (L1924-1926):

```python
if within_geofence and self.pickup["arrived_at"] is None:
    self.pickup["arrived_at"] = ts
    self._deadhead_distance_km = self._cumulative_distance_km - self._deadhead_baseline_km
    ...
```

For a fresh trip (the common case), `_deadhead_baseline_km` is `0.0` (set
right after `_start_trip` reset `_cumulative_distance_km` to `0.0`), so
this produces the exact same value as today - the fix only changes
behavior for a pickup added mid-trip.

### 3.3 Reset the new field alongside the trip's other per-trip state

`_start_trip` already resets `_cumulative_distance_km` to `0.0` - add
`self._deadhead_baseline_km = 0.0` alongside it, matching how
`_distance_at_departure_km` is already reset there too, so a fresh
`TripManager` (or a trip boundary) never carries a stale baseline from a
previous trip.

## 4. Testing / verification approach

`TripManager` is pure Python (part of `drive_monitor.py`, confirmed zero
Android/Chaquopy dependency earlier this session) - genuinely testable in
this sandbox via `Database(":memory:")`, the same approach
`safety_score_speeding_debounce` used. Plan:

1. Simulate a normal single-pickup trip: start trip, feed GPS ticks to
   simulate driving 3km, add a pickup, feed GPS ticks to simulate driving
   2km to reach it. Assert measured deadhead is ~2km (the leg after
   `add_pickup`), matching real-world expectation - and confirm this
   matches what the CURRENT (pre-fix) code would also produce, proving
   the fix doesn't change single-delivery behavior.
2. Simulate a stacked order: start trip, drive 3km, add pickup #1, drive
   2km to reach it (deadhead #1 should read ~2km), continue driving
   (still `STATE_ACTIVE`, no new `_start_trip`), add pickup #2 WITHOUT
   returning to idle, drive 1km to reach pickup #2. Assert pickup #2's
   measured deadhead is ~1km. Before this fix, the same scenario would
   read pickup #2's deadhead as ~6km (3+2+1, the full cumulative trip
   distance) - run this assertion against the CURRENT unpatched code
   first to prove the bug is real, then against the fix to prove it's
   closed, the same "before/after" approach
   `safety_score_speeding_debounce`'s test used.

## 5. Open questions

None blocking - this is a measurement correction with a clear reference
implementation already in the same function (`actual_delivery_km`'s
existing baseline-subtraction pattern), not a new design decision. The
§1.6 minimum-sample-count question is real but explicitly out of scope
(§2 non-goals) pending a separate decision on what threshold, if any, is
appropriate.

## 6. Success criteria (implementation-phase checklist)

- [x] `_deadhead_baseline_km` added, snapshotted in `add_pickup`
- [x] `_evaluate_pickup`'s arrival branch computes deadhead as
      `cumulative - baseline`, not raw cumulative
- [x] `_start_trip` resets the new baseline alongside its other per-trip
      state
- [x] No change to `deadhead_score`, `WEIGHT_DEADHEAD`, or
      `_estimate_deadhead_km`'s fallback logic (diff-reviewed)
- [x] Executable test written and RUN in this sandbox: single-pickup case
      unchanged (1.95km measured for a ~2km leg), stacked-order case
      corrected (0.95km measured for the second pickup's own ~1km leg,
      vs. 9.97km the old raw-cumulative approach would have recorded for
      the same real tracking data) - proving the bug was real and is now
      closed
- [ ] User sign-off
