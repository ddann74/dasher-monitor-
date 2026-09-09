# Progress log -- real parking-difficulty zone map

## Python: shared scoring helper + get_parking_difficulty_zones (2026-09-09)

`_score_parking_difficulty(rows)` extracted from
`get_parking_difficulty_rating`'s own body (`@staticmethod`, takes the
raw `SELECT difficulty, source FROM parking_difficulty_feedback ...`
rows, returns the same dict shape that method used to build directly).
`get_parking_difficulty_rating` is now just the query plus
`json.dumps(self._score_parking_difficulty(rows))` -- no behavior
change, confirmed by regression tests below.

`get_parking_difficulty_zones()` added, mirroring
`get_location_profitability()`'s own join/loop shape exactly (`SELECT
restaurant_name, AVG(lat), AVG(lon) FROM pickup_location_history GROUP
BY restaurant_name`, then a per-restaurant lookup) -- reuses
`_score_parking_difficulty` per restaurant, skips any restaurant where
`has_rating` is false (below `PARKING_DIFFICULTY_MIN_SAMPLES`). Same
honest scope boundary `get_location_profitability` already discloses:
a restaurant needs BOTH a `pickup_location_history` row AND enough
parking samples, or it's silently omitted, never shown with a guessed
color.

### A real regression found and fixed along the way

Re-running the full scratchpad test suite after the refactor broke two
PRE-EXISTING tests (`test_address_book_rates_and_time_detail.py`,
`test_parking_auto_labeling.py`), both `TypeError`s. Root cause: both
tests use a lightweight `FakeEngine` test double that borrows individual
methods directly off `DriveMonitorEngine`
(`get_parking_difficulty_rating = drive_monitor.DriveMonitorEngine.
get_parking_difficulty_rating`) rather than instantiating the real
engine -- a real, pre-existing pattern in this repo's own test suite,
not something this PRD introduced. Once `get_parking_difficulty_rating`
started calling `self._score_parking_difficulty(...)`, `FakeEngine`
needed that method aliased too, but naively doing
`_score_parking_difficulty = drive_monitor.DriveMonitorEngine.
_score_parking_difficulty` silently broke it a SECOND way: accessing a
`@staticmethod` through the class unwraps it to a plain function, and
assigning a plain function as a class attribute makes it an
auto-binding instance method on the new class -- so `self` got passed
as an extra, unwanted first argument. Fixed by wrapping the alias in
`staticmethod(...)` on both `FakeEngine` copies, confirmed by re-running
both tests directly (not just re-running the new test) before touching
anything else.

### Verification

- `python3 -m py_compile drive_monitor.py` -- compiles cleanly, both
  after the extraction and after adding the new method.
- New `test_parking_zone_map.py` (scratchpad, 6 assertions, all
  passed): `get_parking_difficulty_rating`'s own output is unchanged
  for a below-threshold and an at-threshold restaurant (regression
  check on the extraction itself); `get_parking_difficulty_zones`
  correctly excludes a restaurant with enough parking samples but no
  GPS anchor, correctly includes one with both (right lat/lon/label/
  score/sample-count/manual-auto split), correctly excludes one with a
  GPS anchor but too few samples, and doesn't crash on a restaurant
  with a location but zero parking-feedback rows at all.
- Full existing scratchpad suite re-run (33 test files) after fixing
  the two broken `FakeEngine` copies: 31 pass, 2 fail -- both
  PRE-EXISTING, unrelated (`test_dropoff_instruction_wiring.py`, a
  stale pre-#4-fix signature; `test_parking_v4alpha.py`, needs a real
  `GOOGLE_MAPS_API_KEY` env var) -- no regressions left from this
  change.

PRD §6 boxes 1-2 checked. Next: `ParkingZoneMapActivity.java` +
`activity_parking_zone_map.xml`, structural copy of
`LocationProfitabilityMapActivity`.

## Java/XML: ParkingZoneMapActivity + wiring (2026-09-09)

`activity_parking_zone_map.xml`: structural copy of
`activity_location_profitability_map.xml` (osmdroid `MapView` +
empty-state `TextView`), plus one addition -- a small bottom-end
attribution `TextView` crediting Esri World Imagery, a real term of use
for that free tile service (PRD §4 P1), not decorative.

`ParkingZoneMapActivity.java`: structural copy of
`LocationProfitabilityMapActivity.java` (same `Configuration`/user
-agent setup, same center-on-average logic, same tap-to-detail
`AlertDialog`), with the two deliberate differences PRD §3.2 specifies:

- **Tile source**: a custom `XYTileSource` (`ESRI_WORLD_IMAGERY`)
  pointed at `server.arcgisonline.com`'s World Imagery REST endpoint,
  instead of `TileSourceFactory.MAPNIK`. **A real, disclosed risk found
  while writing this, not glossed over**: Esri's REST tile API
  addresses tiles as `.../tile/{z}/{y}/{x}` (row before column) -- the
  OPPOSITE of the `z/x/y` order `XYTileSource`'s own default
  `getTileURLString()` assumes. Overrode that method explicitly to
  build the URL in Esri's real order. This is based on general
  knowledge of both APIs, not confirmed against an actual tile response
  -- flagged in the class's own doc comment as the first place to check
  if satellite tiles fail to load or come back wrong, exactly the kind
  of "first real use of new API surface" risk this app's own
  `LocationProfitabilityMapActivity` already disclosed for osmdroid
  itself.
- **Shaded zones, not pins**: `shadedZoneBitmap(label)` draws a larger
  (72dp vs the profitability map's presumably-smaller dot),
  translucent (~40% alpha) `GradientDrawable` oval with a solid-color
  stroke, colored Easy=green/Normal=orange/Difficult=red -- three real
  levels this feature actually has, deliberately NOT force-mapped onto
  the app's separate 4-level Smart Score palette (Excellent/Good/Fair/
  Poor), which measures a different thing.

`TripHistoryActivity.java`: new `parkingZoneMapButton`, wired with the
exact same one-line `Intent`-launch shape
`locationProfitabilityMapButton` already uses.
`activity_trip_history.xml`: matching new `Button`, same
`backgroundTint="?attr/colorPrimary"` style every other button on this
screen already uses.

`AndroidManifest.xml`: `ParkingZoneMapActivity` declared,
`Theme.DasherMonitor.TripHistory`, parent `TripHistoryActivity` -- same
shape as `LocationProfitabilityMapActivity`'s own declaration.

New strings: `parking_zone_map` (button/title), `parking_zone_map_no_data`
(empty state, same "explain why, don't just show nothing" pattern the
profitability map's own empty state already uses), and
`parking_zone_map_attribution` (the real Esri credit line).

**A second real mistake caught and fixed during this pass, same class
as PR #38's**: every new XML comment I wrote initially used this
codebase's own Java-comment convention (" -- " as a dash), which is
illegal inside an XML `<!-- -->` comment body. Caught immediately via
the well-formedness check (not left for CI this time) in both the new
button's comment in `activity_trip_history.xml` and the manifest
entry's comment -- fixed to single hyphens before moving on, same
lesson from `docs/trip_history_redesign/PROGRESS.md` applied
proactively this time instead of reactively.

### Verification

No Android SDK/emulator/device in this environment.

- Brace/paren balance: `ParkingZoneMapActivity.java` 22/22 braces,
  121/121 parens; `TripHistoryActivity.java` 105/105, 735/735 (after
  the button wiring addition).
- XML well-formedness confirmed on `activity_parking_zone_map.xml`,
  `activity_trip_history.xml`, `strings.xml`, and `AndroidManifest.xml`.
- Full `R.id.*`/`android:id` and `@string/*` cross-check between the
  new Java file and its layout, and between both layouts and
  `strings.xml` -- every reference resolves.
- `python3 -m py_compile drive_monitor.py` -- unaffected by this half
  of the change, re-confirmed clean anyway.

**Not done, and can't be from here**: on-device confirmation that the
Esri satellite basemap actually loads (the real, disclosed risk in
this progress note's own tile-source section), that zones appear at
roughly correct real-world positions, and that tapping one shows
correct detail. Per PRD §6, these and final sign-off are the driver's
own to confirm.

## A real bug in this feature's own join, found and fixed later (2026-09-09)

While scoping a follow-up (a customer/dropoff counterpart to this map),
found that `get_parking_difficulty_zones()`'s join against
`pickup_location_history` was very likely matching close to nothing in
production: `is_walking_pace`, the only writer of
`parking_difficulty_feedback`, only ever checked TripManager's dropoff
stop list, never the active pickup -- so every real row already
carried a dropoff address under the `restaurant_name` column, despite
its name. Full account and the fix (a `stop_type` column, tagged going
forward, backfilled honestly on existing rows) are in
`docs/customer_zone_map/PROGRESS.md`. This restaurant map's own query
now filters `stop_type = 'pickup'` and should actually populate from
here on, rather than only after the fact.
