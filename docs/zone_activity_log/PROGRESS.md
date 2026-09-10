# Progress log -- zone activity snapshot log (monitor a zone without dashing)

## Scoping (2026-09-10)

Driver: "I want to monitor the activity of a particular zone but not
actually sign on to dash." Asked directly (`AskUserQuestion`) how this
should get its data given no active dash - driver chose reading
Dasher's own screen via the accessibility service (same technique this
app already uses everywhere else), not manual screenshots and not
scraping DoorDash's backend (flagged as a likely ToS violation, not
chosen). Also confirmed this belongs IN `dasher-monitor-`, not a
separate app - a second accessibility service reading the same target
app on the same device would risk interfering with the existing one.

Checked `docs/customer_zone_map`, `docs/hotspot_or_home_routing`, and
`docs/location_profitability_map` first in case this already existed
under a different name - all three are about the DRIVER'S OWN
historical data (parking difficulty at past dropoffs, pickup
clustering from past trips, past profitability by restaurant), not
DoorDash's own live zone/heat-map indicator. Genuinely new feature.

## What was built

Deliberately scoped to raw capture only - see PRD ss0 for why
structured "zone X is Y busy" parsing is an explicit follow-up, not
this pass: no real screenshot of Dasher's home/map screen exists in
this project, and every other screen parser here was built from a
real one first.

`drive_monitor.py`:
- `zone_activity_log` table (`timestamp`, `lat`, `lon`, `raw_text`).
- `DriveMonitorEngine._last_zone_snapshot_ts` (new `__init__` field,
  in-memory throttle state).
- `record_zone_activity_snapshot(lines_json, lat=None, lon=None)` -
  throttled to `ZONE_SNAPSHOT_MIN_INTERVAL_SECONDS = 120`, capped at
  `ZONE_ACTIVITY_LOG_MAX_ROWS = 500` (delete-oldest), both UNCONFIRMED
  reasonable defaults. Returns `{"recorded": true}` /
  `{"recorded": false, "reason": "throttled"}` so the Java caller can
  log a diagnostic line without a second round-trip.
- `get_zone_activity_log(limit=100)` / `clear_zone_activity_log()` -
  same shape as `get_diagnostic_log`/`clear_diagnostic_log`.

`DasherAccessibilityService.java`: added right after the existing
dropoff-handling block, reusing the already-computed `resultJson`
(`is_offer_screen`) and `isDropoff` - fires
`record_zone_activity_snapshot` only when NEITHER matched, no new
screen-text read or `getRootInActiveWindow()` call.

`DiagnosticsActivity.java`: new "View Zone Activity Log" button/dialog,
structural copy of the existing "View Diagnostic Log" pattern
(`buildZoneActivityLogText`/`showZoneActivityLog`) - newest-first,
clock time + relative "ago" + lat/lon (or "location unknown"), Clear
Log button, empty-state message telling the driver what to do
("open Dasher without dashing, wait a couple of minutes").

`activity_diagnostics.xml` / `strings.xml`: new button + string
resource, same pattern as every existing button on that screen.

## Verification

- `python3 -m py_compile drive_monitor.py` - compiles cleanly.
- New scratchpad test (`test_zone_activity_log.py`, 15 assertions, all
  passed, not committed - matching this repo's own scratchpad-test
  convention) against a real `DriveMonitorEngine(tmpdir)`: empty log
  returns `{"entries": []}`; a first snapshot records and round-trips
  its raw text/lat/lon correctly; an immediate second call is
  throttled and does NOT add a row; forcing the throttle window to
  have elapsed lets the next call record, with correct newest-first
  ordering and `null` lat/lon preserved when not provided; inserting
  `ZONE_ACTIVITY_LOG_MAX_ROWS + 10` rows (bypassing the throttle
  between each) leaves the table at exactly the cap, with the newest
  row correct and the oldest SURVIVING row being exactly the 11th
  inserted (confirms delete-oldest, not delete-newest or a
  miscounted boundary).
- Brace/paren balance: `DasherAccessibilityService.java` 176/176
  braces, 629/629 parens; `DiagnosticsActivity.java` 63/63 braces,
  366/366 parens (both balanced after their respective additions).
- XML well-formedness confirmed on `activity_diagnostics.xml` and
  `strings.xml`.
- Full `R.id.*`/`android:id` and `@string/*` cross-check between
  `DiagnosticsActivity.java`, `activity_diagnostics.xml`, and
  `strings.xml` - every new reference resolves.

**Not done, and can't be from here**: on-device confirmation (no
Android SDK/emulator/device in this environment, same disclosed
limitation as every other Java/XML change in this repo). Structured
zone-busyness parsing - explicitly deferred, see PRD ss0/ss2, needs a
real screenshot of Dasher's home/map screen to design against.

Remaining PRD ss3 boxes: driver confirms in real use, driver sign-off.
