# PRD: interactive tutorial mode (driver backlog #29)

Status: DESIGNED, NOT implemented. One real open question (§4) needs a
driver decision before any code — do not build the randomization piece
from a guessed default.

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

## 4. Open question — randomized environments (NOT resolved, needs a driver decision)

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

## 7. Success criteria (nothing implemented yet)

- [ ] §4 answered by the driver (which option, or explicitly deferred
      per P2's recommendation) before randomization work starts
- [ ] New `TutorialActivity` + entry button on `MainActivity`
- [ ] All 11 steps implemented, each driving REAL app code with
      synthetic data (not a mockup of the resulting UI)
- [ ] `blockedByLiveMonitoring()`-equivalent guard checked at entry AND
      before each GPS-tick-chaining step (P1)
- [ ] Every simulated offer/trip row uses `is_test_data=1`
- [ ] Skip/exit available at every step, tested explicitly
- [ ] Interrupted-tutorial cleanup verified (P3) — not assumed
- [ ] Real executable test for whatever part of this is pure Python
      (the underlying `parse_offer_screen`/`on_gps_update` calls
      already have coverage elsewhere; this PRD's own test scope is
      the sequencing/cleanup logic specifically)
- [ ] Driver confirms in real use
- [ ] Driver sign-off
