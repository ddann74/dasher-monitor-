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
every other Java/XML change in this repo).

## Pickup-side wiring bug: fixed (2026-09-09)

Driver asked to fix it after all. `TripManager.is_walking_pace` now
calls `check_approaching_pickup` FIRST; only when that returns `None`
(no active, not-yet-arrived pickup -- which is also true for the
entire rest of a trip once pickup is arrived) does it fall back to
`_check_approaching_stop` (the dropoff list), exactly as before. Each
call site now tracks a local `stop_type` ('pickup' or 'dropoff')
alongside `nearest`, and the gap-recording key switches with it:
`restaurant_name` for a pickup, `address` for a dropoff -- the two
identifiers this app already uses for those two things elsewhere
(`pickup_location_history` vs `dropoff_location_history`).

`_record_park_to_walk_gap_sample` gained a `stop_type` parameter,
threaded straight into a new `stop_type` column on
`parking_difficulty_feedback`. Migration backfills every pre-existing
row as `'dropoff'` -- not a guess, an honest description of what every
such row already was (see the finding above). `record_parking_
difficulty_feedback`'s manual-upgrade path needed no change: it only
ever touches `difficulty`/`source` on the row `feedback_id` already
points at, never `stop_type`, so a driver's manual answer inherits
whichever stop type the auto row was already tagged with.

`get_parking_difficulty_rating`, `get_parking_difficulty_zones`, and
`get_customer_parking_difficulty_zones` all gained a matching
`stop_type = 'pickup'` / `'dropoff'` filter. This is the actual
correctness fix for the already-open parking zone map PR: before it,
genuine pickup samples didn't exist at all (the bug), so the join
against real restaurant names matched close to nothing; now that
`is_walking_pace` actually detects walking-to-pickup, the filter makes
sure a restaurant's zone reflects only real pickup samples, and a
customer's zone only real dropoff samples -- never an accidental
cross-match if a restaurant name and a street address were ever
somehow equal.

Also exposed `stop_type` on `get_last_parking_gap_for_feedback`'s
returned dict, for whichever future change wants the post-trip
feedback dialog to say which kind of stop it's actually asking about.
Not wired into any Java caller yet -- no UI copy change was asked for
here, and org.json silently ignores an extra key it doesn't read.

### Verification

- `python3 -m py_compile drive_monitor.py` -- compiles cleanly.
- New scratchpad test (`test_pickup_wiring_fix.py`, 23 assertions, all
  passed):
  - Migration backfill: a hand-built pre-existing database (has
    `source`, predates `stop_type`) gets its existing row backfilled
    as `stop_type = 'dropoff'`, `source` left untouched.
  - Full GPS-tick simulation through `TripManager.is_walking_pace`
    (a genuine park confirmed at `WALKING_MIN_PARK_SECONDS`, then
    sustained walking-pace speed across
    `WALKING_PATTERN_CONSECUTIVE_READINGS` ticks, at a real distance
    -- confirmed against this app's own `haversine_meters`, not
    hand-approximated -- between `ARRIVAL_GEOFENCE_METERS` and
    `APPROACHING_RADIUS_METERS`) for an active pickup: walking
    confirms on the expected tick, exactly one row is auto-recorded,
    keyed by `restaurant_name`, tagged `stop_type = 'pickup'`, and
    `get_last_parking_gap_for_feedback` reports the same.
  - Same simulation for an unmatched dropoff stop with no active
    pickup: still detected, still recorded, now explicitly tagged
    `stop_type = 'dropoff'` -- confirms the fix didn't regress the
    pre-existing (accidental) behavior.
  - Two location anchors seeded under the SAME name, one in
    `pickup_location_history` and one in `dropoff_location_history`,
    with 3 `'pickup'`-tagged Easy samples and 3 `'dropoff'`-tagged
    Difficult samples both under that name: `get_parking_difficulty_
    rating`/`get_parking_difficulty_zones` report Easy (pickup rows
    only), `get_customer_parking_difficulty_zones` reports Difficult
    (dropoff rows only) -- proves the two are never conflated even
    when the underlying name collides.
- Re-ran `test_customer_zone_map.py` (the earlier 15-assertion test)
  after adding the `stop_type` filter -- still passes: SQLite's column
  `DEFAULT 'dropoff'` applies on an `INSERT` that omits the column, so
  rows seeded without an explicit `stop_type` still match the new
  filter as expected.
- Brace/paren balance re-confirmed on `CustomerZoneMapActivity.java`
  (19/19, 113/113) after its doc-comment update.

**Not done, and can't be from here**: on-device confirmation, same
disclosed limitation as above.
