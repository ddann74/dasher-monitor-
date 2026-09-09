# PRD: Real parking-difficulty zone map (satellite basemap, shaded zones)

Status: IMPLEMENTED (all §6 boxes checked except on-device confirmation
and driver sign-off, which are never mine to check).

## 0. Origin

Driver asked for a "rough zone map" of parking availability. First pass
was a standalone web artifact (`docs/../` -- illustrative sketch, no
real data, click-to-mark). Driver then asked to "start working on"
wiring it to real data, and clarified: shade zones by color/hotspot
"based on a google maps satelite image."

**Real constraint, disclosed before scoping further**: a web artifact
runs in a locked-down sandbox that cannot load Google Maps tiles, any
satellite imagery, or any external image at all -- not a style choice,
an enforced limitation. The only place this app can actually show a
real satellite basemap with real data is the Android app itself, via
`osmdroid` (already a dependency, already proven in
`LocationProfitabilityMapActivity`). Driver confirmed: build it as a
real Android screen.

## 1. Investigation

- **Real data already exists, no new collection needed.**
  `parking_difficulty_feedback` (`restaurant_name`, `gap_seconds`,
  `difficulty` in `{"easy","normal","difficult"}`, `timestamp`,
  `source`) -- populated two ways: auto-labeled from the real measured
  park-to-walk gap (`_auto_parking_difficulty_label`,
  `PARKING_AUTO_LABEL_*` constants) and driver-confirmed via the
  feedback dialog's "Parking" quick-tap row
  (`record_parking_difficulty_feedback`). Has NO lat/lon of its own --
  keyed only by `restaurant_name` (text).
- `pickup_location_history` (`restaurant_name`, `lat`, `lon`,
  `timestamp`) is the real GPS anchor per restaurant --
  `get_location_profitability()` already joins these two tables' shape
  (name-keyed feedback against `pickup_location_history` averaged by
  name) for a different metric (Smart Score profitability). This PRD's
  new query follows the identical join pattern for parking difficulty
  instead.
- `get_parking_difficulty_rating(restaurant_name)` (already exists,
  `drive_monitor.py:4137`) already computes exactly the per-restaurant
  aggregate this map needs: `difficulty_map = {"easy": 0, "normal": 50,
  "difficult": 100}`, averaged, labeled Easy/Normal/Difficult at the
  33/66 thresholds, gated on `PARKING_DIFFICULTY_MIN_SAMPLES = 3`. This
  PRD's new map query reuses this EXACT scoring logic (not a second,
  differently-tuned copy) across every restaurant with a location, the
  same way `get_location_profitability()` loops per-restaurant and
  reuses (not reinvents) existing per-restaurant logic.
- **Same honest scope boundary `get_location_profitability()` already
  discloses**: only a restaurant with at least one real GPS-confirmed
  pickup (a `pickup_location_history` row) can be plotted. A restaurant
  with parking feedback recorded before location tracking existed, or
  one somehow missing that row, has no anchor to plot -- not solvable
  without a real GPS fix that doesn't exist for that case.
- **osmdroid, not Google Maps SDK** -- same reasoning
  `docs/location_profitability_map/PRD.md` ss1.3 already established
  (no API key, no new Google Cloud Console setup for the driver) --
  reused, not re-litigated.
- **Satellite tiles, the one genuinely new piece**: osmdroid's built-in
  `TileSourceFactory.MAPNIK` (used by `LocationProfitabilityMapActivity`)
  is a street map, not satellite imagery. osmdroid supports a custom
  `XYTileSource` for any standard slippy-map tile URL template.
  Recommended: **ESRI World Imagery**
  (`server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer`)
  -- real satellite/aerial photography, free, no API key, a
  well-established choice for exactly this (osmdroid + free satellite
  tiles) outside this app. NOT literally Google's own satellite tiles
  (Google's tile servers aren't freely usable outside the Google Maps
  SDK, which reintroduces the API-key/Cloud-Console cost this app has
  twice already chosen to avoid) -- a real, disclosed substitution, not
  silently passed off as "Google Maps."
- **Real cost worth disclosing, not hidden**: satellite imagery tiles
  are meaningfully larger downloads than street-map vector-style tiles
  -- more mobile data per map view than `LocationProfitabilityMapActivity`
  already uses. Not blocking, but the driver should know before this
  ships, especially opened often while driving.

## 2. Definition of "functional"

- [x] New `ParkingZoneMapActivity`, reachable from `TripHistoryActivity`
      (same button-list pattern `locationProfitabilityMapButton` already
      uses), themed `Theme.DasherMonitor.TripHistory`.
- [x] Shows a real satellite basemap (ESRI World Imagery via a custom
      `XYTileSource`) centered on the average of every plotted zone.
- [x] One shaded (translucent, not a pin) zone per restaurant with
      BOTH a `pickup_location_history` anchor AND at least
      `PARKING_DIFFICULTY_MIN_SAMPLES` real parking-difficulty samples
      -- color per the real label (`Easy`/`Normal`/`Difficult`), reusing
      `get_parking_difficulty_rating`'s exact thresholds.
- [x] Tapping a zone shows its restaurant name, label, sample count, and
      the manual-vs-auto sample split (`get_parking_difficulty_rating`
      already returns this) -- same "disclose confidence, don't just
      show a color" principle `get_location_profitability`'s own
      `sample_count` already follows.
- [x] Restaurants below the sample threshold, or with no GPS anchor, are
      simply not plotted -- not shown as a guessed/default color.

Non-goals:
- No new data collection -- both source tables already exist and are
  already populated by real, working mechanisms. This PRD is a new
  read-side query plus a new screen, nothing else.
- Not a redesign of the existing `LocationProfitabilityMapActivity` --
  a new, separate screen and query, for a different metric.
- Zone SIZE is a fixed, rough illustrative radius around the averaged
  coordinate, not a measured real boundary (no data exists for an
  actual parking-area shape) -- same "rough guide" spirit the driver's
  own original ask stated for the web-sketch version.

## 3. Design

### 3.1 Python (`drive_monitor.py`)

New `get_parking_difficulty_zones(self)` on `DriveMonitorEngine`,
directly mirroring `get_location_profitability()`'s own shape:

```
location_rows = SELECT restaurant_name, AVG(lat), AVG(lon)
                 FROM pickup_location_history GROUP BY restaurant_name
for each row:
    difficulty rows = SELECT difficulty, source FROM parking_difficulty_feedback
                       WHERE restaurant_name = ?
    (same difficulty_map / threshold / gating logic get_parking_difficulty_rating
     already implements -- factor it out into a shared helper both methods
     call, rather than a second copy of the same three-line scoring formula)
    if sample_count < PARKING_DIFFICULTY_MIN_SAMPLES: skip
    entries.append({restaurant_name, lat, lon, avg_score, label,
                     sample_count, manual_sample_count, auto_sample_count})
return json.dumps({"entries": entries})
```

**Refactor, not a duplicate**: `get_parking_difficulty_rating`'s own
scoring body (the `difficulty_map`/averaging/labeling block) gets
pulled into a small shared helper (e.g. `_score_parking_difficulty(rows)`)
that both the existing single-restaurant method and this new
all-restaurants method call -- avoids a second, driftable copy of the
same three-line formula, the same "centralize, don't duplicate" call
this session has made repeatedly elsewhere.

### 3.2 `ParkingZoneMapActivity.java` / `activity_parking_zone_map.xml`

Structural copy of `LocationProfitabilityMapActivity`'s own shape
(osmdroid `Configuration`/user-agent setup, center-on-average, tap
-to-detail `AlertDialog`) with two real differences:

- `mapView.setTileSource(...)`: a custom
  `org.osmdroid.tileprovider.tilesource.XYTileSource` pointed at ESRI
  World Imagery's REST tile endpoint, instead of
  `TileSourceFactory.MAPNIK`.
- Markers are shaded circles (a semi-transparent `GradientDrawable`
  oval, larger and more transparent than `LocationProfitabilityMapActivity`'s
  small solid dots -- "shaded zone," not a pin) rather than solid small
  dots, colored per label:
  - Easy -> green `#2E7D32` (same hex this app's own Excellent/Good
    score language already uses elsewhere, reused not reinvented)
  - Normal -> orange `#EF6C00`
  - Difficult -> red `#C62828`
  (Three real levels here, not the app's separate 4-level Smart Score
  palette -- not force-mapped onto Excellent/Good/Fair/Poor, a
  different scale measuring a different thing.)

## 4. Premortem

- **P1 -- ESRI's tile endpoint changes, rate-limits, or requires
  attribution this app doesn't show.** Real risk with any free
  third-party tile service. Mitigation: disclose ESRI World Imagery's
  own attribution requirement in the screen (a small on-screen credit
  line, same as `LocationProfitabilityMapActivity` should arguably also
  carry for OpenStreetMap -- worth checking whether that's already
  present there before assuming it needs adding fresh here).
- **P2 -- satellite tiles' larger data usage matters more than
  disclosed.** §1's own cost note -- not solved here, just named. A
  driver who opens this often on a limited data plan is a real,
  unaddressed cost.
- **P3 -- same "no anchor, no plot" gap `get_location_profitability`
  already has**, now doubled: a restaurant needs BOTH a
  `pickup_location_history` row AND >= `PARKING_DIFFICULTY_MIN_SAMPLES`
  parking samples. Likely a SMALLER set of plottable restaurants than
  the profitability map already shows, especially early on -- not a
  bug, just worth the driver knowing before expecting a dense map on
  first use.
- **P4 -- no Android SDK/emulator/device in this environment, same
  disclosed limitation as every other Java/XML PRD here.** A custom
  `XYTileSource` is new, unverified-in-this-app API surface (even
  though `MapView`/`Marker`/osmdroid's general shape is now proven via
  `LocationProfitabilityMapActivity`) -- code review only, not a real
  render check.

## 5. Open questions

None blocking -- driver already confirmed the build-target decision
(§0) and the satellite-tile substitution (§1) is disclosed, not a
silent guess requiring sign-off before starting.

## 6. Success criteria (ralph-loop checklist)

- [x] `_score_parking_difficulty(rows)` extracted as a shared helper;
      `get_parking_difficulty_rating` refactored to use it (no behavior
      change, confirmed by re-running its own existing real test if one
      exists, or writing one if not)
- [x] `get_parking_difficulty_zones()` added, real Python test covering:
      a restaurant below the sample threshold is excluded; one at/above
      it is included with the correct label/score; a restaurant with
      parking feedback but no `pickup_location_history` row is excluded
      (no anchor); manual/auto sample split matches
      `get_parking_difficulty_rating`'s own
- [x] `activity_parking_zone_map.xml` + `ParkingZoneMapActivity.java`
      created, structural copy of `LocationProfitabilityMapActivity`
      with the two disclosed differences (§3.2)
- [x] `TripHistoryActivity`: new button launching
      `ParkingZoneMapActivity`, same pattern as
      `locationProfitabilityMapButton`
- [x] `AndroidManifest.xml`: new Activity declared,
      `Theme.DasherMonitor.TripHistory`, parent `TripHistoryActivity`
- [x] ESRI World Imagery attribution shown on-screen (P1)
- [x] Brace/paren balance + XML well-formedness on every touched/new
      file
- [ ] Driver confirms on-device: the satellite basemap actually loads,
      zones appear at roughly the right real-world locations, tapping
      one shows correct detail
- [ ] Driver sign-off
