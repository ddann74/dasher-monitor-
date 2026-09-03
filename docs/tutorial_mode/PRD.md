# PRD: interactive tutorial mode (driver backlog #29)

Status: IMPLEMENTED and tested (2026-09-03). §4 resolved: driver chose
Option B + Option C combined (see §8 for the implementation writeup).

## 0. Where this ask came from

Driver backlog #29's original text: "Add a screen recording function to
learn how the app works." Investigated during the original 36-item
triage (`docs/driver_backlog_2026_09_03/PRD.md`) and flagged as not
literally mapping onto anything buildable as written — `docs/
screen_recording/` is a different feature entirely (in-trip recording
for the driver's own review, not a teaching tool). Backburnered, then
picked back up in a later conversation and scoped through direct
back-and-forth with the driver:

1. First pass: what should it teach, what format. Driver answered: the
   Smart Score's 6 factors AND the app's real screens; an interactive
   walkthrough, not a static guided tour or a written FAQ.
2. Second pass (this PRD's actual shape): the driver asked directly
   whether the screens tour would show screens "at every delivery
   stage" — a real improvement on the original static-cards idea. This
   PRD documents that staged, simulated-delivery design instead.
3. Third addition: randomize the simulated environment each run, so
   repeat use doesn't always show the identical canned scenario. Not
   yet resolved into a single design — see §4.

## 1. Investigation — what already exists to build on

`DeveloperTestingActivity.java` already proves every mechanism this
feature needs, feeding synthetic data through REAL code paths (not a
mockup of the resulting behavior):

- `simulateOfferScreen()` (L63-129): feeds one fixed canned offer
  ("$13.65, 5.1 km, KFC Fairy Meadow, deliver by 4:25pm, 17 min") through
  the real `parse_offer_screen` → real `smart_score` computation,
  matching `DasherAccessibilityService.handleOfferResult`'s own badge
  text/voice-announcement exactly. Also calls `add_pickup(restaurant_name,
  -33.900, 151.200, distance_km)` — a fixed, hardcoded lat/lon, never
  varied. This is the direct citation for §4's randomization question:
  today's simulation mechanism has ZERO variation built in anywhere.
- `simulateDriveAndArrival()` (L182-244): feeds ~77 sequential synthetic
  `on_gps_update` calls (first simulating driving, then simulating
  parked/arrived) through the REAL trip-state machine, registers a real
  stop via `add_stop_to_buffer`, and confirms real arrival detection +
  a real customer-instruction announcement fire off the fake data. Runs
  on a background thread specifically because ~77 sequential Python
  calls could ANR the UI thread if run synchronously — the staged
  tutorial sequence will need the same treatment for any step that
  chains multiple simulated GPS ticks.
- `blockedByLiveMonitoring()` (L359-366): the exact guard against the
  real risk this PRD's own premortem (§5) flags — simulated and real
  GPS timestamps sharing one engine singleton would corrupt real trip
  state if both ran at once. Every simulate method that touches trip
  state uses this guard except the two that deliberately don't
  (`addTestStopNearby`, `simulateDashPausedResumed`) for their own
  stated, specific reasons.
- `simulateOfferOutcomes()` (L332-351): confirms the established
  pattern for marking simulated data as simulated —
  `record_offer_outcome`/`record_offer_timeout`'s trailing
  `is_test_data=true` argument, which every real report/stat already
  excludes. Any offer/trip data the tutorial's simulation produces MUST
  use this same flag — a real, already-solved problem, not something to
  redesign here.

`OverlayHelper.java`'s real, already-working overlay methods the staged
tutorial can trigger for real: `showMessage` (the Smart Score badge),
`showStatusDot`/`DotState` (RED_FLASHING / BLUE_FLASHING / YELLOW /
GREEN / WALKING), `showNavigationIcon`, `showPersistentTappableMessage`
(the approach-instruction overlay), `showReturnToSweetSpotIcon`,
`showHotspotOrHomeIcon`. All confirmed callable with synthetic
arguments, same as the real app already does.

`MainActivity.java` (L86-96): the six-category-screens button pattern
(`tripHistoryNavButton`, `permissionsNavButton`, `dataManagementNavButton`,
`diagnosticsNavButton`, `developerTestingNavButton`) — the natural
precedent for where a new "Tutorial" entry button belongs, alongside
these, not buried inside `DeveloperTestingActivity` itself (that screen
is a hidden dev tool, wrong audience for a driver-facing tutorial).

## 2. What's missing

- No sequencing mechanism to chain multiple simulate-style calls into
  one driver-paced, steppable walkthrough (today each Developer Testing
  button is a one-shot, independent action).
- No "Next"/step-counter UI at all in this app.
- No variation in the canned scenario — confirmed in §1, the fixed
  lat/lon/restaurant/distance never changes.
- No entry point for a driver-facing (non-developer) audience.

## 3. Design: staged, simulated delivery

New `TutorialActivity`, entered via a new "Tutorial" button on
`MainActivity` (alongside the existing nav buttons, §1). A single
step-by-step screen: a step counter ("Step 4 of 11"), the current
step's explanation text, a "Next" button, and a "Skip Tutorial" exit
available at every step (never trap the driver in it).

Each step corresponds to a real moment in an actual delivery, driven by
chaining the SAME simulate-style calls `DeveloperTestingActivity`
already proves work, guarded by the same `blockedByLiveMonitoring()`
check at entry (§5 covers what happens if that guard trips):

1. **Offer appears** — the canned offer, `parse_offer_screen` result
   shown as the real badge would.
2. **Reveal $/km, $/hr** — plain-language explanation alongside the
   real numbers.
3. **Reveal deadhead** — same.
4. **Reveal restaurant wait time** — same, noting it's learned from
   real history once any exists.
5. **Reveal traffic/time-of-day** — same.
6. **Final combined score + label** — how the six pieces combine.
7. **"Accept" simulated** — badge clears, `showStatusDot` fires for
   real (GREEN/YELLOW depending on simulated mode).
8. **Driving to pickup** — synthetic GPS ticks (background thread, per
   §1's ANR note), explain this phase is silent/normal.
9. **Arrival at pickup, then departure toward dropoff** — explain wait
   tracking; at the simulated stop's ~500m mark the real navigation
   icon appears (`APPROACHING_RADIUS_METERS`) — explain it.
10. **Approach within 50m** — the real approach-instruction voice
    announcement + persistent overlay fire (`INSTRUCTION_READ_RADIUS_
    METERS`, driver backlog #4's own recent work) — explain it.
11. **Arrival, walking detection, completion, feedback dialog** —
    status dot switches to WALKING, then the real post-trip feedback
    dialog appears; explain what it feeds into (personal calibration).
    Closing note: "everything from a real delivery ends up in Trip
    History — go look at a real one once you've done it."

Every offer/trip row this simulation produces uses `is_test_data=1`
(§1's already-established pattern) so it can never pollute real stats,
Trip History, or calibration.

## 4. Randomized environments — RESOLVED (2026-09-03): Option B + C combined

Driver chose both. Resolved into one combined design, implemented in
§8: if the driver has any real `pickup_location_history` (Option C),
pick one of their own real restaurants at random each run, using its
real averaged lat/lon plus real learned wait/deadhead data where
available. Otherwise (a brand-new driver, Option B's own stated
fallback need), pick at random from a small built-in pool of synthetic
profiles, each with its own distance so the simulated drive genuinely
varies run to run. B's own flagged geofence risk (an offset landing
already-arrived at simulation start) is avoided by construction — the
simulated START point is always wherever the driver actually is right
now (or a fixed fallback), and the destination is offset far enough
away (§8) that the drive phase is never trivially zero-distance.

Original options, preserved for reference:

Driver asked for variety across tutorial runs instead of always the
identical canned scenario. Three genuinely different shapes this could
take, each with a real tradeoff — not resolved here, matching this
session's own established discipline against guessing on a real design
fork:

**Option A — vary the numbers only, same fake coordinates.**
Pick from a small fixed pool (e.g. 4-5) of pre-written canned offers —
different restaurant names, distances, payouts, wait times — reusing
the exact fixed lat/lon `simulateOfferScreen` already uses. Smallest
change, zero interaction with real geofence math (the "environment"
that varies is just the numbers shown, not real position), but "random
locations" undersells what's actually varying — nothing about
geographic environment changes.

**Option B — vary simulated GPS coordinates too, still synthetic.**
Each canned scenario also gets its own fake lat/lon (still not real
GPS), offset enough that the simulated "driving to pickup" segment
covers a visibly different distance/duration per run. Closer to what
"different environments" literally suggests, but interacts with real
geofence/arrival-detection math (`ARRIVAL_GEOFENCE_METERS`,
`APPROACHING_RADIUS_METERS`) — needs care that a randomly-generated
offset can't accidentally produce a geofence that's already satisfied
at simulation start (an instant, confusing "arrival").

**Option C — vary using the driver's OWN real address-book history.**
Once a driver has real `pickup_location_history`/`get_address_book`
data, pull a few of their own real, familiar restaurants into the
canned scenarios (using real names/coordinates, but still feeding fake
GPS ticks, still `is_test_data=1`) instead of always "KFC Fairy
Meadow." Most personally relevant, reuses real data already in the
schema — but means a brand-new driver with zero history still needs a
fallback pool (effectively Option A or B) for their very first run,
so this is additive to one of the other two, not a full replacement.

Recommend Option A to ship first (§5's premortem explains why), with B
or C as a real, disclosed follow-up — but this is the driver's call to
make, not assumed here.

## 5. Premortem — assume this goes wrong

- **P1 — simulated GPS ticks racing a REAL active trip.** The staged
  tutorial chains multiple simulate-style calls sequentially, unlike
  today's one-shot Developer Testing buttons — a longer window where a
  driver could background the tutorial, get a real offer, and corrupt
  shared trip state if both write to the same engine singleton at once.
  Mitigation: reuse `blockedByLiveMonitoring()` at TutorialActivity
  entry (§1's already-proven guard) AND re-check it before each
  GPS-tick-chaining step, not just once at entry — a real trip could
  start mid-tutorial, not just before it.
- **P2 — randomization (§4) delaying the core feature.** Option B's
  geofence-interaction risk in particular could turn a self-contained
  UI feature into a multi-session debugging effort against real
  arrival-detection math. Recommendation: ship the staged walkthrough
  with Option A (or even a single fixed scenario, §4 deferred entirely)
  first, add variety as a disclosed, separate follow-up — don't let an
  open design question block driver value that's otherwise ready.
- **P3 — an interrupted tutorial leaving dangling simulated state.** If
  a driver backs out or switches apps mid-sequence (e.g. during step 8's
  synthetic driving), a fake "in-progress trip" could linger in the
  shared engine state, confusing the REAL app afterward (e.g. status
  dot stuck showing a stale simulated mode). Mitigation: `TutorialActivity.
  onDestroy()` (or `onPause`, whichever proves reliable) must explicitly
  reset/clear whatever simulated trip state the sequence created — this
  needs to be verified as its own success-criteria item (§7), not
  assumed to "just work" because Developer Testing's one-shot buttons
  never needed this (they don't leave a mid-sequence state to clean up).

## 6. Non-goals

- Not modifying `DeveloperTestingActivity` itself — the tutorial is a
  new, separate, driver-facing screen; the dev-only screen stays as-is.
- Not building §4's Option C's real-address-book integration without a
  driver decision that Option C (or a hybrid) is actually wanted.
- Not a recorded video or literal screen recording — confirmed in the
  original triage this doesn't map onto how the app works; still true.
- Not auto-launching on first install — a separate, related driver
  decision (screen recording's own "capture by default" open question,
  `docs/screen_recording/PRD.md` §7.5, already documents this exact
  class of decision) not bundled into this PRD.

## 7. Success criteria

- [x] §4 answered by the driver — Option B + C combined
- [x] New `TutorialActivity` + entry button on `MainActivity`
- [x] All 11 steps implemented — see §8 for the one disclosed scope
      adjustment (steps 9/10 use direct, real overlay/voice calls
      rather than replicating `handleGpsResult`'s full state machine)
- [x] `blockedByLiveMonitoring()`-equivalent guard checked at entry AND
      before the GPS-tick-chaining step (P1)
- [x] Every simulated offer/trip row uses `is_test_data=1` — confirmed,
      `parse_offer_screen`'s own scoring path never writes to
      `offer_outcomes` at all (only `record_offer_outcome`/
      `record_offer_timeout` do, neither called by the tutorial)
- [x] Skip/exit available at every step
- [x] Interrupted-tutorial cleanup implemented and tested (P3) — new
      `discard_pending_pickup_and_stops()`, called from both a clean
      finish and `onDestroy()`
- [x] Real executable test for the new pure-Python logic:
      `get_tutorial_environment()` (7 cases) and
      `discard_pending_pickup_and_stops()` (3 cases)
- [ ] Driver confirms in real use
- [ ] Driver sign-off

## 8. Implementation writeup (2026-09-03)

### 8.1 Randomization: Option B + C combined

New `DriveMonitorEngine.get_tutorial_environment(base_lat, base_lon)`:
queries `pickup_location_history` for any real restaurant (same real
geo-anchor `docs/location_profitability_map/PRD.md` already
established). If any exist, picks one at random, using its real
averaged lat/lon plus real learned wait time
(`restaurant_wait_history`) and deadhead distance
(`offer_distance_accuracy`) where available — falling back to sane
defaults (5.0 km, 8.0 min) if that specific restaurant has no learned
wait/deadhead yet. Otherwise, picks at random from a new
`TUTORIAL_SYNTHETIC_ENVIRONMENTS` pool (4 built-in profiles with
distinct distances/payouts/wait-times). `base_lat`/`base_lon` (the
phone's real current location if known, else the same fixed fallback
`DeveloperTestingActivity.addTestStopNearby()` already uses) double as
BOTH the synthetic destination's offset origin AND the simulated
drive's start point — avoiding §4's flagged "already arrived at
simulation start" risk by construction, since the destination (real or
synthetic) is never the same point as the start.

`payout` for a real-restaurant scenario is explicitly flagged
(`payout_is_illustrative: true`) — this app has never persisted one
canonical dollar figure per restaurant, only per individual offer, so a
derived number (`distance_km * 2.5`) is used and disclosed as
illustrative rather than presented as if it were a real historical
payout.

### 8.2 Scope adjustment found during implementation (disclosed, not silent)

Re-reading `TripForegroundService.handleGpsResult` in full (to wire
steps 9/10's navigation icon and approach-instruction overlay from real
simulated GPS results, as originally envisioned in §3) found it's a
large, side-effect-heavy state machine — mode/trip-state tracking, a
scoped WakeLock, the hotspot-or-home/sweet-spot check,
`notifyRateThisDelivery()` (which brings MainActivity to the
foreground). Replicating this faithfully from `TutorialActivity` would
be genuinely risky to get right, and `notifyRateThisDelivery()`
specifically expects a REAL trip ID — firing it against fake data would
be a real app-wide side effect a tutorial has no business causing.

**Decision**: steps 9 (navigation icon) and 10 (approach-instruction
overlay + voice) call `OverlayHelper`/`VoiceAnnouncer` DIRECTLY with
illustrative content, driven by the tutorial's own step sequence rather
than derived from a live simulated GPS stream. The overlay/voice code
itself is real (the exact same `OverlayHelper.showNavigationIcon`/
`showPersistentTappableMessage`/`VoiceAnnouncer.speak` a real delivery
uses) — only the trigger is simplified. Matches this session's own
established principle (first used in the Profitability Map's marker
taps) of preferring lower-risk, already-proven APIs for the interactive
part over a less-certain full replication.

Step 8 (the drive itself) still uses the real, proven `on_gps_update` +
`add_stop_to_buffer` chain (mirroring
`DeveloperTestingActivity.simulateDriveAndArrival()` almost exactly,
generalized to variable start/destination coordinates via linear
interpolation over 10 ticks, then up to 80 arrival-detection ticks at
the destination) — genuine real code, real arrival detection, on a
background thread per the existing ANR precedent.

### 8.3 Cleanup (P3)

New `TripManager.discard_pending_pickup_and_stops()` (+
`DriveMonitorEngine` wrapper): a plain reset of `self.pickup`/
`self.stops`, deliberately NOT reusing `add_pickup`'s own overwrite path
(which persists the outgoing pickup's job row for a real stacked order
— a discarded tutorial pickup was never real and must never be
persisted). Called from step 11's own "delivery complete" narration on
a clean finish, and from `TutorialActivity.onDestroy()` for an
interrupted exit (back button, Skip, or the OS reclaiming the
Activity) — `pickupRegistered` tracks whether there's anything to
discard, so a clean finish's own cleanup makes the `onDestroy()` path a
no-op rather than double-discarding.

### 8.4 Self-caught bug during implementation

Both new/touched XML files (`AndroidManifest.xml`'s new `<activity>`
comment, matching the exact same mistake already self-caught once in
`docs/location_profitability_map/PRD.md`) initially used `--` as an
em-dash inside an XML comment body — invalid XML. Caught by
re-validating with `xml.etree.ElementTree.parse()` rather than trusting
visual inspection, same discipline as before.

### 8.5 Verification

Same disclosed limitation as every Java-side PRD in this repo — no
Android SDK/emulator/device.

- `drive_monitor.py` recompiles cleanly.
- Real Python test (`test_tutorial_environment.py`, 7 cases): synthetic
  pool correctly used with no real history (confirmed real variation
  across 20 calls, not always the same profile), correct start/dest
  offset math; real restaurant correctly used once
  `pickup_location_history` has any entry, with real lat/lon and real
  learned wait/deadhead where available, sane defaults where not.
- Real Python test (`test_tutorial_discard_pickup.py`, 3 cases):
  discard clears both `pickup` and `stops`, confirmed no
  `offer_distance_accuracy` row is ever persisted for a discarded fake
  pickup, and the `DriveMonitorEngine` wrapper delegates correctly.
- Brace/paren balance: `TutorialActivity.java` 44/44 braces, 213/213
  parens; `MainActivity.java` 151/151 braces, 698/698 parens.
  `AndroidManifest.xml`/`activity_tutorial.xml`/`activity_main.xml`/
  `strings.xml` all re-validated as well-formed XML.
- Re-ran the full existing scratchpad test suite — no regressions.
