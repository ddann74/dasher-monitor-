# PRD: Fix deadhead measurement for a stacked/batch-order pickup

Status: Part 1 (deadhead measurement, §1-§6) IMPLEMENTED and tested -
awaiting sign-off. Part 2A (§7.4-§7.6, the per-job schema design pass
this PRD's own RALPH_PROMPT required before any code) IMPLEMENTED and
tested this pass (2026-09-02), built jointly with
`docs/hourly_rate_actual_vs_estimated/` §4.B since both needed the
exact same per-job row shape (see §7.4). A real, previously-undocumented
data-loss bug was found and fixed while doing this design pass - see
§7.4.1. Part 2B (full dropoff-side per-job phase timing, i.e. the
original §7.2/§8 scope) remains a genuinely blocked DRAFT - see §7.6.
Scope: two related measurement gaps for stacked/batch orders. Not a
general codebase pass.

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

## 7. Part 2 (added at driver's request): show timings for each job after a completed delivery

### 7.1 Why - investigated 2026-08-31, a real related gap found while adding this

Read `TripManager._update_current_trip_phase_timestamp`, the
`dropoff_arrival_ts`/`walking_confirmed_ts` capture sites, and
`_build_trip_summary_dict`'s `phase_breakdown` construction.

1. **Confirmed real: today's phase-timing breakdown already discloses,
   in its own comment, that it only covers "the first pickup and first
   dropoff"** for a multi-stop batch (`_build_trip_summary_dict`,
   `phase_breakdown` block) - a SECOND job's timing was never shown at
   all. This is the literal gap the driver asked about.
2. **Confirmed real, and worse than just "incomplete": the underlying
   capture is INCONSISTENT between pickups and dropoffs, and can already
   produce a wrong (not just missing) number for a batch order today.**
   - `dropoff_arrival_ts` and `walking_confirmed_ts` are written via
     explicit `UPDATE trips SET ... WHERE end_time IS NULL AND
     {column} IS NULL` (e.g. L2315, L2659) - guarded to capture only the
     FIRST occurrence per trip, later ones are silently ignored.
   - `pickup_arrival_ts`/`pickup_departure_ts` are written via the
     shared `_update_current_trip_phase_timestamp` helper (L1963-1975):
     `UPDATE trips SET {column_name} = ? WHERE end_time IS NULL` - NO
     `IS NULL` guard. Every subsequent pickup in the same trip
     OVERWRITES the previous one, so by the time the trip ends, these
     two columns reflect the LAST pickup's timing, not the first.
   - **The consequence**: for a batch order, `phase_breakdown`'s
     `driving_to_dropoff_seconds = dropoff_arrival_ts -
     pickup_departure_ts` mixes the FIRST dropoff's arrival with the
     LAST pickup's departure - two timestamps from what could be
     different, out-of-order jobs entirely. If the route interleaves
     pickups and dropoffs (a real batch-order shape, not exotic), this
     can go negative or otherwise not describe any real phase of any
     actual job. Not confirmed against a real batch-order trip's data
     yet (no diagnostic log for this specific scenario), but the
     mechanism is real and directly readable from the code as written.
3. **Same root cause as Part 1's deadhead bug**: single-value columns on
   the `trips` row cannot represent per-job data when a trip contains
   more than one job. `offer_distance_accuracy` already solved this for
   distance (one row per pickup, not per trip) - the natural precedent
   for timing too, rather than inventing a different shape.

### 7.2 Design

Add per-job phase timestamps to `offer_distance_accuracy` (already
one-row-per-pickup, and this PRD's Part 1 already touches this table's
surrounding code) rather than the single-value `trips` columns:
`pickup_arrival_ts`, `pickup_departure_ts`, `dropoff_arrival_ts`,
`walking_confirmed_ts`, `job_end_ts` columns added to that table,
populated at the same real capture points that currently write to the
`trips` row, keyed to whichever pickup is current at each capture moment
(the same `self.pickup` reference `_deadhead_baseline_km`/
`_deadhead_distance_km` already use). `_build_trip_summary_dict` then
builds a LIST of per-job phase breakdowns (one dict per
`offer_distance_accuracy` row for the trip) instead of a single
trip-level `phase_breakdown` - each job's own driving/wait/delivery/
completing durations, shown separately after that job's own delivery
completes, not lumped into one trip-wide figure.

**Not designed in full detail here** - this needs its own schema
migration (new columns, a decision on whether existing `trips`-level
`pickup_arrival_ts`/etc. stay for backward compatibility with old rows
or get deprecated) and its own executable test (a real synthetic
batch-order trip, same technique as Part 1's test, asserting each job's
timing is captured separately and correctly ordered). Scoped as a
follow-up implementation pass, not bundled into Part 1's already-tested,
already-committed fix.

### 7.3 Open question

Should Part 1's already-fixed `_deadhead_baseline_km` snapshot become the
SAME per-job anchor point the new timing columns use (i.e., generalize
"per-job baseline" once, for both distance and timing), or should timing
be captured independently? ANSWERED by the design pass below (§7.4):
partially yes - the per-job *row* is shared with distance/payout data,
but full per-job *phase timing* (dropoff side) is NOT part of that row
yet - see §7.6 for why.

## 7.4 The design pass this PRD's own RALPH_PROMPT required, done 2026-09-02

Per the RALPH_PROMPT guardrail ("needs a proper design pass ... before
any code is written"), this is that pass - done jointly with
`docs/hourly_rate_actual_vs_estimated/` §4.B, since re-reading both
PRDs together confirmed they need the exact same real shape: a row per
PICKUP JOB, not a per-trip column or a per-trip scalar field. No driver
override was given by the time the ralph-loop continuation reached
this design pass, so it proceeds on its own recommendation, documented
here rather than assumed silently.

### 7.4.1 A real, previously-undocumented bug found while designing this

Re-reading `add_pickup`, `_evaluate_pickup`, and `_persist_distance_accuracy`
together (not just each in isolation, as the original §1 investigation
did) found something worse than "Part 1's deadhead fix only helps
job #2's own number": **`offer_distance_accuracy` gets exactly ONE row
inserted per TRIP, not one per JOB - `_persist_distance_accuracy` only
ever reads `self.pickup` (a single dict) and the single scalar fields
`_deadhead_distance_km`/`_distance_at_departure_km`/`_departure_timestamp`,
all of which get silently OVERWRITTEN by the second `add_pickup` call in
a stacked order.** For a 2+ job trip, job #1's claimed distance, real
deadhead, and (new, per hourly_rate §4.B) payout/accepted-time data are
never persisted anywhere at all once job #2 is added - not measured
wrong, genuinely lost. This is confirmed directly from the code as
written (not yet confirmed against a real stacked-order trip's data,
same honest caveat as the rest of this PRD), and it's a bug even with
Part 1's fix already in place, since Part 1 only fixed the VALUE
computed for whichever job happens to be the last one standing when the
trip ends.

### 7.4.2 Scoped fix: persist a row per job at the moment it's known to be complete, not only at trip end

`self.pickup` already gets overwritten by each new `add_pickup` call -
that overwrite is exactly the moment an in-progress job's own data
would otherwise be lost. Fix: right before that overwrite, if the
OUTGOING job already has real deadhead + departure data (i.e. it was
actually picked up and left, not still pending), persist its
`offer_distance_accuracy` row THEN, using a shared helper -
`TripManager._persist_pickup_job_row(job, deadhead_km, now_ts,
distance_at_departure_km=None, cumulative_km_now=None)`. Called from
two places:

1. **`_persist_distance_accuracy` (trip end, the current/last job)** -
   same as today, `distance_at_departure_km`/`cumulative_km_now` known,
   so `actual_delivery_km`/`actual_total_km` are computed for real.
2. **`add_pickup` (a stacked order's earlier job, about to be
   overwritten)** - `distance_at_departure_km`/`cumulative_km_now` NOT
   passed (that job's own dropoff hasn't happened yet, and isn't
   linkable to a specific dropoff event - see §7.6), so
   `actual_delivery_km`/`actual_total_km`/`actual_hourly_rate` are left
   NULL rather than fabricated. What IS real and known at this point -
   `restaurant_name`, `claimed_distance_km`, `actual_deadhead_km`
   (Part 1's already-tested per-job value), `accepted_ts`, `payout`,
   `estimated_hourly_rate` - is still persisted instead of silently
   dropped. This closes §7.4.1's data-loss bug without needing to know
   anything about the dropoff side at all.

This deliberately does NOT restructure `self.pickup` into a multi-job
list/dict (the bigger alternative considered) - that would touch every
method that reads `self.pickup` (`update_pickup_coordinates`,
`update_pickup_address`, `record_pickup_unassigned_for_long_wait`, plus
every Java call site's "current pickup" assumption) for a benefit this
PRD doesn't need: the single-slot-plus-snapshot-on-overwrite approach
already captures every job's pickup-side data correctly, using the
existing snapshot pattern this whole PRD already established in Part 1.
Matches Part 1's own §2 scoping ("not a general codebase pass").

### 7.4.3 Schema: extend `offer_distance_accuracy`, not a new table

New columns (`ALTER TABLE`, same migration pattern already used for
`trips`'s phase-timing columns): `accepted_ts REAL`, `payout REAL`,
`estimated_hourly_rate REAL`, `actual_hourly_rate REAL`. Reuses the
existing one-row-per-pickup table rather than inventing
`offer_earnings_accuracy` (hourly_rate PRD §4's original B.3 draft
proposal) - same precedent §7.2 already cited, now doubly justified
since both PRDs' data lives on the literal same job.

`estimated_hourly_rate` is read out of `job["score_snapshot_json"]`
(already stored, already contains a real `"hourly_rate"` key from
`SmartScoreEngine.calculate()`'s return dict) - no new parameter needed
for it. `accepted_ts`/`payout` DO need new capture: `add_pickup` gains
a `payout=None` parameter (appended at the end, not inserted into the
middle - keeps existing positional call sites from
`DeveloperTestingActivity` working unchanged) and sets
`self.pickup["accepted_ts"] = time.time()` unconditionally (real
accept-time, matching hourly_rate PRD §5's already-answered "accepted_ts,
not trip start" decision).

### 7.4.4 `actual_hourly_rate`, per hourly_rate PRD §5's answered question

`actual_hourly_rate = payout / ((now_ts - accepted_ts) / 3600.0)`,
guarded against `payout` being the Java side's existing `-1` "unknown"
sentinel (`lastSeenPayout`'s established convention elsewhere in this
codebase - see `DasherAccessibilityService`) or a non-positive elapsed
time - both left NULL rather than producing a nonsensical negative or
divide-by-zero rate.

## 7.5 Success criteria for Part 2A (this design pass + its implementation)

- [x] Design pass done and documented (this section) before any code
      written, per the RALPH_PROMPT guardrail.
- [x] §7.4.1's data-loss bug fixed: a stacked/batch order's earlier job
      gets its own `offer_distance_accuracy` row instead of being
      silently overwritten and lost.
- [x] `accepted_ts`/`payout`/`estimated_hourly_rate`/`actual_hourly_rate`
      columns added and populated (the shared piece with hourly_rate
      PRD §4.B - see that PRD for its own checklist).
- [x] No change to `deadhead_score`, `WEIGHT_DEADHEAD`, or
      `_estimate_deadhead_km`'s fallback logic (diff-reviewed) - same
      guardrail as Part 1.
- [x] `self.pickup` stays a single slot (not restructured into a
      multi-job list) - §7.4.2's deliberate scope limit, diff-reviewed.
- [x] Executable test written and RUN: a synthetic 2-job stacked-order
      trip confirms TWO `offer_distance_accuracy` rows exist afterward
      (proving §7.4.1's bug is fixed - before this fix, only one row
      would exist), job #1's row has real deadhead/payout/accepted_ts
      but NULL delivery/hourly-rate fields, job #2's (trip-end) row has
      every field populated correctly.
- [ ] User sign-off

## 7.6 Part 2B - full dropoff-side per-job phase timing (still blocked, NOT started)

The ORIGINAL §7.2 scope - `dropoff_arrival_ts`, `walking_confirmed_ts`,
`job_end_ts` captured per job, and `_build_trip_summary_dict` returning
a per-job list of phase breakdowns - is NOT resolved by §7.4 above.
Real reason, confirmed while doing this design pass: pickup-side data
(§7.4) has a natural per-job anchor (`self.pickup`, overwritten exactly
once per job, snapshot-on-overwrite works cleanly). Dropoff-side events
have no equivalent - `dropoff_arrival_ts`/`walking_confirmed_ts` are
captured against `StopsBuffer`'s stop list via geofencing, with no
existing mechanism linking a specific dropoff arrival to a specific
EARLIER pickup job when a trip has 2+ of each. Building that linkage
(nearest-unresolved-stop matching? explicit stop-to-job IDs threaded
through the whole stops pipeline?) is a real, separate design question
this pass did not resolve - still needs its own pass, still blocking
`docs/feedback_dialog_phase_timings/` §4B's per-phase scoring for a
multi-job trip. Not started; no timeline implied.

## 8. Success criteria for Part 2B (not started, blocked - see §7.6)

- [ ] A real per-job dropoff-linkage design exists (own design pass,
      not yet done)
- [ ] Schema migration: per-job dropoff/walking/job-end timestamp
      columns added to `offer_distance_accuracy`
- [ ] Capture points (dropoff arrival, walking confirmed, job end)
      write to the correct job's own row via that linkage, not a
      trip-wide column
- [ ] `dropoff_arrival_ts`/`walking_confirmed_ts`'s existing first-wins
      guard replaced by real per-job capture
- [ ] `_build_trip_summary_dict` returns a per-job list of phase
      breakdowns, not one trip-level dict
- [ ] Executable test written and RUN: a synthetic batch-order trip (2+
      jobs) confirming each job's own phase timing is captured correctly
      and independently
- [ ] User sign-off
