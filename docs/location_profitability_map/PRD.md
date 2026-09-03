# PRD: location profitability map (driver backlog #1)

Status: DESIGNED, being implemented this pass.
Driver ask (originally backlog #1): "Build a database of smart score
data and geolocation so I can determine the most profitable locations
to be in." Scoped directly (2026-09-03, `docs/driver_backlog_2026_09_03/
PROGRESS.md`) - asked whether the smallest useful version (a stat added
to an existing screen) or the full original ask was wanted. Driver
answered: **a dedicated new map**.

## 1. Investigation (2026-09-03)

### 1.1 No schema change needed - the original triage overstated this

The original backlog triage said this needs "a schema change joining
[`offer_outcomes` and `pickup_location_history`]." Re-checked directly
against the real schema before designing anything: both tables already
share `restaurant_name` as a key, and this exact join-by-restaurant-name
pattern is ALREADY the established approach `get_address_book()` uses
today (joining `restaurant_wait_history` and `offer_distance_accuracy`
the same way). No new column, no migration - a straight `JOIN ...
restaurant_name` query, same as existing code.

- `offer_outcomes`: `restaurant_name`, `smart_score`, `payout`,
  `distance_km`, `hourly_rate`, `outcome`, `is_test_data` - no lat/lon,
  correct that part of the original triage.
- `pickup_location_history`: `restaurant_name`, `lat`, `lon`,
  `timestamp` - one row per REAL, GPS-confirmed pickup arrival (written
  by `TripManager._evaluate_pickup`'s real arrival-geofence detection,
  not from the offer screen's claimed address). This is the real
  geo-anchor this feature needs.

### 1.2 A real, honest scope boundary this join creates

`pickup_location_history` only gets a row when a pickup was physically
GPS-confirmed - meaning a restaurant you have only ever DECLINED offers
from (never once accepted and driven there) has no recorded location at
all, regardless of how many `offer_outcomes` rows exist for it. **The
map can only plot restaurants you've actually picked up from at least
once** - a genuine, disclosed limitation, not a bug to fix here (there
is no other real GPS anchor to use for a restaurant you've never
visited).

### 1.3 osmdroid was already half-built into this app's history

`app/build.gradle`'s dependency list carries a comment: `osmdroid` "was
previously listed here for a planned map view (route trail + delay
pins) that was never actually built... Add it back if/when that map
screen actually gets built." That moment is now. osmdroid renders
OpenStreetMap tiles and needs **no API key** - unlike Google Maps SDK,
there's no new Google Cloud Console configuration for the driver to do,
and this app's manifest already declares the only two permissions it
needs (`INTERNET`, `ACCESS_NETWORK_STATE` - confirmed present, both
already used for the existing geocoding calls).

### 1.4 Score-label color convention already established

`SmartScoreEngine._label(score)` (`drive_monitor.py`) is the single
source of truth for score->label thresholds (Excellent >=85, Good >=70,
Fair >=50, else Poor) used everywhere else in this app.
`DasherAccessibilityService.colorForLabel`/`DeveloperTestingActivity`'s
own color switch already map those exact 4 labels to specific hex
colors. Reusing both directly for map markers - not inventing a third
bucketing scheme or a new color palette for the same concept.

## 2. Design

### 2.1 Python: `get_location_profitability()`

New method on `DriveMonitorEngine`. For each `restaurant_name` present
in `pickup_location_history` (the real geo-anchor, per ss1.2):

- `lat`/`lon`: average of every recorded pickup GPS fix for that
  restaurant (reduces GPS noise across repeat visits - a restaurant's
  real-world location doesn't move, multiple samples should converge on
  it).
- `avg_smart_score`, `avg_dollar_per_km`, `avg_dollar_per_hr`,
  `sample_count`: from `offer_outcomes` for that restaurant, `is_test_data
  = 0`, ANY outcome (accepted/declined/timed out) - same deliberate
  scope choice already established for the Address Book's own rate/score
  stats (2026-09-03, `docs/driver_backlog_2026_09_03/PRD.md` ss13.2):
  "profitable to be near" is informed by every offer this location has
  ever produced, not just the ones actually accepted. Same $/km exclusion
  rules already established (missing payout, zero/missing distance).
- `label`: `SmartScoreEngine._label(avg_smart_score)` - reused directly,
  not reimplemented.
- Gated on a new `LOCATION_PROFITABILITY_MIN_SAMPLES = 3` (matching the
  existing small-sample-caution precedent already used elsewhere in this
  file, e.g. `PARKING_DIFFICULTY_MIN_SAMPLES`) - a restaurant with 1-2
  samples is omitted from the map entirely rather than shown as a
  misleadingly confident data point.

### 2.2 Java: new `LocationProfitabilityMapActivity`

New Activity, new layout, using `org.osmdroid:osmdroid-android` (new
Gradle dependency - re-added, not new to this codebase's history per
ss1.3). An osmdroid `MapView` centered on the average of every plotted
point (or, if there's no data yet, a message screen instead of an empty
map - "not enough data yet, need at least N pickups from M different
restaurants"). One colored `Marker` per qualifying restaurant, colored
via ss1.4's exact existing label->color mapping. Tapping a marker shows
its info window: restaurant name, avg Smart Score + label, avg $/km,
avg $/hr, sample count - the same fields `get_address_book()`'s entries
already show, just geographically placed instead of listed.

**Entry point**: new "Profitability Map" button on `TripHistoryActivity`,
alongside Address Book and the other data-analysis screens - the
natural home, matching where every other cross-restaurant analysis view
already lives.

## 3. Non-goals / honest gaps (v1)

- Restaurants never picked up from (decline-only) are not plotted at
  all (ss1.2) - a real, structural limitation, not solved here.
- No route/heatmap rendering, no clustering for restaurants close
  together on screen, no driver-adjustable time window (all-time data
  only) - all real, disclosed simplifications for a first version, not
  silently promised.
- No offline tile caching configuration beyond osmdroid's own defaults -
  first map open needs a real network connection to fetch tiles.

## 4. Verification approach - higher uncertainty than usual, disclosed

Same base limitation as every Java-side PRD in this repo (no Android
SDK/emulator/device in this environment - confirmed directly: `gradle`/
`gradlew` exist here but no Android SDK is installed, so even a
compile-only check isn't possible, not just a device/emulator gap).
**Additional, real uncertainty specific to this PRD**: `osmdroid` has
never been used anywhere in this codebase's current history (only
referenced in a since-removed, never-built dependency line) - unlike
this repo's own first-party classes, there's no existing working
reference implementation in this codebase to model the exact API calls
against. Code written against osmdroid's public API from documentation
knowledge, not verified compiling. Flagged explicitly as a real,
elevated risk for this specific PRD, not glossed over as "the usual
limitation."

- `drive_monitor.py`: real, runnable Python test for
  `get_location_profitability()` (min-sample gating, lat/lon averaging,
  $/km exclusion rules, `is_test_data` exclusion, label bucketing) - 4
  cases, all passed.
- Java: brace/paren balance plus careful code review, same as always -
  but with the above caveat that this is weaker evidence than usual for
  the osmdroid-specific portions.
- `app/build.gradle`: dependency re-added, version pinned explicitly.
- **Self-caught bug during implementation**: both new XML files (the
  manifest's new `<activity>` entry comment and the new layout's own
  comment) initially used `--` as an em-dash inside an XML comment body
  - invalid XML (a comment may not contain `--` anywhere except its
  closing `-->`, per the XML spec). Caught by actually re-validating
  both files with `xml.etree.ElementTree.parse()` rather than assuming
  well-formedness from visual inspection, same discipline this repo's
  other PRDs already use for touched XML - fixed before this would have
  broken the Gradle resource-merge step.

## 5. Success criteria

- [x] Confirmed no schema change is actually needed (re-checked against
      the real schema, not assumed from the original triage)
- [x] `get_location_profitability()` implemented and tested
- [x] `osmdroid` dependency re-added with an explicit pinned version
- [x] `LocationProfitabilityMapActivity` implemented: markers colored by
      the existing label convention, tap-for-detail, empty-state message
      when there's not enough data
- [x] Entry point wired into `TripHistoryActivity`
- [ ] Driver confirms in real use: the map actually renders tiles, shows
      correctly colored/positioned markers, and the elevated osmdroid-
      specific risk (ss4) didn't turn into a real compile/runtime problem
- [ ] Driver sign-off.
