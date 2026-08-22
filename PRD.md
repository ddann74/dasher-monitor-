# PRD — Dasher Monitor

**Status of this document:** Derived from this repo's `README.md` plus a direct read of the
source (`app/src/main/java/com/drivingefficiency/app/*.java`, `app/src/main/python/drive_monitor.py`).
The README is itself a running changelog of features added, bugs fixed, and honesty caveats
across many prior sessions — treat every claim in it as *plausible, written in good faith, but
unverified* until something concrete (a successful build, a passing test, a screenshot, a log
line) backs it up. That gap is what the attached Ralph loop is for.

**Known contradiction found while writing this PRD**: the README's own "Notes / TODOs to make
this production-ready" section (lines 605–634) is stale. It lists three things as *not done* —
post-accept address reading, address→lat/lon geocoding, and battery-optimization-exemption
requesting — that the code and earlier README sections show *are* implemented:
`DasherAccessibilityService` does parse the post-accept "Deliver to X" screen (search
`lastDropoffAddressKey` / "Real post-accept dropoff address extraction"), `GoogleApiHelper`
does geocode via the Google Maps Geocoding API (`geocodeAddress`, `geocodeAddressWithFormatted`),
and `PermissionsActivity` does have a working `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
button. This doesn't mean these features are *proven* — none have been confirmed on a real
device — but the TODO list is simply out of date, not evidence of missing code. See §7 (Open
Risks) — reconciling this TODO section is itself a task.

## 1. Problem Statement

DoorDash Dashers decide, several times an hour, whether to accept or decline a delivery offer
using only what the Dasher app's offer screen shows — a dollar amount, a distance, and a time
window — with no visibility into deadhead distance to the restaurant, likely restaurant wait
time, traffic conditions, or how the trip is actually going to pay per hour or per km relative
to their own history. Drivers also want a general driving-efficiency and safety record (harsh
braking, speeding, time efficiency) on the miles they drive when not actively dashing. Existing
tools either require the Dasher app itself (which won't show this analysis) or a cloud service
the driver has to trust with a continuous feed of their location and messages.

## 2. Goal

Ship an Android app that runs alongside the Dasher app and:
1. Watches for a new offer (via the Dasher app's on-screen offer text, read through
   Accessibility Service, and/or a Dasher push notification) and shows a floating "Smart Score"
   badge over it — a 0–100 composite score plus the real numbers behind it ($/km, $/hr,
   deadhead, restaurant wait, traffic risk) — before the driver has to decide.
2. Tracks the whole trip via GPS (pickup arrival/departure, dropoff arrival, phase-by-phase
   timing) to learn the driver's *own* historical deadhead distance, restaurant wait time, and
   delivery speed per restaurant, replacing generic assumptions with personalized ones over time.
3. Reads customer delivery instructions aloud (SMS/Messenger) the instant they arrive and again
   on arrival at the stop, with a floating overlay as a backup.
4. Also functions as a plain driving-efficiency/safety monitor (GENERAL mode) whenever the
   Dasher app isn't in use, so the same install is useful on every mile driven, not just
   deliveries.
5. Does all of this **offline-first**: SQLite for all persistence, no cloud backend, no account,
   optional (user-supplied-key) network calls only for geocoding/traffic/weather — everything
   else works with no internet at all.

Primary user: a single DoorDash Dasher running the app on their own phone, side-by-side with the
real Dasher app, for their own personal use.

## 3. Users & Use Cases

- **Dasher deciding whether to accept an offer**: sees the Smart Score badge appear over the
  offer screen with $/km, $/hr, deadhead estimate, restaurant wait estimate, and traffic risk,
  before tapping Accept or Decline.
- **Dasher mid-delivery**: gets customer delivery notes read aloud immediately and again on
  arrival at the stop (TTS + floating overlay), without having to re-open the message thread.
- **Dasher reviewing a shift**: opens "View Last Trip Summary" / trip history to see
  efficiency/safety/composite scores and a phase-by-phase "Where The Time Went" breakdown per
  trip.
- **Driver not currently dashing**: the same running app scores ordinary driving (safety,
  time-efficiency) with no delivery-specific UI active.
- **Anyone texting the driver personally** (not a customer): reaches the driver via TTS
  read-aloud only if explicitly added to the trusted-contacts allowlist — everyone else's
  personal messages are silently ignored, never logged or spoken.

## 4. Feature Inventory — Functional Requirements & Acceptance Criteria

Each item replaces a README claim with something a Ralph loop iteration can actually check —
a specific class/file, a test that should exist, or (where a real Dasher offer/device is
required) an explicit manual-verification step.

### 4.1 Trip lifecycle & GPS tracking
- **FR-1**: `TripManager` (`drive_monitor.py`) tracks pickup arrival/departure and dropoff
  arrival via GPS proximity, snapshotting cumulative distance at each transition.
  *Acceptance*: unit test feeding synthetic GPS points through `TripManager` asserts deadhead
  distance = distance-to-pickup-arrival and delivery distance = distance-from-pickup-departure
  to dropoff-arrival, both within a defined tolerance.
- **FR-2**: `TripForegroundService` polls GPS via `FusedLocationProviderClient` and writes a
  heartbeat timestamp (`MonitoringWatchdogReceiver.markIntendedActive`) on every location
  update while monitoring is active. *Acceptance*: manual — start monitoring on a device/emulator
  with simulated GPS, confirm the on-screen status dot goes solid green and the diagnostic log
  shows periodic location events.
- **FR-3**: A GENERAL-mode trip (no delivery stops ever registered) still auto-ends when parked
  5+ minutes; a DASHER-mode trip waits for its pending stop(s) to be reached first.
  *Acceptance*: unit test on `TripManager._evaluate_trip_end` covering both branches, including
  the documented fix where an active not-yet-departed pickup itself counts as a DASHER-mode
  signal (so a long restaurant wait doesn't falsely end the trip via the GENERAL-mode parking
  rule).
- **FR-4**: Phase-by-phase timing ("Where The Time Went") captures deadhead → pickup wait →
  delivery leg → parking-to-walking → completing dropoff. *Acceptance*: a completed simulated
  trip (via the Developer Testing "Simulate Drive + Arrival" button) shows all five phases with
  non-zero/plausible durations in the trip summary.

### 4.2 Smart Score engine
- **FR-5**: `SmartScoreEngine` computes a 6-factor weighted composite score (the original 5 plus
  weather at 10%, others rescaled 0.9x). *Acceptance*: unit test asserts the six factor weights
  sum to 1.00 and that a known synthetic input produces the expected composite score.
- **FR-6**: `$/hr` is estimated from `delivery_speed_kmh` (learned per-driver average once ≥1
  delivery completed, else the 25 km/h fallback), **not** from the offer's deadline-until-time.
  *Acceptance*: unit test confirms `delivery_speed_is_learned` is `false` before any completed
  delivery and `true` (using the running average) after one.
- **FR-7**: Deadhead distance uses `_estimate_deadhead_km()` — restaurant-specific average, else
  cross-restaurant average, else 0 km with `deadhead_samples: 0`. *Acceptance*: unit test covers
  all three tiers by seeding `restaurant_wait_history`/deadhead history with 0, 1 (different
  restaurant), and 1+ (same restaurant) prior records.
- **FR-8**: Restaurant wait time is genuinely learned (`record_restaurant_wait`, 3-tier fallback:
  restaurant-specific → cross-restaurant average → 6.0 min default), badge exposes
  `restaurant_wait_is_restaurant_specific`. *Acceptance*: same 3-tier unit test pattern as FR-7.
- **FR-9**: Traffic risk has FOUR tiers checked in order — live (Distance Matrix result <5 min
  old) → zone (same rounded lat/lon zone + hour, 3+ completed trips there) → personal (5+
  completed trips, hour-of-day-only average speed) → generic (lunch/dinner clock-time
  heuristic) — exposed via `traffic_risk_source`. *Acceptance*: unit test forcing each
  precondition and asserting the reported source matches. (Corrected 2026-08-21: this
  requirement originally said three tiers, omitting the zone tier — found and fixed via
  `ralph/PROGRESS.log` iteration 8's real-code trace of `_get_traffic_risk`.)
- **FR-10**: Weather factor uses Open-Meteo current precipitation/wind at the driver's location,
  10-minute cooldown, neutral 100 if no reading <15 min old. *Acceptance*: unit test with a mocked
  Open-Meteo response asserts the score moves in the expected direction for heavy rain vs. clear.

### 4.3 Offer detection & parsing
- **FR-11**: `OfferScreenParser` reads the Dasher app's on-screen offer text via
  `DasherAccessibilityService` when Dasher is foregrounded. *Acceptance*: this was built from two
  real screenshots per the README — confirm the parser's regex/field-extraction still matches a
  real or saved-sample offer screen text dump; log a regression test with that sample text if
  none exists yet.
- **FR-12**: `parse_offer_notification()` detects offers from the Dasher push notification alone
  (dollar amount + distance regex), independent of what's on screen. *Acceptance*: **unconfirmed
  by design** — README states this parser was built with no real Dasher offer-notification
  sample. Task: capture one real offer notification's title/text and add it as a fixture; confirm
  the parser extracts the correct amount/distance from it.

### 4.4 Message intelligence & announcements
- **FR-13**: `MessageIntelligence` recognizes delivery notes/address corrections/ETA updates in
  customer SMS/Messenger text. *Acceptance*: unit test with representative sample strings for
  each recognized category.
- **FR-14**: Immediate TTS on notification arrival (`AppNotificationListenerService` →
  `VoiceAnnouncer`) and a second TTS + floating overlay (auto-dismiss 8s) on GPS arrival at the
  matching stop (`TripForegroundService` → `OverlayHelper`). *Acceptance*: manual — via
  "Simulate Customer SMS" + "Simulate Drive + Arrival" in Developer Testing, confirm both the
  immediate TTS and the arrival TTS+overlay fire, and the message appears in the post-trip
  summary.
- **FR-15**: Non-Dasher/SMS/Messenger notifications are also read aloud (title+text), except this
  app's own notifications and "ongoing" (foreground-service-style) ones. *Acceptance*: unit test
  or manual check that an `isOngoing()` notification and a self-package notification are both
  skipped.
- **FR-16**: Trusted-contacts allowlist for personal SMS/Messenger, substring/case-insensitive
  match, add/remove via `TrustedContactsActivity`, backed by `TrustedContacts.is_trusted()`.
  *Acceptance*: unit test covering exact match, substring match, case-insensitivity, and a
  non-matching sender being silently dropped.

### 4.5 Dual mode detection (Dasher vs. General)
- **FR-17**: `DasherAccessibilityService` reports foreground-app changes to
  `set_dasher_foreground()`; `TripManager.get_mode()` returns DASHER while the Dasher app is
  foregrounded **or** an unmatched stop is pending **or** an active not-yet-departed pickup
  exists; GENERAL otherwise. *Acceptance*: unit test covering all three DASHER-triggering
  conditions independently.
- **FR-18**: On-screen content is only read (`getRootInActiveWindow()`/text-node walk) when the
  foregrounded app is actually Dasher; for every other app only the bare package name is
  inspected — a documented privacy boundary. *Acceptance*: code review confirms no content-read
  call is reachable outside the `isDasher` branch (already true as of the mode-flapping fix that
  added an early `isSelf` return before the debounce block).
- **FR-19**: Mode changes update the persistent notification title/icon, play a one-time spoken
  cue, and update `MainActivity`'s status text — on actual transitions only, not every GPS tick or
  accessibility event. *Acceptance*: regression check for the mode-flapping bug fixed on
  `fix-watchdog-recovery-and-notification-noise` (self-app accessibility events were triggering
  spurious transitions) — confirm the fix (`isSelf` early return) prevents repeat firing on a
  synthetic burst of self-package `TYPE_WINDOW_STATE_CHANGED` events.

### 4.6 Monitoring reliability (watchdog)
- **FR-20**: `MonitoringWatchdogReceiver` fires via `setExactAndAllowWhileIdle`, checks heartbeat
  staleness against a mode-aware threshold (60s Dasher / 3min General), and on every stale firing
  (not just the first) either kicks GPS updates (`ACTION_KICK_LOCATION_UPDATES`, service alive but
  stuck) or restarts the service fully (`ACTION_START_TRACKING`, service dead) — the fix for the
  Aug 7 ~3.6-hour outage where the old `if (!isRunning)` guard stopped acting after one firing.
  *Acceptance*: unit/manual test simulating a stale heartbeat with `isRunning=true` confirms
  `ACTION_KICK_LOCATION_UPDATES` fires on every subsequent watchdog tick, not just once; a build
  of the branch that introduced this is the first checkable artifact (see TASKS.md).
- **FR-21**: A high-priority alert notification (sound+vibration) fires alongside the recovery
  attempt when staleness crosses the threshold. *Acceptance*: manual — force a stale heartbeat and
  confirm the notification appears with correct minutes-stale text.

### 4.7 OEM background/autostart handling
- **FR-22**: `OemBackgroundHelper` deep-links into manufacturer-specific autostart/background
  settings screens (Samsung, Xiaomi/MIUI, Huawei, OnePlus, Vivo, etc.) with a graceful fallback to
  the app's own details page, surfaced via a "Fix Background/Autostart Settings" button on
  `PermissionsActivity` (shown only on known-affected manufacturers, and auto-popped when
  accessibility is found off on one of them). *Acceptance*: **unconfirmed** — README states these
  are undocumented OEM-internal screens with no universal API; task is to verify at least one
  deep-link (e.g. Samsung) actually opens the intended screen on a real device of that brand,
  rather than falling through to the generic fallback every time.
- **FR-23**: Standard `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` exemption button exists in
  `PermissionsActivity`. *Acceptance*: manual — tap the button on a device, confirm the system
  battery-exemption dialog appears for this app's package name. (Note: this directly contradicts
  the README's own TODO line claiming this isn't implemented — see the contradiction note above
  and §7.)

### 4.8 Diagnostics & data management
- **FR-24**: `log_diagnostic()` records lifecycle transitions, caught exceptions, offer
  detections, and API outcomes to a capped (500-entry) persistent log, viewable via
  `DiagnosticsActivity`. *Acceptance*: already confirmed in production use — a real 106K-line
  diagnostic log was reviewed this session and used to find/fix three real bugs. Task is narrower:
  confirm the three just-fixed log lines (`WATCHDOG` kick messages, `PERSONAL_MSG` Messenger-title
  skip, mode-flap suppression) actually appear correctly formatted once exercised.
- **FR-25**: "Reset All Data" wipes every table with a confirmation dialog. *Acceptance*: manual —
  confirm all tables listed in `Database` schema are empty after tapping through the dialog.

## 5. Real vs. Stub vs. Unconfirmed

| Feature | Current status | What would confirm it |
|---|---|---|
| Post-accept customer address reading | **Implemented in code** (`DasherAccessibilityService`, `lastDropoffAddressKey` flow) despite README's TODO list saying it's still a stub — TODO section is stale, not the code | Real device test: accept a real offer, confirm "Deliver to X" text is captured and geocoded |
| Address → lat/lon geocoding | **Implemented** (`GoogleApiHelper.geocodeAddress` / `geocodeAddressWithFormatted`, Google Maps Geocoding API) — again contradicts the README TODO line | Real delivery with a Maps API key configured; confirm placeholder `(0.0, 0.0)` gets replaced with real coordinates and arrival detection fires |
| Battery-optimization exemption button | **Implemented** (`PermissionsActivity`, `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) — third TODO-list item that's actually done | Tap it on a real device, confirm the system dialog appears |
| RoadWarrior `geo:` deep link | Implemented but **unconfirmed** — no documented RoadWarrior deep-link API exists | Real device with RoadWarrior installed: tap the pickup/dropoff nav icon, confirm RoadWarrior (not a fallback maps app) opens |
| Offer-notification parsing (`parse_offer_notification`) | Implemented but **unconfirmed** — built without a real Dasher offer-notification text sample | Capture one real offer notification's title/text; confirm regex extracts correct $ and distance |
| Peak-hour traffic windows / harsh-brake thresholds | **Generic hardcoded**, not personalized (explicitly flagged in README) | Would need the same "learn from N trips" pattern already used for deadhead/wait-time/delivery-speed — not built yet |
| Speed-limit-based speeding detection | **Not implemented** — no map speed-limit data source | Would need a new data source (e.g. Google Roads API); currently out of reach |
| Fuel cost estimates | **Not implemented** — no vehicle fuel-efficiency input | Needs a user-supplied efficiency figure or a new data source |
| OEM autostart deep-links (Samsung/Xiaomi/Huawei/etc.) | Implemented, **unconfirmed** per-manufacturer | Real device of each targeted brand; confirm the correct settings screen opens, not the generic fallback |
| Watchdog recovery fix (kick-vs-restart) | Implemented, committed (`fix-watchdog-recovery-and-notification-noise`), **unconfirmed** — never built or run | A build of that branch, plus a manual staleness simulation |
| Messenger "Chat heads active" false-positive fix | Implemented, committed, **unconfirmed** | Same — needs a build and a Messenger notification test |
| Mode-flapping self-notification fix | Implemented, committed, **unconfirmed** | Same — needs a build and an accessibility-event burst test |

## 6. Out of Scope (this v1.0 skeleton)

Per the README, the original product report's v2.x–v4.0 roadmap items are **not implemented**
here. This PRD does not add them as requirements:
- Anything beyond the v1.0 "current features" skeleton the README describes.
- A cloud backend, user accounts, or multi-device sync (app is offline-first, single-device,
  SQLite-only by design).
- Live/predictive traffic beyond the existing Distance Matrix + personal-average + generic
  3-tier fallback.
- Any UI/feature not already listed in §4.

## 7. Open Risks

1. **README TODO section is stale.** Three items it lists as not-yet-built (post-accept address
   reading, geocoding, battery-optimization exemption) are actually implemented in code — see the
   contradiction note at the top and §5. Risk: anyone trusting the TODO list at face value would
   duplicate work or misjudge what's left. *Task*: reconcile/rewrite that section once the Ralph
   loop confirms current status of each item for real.
2. **Nothing in this codebase has ever been built.** There is no recorded `./gradlew
   assembleDebug` (or equivalent) success anywhere in this session's history or evident in the
   repo. Every fix, including the three just committed, is unverified beyond manual code reading
   and brace-balance checks.
3. **Three just-fixed bugs are unverified.** Watchdog recovery, Messenger false-positive
   suppression, and mode-flap suppression are all committed to
   `fix-watchdog-recovery-and-notification-noise` but none has been exercised on a build or
   device.
4. **"Accessibility revoked" is a still-open OS/OEM limitation**, not a bug this app can fully
   fix — `OemBackgroundHelper`'s deep-links are best-effort only, and the diagnostic log reviewed
   this session still showed recurring revocations even with the mitigation in place.
5. **Offer-notification parsing has no real calibration sample** — unlike the screen parser
   (built from two real screenshots), this is regex built on assumption alone.
6. **RoadWarrior integration is speculative** — no documented API, unconfirmed on any real device.
7. **Simulated and real trip data share one SQLite database** — the README already flags this as
   an intentional but real risk (a simulated trip could be mistaken for a real one when reviewing
   history) with no in-app distinguishing flag between them.
