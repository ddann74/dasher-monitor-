# Dasher Monitor - Android Studio Project

Generated from the Driving Efficiency App product report (v1.0 current features).

## Opening the project

1. Open **Android Studio**.
2. **File > Open** and select this folder
   (`C:\AndroidProjects\dasher_monitor` unless you generated it elsewhere).
3. Let Gradle sync. The **Chaquopy** plugin will download a Python
   distribution for Android on first sync (requires internet once).
4. Update the placeholder Dasher package name
   (`com.doordash.driverapp`) in:
   - `AppNotificationListenerService.java`
   - `DasherAccessibilityService.java`
   - `AndroidManifest.xml` (`accessibility_service_config.xml`)
   to match the real installed package name.

## Where each report feature lives

| Report feature                     | File                                              |
|-------------------------------------|----------------------------------------------------|
| Trip Life Cycle state machine       | `app/src/main/python/drive_monitor.py` (`TripManager`) |
| GPS logging / foreground service    | `TripForegroundService.java`                       |
| Smart Score engine (5-factor)       | `drive_monitor.py` (`SmartScoreEngine`)             |
| Offer screen parsing (on-screen, not notification) | `drive_monitor.py` (`OfferScreenParser`) + `DasherAccessibilityService.java` |
| Post-acceptance address read        | `DasherAccessibilityService.java` (implemented, geocoded via `GoogleApiHelper` -- unconfirmed on a real device, see TODOs) |
| One-Tap Instant Pinpoint / buffer   | `drive_monitor.py` (`StopsBuffer`)                  |
| Message Intelligence (parsing)      | `drive_monitor.py` (`MessageIntelligence`)          |
| Message read aloud on arrival (TTS) | `VoiceAnnouncer.java`, triggered from `AppNotificationListenerService.java` (immediate) and `TripForegroundService.java` (on arrival) |
| Floating arrival overlay            | `OverlayHelper.java`, triggered from `TripForegroundService.java` |
| Post-trip summary                   | `drive_monitor.py` (`DriveMonitorEngine.get_last_trip_summary`), shown via `MainActivity`'s "View Last Trip Summary" button |
| SQLite persistence (offline)        | `drive_monitor.py` (`Database`)                     |
| UI entry point                      | `MainActivity.java` / `activity_main.xml`           |
| Dual mode (Dasher vs General) detection | `drive_monitor.py` (`TripManager.get_mode`), `DasherAccessibilityService.java`, shown via `TripForegroundService.java`'s notification + `MainActivity`'s status text |
| Smart Score badge (actually displayed) | `DasherAccessibilityService.java` (`handleOfferResult`), rendered via `OverlayHelper.java` |
| Restaurant/pickup wait learning | `drive_monitor.py` (`TripManager` pickup tracking, `SmartScoreEngine.record_restaurant_wait`) |

## How the three notification-driven announcements work together

1. **Immediate TTS** -- the instant a customer message notification arrives
   and `MessageIntelligence` recognizes it (delivery note, address
   correction, ETA update), `AppNotificationListenerService` reads it aloud
   right away via `VoiceAnnouncer`.
2. **Arrival announcement (TTS + overlay)** -- separately, when GPS detects
   you've arrived at the stop that instruction belongs to,
   `TripForegroundService` reads it aloud again *and* shows a floating
   overlay bubble (auto-dismisses after 8 seconds) with the address and
   instructions -- so it's still visible even if you missed the first
   announcement.
3. **Post-trip summary** -- after the trip ends, tapping "View Last Trip
   Summary" in the app shows every instruction captured that trip alongside
   the efficiency/safety/composite scores, pulled straight from SQLite.

All three require **notification access** and **accessibility access**
(for message/offer capture) plus the **overlay permission** (for step 2's
floating bubble) -- all three have buttons on the main screen to enable them.

## Troubleshooting: `cannot find symbol ... method isNone()`

An earlier version of this generator used `PyObject.isNone()`, which
doesn't exist in Chaquopy's API -- a mistake, not a real method. Chaquopy
represents Python's `None` as Java `null` directly: when a
`drive_monitor.py` function returns `None`, `engine.callAttr(...)` returns
`null`, not a non-null `PyObject` wrapping `None`. The fix (already applied
in this version) is to check `result != null` instead of calling any method
on the result. If you see this error, you're looking at a stale copy of
the project -- regenerate it.

## Testing on an Android emulator

What works out of the box:
- GPS-based trip tracking, safety scoring, and GENERAL mode -- feed fake
  GPS via the emulator's Extended Controls (single location or route
  playback with speed). Requires an AVD image with Google Play services
  (pick a "Google Play" system image, not just "Google APIs" -- the Fused
  Location Provider needs it).
- All pure-UI features: trusted contacts, permissions screens, trip summary.

What does NOT work without the real Dasher app installed and actively
receiving offers:
- Offer parsing / Smart Score badge -- reads the real Dasher app's on-screen
  text via Accessibility Service. No Dasher app on screen, nothing to read.
- SMS/Messenger message reading -- needs real notifications from those apps.

**Developer Testing section** (bottom of the main screen) closes this gap:
"Simulate Offer Screen", "Simulate Customer SMS", "Simulate Trusted/Unknown
Text", and "Simulate Drive + Arrival" feed the exact same canned data used
in `demo_full_delivery.py` through the real code paths (`parse_offer_screen`,
`on_notification`, `is_trusted_sender`, the arrival pipeline), so you can see
the badge appear and hear the TTS on an emulator with no Dasher account and
no real GPS movement.

**Important caveat**: these buttons share the same Python engine singleton
(`PythonBridge`) as the real `TripForegroundService` -- running both at once
would interleave real and fake GPS timestamps into the same trip state and
corrupt it. They check `TripForegroundService.isRunning` and refuse to run
while live monitoring is active, but the reverse isn't guarded: if you run a
simulation and then start real monitoring afterward, the shared engine
still has whatever state the simulation left behind (e.g. an already-active
trip). For a clean slate, either restart the app process between simulating
and real testing, or note that simulated trips get persisted into the same
on-device SQLite database as real ones (mixed test/real data is fine for
development, just don't mistake a simulated trip for a real one when
reviewing "View Last Trip Summary").

## Two more assumptions turned into learned data

Following the same pattern as deadhead and restaurant wait time, two more
previously-hardcoded assumptions now learn from real driving:

- **Delivery speed** (`delivery_speed_kmh` in the score output): the
  `$/hr` estimate used to assume a flat 25 km/h for every driver
  everywhere (`ASSUMED_DELIVERY_SPEED_KMH`). Now `SmartScoreEngine`
  measures your actual delivery-leg speed (distance / time from pickup
  departure to trip end -- the same window already used for
  `offer_distance_accuracy`) after every completed delivery, and uses a
  running average of that for future `$/hr` estimates instead. This is
  learned globally, not per-restaurant, since driving speed reflects
  general traffic/road conditions for you, not something specific to any
  one restaurant. Falls back to the original 25 km/h assumption
  (`delivery_speed_is_learned: false`) until at least one delivery
  completes.
- **Restaurant wait cold-start default**: previously jumped straight from
  "this exact restaurant's average" to a flat hardcoded 6.0 minutes for
  any brand-new restaurant, with no middle ground. Now uses a three-tier
  fallback matching deadhead's pattern: restaurant-specific average if
  available, else the average across *all* restaurants you've been to (a
  much better cold-start guess for a new restaurant than an arbitrary
  constant), else the 6.0-minute default only if there's no history
  anywhere yet. The badge distinguishes these with
  `restaurant_wait_is_restaurant_specific` -- e.g. "6 min (3 past pickups
  here)" vs. "~6 min (avg of 12 elsewhere)".

## Critical fix: a crash bug that looked like "monitoring stops randomly"

Found and fixed a serious bug: `GoogleApiHelper`'s async geocoding/traffic
callbacks called `engine.callAttr(...)` with **zero exception handling**.
Since these callbacks run via `Handler.post()` on the main thread, any
error there (a `PyException`, anything) crashed the **entire app
process** -- not just that one call -- silently killing
`TripForegroundService` along with everything else. This exactly matched
the symptom "monitoring stops as soon as I get an offer," since
offer-handling is precisely when these callbacks fire. All such callbacks
(geocoding, traffic, weather) now have proper exception guards.

## New: offer detection from notifications, not just the Dasher screen

Previously, a new offer was only ever detected while the Dasher app's own
screen was open and being read by `DasherAccessibilityService` -- if an
offer arrived as a notification while you were doing something else (e.g.
navigating via Google Maps), nothing happened until you opened Dasher to
look. `parse_offer_notification()` now detects and scores offers directly
from the notification itself, the moment it arrives, regardless of what's
on screen. **Honesty note**: this parser was built without a real Dasher
offer-notification sample to calibrate against (unlike the screen parser,
built from two real screenshots) -- it's intentionally lenient regex
extraction (a dollar amount + a distance, anywhere in the text). If it
doesn't reliably detect real offers, capture the actual notification text
and this can be corrected the same way the screen parser was.

## New: all other notifications are read aloud too

`AppNotificationListenerService` now reads aloud any notification from
any app (title + text), not just Dasher/SMS/Messenger -- skipping this
app's own notifications and "ongoing" ones (persistent status displays
like a foreground-service notification, which aren't new information).

## New: persistent, in-app diagnostic log

Previously, caught exceptions were silently swallowed with nothing but a
code comment -- there was no way to see what actually happened when
something went wrong, especially in the field with no computer connected
to check Android's own (ephemeral) system logs. `log_diagnostic()` now
records service lifecycle transitions (`onCreate`/`onDestroy`/start/stop),
every caught exception, offer detections (both screen- and
notification-based), and API call outcomes (geocoding, traffic, weather)
to a persistent, capped (500-entry) log, viewable via "View Diagnostic
Log" on the main screen. This is NOT a log of every function call (that
would hurt battery/performance for little benefit) -- just the events
that actually matter for debugging.

## New: weather as a Smart Score factor

Added as a genuine 6th scoring factor (10% weight), with the other five
proportionally rescaled (0.9x each) to keep the total at 1.00. Uses
Open-Meteo (free, no API key) for real current precipitation and wind
speed at your current location. Simple heuristic: heavy rain / high wind
reduce the score; neutral 100 (assume fine conditions) if no fresh (<15
min) reading exists. Checked with a 10-minute cooldown, not on every
single offer. Note: Open-Meteo can specifically wrap BOM's own ACCESS-G
model, but BOM's open-data delivery is temporarily suspended during a
platform upgrade on their end as of this writing -- so this uses
Open-Meteo's default best-available model instead of depending on
something explicitly flagged unavailable.

## Confirmed: OEM battery/background app killing, with an in-app fix

A real 3-day diagnostic log confirmed this happening: 9 separate
"Accessibility revoked while monitoring active" events in one ~4-hour
window, every one of them with the screen on, Doze off, and standard
Android battery-optimization exemption already granted -- ruling out
simple screen-off/Doze killing and a missing standard exemption as the
cause. That combination points at a manufacturer-specific background/
autostart management sweep (Samsung, Xiaomi/MIUI, Huawei, OnePlus, Vivo,
and others all have their own aggressive app-killing on top of Android's
own battery optimization, gated behind a SEPARATE setting -- commonly
called "Auto-start," "Protected apps," "App power management," or
similar -- that standard `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
never reaches).

The Permissions screen now has a manufacturer-aware **"Fix Background/
Autostart Settings"** button (only shown on manufacturers with a known
history of this) that best-effort deep-links straight into that OEM's own
autostart settings screen, with manufacturer-specific guidance text
explaining what to look for. It also pops up automatically if
accessibility is found to be off on a device from one of these
manufacturers. HONEST LIMIT: these are undocumented, OEM-internal
settings screens that have changed across OS versions/regions before, so
every attempt falls back gracefully to the app's own details settings
page if the specific screen can't be found -- there's no universal API
for this, only best-effort per-manufacturer deep links
(`OemBackgroundHelper.java`).

## Real Google Maps integration: geocoding + live traffic

Requires your own Google Maps Platform API key with the **Geocoding API**
and **Distance Matrix API** enabled, with billing configured on your
Google Cloud project (both are paid beyond a monthly free credit).

**Setup**: add one line to your existing `local.properties` file (the one
Android Studio already created, containing `sdk.dir=...` -- don't replace
the whole file, just add this line):
```
GOOGLE_MAPS_API_KEY=your_real_key_here
```
This file is gitignored and never committed -- the key is read into a
`BuildConfig` field at build time. **Never hardcode a real API key
directly into any committed source file.** If no key is configured, every
API-dependent feature below silently no-ops and the app falls back to its
previous offline-only behavior.

**What this actually fixes, not just adds**: every pickup in the real app
previously used hardcoded `(0.0, 0.0)` placeholder coordinates, since
nothing converted a restaurant name into real coordinates anywhere. This
meant arrival detection -- and everything built on top of it (deadhead
distance, restaurant wait time, delivery speed learning) -- could never
actually fire in real-world use; they only worked in simulated testing
with realistic fake coordinates. Real geocoding is the fix, and it's what
makes the traffic feature possible at all (a traffic API needs real
coordinates on both ends of a route).

**How it works** (`GoogleApiHelper.java`):
1. When a new offer appears, `add_pickup()` is called immediately with
   placeholder coordinates (unchanged from before) so nothing breaks if
   geocoding is slow, fails, or no key is configured.
2. In parallel, the restaurant name is geocoded via the Geocoding API. On
   success, `update_pickup_coordinates()` replaces the placeholder with
   real coordinates -- but only if arrival hasn't already been detected
   against the placeholder in the meantime (a safety guard, tested
   directly).
3. If a real current GPS position is available (from
   `TripForegroundService.lastKnownLat/lastKnownLon`), a Distance Matrix
   query checks live traffic (`departure_time=now`) for the route from
   your current location to the pickup.
4. The resulting traffic delay ratio is fed into the Smart Score's
   traffic-risk factor, which has FOUR tiers, checked in order: **live**
   (real traffic, if a result was recorded within the last 5 minutes) →
   **zone** (this specific rounded lat/lon area at this hour, if you have
   3+ completed trips that started in the same zone at the same hour --
   more specific than the hour-only tier below) → **personal** (your own
   historical average speed by hour of day only, if you have 5+ completed
   trips) → **generic** (the original lunch/dinner clock-time guess).
   `traffic_risk_source` in the score output tells you which tier was
   actually used.

**Important architecture note**: all network calls run on a background
thread with results delivered via callback -- an accessibility service or
activity must never block directly on a live network call (this would
risk ANRs / dropped accessibility events). This means the Smart Score
badge may show a slightly different traffic reading a moment after it
first appears, once geocoding and the traffic query actually resolve --
this is expected, not a bug.

## New: pickup address, approach icon, notes, and the final timing stage

Dropoff has always had a real geocoded address, an approach-warning
RoadWarrior icon, and arrival detection. Pickup only ever had a
restaurant *name* and raw coordinates -- no address text, no approach
icon, nowhere to leave a note about the location itself. This closes
that gap by mirroring the existing dropoff pattern rather than inventing
a new one:

- **Real pickup address**: `GoogleApiHelper.geocodeAddressWithFormatted`
  (a new method alongside the existing `geocodeAddress`, so every other
  caller is untouched) also captures Google's own formatted street
  address for the restaurant-name geocode already being done, not just
  lat/lon. Stored on the trip row (`pickup_address`) and shown in the
  trip summary.
- **RoadWarrior icon while approaching pickup**: the same quick-nav icon
  already shown while approaching a dropoff now also appears while
  approaching the pickup (`TripManager.check_approaching_pickup`,
  mirroring `_check_approaching_stop`), tappable to open navigation. If
  the icon appears before the formatted address has resolved, it falls
  back to the restaurant name + coordinates -- still enough for real
  navigation, just a less precise pin.
- **"Waiting for pickup address"**: a brief overlay message the first
  time the pickup icon would show but the address hasn't resolved yet --
  a visible signal rather than the icon just silently appearing with
  nothing extra.
- **Pickup notes**: a **Pickup Note** button appears on the main screen
  only while a pickup is actively registered (offer accepted, not yet
  departed), opening a small dialog to add/edit a note -- e.g. "gate code
  1234," "enter through side door." Saved per restaurant name
  (`pickup_location_notes` table), so it's still there next time an offer
  comes in from the same place, the same "learn per restaurant" pattern
  already used for parking-difficulty feedback and restaurant wait times.
- **Final timing stage**: the phase-by-phase "Where The Time Went"
  breakdown previously stopped at "parking to walking." It now also
  captures **completing dropoff** -- from reaching the door (or dropoff
  arrival, if walking wasn't separately detected) to the delivery
  actually being marked complete -- the photo/knock/hand-off time that
  wasn't measured before.

None of this has been confirmed against a real device yet -- same
honest-limit caveat as the rest of Real Google Maps Integration above:
the pickup icon/address/waiting-message flow reuses proven mechanics
(the exact same code paths dropoff already uses), but hasn't itself been
watched happen on an actual delivery.

## Distance accuracy: empirically checking what the offer's "X km" means

DoorDash doesn't publicly document whether the distance figure on the offer
screen (e.g. "5.1 km") includes the drive from your current location to the
restaurant, or just the restaurant-to-customer delivery leg. Rather than
guess, this measures it directly from your own real driving:

- `TripManager` tracks real cumulative GPS distance throughout each trip,
  snapshotting it at the moment you arrive at the pickup (= actual deadhead
  distance) and again when you depart (= start of the actual delivery leg).
- When a trip completes with pickup tracking data AND the offer's claimed
  distance (captured via `add_pickup`'s `claimed_distance_km` parameter),
  a comparison row is stored in `offer_distance_accuracy`: claimed distance
  vs. actual deadhead vs. actual delivery vs. actual total.
- "View Distance Accuracy" on the main screen aggregates every recorded
  delivery and reports which hypothesis (claimed == delivery-only vs.
  claimed == total-trip) has the lower average error -- with real numbers,
  and how many deliveries that conclusion is based on.
- **Needs real data to mean anything**: with zero or very few completed
  deliveries, this can't reach a meaningful conclusion -- the more real
  deliveries you complete with pickup tracking, the more confident the
  answer becomes. A single delivery's result could easily be misleading;
  treat early results as provisional.

## Smart Score breakdown: what's real, what's a proxy, what's not tracked

The floating badge over the offer screen (and `parse_offer_screen()`'s
output) now shows more than just the headline score:

- **`$/km` and `$/hr`**: `$/hr` is estimated from distance using an assumed
  ~25 km/h average delivery speed, NOT from the offer's "Deliver by" deadline.
  An earlier version used time-until-deadline for this, which produced a
  wildly misleading number (e.g. $5/hr) depending purely on what moment you
  happened to open the offer -- deadline slack time isn't travel time. Fixed
  to use a stable distance-based estimate instead.
- **Deadhead distance**: this was a real bug, not a design tradeoff -- an
  earlier version always passed `deadhead_km=0.0` into the score, silently
  maxing that factor's score at 100 (its full 15% weight) for every single
  offer regardless of reality. Fixed the same way as restaurant wait time:
  `SmartScoreEngine._estimate_deadhead_km()` now uses this driver's own
  historical actual deadhead measurements (from `TripManager`'s pickup
  arrival/departure distance tracking) -- restaurant-specific average if
  available, falling back to the overall average across all restaurants,
  or an honest 0-sample/0km reading if there's no history at all yet. The
  badge shows `deadhead_samples`/`deadhead_is_restaurant_specific` so it's
  clear whether a given figure is real learned data or just an early
  placeholder -- same transparency pattern as restaurant wait time.
- **Restaurant / pickup wait time**: this is **genuinely learned data**, not
  a permanent placeholder. `TripManager` tracks real arrival-at-pickup and
  departure-from-pickup GPS events; `SmartScoreEngine.record_restaurant_wait()`
  updates a running average in `restaurant_wait_history` after every real
  pickup. Until a restaurant has at least one recorded pickup, the badge
  shows a `~6 min (no history yet)` starting estimate -- clearly marked as
  unlearned (`restaurant_wait_is_learned: false`) rather than presented as
  real data.
- **Traffic**: labeled `traffic_risk` ("High (peak hours)" / "Low
  (off-peak)") -- this is a lunch/dinner clock-time proxy (`_is_peak_hour`),
  **not** live traffic data. Real traffic would require an internet-connected
  maps/traffic API, which conflicts with this app's offline-first, no-cloud
  design; the clock-time heuristic is the deliberate tradeoff instead.
- **Parking**: there is deliberately no separate parking metric.
  `parking_note` in the score output says so explicitly. GPS can't tell
  "searching for parking" apart from "waiting for the order to be ready" --
  both look identical (stationary near the restaurant) -- so pickup wait
  time already includes both, and inventing a fake separate "parking score"
  would just be noise dressed up as data.
- **Important interaction**: an active, not-yet-departed pickup counts as a
  DASHER-mode signal in its own right (`TripManager.get_mode()` /
  `_evaluate_trip_end`), independent of `dasher_app_foreground` or pending
  dropoff stops. Without this, a long pickup wait (parked, near-zero speed)
  would satisfy GENERAL mode's "parked 5+ minutes ends the trip" rule and
  end the delivery before the driver ever left the restaurant -- a real bug
  caught while building and testing this feature, not a hypothetical.

## Four more gaps closed

- **Auto-minimize / auto-summary**: "Start Monitoring" now calls
  `moveTaskToBack(true)` immediately, getting the app out of your way
  without a manual switch. "Stop Monitoring" now shows the trip summary
  right away, since the app is already open at that point.
- **Battery optimization exemption**: new "Disable Battery Optimization"
  button uses the standard `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` flow so
  aggressive OEM battery managers are less likely to kill the always-on
  service.
- **Personalized traffic-risk detection**: `_is_peak_hour` no longer
  assumes a generic lunch/dinner rush -- it learns YOUR actual average
  speed by hour-of-day from completed trips (needs 5+ trips with real
  distance/time to kick in), flagging hours where you've historically
  driven notably slower. Falls back to the generic window guess until
  there's enough data. Since superseded by real live traffic when a
  Google Maps API key is configured -- see `traffic_risk_source` below.
- **Dead `osmdroid` dependency removed** -- it was declared for a planned
  map view (route trail + delay pins) that was never actually built, just
  adding APK size for nothing.
- **"Reset All Data" button** -- wipes every table (trips, stops, events,
  delays, messages, all learned history, trusted contacts) with a
  confirmation dialog first, since it's destructive. Previously the only
  way to start fresh was manually clearing app storage via Android
  Settings.
- **Bug caught while building the above**: surfacing safety events/delays
  in the trip summary (see the "surfacing collected data" section above)
  exposed a real pre-existing bug in delay detection -- it was logging a
  new delay record on every single GPS tick once the threshold was
  crossed, so one continuous 5-minute stop showed up as 181 separate
  "delays." Fixed to log exactly once per continuous parked stretch.

## Troubleshooting: can't tell if monitoring is on

The on-screen status indicator (top-center, a plain solid colored dot --
solid green while active, solid red while idle, no text, no animation)
requires the **overlay permission** (`SYSTEM_ALERT_WINDOW`) to be granted.
If it was never granted, the dot silently never appears at all -- there's
no error, it just doesn't show up. If you can't see it:

1. Tap "Enable Overlay Permission" on the main screen and grant it.
2. Check the persistent notification in your notification shade -- it now
   appends "(enable overlay permission to see on-screen status)" to its
   text whenever that permission is missing, so it's the fallback way to
   notice this even if the dot itself is invisible.
3. The dot went through a couple of design iterations before landing here:
   a small 16dp unlabeled circle (too easy to miss), then a labeled
   "MONITORING" / "NOT MONITORING" text badge, then a pulsing animated
   version -- all reverted per explicit preference back to a plain,
   solid, 32dp colored circle: no words, no animation, just color.
4. **"Not Monitoring" was itself ambiguous** -- by design, the service
   deliberately keeps running (showing the idle red dot + notification)
   even when not actively tracking, so status stays visible at a glance.
   But this made it impossible to tell "idle but still running in the
   background" apart from "actually, fully off." There are now three
   distinct, checkable states:
   - **Driving/Idle -- Mode** -- actively tracking
   - **Not Monitoring (still running in background)** -- paused, service
     alive, badge/notification visible
   - **Fully Off** -- genuinely nothing running; only reachable via the
     new "Quit App Completely" button (in-app, with a confirmation
     dialog) or the "Quit Completely" action on the idle notification.
     Reaching this state removes the notification and badge entirely --
     if you see neither, it's definitely off.

## Dual mode: Dasher delivery vs. general driving

The app works both as a Dasher delivery co-pilot AND as a plain
driving-efficiency/safety monitor when the Dasher app isn't running at all
-- clearly indicated so you always know which one is active.

- **DASHER mode**: the Dasher app is currently in the foreground, or there's
  an unmatched delivery stop pending (covers briefly alt-tabbing to Maps
  mid-delivery). Offers, customer messages, and stop arrivals all work as
  described above.
- **GENERAL mode**: everything else -- just GPS-based trip tracking, safety
  scoring (harsh braking/acceleration, speeding), and time-efficiency
  scoring, with no delivery-specific features active.

**How the mode is shown:**
- The persistent notification (always visible while monitoring is on)
  shows "Dasher Mode" or "General Driving Mode" as its title, with a
  different icon for each, and updates immediately when the mode changes.
- A brief spoken cue ("Switched to Dasher delivery mode" / "Switched to
  general driving mode") plays on each transition -- not on every GPS tick,
  only on an actual mode change.
- The main screen's status text shows the same thing when the app is open,
  refreshed every few seconds.
- The post-trip summary dialog shows which mode that trip was recorded in.

**How mode detection works**: `DasherAccessibilityService` listens for
window-state-changed events across all apps (not just Dasher) purely to
know which app currently has focus -- this is reported to
`set_dasher_foreground()` in `drive_monitor.py`. Despite listening broadly
for *that* signal, the service still only ever reads on-screen *content*
(`getRootInActiveWindow()` / the text-node walk) when the foregrounded
app is actually Dasher -- for every other app, only the bare package name
is checked, nothing is read. This is a deliberate, documented privacy
boundary; see the comments in `DasherAccessibilityService.java` and
`accessibility_service_config.xml`.

**Known limitation**: app-switch detection via accessibility events is a
best-effort signal, not a guarantee -- some OEM launchers/home-screen
transitions may not always fire a window-state-changed event the same way,
so there could be a brief lag detecting when Dasher was backgrounded. This
doesn't affect DASHER mode's core safeguard (won't end a delivery trip
early) since that's driven primarily by unmatched stops, not just the
foreground signal.

**Bug fixed along the way**: trip-ending logic previously required at
least one delivery stop to ever exist before a trip could end -- meaning a
GENERAL-mode trip (no stops, ever) would never auto-end no matter how long
you stayed parked. Fixed so parking alone ends the trip in GENERAL mode (or
in DASHER mode once every registered stop is matched); DASHER-mode trips
still correctly wait for a pending stop to be reached before ending.

## Personal messages: trusted-contacts allowlist (separate feature)

This is independent of the work/customer message handling above. It lets
**personal** SMS and Facebook Messenger messages be read aloud too -- but
**only** for senders you explicitly add, so no one else's messages are ever
surfaced or logged.

- Add a name via the "Add Trusted Contact" field on the main screen.
  **Matching is substring-based**, not exact: an entry matches if it
  appears anywhere in the notification's sender name (case-insensitive,
  whitespace-trimmed). Adding "Mom" matches "Mom", "Mom ❤️", or any name
  containing "mom".
- **Why substring matching**: the same person often shows up under
  completely different display names across apps -- SMS typically shows
  the name saved in your phone's Contacts app, while Messenger shows their
  *Facebook* profile name (which may be a nickname, maiden name, or have
  decorative emoji added) -- these frequently don't match at all. A single
  short, distinctive entry (e.g. a first name) usually covers a person
  across every app, instead of needing a separate exact entry per app per
  name variant.
- **Tradeoff**: a very short or common entry (e.g. "Sam") could also match
  an unrelated sender whose name happens to contain it (e.g. "Samantha").
  Use a longer or more specific fragment if that's a concern for you.
- **Finding the right fragment**: the most reliable way is to look at an
  actual notification you've already received from that person (pull down
  the notification shade) and use a recognizable piece of the name shown
  there -- don't guess from their Contacts entry, since Messenger in
  particular may not match it at all.
- "View / Remove Trusted Contacts" lists everyone currently trusted; tap a
  name to remove them.
- Implementation: `TrustedContacts.is_trusted()` in `drive_monitor.py`
  (SQLite-backed, same offline-only storage as everything else), checked
  from `AppNotificationListenerService.java` for every SMS/Messenger
  notification.
- This is allowlist-only by design: a message from anyone not matching an
  entry is silently ignored -- not logged, not parsed, not spoken.
- **Messenger package name caveat**: `com.facebook.orca` is Messenger's
  package name as of this writing. If personal Messenger messages aren't
  triggering TTS, verify this against your installed version (e.g. via
  `adb shell dumpsys package facebook | grep messenger` or similar) and
  update the constant in `AppNotificationListenerService.java` if it's
  changed.

## Troubleshooting: `package com.google.android.gms.location does not exist`

`TripForegroundService.java` uses the Fused Location Provider API
(`FusedLocationProviderClient`, `LocationRequest`, etc.), which requires the
`com.google.android.gms:play-services-location` Gradle dependency — already
included in `app/build.gradle` in this project. If you see this error,
double check that dependency line wasn't removed and re-sync.

## Troubleshooting: `resource mipmap/ic_launcher not found`

This project ships a simple vector-based adaptive launcher icon
(`res/mipmap-anydpi-v26/ic_launcher.xml` + `drawable/ic_launcher_foreground.xml`)
so no binary PNGs were needed. If you still see this AAPT error, make sure
those files exist under `app/src/main/res/` and weren't excluded/deleted,
or generate a full icon set instead via Android Studio's
**File > New > Image Asset** wizard (right-click `res` folder).

## Troubleshooting: `org.gradle.util.VersionNumber` build failure

If Gradle sync/build fails with an error like:

```
* What went wrong:
org/gradle/util/VersionNumber
> org.gradle.util.VersionNumber
```

this means the project is using Gradle 9.0+ instead of the pinned 8.7.
Chaquopy 15.0.1's version-check code depends on an internal Gradle class
that was removed in Gradle 9.0 (see chaquo/chaquopy issue #1096 on GitHub).
Fix:

1. Confirm `gradle/wrapper/gradle-wrapper.properties` in this project still
   points at `gradle-8.7-bin.zip` (don't accept an "Upgrade Gradle wrapper"
   prompt from Android Studio unless you also bump Chaquopy to a version
   that supports the newer Gradle).
2. In Android Studio: **File > Settings > Build, Execution, Deployment >
   Build Tools > Gradle**, make sure "Gradle JDK" and distribution are set
   to use the project's Gradle wrapper (not a bundled/newer Gradle).
3. If `gradlew`/`gradlew.bat` can't find `gradle-wrapper.jar` (it isn't
   included here as a binary), let Android Studio's "Fix Gradle wrapper"
   prompt regenerate it, or run `gradle wrapper --gradle-version 8.7`
   once from a machine with Gradle installed.

## Notes / TODOs to make this production-ready

Remaining real gaps, as of the current build:

- **Post-accept address reading**: implemented -- `DasherAccessibilityService`
  parses the post-accept "Deliver to X" screen and extracts the real
  customer address (built from real screenshots, the same way the offer
  parser was). **Unconfirmed on a real device**: never exercised against
  an actual live delivery, so whether Dasher's real post-accept screen
  still matches what this expects hasn't been verified.
- **Geocoding**: implemented -- `GoogleApiHelper.geocodeAddress()` /
  `geocodeAddressWithFormatted()` convert both the pickup restaurant name
  and the post-accept dropoff address into real lat/lon via the Google
  Maps Geocoding API. **Unconfirmed on a real device**, together with the
  above: this is what would actually make arrival detection work on a
  real delivery instead of simulated/manually-entered coordinates, but
  that hasn't been exercised end-to-end yet.
- **Battery optimization exemption**: implemented -- `PermissionsActivity`
  has an `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` button.
  **Unconfirmed on a real device**: never confirmed that tapping it
  actually produces the system exemption dialog, or that the exemption
  holds up against a real OEM's aggressive battery manager (Samsung,
  Xiaomi, etc.).
- **RoadWarrior's `geo:` intent is unconfirmed**: no documented deep-link
  API exists for RoadWarrior (see `NavigationHelper.java`'s comments), so
  whether it actually opens RoadWarrior specifically (vs. falling back to
  another maps app) hasn't been verified on a real device.
- **Peak-hour traffic windows and harsh-accel/brake thresholds** are still
  generic hardcoded assumptions, not personalized to this driver's own
  historical patterns the way deadhead/wait-time/delivery-speed now are.
- **Speed-limit-based speeding detection, fuel cost estimates, and Smart
  Score weights/thresholds** can't currently be learned or fixed without
  data this app doesn't have access to (map speed-limit data, vehicle
  fuel efficiency, and a way to track whether offers were actually
  accepted/declined, respectively).
- This is a v1.0 (current features) skeleton only -- the v2.x-v4.0 roadmap
  items in the original product report are not implemented here.

## Permissions this project requests

Fine/background location, foreground service, Bluetooth, notification
listener access, accessibility access, SMS send (for the v2.3 emergency
alert stub), overlay (status badge + Smart Score badge + arrival
announcements), internet (Google Maps Geocoding API for pickup/dropoff
geocoding, already implemented via `GoogleApiHelper` --
everything else is 100% offline in SQLite).
