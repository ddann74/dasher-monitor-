# Auto-labeled parking-difficulty samples

STATUS: IMPLEMENTED (2026-09-03)

## 0. Origin

The driver asked whether Google Maps' update frequency could power a
low-available-parking heat map. The honest answer: Google exposes no
public real-time parking-availability API, but this app already
measures something related and real -- the time between a genuine park
and the driver actually walking (`park_to_walk_gap_history`,
`parking_difficulty_feedback`) -- which could feed a heat map using the
map infrastructure just built for `docs/location_profitability_map/`.

The driver's response, twice, was about speed, not the heat map itself:

> "i need more data points much sooner"
> "i need more data points now not over the course of months of work"

So the heat map stays deferred. This PRD is only about the data supply
it would eventually draw from.

## 1. Investigation -- why data was accumulating so slowly

`TripManager._record_park_to_walk_gap_sample` (drive_monitor.py, called
from `is_walking_pace` every time a genuine park-to-walk transition is
detected) already fires automatically, per stop, with zero driver
action -- it feeds the GLOBAL `park_to_walk_gap_history` average used
to learn the walking-speed threshold. But it only ever stashed the
result in two scalars, `_last_gap_restaurant_name`/`_last_gap_seconds`,
overwritten on every park event.

Those scalars are read exactly once, at trip end, by
`MainActivity.showFeedbackDialog` (L688-787) -- and only if the driver
doesn't tap "Skip". A `parking_difficulty_feedback` row (the table
`get_parking_difficulty_rating` actually gates on, `PARKING_DIFFICULTY_
MIN_SAMPLES = 3` per restaurant) was therefore created:

- at most ONCE per trip, not once per stop (a multi-stop trip's earlier
  stops were silently overwritten before ever being surfaced),
- only for whichever stop happened to be walked-to LAST,
- only if the driver manually answered Easy/Okay/Hard instead of Skip.

Real accumulation rate: well under one confirmed sample per trip, for
one restaurant. Reaching `PARKING_DIFFICULTY_MIN_SAMPLES=3` for any
single restaurant this way realistically takes weeks to months of
driving, matching exactly what the driver was pushing back on.

## 2. Options considered

Presented to the driver, not picked silently (this file's own repo
convention: real accuracy tradeoffs are a driver decision):

1. **Auto-label from the raw measured duration** -- write a
   `parking_difficulty_feedback` row the instant a gap is measured,
   using a cheap threshold against the gap itself, not waiting on a
   manual answer at all.
2. **Stop discarding multi-stop data** -- persist every stop's gap, not
   just the last one that happened to survive to trip-end.
3. **Ask per-stop instead of per-trip** -- move the manual Easy/Okay/
   Hard question to fire after every stop, not just once at the end.

Driver's answer ("now, not over months") pointed at 1+2 combined:
both remove the dependency on manual driver action entirely and both
apply to data going forward immediately, rather than waiting on driver
behavior to accumulate over further driving. Option 3 was not built --
it still gates on the driver answering every single stop, which cuts
against "now."

## 3. Design

- New auto-labeling threshold (`TripManager._auto_parking_difficulty_
  label`): same fixed-default-then-learned-baseline shape already used
  by `_learned_walking_speed_threshold_kmh` and `_learned_recently_
  parked_window_seconds` in this exact file -- a fixed 45s guess before
  `PARK_TO_WALK_GAP_MIN_SAMPLES_TO_LEARN` (10) real gaps exist, the
  learned global average after that. `gap <= baseline*0.6` -> easy,
  `gap >= baseline*1.5` -> difficult, else normal.
- `_record_park_to_walk_gap_sample` now inserts a `parking_difficulty_
  feedback` row immediately, tagged `source='auto'`, for EVERY stop
  that has a restaurant name -- this alone fixes both option 1 and
  option 2 (every stop already runs through this one function).
- The row's id is remembered (`_last_gap_feedback_row_id`) and
  round-tripped through `get_last_parking_gap_for_feedback` ->
  MainActivity -> `record_parking_difficulty_feedback`, so a driver's
  real manual answer (still fully intact, unchanged UI) UPGRADES that
  same row to `source='manual'` in place, instead of inserting a
  second row for the same physical parking event -- a real answer is
  still preferred over a guess, it just isn't required to get *a* data
  point.
- `get_parking_difficulty_rating` counts both sources toward the
  min-sample gate (that's the entire point), and now also returns
  `manual_sample_count`/`auto_sample_count` so any future UI can
  disclose how much of a rating is confirmed vs. guessed, rather than
  presenting an auto-only rating as fully driver-verified.
- Schema: `parking_difficulty_feedback` gets a new `source TEXT DEFAULT
  'manual'` column, added via the same `PRAGMA table_info` + `ALTER
  TABLE ... ADD COLUMN` migration pattern already used for `offer_
  outcomes.is_test_data` etc. (`Database.__init__`). Existing rows
  default to `'manual'`, correctly -- the auto path didn't exist when
  they were written.

## 4. Simulated-data contamination check

`parking_difficulty_feedback` has no `is_test_data` column, so an
auto-write firing from a SIMULATED GPS sequence (Developer Testing's
`simulateDriveAndArrival`, or Tutorial Mode's `showStepDriving`) would
have polluted real per-restaurant data with no way to ever filter it
back out. Checked directly before shipping this:

`is_walking_pace` only records a genuine park when `speed_kmh <
WALKING_SPEED_MIN_KMH` (0.5, strict less-than). Both existing simulated
GPS sequences hold speed at exactly `0.5` during their "arrived, not
yet walking" phase (`DeveloperTestingActivity.java` L203, `Tutorial
Activity.java` L287) -- `0.5 < 0.5` is false, so neither ever
accumulates a genuine-park state, and `_record_park_to_walk_gap_sample`
is never reached by either. Confirmed by grepping every `on_gps_update`
call site in the Java tree (`TripForegroundService` real GPS,
`DeveloperTestingActivity`, `TutorialActivity` -- no others exist).

This is a real dependency on both simulated paths staying at exactly
that boundary value, not a structural guarantee -- flagged here rather
than left implicit, per this repo's own established discipline. A
future simulated GPS sequence that ever ramps speed down through the
walking-pace range (0.5-8 km/h) before settling would need either to
avoid that range entirely (matching the existing two) or for
`on_gps_update`/`is_walking_pace`/`_record_park_to_walk_gap_sample` to
take an explicit `is_test_data` flag threaded through from the caller,
the same way every other simulated write in this codebase is tagged.
Not built now -- no existing simulated flow needs it, and threading a
new parameter through the single most-called real-GPS entry point for
a currently-hypothetical need was judged out of scope for a data-volume
fix. Noted here as the natural next step if that ever changes.

## 5. A visible side effect

`get_address_book` (drive_monitor.py L4906) already calls
`get_parking_difficulty_rating` and displays `parking_difficulty`/
`parking_difficulty_samples` per restaurant -- that screen is already
wired into the UI (Address Book), it was just starved of data. No UI
change was needed for this to start actually showing "Easy"/"Normal"/
"Difficult" per restaurant far sooner than before.

## 6. Verification

No Android SDK/emulator in this environment (confirmed earlier in this
session) -- verified by:
- `python3 -m py_compile drive_monitor.py` (clean).
- Real, executable tests: `test_parking_auto_labeling.py` (8 cases) --
  immediate auto-write, multi-stop persistence (not just the last
  stop), rating reachable from auto-only samples, manual-answer
  upgrade-not-duplicate, feedback_id=-1 fallback, no-restaurant-name
  skip (and stale feedback_id clearing), learned-baseline switch, and
  the schema migration against a real pre-existing on-disk SQLite file.
- Full existing suite re-run: only the known pre-existing, unrelated
  `test_dropoff_instruction_wiring.py` failure (stale signature, not a
  regression -- documented in this repo's earlier session work).
- `MainActivity.java` brace/paren balance confirmed (151/151, 700/700)
  before and after the edit.

## 7. Non-goals

- The parking-difficulty heat map itself -- still deferred, per the
  driver's own explicit prioritization ("now, not the heat map").
- Option 3 (per-stop manual question) -- not built, see ss2.
- Per-restaurant auto-labeling baseline (vs. the current global
  average) -- the existing `park_to_walk_gap_history` table is a
  single global row; a per-restaurant version would need a new table
  and wasn't asked for. The min-sample GATE is still per-restaurant,
  which is what makes the final rating meaningful.
