# PRD: navigate to hotspot or home, based on shift rate

Status: DESIGNED, being implemented this pass.
Driver ask (2026-09-03, mid-session, not one of the original 36-item
backlog): "I want the navigate to hotspot or home location based on
how busy my shift is. If my jobs fall below a certain rate then I
should return home, if it is above the rate I should go to hotspot. I
don't know how I want to calculate that rate." Asked directly which
rate calculation to use (AskUserQuestion, three real options with
tradeoffs) - driver chose: **average $/hr of the last 3-5 accepted
offers** (the Smart Score's own per-offer hourly-rate estimate,
smoothed over recent acceptances, not raw wall-clock earnings).

## 1. What already exists, reused here

- **The trigger moment**: `TripForegroundService`'s existing
  `TRIP_ACTIVE -> IDLE` transition check (`TripForegroundService.java`
  ~line 1267) already fires `check_show_return_to_sweet_spot` once all
  deliveries in a trip are complete - the exact right moment to also
  ask "should I suggest heading somewhere now."
- **The hotspot destination**: `get_recent_pickup_hotspot()` (driver
  backlog #5, just shipped) and `get_pickup_sweet_spot_zone()` already
  compute exactly this - reused as-is, recent preferred, falling back
  to all-history.
- **Geocoding**: `GoogleApiHelper.geocodeAddress()` already resolves a
  typed address to lat/lon (used for pickup/dropoff addresses today) -
  reused for the driver's home address, no new geocoding code.
- **The icon pattern**: `OverlayHelper.showReturnToSweetSpotIcon()`
  already does exactly the right THING (a tappable floating icon,
  shown once trip goes idle, navigates on tap) - but always renders a
  house emoji regardless of destination, which would be actively
  misleading for a hotspot suggestion. NOT reused directly - see §3.

## 2. What's missing

- No persisted `hourly_rate` (the dollar figure) on `offer_outcomes` -
  only `payout`/`distance_km` (raw) and `components_json` (the 0-100
  sub-scores, NOT the dollar hourly rate) are stored per offer today.
  Confirmed by reading exactly what `DasherAccessibilityService`
  passes into `record_offer_outcome`/`record_offer_timeout`.
- No home address storage anywhere in the app.
- No driver-settable rate threshold anywhere.
- No combined "which one, and is it worth suggesting" decision logic.

## 3. Design

**Rate**: new `offer_outcomes.hourly_rate REAL` column (migration, same
pattern as `omitted_from_calibration`). Captured at the same point
`DasherAccessibilityService` already captures `lastSeenComponentsJson`
(the live badge's own `score.optDouble("hourly_rate", 0)` - already
computed, just never persisted). `record_offer_outcome`/
`record_offer_timeout` gain an `hourly_rate` parameter - all 5 real
call sites updated (2 in `DasherAccessibilityService`, 3 in
`DeveloperTestingActivity`'s simulated-offer buttons, using a fixed
placeholder value there since `is_test_data=1` already excludes them
from every real calculation).

New `RECENT_RATE_WINDOW = 5`. `get_recent_shift_rate()`: average
`hourly_rate` across the last `RECENT_RATE_WINDOW` ACCEPTED, non-test
offers with a recorded rate. Returns `has_rate: False` below any real
sample (not zero, not a guess).

**Home + threshold**: new `shift_routing_prefs` SharedPreferences
(same pattern as `screen_recording_prefs`) - `home_address` (text),
`home_lat`/`home_lon` (set via `GoogleApiHelper.geocodeAddress` on
save, cleared if geocoding fails so a stale/wrong location is never
silently kept), `rate_threshold_dollars_per_hr` (float, driver-typed,
no default - the feature stays inactive until BOTH are explicitly set,
same "opt-in, no fabricated default" discipline as every other
driver-configured feature in this app, e.g. screen recording).
Setup UI: two new fields on `PermissionsActivity` (address `EditText` +
Save, threshold `EditText` + Save), following that screen's existing
section pattern.

**Decision**: new `check_show_hotspot_or_home_suggestion(lat, lon,
home_lat, home_lon, threshold)` - Java passes the SharedPreferences
values in directly (Python has no reason to know about Java-side
settings storage, same separation already used for the screen-recording
toggle). Logic: no rate sample yet, or home/threshold not configured ->
`should_show: False` with a specific reason (never silently nothing).
Otherwise: rate >= threshold -> hotspot destination (recent hotspot,
falling back to all-history sweet spot, in that order); rate <
threshold -> home. Suppressed if already within `UNFAMILIAR_AREA_
THRESHOLD_KM` of the chosen destination (reusing the existing constant
`check_show_return_to_sweet_spot` already uses for the identical
"don't suggest somewhere you're already at" reasoning).

**Java wiring**: at the SAME `TRIP_ACTIVE -> IDLE` trigger point - if
home+threshold are BOTH configured, call the NEW combined function
INSTEAD of the old sweet-spot-only check; if not configured, fall back
to the EXISTING `check_show_return_to_sweet_spot` call completely
unchanged. This means a driver who never sets up home+threshold sees
zero behavior change - the existing sweet-spot suggestion keeps working
exactly as it always has. New icon
(`OverlayHelper.showHotspotOrHomeIcon`, a SEPARATE overlay slot from
`showReturnToSweetSpotIcon` to avoid the two colliding): house emoji
for "home", the same fire emoji (🔥) already used in the Address Book's
hotspot text for "hotspot" - never the ambiguous house emoji for a
hotspot suggestion.

## 4. Non-goals / honest gaps (v1)

- No UI to VIEW the current rate/threshold at a glance outside this
  one suggestion moment - a real, disclosed limitation, not solved
  here.
- The 3-5 offer window is a fixed `RECENT_RATE_WINDOW = 5`, not
  driver-configurable in v1.
- No handling for "home address geocodes to the wrong place" beyond
  what `GoogleApiHelper`'s existing error surfacing already does for
  every other geocoded address in this app.

## 5. Success criteria

- [x] `offer_outcomes.hourly_rate` column + migration
- [x] `record_offer_outcome`/`record_offer_timeout` capture and persist
      it; all real call sites updated (`DeveloperTestingActivity`'s 3
      simulated-offer calls needed no change -- appended as a new
      trailing parameter with a `None` default, so existing shorter
      calls keep working unchanged)
- [x] `get_recent_shift_rate()` implemented and tested
- [x] Home address + threshold settings UI, geocoded, persisted
      (`PermissionsActivity`, `ShiftRoutingPrefs`)
- [x] `check_show_hotspot_or_home_suggestion()` implemented and tested
      (both destinations, both "not configured" and "no data yet"
      cases, the already-close suppression) - 8/8 real test assertions
      passed
- [x] Java wiring: new combined check when configured, OLD sweet-spot
      check unchanged when not
- [x] New icon distinguishing home (house) vs. hotspot (fire) visually
- [ ] Driver confirms in real use
- [ ] Driver sign-off

## 6. Verification

No Android SDK/emulator/device in this environment (disclosed
limitation throughout this repo) - real runnable Python tests for the
pure-Python logic, code review plus brace/paren balance for Java.

- `drive_monitor.py` recompiles cleanly.
- Real Python test (`test_hotspot_or_home.py`, 8 cases, all passed): no
  rate data yet; `not_enough_rate_data` reason surfaced correctly;
  average of 3 accepted offers computed exactly right with declined and
  test-data rows correctly excluded; below-threshold with no home set
  (`no_home_address_set`); below-threshold with home set and far away
  (suggests home, correct coordinates); below-threshold but already
  near home (`already_close` suppression); above-threshold with no
  pickup history (`no_hotspot_data`); above-threshold with real pickup
  history (suggests hotspot, correct coordinates).
- Java brace/paren balance: `DasherAccessibilityService.java` 151/151
  braces, 544/544 parens; `PermissionsActivity.java` 77/77 braces,
  419/419 parens; `OverlayHelper.java` 77/77 braces, 327/327 parens;
  `TripForegroundService.java` 194/194 braces, 881/881 parens;
  `ShiftRoutingPrefs.java` 13/13 braces, 47/47 parens (new file).
- `activity_permissions.xml`/`strings.xml` re-validated as well-formed
  XML.
- Confirmed `GoogleApiHelper.GeocodeCallback`'s real method names
  (`onResult`/`onError`, not the `onSuccess`/`onFailure` first assumed)
  by reading the interface directly before using it - caught and fixed
  before this would have been a compile error.
