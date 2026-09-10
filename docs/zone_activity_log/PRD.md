# PRD: zone activity snapshot log (monitor a zone without dashing)

Status: DESIGNED, being implemented this pass.

Driver ask (2026-09-10): "I want to monitor the activity of a
particular zone but not actually sign on to dash." Clarified directly
(`AskUserQuestion`) before designing anything - driver chose reading
the Dasher app's own screen via the accessibility service (same
technique this app already uses for offers/dropoffs), not sending
screenshots manually and not scraping DoorDash's backend directly (the
last option was flagged as likely a ToS violation and the driver did
not pick it). Also confirmed: build this INTO `dasher-monitor-` rather
than as a separate app, since it reuses the exact same accessibility
service already reading Dasher's screen - a second, separate service
doing the same thing would risk the two interfering with each other on
the same device.

## 0. What this is / isn't

- **Is**: passive capture of whatever Dasher's own screen shows when
  the app is open but nothing else (an offer, a dropoff, the paused
  screen) is already being handled - in practice, most likely the
  home/map screen a driver sees before tapping "Dash Now." Captured
  automatically, on a throttle, with no dashing required - the driver
  never has to go online for this to log something.
- **Isn't**: a real "zone X is Y busy" parser. No real screenshot of
  Dasher's home/map screen exists in this project (every other screen
  parser here - offer, dropoff, dash-paused - was built from a real
  screenshot first; see the README's own repeated "built from two real
  screenshots" callouts). Guessing at DoorDash's exact zone-label
  wording without one would risk the same class of bug this app has
  already hit and fixed twice before (the offer-notification parser
  shipping "intentionally lenient" until a real sample existed; the
  Accept/Decline button-matching bug that silently never fired because
  the assumed button text was never confirmed). This PRD captures RAW
  text only - structured parsing is an explicit, disclosed follow-up
  once a real snapshot is available to design it against.
- **Isn't**: a live map UI. `ParkingZoneMapActivity`/
  `CustomerZoneMapActivity` are real maps because they have real,
  GPS-anchored lat/lon per data point. A zone-activity snapshot has
  only the driver's OWN current lat/lon (if available) and whatever
  text was on screen - nothing zone-shaped to plot yet. A plain,
  newest-first text log (same UI pattern as "View Diagnostic Log")
  is what this ships as.

## 1. Design

### 1.1 Schema (`drive_monitor.py`, `Database._create_schema`)

```sql
CREATE TABLE IF NOT EXISTS zone_activity_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp REAL,
    lat REAL,
    lon REAL,
    raw_text TEXT
);
```

Same shape as `diagnostic_log` (timestamp + free text), plus the
driver's own lat/lon at capture time (nullable - not every capture
happens with a location fix available, same nullability every other
lat/lon column in this schema already has).

### 1.2 Capture (`DriveMonitorEngine.record_zone_activity_snapshot`)

Called from Java on every Dasher content-changed event where NEITHER
`is_offer_screen` NOR `is_dropoff_screen` matched (the paused-screen
branch already `return`s before reaching this point, so by
construction this can't double-fire on that screen either). Throttled
to `ZONE_SNAPSHOT_MIN_INTERVAL_SECONDS = 120` (2 minutes, UNCONFIRMED
reasonable default, same honesty status as every threshold in this
app) - the idle/home screen can re-fire content-changed events roughly
once a second while just sitting open (confirmed pattern from the
Dash-Paused screen's own countdown timer causing the identical
re-render frequency), and one row per event would flood the table with
near-duplicates for no benefit. Capped at the most recent
`ZONE_ACTIVITY_LOG_MAX_ROWS = 500` rows (delete-oldest, matching this
count exactly for consistency with `diagnostic_log`'s own
`DIAGNOSTIC_LOG_ROTATION_LIMIT`) - simple cap, not diagnostic_log's
rotate-to-archive-file behavior, since this is informational snapshot
data, not an audit trail that needs to survive indefinitely.

### 1.3 Viewer (`DiagnosticsActivity`)

New "View Zone Activity Log" button, same screen Diagnostic Log
already lives on (this is diagnostic-adjacent data, not a core
Dasher-workflow feature deserving its own top-level entry point).
`showZoneActivityLog()` mirrors `showDiagnosticLog()` exactly: an
`AlertDialog` listing entries newest-first (clock time + relative
"Xm ago", reusing the same formatting helper shape), a "Clear Log"
button, an empty-state message when there's nothing captured yet.

### 1.4 Java wiring (`DasherAccessibilityService.onAccessibilityEvent`)

Added right after the existing dropoff-handling block (§ real code:
after `if (isDropoff) { handleDropoffScreen(linesJson); }`), reusing
the ALREADY-computed `resultJson` (has `is_offer_screen`) and
`isDropoff` boolean - no new screen-text collection, no new
`getRootInActiveWindow()` call, this rides the same single read every
other check this event already uses.

## 2. Non-goals / honest gaps (v1)

- No structured zone-busyness data - explicitly deferred to a follow-up
  PRD once a real screenshot of Dasher's home/map screen exists to
  design a parser against (see §0).
- No map visualization - a text log only, same reasoning as §0.
- The 2-minute throttle and 500-row cap are both UNCONFIRMED guesses,
  not device-tested.
- Zone snapshots captured while Dasher is open are NOT scoped to only
  fire on a specific screen shape - "neither an offer nor a dropoff
  screen" is a broad bucket that could also capture the app's loading
  spinner, a settings screen, an in-app promo dialog, etc., not just
  the home/zone map. Disclosed, not solved - refining this needs the
  same real-screenshot-first approach as §0.

## 3. Success criteria

- [x] `zone_activity_log` table + schema
- [x] `record_zone_activity_snapshot` implemented: throttled, capped,
      persists raw text + lat/lon + timestamp
- [x] `get_zone_activity_log` / `clear_zone_activity_log` implemented
- [x] Java wiring in `DasherAccessibilityService` - fires only when
      neither offer nor dropoff screen matched
- [x] "View Zone Activity Log" button + dialog on `DiagnosticsActivity`
- [x] Real Python test: throttle suppresses a second call within the
      window, a call after the window succeeds, cap deletes the oldest
      row once exceeded, empty-log and populated-log JSON shapes both
      correct
- [x] `python3 -m py_compile drive_monitor.py` clean
- [x] Brace/paren balance + XML well-formedness on every touched
      Java/XML file
- [x] Pushed to a PR; CI green on the real commit - both `build` check
      runs on commit `4a475d5` completed with `conclusion: success`
      (https://github.com/ddann74/dasher-monitor-/actions/runs/34487743952/job/102906260643,
      https://github.com/ddann74/dasher-monitor-/actions/runs/34487713470/job/102906158481)
- [ ] Driver confirms in real use: opening Dasher without dashing
      produces log entries within a couple of minutes
- [ ] Driver sign-off
