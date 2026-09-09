# Progress log -- customer (dropoff) parking-difficulty zone map

## A real pre-existing bug found while scoping this (2026-09-09)

Asked to extend `docs/parking_zone_map`'s restaurant zone map to also
cover customer dropoff locations. While tracing where dropoff data
would come from, found that it already exists -- by accident.

`parking_difficulty_feedback` is named `restaurant_name` throughout
(the column, every doc comment, `get_parking_difficulty_rating`'s
parameter). The only place that ever writes a row --
`TripManager._record_park_to_walk_gap_sample`, fed by
`is_walking_pace` -- calls `_check_approaching_stop(lat, lon)`, which
walks `self.stops`. `self.stops` is TripManager's **dropoff** stop
list: `check_approaching_pickup`'s own docstring confirms the split
("the single active pickup rather than a list of dropoff stops").
There is no second call site and no pickup-specific walking-detection
path. So every real row in `parking_difficulty_feedback` -- auto
-labeled and manual (the manual path upgrades the SAME auto row in
place via `feedback_id`, never touching its `restaurant_name` column)
-- already carries a dropoff address, not a restaurant name.

Consequence for the already-open parking zone map PR: its
`get_parking_difficulty_zones()` joins this table against
`pickup_location_history` (real restaurant names, from
`record_pickup_location`). A restaurant name and a customer street
address are essentially never equal, so that join is very likely
matching close to nothing in production. Driver chose (asked directly,
answered "Dropoff-only for now") to leave that pickup-side wiring bug
alone for this pass and build the correctly-wired customer screen on
top of what already exists, rather than also fixing
`is_walking_pace` to distinguish pickup from dropoff. That means this
change needed no new feedback-recording code at all -- only a new GPS
-anchor table for dropoffs (nothing like `pickup_location_history`
existed for them) and a new join against it.

## What was built

`drive_monitor.py`:
- `dropoff_location_history` table (`address`, `lat`, `lon`,
  `timestamp`) -- same shape as `pickup_location_history`, no
  canonicalization step needed (a geocoded address doesn't accumulate
  spelling variants the way a hand-typed restaurant name does).
- `record_dropoff_location(address, lat, lon)` -- same "start
  persisting real coordinates going forward" pattern as
  `record_pickup_location`, same placeholder-(0,0) guard.
- `get_customer_parking_difficulty_zones()` -- same shape as
  `get_parking_difficulty_zones()`, reusing `_score_parking_difficulty`
  unchanged, just grouping `dropoff_location_history` by `address`
  instead of `pickup_location_history` by `restaurant_name`. Same
  honest scope boundary: an address needs both a GPS anchor and
  >= `PARKING_DIFFICULTY_MIN_SAMPLES` samples to be plotted.

`DasherAccessibilityService.java`: `record_dropoff_location` called
right where `add_stop_to_buffer` already fires on a resolved dropoff
geocode (`handleDropoffScreen`'s success callback) -- the exact mirror
of where `record_pickup_location` is called on the pickup side.

`CustomerZoneMapActivity.java` / `activity_customer_zone_map.xml`:
structural copy of `ParkingZoneMapActivity` / its layout -- same
satellite tile source (with the same disclosed Esri z/y/x ordering
risk), same shaded-zone style and thresholds, same tap-to-detail
dialog -- calling `get_customer_parking_difficulty_zones` and keying
markers/dialogs on `address` instead of `restaurant_name`. Reuses
`parking_zone_map_attribution` directly (tile provenance, not
restaurant-vs-customer, so nothing to duplicate).

`TripHistoryActivity.java` / `activity_trip_history.xml`: new
`customerZoneMapButton`, same one-line `Intent`-launch shape
`parkingZoneMapButton` already uses.

`AndroidManifest.xml`: `CustomerZoneMapActivity` declared, same shape
as `ParkingZoneMapActivity`'s own entry.

New strings: `customer_zone_map`, `customer_zone_map_no_data`.

### Verification

- `python3 -m py_compile drive_monitor.py` -- compiles cleanly.
- New scratchpad test (`test_customer_zone_map.py`, 15 assertions, all
  passed) against `Database(":memory:")`: `record_dropoff_location`
  drops placeholder (0,0) coordinates and persists real ones verbatim;
  `get_customer_parking_difficulty_zones` excludes a below-threshold
  address, excludes an address with feedback but no GPS anchor,
  includes an at-threshold address with the correct label/score/lat
  /lon, and reports the correct manual/auto split for a mixed-source
  address.
- Brace/paren balance: `CustomerZoneMapActivity.java` 19/19 braces,
  115/115 parens; `TripHistoryActivity.java` 105/105, 739/739;
  `DasherAccessibilityService.java` 169/169, 615/615 (all balanced
  after their respective additions).
- XML well-formedness confirmed on `activity_customer_zone_map.xml`,
  `activity_trip_history.xml`, `strings.xml`, `AndroidManifest.xml`.
  **Same mistake as `docs/parking_zone_map/PROGRESS.md` flagged before
  (and still not fully internalized)**: the new layout's own top
  comment used " -- " as a dash, illegal inside an XML `<!-- -->` body
  -- caught by this same well-formedness check, fixed to a plain comma
  before moving on.
- Full `R.id.*`/`android:id` and `@string/*` cross-check between the
  new Java file, its layout, and every touched layout/manifest --
  every reference resolves.

**Not done, and can't be from here**: on-device confirmation (no
Android SDK/emulator in this environment, same disclosed limitation as
every other Java/XML change in this repo). The pickup-side wiring bug
this investigation surfaced (`is_walking_pace` only ever checking
dropoff stops) is also **not fixed** -- left alone per explicit
driver choice, not an oversight.
