# PRD: driver backlog triage (2026-09-03) - 36 items

Status: TRIAGED, not implemented. This PRD documents a full
investigation of a raw 36-item list the driver dumped in one message
(bug reports, feature requests, and direct questions, no
prioritization) against the real code. Nothing in §4/§5's checklists is
built yet - this is the planning document a ralph loop (see
RALPH_PROMPT.md) works through, one item at a time, same as every other
PRD in this repo.
Scope: this PRD covers ONLY the 36 items below. It is not a general
codebase audit, and finding something unrelated while working an item
does not expand this PRD's scope - flag it in PROGRESS.md and open a
new PRD folder instead, per this repo's own established convention.

## 0. How this was produced

The driver sent 36 items verbatim, in one message, as a raw checklist
(mixed `[ ]`/`[V]` markers, no numbering, no separation between bug
reports, feature requests, and plain questions). A general-purpose
agent investigated each one against the real code before anything was
written here: checked whether an existing `docs/<topic>/PRD.md` already
covers it (and spot-checked the actual Java/Python behind that PRD's
own "Status:" claim, rather than trusting it - a couple of PRDs in this
repo have had stale status headers before), then classified each item
into one of four buckets. §1-§5 below are that investigation's findings,
organized for action; the original 36-item list, numbered for
reference, is preserved in §6.

## 1. Category A - already implemented or directly answerable, no code needed

These are closed by this PRD - no checklist entry, nothing to build.
Kept here as the reference record for why.

- **#3** (screen recording in general driving mode) - yes, recording is
  wired into `TripForegroundService.startTracking()`
  (`TripForegroundService.java:349-403`), which runs identically
  regardless of Dasher/General mode.
- **#10** (force Dasher foreground before scoring) -
  `docs/foreground_before_scoring/PRD.md`, IMPLEMENTED.
  `launchDasherApp()` fires unconditionally for any recognized offer
  notification (`AppNotificationListenerService.java:387`).
- **#12** (feedback page shown directly, not via notification) -
  `docs/feedback_page_direct/PRD.md`, IMPLEMENTED
  (`TripForegroundService.java:834-873`). Caveat carried into §4/#13
  below: gated on `mode=="DASHER"`, General-mode trips never get this
  prompt - noted, not itself a bug to fix here.
- **#18** (read all notifications if no contacts loaded) - already
  exactly this default (`drive_monitor.py:5060-5073`, wired from
  `AppNotificationListenerService.java:235-241`).
  `docs/notification_reading_reliability/PRD.md`.
- **#19** (does the log show mode switches, is that what's being read
  aloud) - yes to both. `MODE` diagnostic lines fire on real
  transitions; `onModeChanged` speaks the same transition aloud
  (`TripForegroundService.java:1422-1436`).
- **#24** (how offer detection works off-screen) - direct answer:
  `AppNotificationListenerService` parses the Dasher notification text
  the instant it posts, independent of what's on screen, then always
  opens Dasher for the real screen-based score
  (`AppNotificationListenerService.java:26-63,337-443`).
- **#28** (hearing a notification sound -> app checks offer screen) -
  confirmed exactly matches driver's belief:
  `handleDasherNotification` -> `launchDasherApp()` unconditionally
  (`AppNotificationListenerService.java:387`).
- **#30** (auto-detect dash mode + alert if unmonitored) - IMPLEMENTED,
  `docs/dash_monitoring_awareness/PRD.md`.
- **#31** (post-order rating feeds the recommendation engine) - already
  fully wired: `save_trip_feedback` -> `recalculate_personal_calibration`
  (`drive_monitor.py:1074-1101`).
- **#32** (RoadWarrior icon near delivery, disappears on complete) -
  IMPLEMENTED exactly as described, `docs/road_warrior_icon/PRD.md`
  (`TripForegroundService.java:1257,1302`; `drive_monitor.py:2539`).
- **#34** (what happens if monitoring starts before Dasher is open) -
  direct answer: tracks GPS as `GENERAL` mode; auto-flips to `DASHER` +
  auto-starts once Dasher opens (`dash_monitoring_awareness`).
- **#35** (can the notification reader be tested) - yes, today:
  `DeveloperTestingActivity`'s `simulateMessageButton`/
  `simulateTrustedButton` (`DeveloperTestingActivity.java:34-53`).
- **#23 / #36** (report card for rejected offers / "is there a Dasher
  report card") - already implemented, not new:
  `get_rejected_offers_report()` (`drive_monitor.py:4032-4109`) computes
  per-factor average score across accepted/declined/timed-out offers -
  literally "help the algorithm learn how to rate future offers."
  Wired to `TripHistoryActivity.java:360`. #36 is a duplicate of #23.
- **#33** (test with screenshots of past offers) - clarifying answer,
  not a bug: the app never processes images, it reads accessibility
  text nodes live off the real screen - "screenshot testing" doesn't
  map onto how it works. Closest real equivalent:
  `DeveloperTestingActivity`'s canned offer-text buttons
  (`DeveloperTestingActivity.java:63-75`).
- **#20** (app keeps switching Dasher<->General) - a fix already exists
  in code (found inline, not via a docs/ PRD): a 2-second debounce
  (`MODE_CHANGE_DEBOUNCE_MS`, `DasherAccessibilityService.java:770`),
  requiring a candidate state to persist before it commits
  (`DasherAccessibilityService.java:444-509`). Carried forward as §4/#20
  below anyway, since "already fixed in code" isn't the same as
  "driver confirmed the fix holds" - if it's still happening on a build
  that has this debounce, that's new evidence worth a fresh log, not a
  re-investigation from scratch.

## 2. Category B - duplicates

- **#15** = duplicate of **#11** (address book not populating) - same
  feature, same likely cause. Tracked once, as #11, in §4.
- **#36** = duplicate of **#23** (see §1 above, both already answered).
- **#9** is closely adjacent to **#8** (both about what Trip History
  surfaces) but is a distinct, separately-trackable ask - kept separate
  in §5.

## 3. Open question before any ralph-loop work starts

- ~~**#4**~~ **RESOLVED (2026-09-03) and IMPLEMENTED** - driver finished
  the sentence: "force-acknowledging any customer messages, keep
  reading aloud every 30 secs until i acknowledge; also read aloud
  customer instructions including generic instructions when i approach
  the address within 50 meters." Two real, separate mechanisms in the
  codebase mapped directly onto this: the urgent-customer-message voice
  path (`AppNotificationListenerService`) and the existing
  approach-instruction overlay (`TripForegroundService`/`_check_
  approach_instruction`). See §10/§11 for the full writeup and
  PROGRESS.md for verification.
- ~~**#17**~~ **RESOLVED (2026-09-03), reading (b)** - driver confirmed
  they meant the "navigate home with a saved/preset route" reading
  (§4/#17b), not the RoadWarrior-icon bug reading (§4/#17a). Satisfied
  by `docs/hotspot_or_home_routing/` (shipped, PR #19, merged
  2026-09-03) - built for a different, concurrent driver request (shift-
  rate-based routing) but its icon-appears-on-trip-end + tap-to-navigate
  mechanism is the same shape as what #17 asked for. Driver confirmed
  this covers it. One honest, disclosed difference, not silently
  papered over: the icon is conditional on the shift-rate algorithm (only
  suggests home when the recent rate is BELOW the driver's threshold;
  suggests the hotspot instead when above) - it is not an always-
  available "just take me home now" button regardless of shift
  performance. If that unconditional version is ever wanted, it's a
  small, separate, NOT-yet-built follow-up, not assumed here. #17a (the
  RoadWarrior-icon-didn't-appear bug reading) remains untouched - it was
  not what the driver meant, so it needed no fix, not "fixed by this."
- **#26** ("improve report formatting") doesn't say which report - the
  full diagnostic export (`export_full_report`, already has some table
  formatting via `_format_table`, `drive_monitor.py:4162`), Trip
  History, or Address Book. Tracked as an open question in §5.

## 4. Category C - real bugs, no existing tracking (or open/blocked tracking)

Checklist for the ralph loop. Each item: what's confirmed, what (if
anything) is still needed before a fix can be attempted.

- [ ] **#13 - review/feedback screen never appeared.** ALREADY TRACKED:
      `docs/feedback_prompt_never_shown/PRD.md`. Diagnosability fix
      shipped; real root-cause fix explicitly BLOCKED pending a real
      diagnostic log from a trip where it didn't appear. Two candidates
      already documented there (mode never became `"DASHER"`, or the
      direct-launch/notification-fallback silently failed). **Action
      for this ralph loop: none until that log arrives** - do not
      duplicate that PRD's own blocked status here.
- [x] **#21 - rejected offers not being recorded.** PARTIALLY ADDRESSED
      (2026-09-03) - driver had no specific diagnostic log to root-cause
      against, and reframed the real goal directly: "collect all data to
      build the smart score engine." Rather than guess at an unconfirmed
      bug, hardened the ALREADY-NAMED weak point instead - see §15/§16
      for the full writeup. **Not closed as fully verified** - the
      hardening addresses a real, disclosed risk in the existing code,
      but whether a decline was actually ever being lost in practice is
      still unconfirmed (no device/log evidence either way). Re-open with
      a real diagnostic log (`OUTCOME: Declined` vs. `OUTCOME: Timed out`
      lines around a session with real declines) if the driver notices
      anything still missing after this ships.
- [x] **#22 part (a) - accessibility (and the other 3 critical
      permissions) already off at monitoring start.** FIXED (2026-09-03)
      - see §17/§18 for the full writeup. Part (b) (dot-duration /
      announcement-timing correlation) - driver confirmed part (a) alone
      is enough for now; part (b) not built, per the driver's own
      explicit choice not to build speculative instrumentation for a
      one-off report.
- [x] **#27 - missed hearing the "dash paused" notification/
      announcement.** FIXED (2026-09-03): `DashPauseDetector`/
      `is_dash_paused_screen` (`DasherAccessibilityService.java:629-646`)
      already detected a paused dash (screen-based), paused GPS, and
      wrote a diagnostic log line - but never called
      `VoiceAnnouncer.speak()`. Added one call at the existing detection
      point. Scoped to the PAUSE announcement only (the driver's literal
      complaint) - the RESUME path (`attemptAutoStartMonitoring`) was
      also found silent during this fix but is NOT touched here, per
      RALPH_PROMPT.md's "don't expand into general cleanup" guardrail;
      left as a candidate for a future, separately-scoped item if the
      driver wants it too. See PROGRESS.md.
- [x] **#16 - status dot doesn't always appear.** PARTIALLY ADDRESSED
      (2026-09-03) - no diagnostic log available, so rather than guess,
      hardened a real, already-named-elsewhere risk class instead (same
      approach as #21). See §19/§20 for the full writeup. **Not closed
      as fully verified** - still unconfirmed whether this was the
      driver's actual cause; existing partial mitigation for "overlay
      permission never granted" (a notification nudge,
      `TripForegroundService.java`, line number approximate) untouched.
- [ ] **#17a - RoadWarrior icon didn't appear that one time** (the
      "already implemented" reading of #17 - see §3). If this is what
      the driver meant, it's a bug report against
      `docs/road_warrior_icon/PRD.md`'s existing feature, not new work.
      **Action: needs the driver to confirm which reading of #17 they
      meant before this checklist item is actionable at all** - see
      §5/#17b for the alternative "new feature" reading.

## 5. Category D - new feature requests, no existing design

Rough size estimates are for THIS codebase's existing infrastructure,
not effort in the abstract - "small" means the underlying data mostly
already exists and this is wiring/UI; "large" means new schema plus new
analysis plus new UI.

- [x] **#1 - geo-tagged smart-score database -> most profitable
      locations.** SCOPED (2026-09-03) and moved to its own PRD - driver
      confirmed the full version: a dedicated new map, not just a stat
      shown in an existing screen. See `docs/location_profitability_map/`
      for the full design/implementation.
      `docs/market_relative_score_thresholds/` (DRAFT, driver decision
      still pending) is adjacent (learned score thresholds) but doesn't
      cover this - a separate design pass.
- [x] **#2 - per-offer omit/include toggle for calibration.** FIXED
      (2026-09-03): new `omitted_from_calibration` column on
      `offer_outcomes` (deliberately separate from `is_test_data`, which
      means something different and is never driver-controlled) plus a
      migration for existing databases. New
      `get_calibration_offers_list()`/`set_offer_omitted_from_calibration()`
      on `DriveMonitorEngine`; `recalculate_personal_calibration`'s
      Source-2 query now excludes omitted offers. UI: a checklist dialog
      (checked = included) reachable via a new "Edit Offers Used" button
      on the existing Personal Calibration screen, each toggle saved
      immediately. Verified with a real, runnable Python test covering
      the list, the toggle, and the calibration query's own exclusion.
- [x] **#5 - suggested hotspot from the last 5 deliveries, weighted by
      offer quantity, with a copy-coordinates button.** FIXED
      (2026-09-03): `get_pickup_sweet_spot_zone()`'s zone-grid-frequency
      logic factored into a shared `_best_zone_from_pickup_rows()` helper
      (avoiding duplicated logic), reused by new
      `get_recent_pickup_hotspot()` - same algorithm, restricted to the
      5 most recent pickups, with its own much smaller min-samples
      threshold (3, since the window itself is capped at 5). Shown in
      the Address Book dialog alongside the existing all-history sweet
      spot; a "Copy Recent Hotspot" button (only offered when a
      suggestion exists) copies the coordinates via this app's existing
      simple clipboard-copy pattern. Verified with a real, runnable
      Python test proving the recent window picks a DIFFERENT zone than
      all-history when recent behavior actually differs.
- [x] **#6 - average $/km for accepted vs. declined offers.** FIXED
      (2026-09-03): added a `rate_comparison` block to
      `get_rejected_offers_report()`, using a query separate from the
      existing per-factor `comparison` (which is scoped to rows with a
      components snapshot - $/km doesn't need one, so reusing that query
      would silently drop valid rows). Excludes zero-distance and
      missing-payout rows to avoid division by zero. Shown in
      `TripHistoryActivity`'s existing Rejected Offers Report dialog, one
      line above the per-factor comparison. Verified with a real,
      runnable Python test (zero-distance/missing-payout/test-data rows
      confirmed excluded, averages confirmed correct).
- [x] **#7 - per-restaurant breakdown: last 10 visits with dates, times,
      ratings, average, and standard deviation.** FIXED (2026-09-03),
      WITH A REAL, DISCLOSED GAP found during implementation: the PRD's
      own original framing ("joining `trips`/`trip_feedback` by
      restaurant name") turned out not to be possible - `trips` has no
      `restaurant_name` column and `offer_score_snapshot_json` doesn't
      include one either (confirmed by reading
      `SmartScoreEngine.calculate()`'s own returned dict), so driver
      star ratings (trip-level) cannot be reliably linked to a specific
      restaurant. A fragile timestamp-proximity join was considered and
      rejected - it could silently attribute the wrong rating to the
      wrong restaurant. New `get_restaurant_visit_history()` uses each
      visit's own Smart Score instead (always available, genuinely tied
      to that restaurant, per `offer_outcomes`), with average and SAMPLE
      standard deviation, and an explicit `rating_note` naming this
      substitution rather than silently presenting a Smart Score as if
      it were a "rating." UI: new "Restaurant Visit History" button ->
      restaurant chooser -> per-restaurant breakdown dialog. Verified
      with a real, runnable Python test (empty/single/multi-visit cases,
      exact stdev matched against Python's own `statistics.stdev`,
      limit + ordering, test-data exclusion).
- [x] **#8 - trip history: full stage breakdown (driving to pickup,
      waiting at restaurant incl. wait-time rating, driving to dropoff,
      completing dropoff, deadhead time if applicable).** FIXED
      (2026-09-03): both missing pieces added to
      `TripHistoryActivity.buildTripSummaryBody()`. Wait-time RATING -
      `feedback_merchant_wait` ("Fast"/"Okay"/"Slow", already returned
      in the same summary JSON, just never read here before) now shown
      as "(rated: X)" next to the wait duration. Deadhead TIME - reuses
      `phase_breakdown`'s existing `driving_to_pickup_seconds` (for the
      single/first-job scope this data already has, driving-to-pickup
      time IS the deadhead leg's time, not a new computation), shown
      alongside the existing "Deadhead: X km" line rather than as a
      separate, confusingly-duplicate "Driving to pickup" entry. See
      PROGRESS.md for the honest caveat about that reuse.
- [x] **#9 - separate Dasher vs. General trips in the report.** FIXED
      (2026-09-03): `showTripHistory()` now shows an up-front chooser
      ("All Trips" / "Dasher Only" / "General Only") before the list -
      filtered client-side from the same already-fetched `trips` array
      (`trip.mode` was already returned by `get_trip_history()`, just
      never used to filter). No Python change needed.
- [x] **#14 - surface the traffic ratio as a reported metric.** FIXED
      (2026-09-03): `_get_traffic_risk()` now returns the raw live-traffic
      delay ratio as a third value (only when its source is actually
      "live" - the zone/personal/generic sources are binary flags with
      no real ratio behind them, so `None` for those rather than a
      fabricated number). Added to `calculate()`'s returned dict as
      `traffic_ratio`, shown in `TripHistoryActivity` as "(X% of
      typical)" next to the existing High/Low label. Verified with a
      real, runnable Python test covering all three cases: no live data,
      fresh live data, and stale live data falling back correctly.
- [x] **#17b - "navigate home" with a pre-determined/saved route.**
      RESOLVED (2026-09-03) - driver confirmed this was the intended
      reading of #17, and confirmed it's satisfied by the already-shipped
      `docs/hotspot_or_home_routing/` feature (home address + tap-to-
      navigate-via-Waze icon on trip completion). See §3 for the full
      writeup and the one disclosed gap (conditional on the shift-rate
      algorithm, not an unconditional manual home button).
- [x] **#25 - add $/km and $/hr metrics to the smart score
      recommendation itself.** ALREADY IMPLEMENTED (found during this
      pass, no new code needed): `DasherAccessibilityService.java`'s
      live Smart Score badge already shows `$X.XX/km   $X.XX/hr` in
      both its compact and expanded text (lines ~894-903, ~925-926) -
      an existing inline comment there even says "Restored to the live
      badge per explicit request: $/km and $/hr specifically." The
      original triage (§1-§5's investigation pass) missed this one;
      corrected here rather than duplicating working code. Distinct from
      #6, which is the historical accepted-vs-declined comparison, not
      the live per-offer badge.
- [x] **#26 - improve report formatting for readability.** RESOLVED
      (2026-09-03) - driver named Trip History and Address Book, plus a
      real accuracy concern ("some don't look accurate") on the "Where
      The Time Went" breakdown, and asked for more Address Book detail
      (avg $/km, avg $/hr, avg Smart Score + standard deviation). See §16
      for the full writeup.
- [ ] **#29 - screen-recording-based tutorial/walkthrough to learn how
      the app works.** DESIGNED, moved to its own PRD (2026-09-03) -
      the design was refined further in a later conversation (a staged,
      simulated-delivery walkthrough, not static cards) plus a new
      randomization ask. See `docs/tutorial_mode/PRD.md` for the full
      investigation, design, and premortem - one real open question
      there (§4, which randomized-environment option) still needs a
      driver decision before implementation starts.

## 6. Recommended priority order (my judgment call, not driver-specified)

Not driver-ordered - disclosed here as a recommendation, open to being
reordered on request, same as every other "here's my stated
recommendation" judgment call in this repo's other PRDs.

1. **#27 (voice-announce a dash pause)** - smallest, clearest, already
   root-caused, zero open questions. Do first.
2. **#8 (trip history stage breakdown completion)** - small, mostly
   already built, immediately useful.
3. **#6 and #9 (accepted/declined $/km, Dasher/General separation)** -
   both small, both pure additions to existing, already-returned data.
4. **#14 (surface traffic ratio)** - small, already-computed value,
   just needs exposing.
5. **#2 (per-offer omit/include toggle)** - small-medium, directly
   improves the calibration engine's own input quality.
6. **#5 and #7 (hotspot-from-last-5, per-restaurant visit breakdown)** -
   medium, both build on existing Address Book infrastructure.
7. **#25 (live $/km, $/hr in the recommendation)** - small-medium,
   builds on already-implemented hourly-rate work.
8. Resolve the open questions (§3: #4, #17, #26) with the driver -
   needed before those items can even be sized/started, so worth
   raising early rather than leaving until the loop reaches them.
9. **#21, #22, #16 (bug investigations needing fresh diagnostic
   logs)** - can't be scheduled on a timeline; pick up whenever the
   relevant log arrives, same as this session's other diagnostic-log-
   driven fixes.
10. **#1 (geo-tagged profitability database) and #29 (tutorial mode)**
    LAST - both large, both genuinely new subsystems, both worth a
    dedicated design conversation with the driver before any code,
    given the disproportionate effort for a single-driver tool if the
    driver's actual need turns out to be smaller than the full ask
    (see §7, P3).

## 7. Premortem: assume working this backlog goes wrong

Written before any item in §4/§5 is implemented, same discipline as
this repo's other premortems (e.g. `docs/screen_recording/PRD.md` §4a).
This backlog is a different SHAPE of risk than a single-feature PRD's
premortem - 18 independent checklist items spanning most of the
codebase's major subsystems, worked one at a time over many sessions -
so the risks below are about that shape, not about any one item's own
logic.

- **P1 - schema drift between related items implemented independently.**
  #1 (geo-tagged profitability DB) and #5 (hotspot weighted by offer
  quantity) both touch geo/location data; #6 and #25 both touch $/km;
  #8 and #26 both touch report formatting. If a ralph loop works these
  strictly one-at-a-time, weeks apart, without re-reading what a sibling
  item already built, real risk of two slightly different schemas or
  UI patterns for the same underlying concept. Mitigation: before
  starting #1, #5, #6, #7, #14, or #25, re-read whichever of that group
  was implemented most recently first - not just this PRD's own
  description of it.
- **P2 - the "small" estimates in §5 assume the existing code the
  estimate leans on still works as described.** Several size estimates
  above cite specific functions (`get_pickup_sweet_spot_zone`,
  `get_address_book`, `_live_traffic_ratio`) as "already computing this,
  just needs exposing." None of those citations were re-verified beyond
  the triage agent's one investigation pass - if one of them has a
  latent bug (this session has found several "confirmed implemented"
  claims that turned out to be wrong, screen recording's own §13 being
  the most recent), the "small" item built on top of it inherits that
  bug silently. Whoever picks up a §5 item should spot-check its cited
  function actually returns what this PRD claims, not just wire UI to
  it on faith.
- **P3 - scope creep proportional to a single driver's actual need.**
  #1 and #29 in particular ask for real analytics/tutorial subsystems
  that would be substantial even in a team-maintained commercial app.
  Building either at full scope for one driver's personal tool, without
  first confirming with the driver what the SMALLEST version that
  actually helps looks like, risks large effort for a feature that gets
  used once or never. Both are deliberately placed last in §6 and
  flagged as needing a scoping conversation before design, not
  silently sized down here without the driver's input.
- **P4 - the open questions in §3 get silently resolved by
  assumption instead of by asking.** This session has an established
  pattern of using a PRD's "stated recommendation" to proceed under a
  blanket "continue" instruction when a genuine open question exists.
  That pattern is appropriate for a PRD's own design tradeoffs (e.g.
  "should X default on or off") - it is NOT appropriate for #4's
  incomplete sentence, #17's two genuinely different possible meanings,
  or #26's unnamed report, because guessing wrong there means building
  the WRONG THING entirely, not just picking a defensible default among
  known-equivalent options. RALPH_PROMPT.md's guardrails say this
  explicitly.
- **P5 - no device access, still, for every UI-touching item.** Every
  single item in §4 and §5 that touches a screen (which is most of
  them) can only be verified by code review in this environment, the
  same disclosed limitation as every other PRD in this repo. For a
  backlog this size, that adds up to a lot of "should work, unverified"
  surface area accumulating across many merged PRs before the driver
  gets a chance to actually look at any of it on a real device. Worth
  the driver doing a real-device pass across several completed items at
  once periodically, rather than only ever reacting to what's most
  recently merged.

## 8. Original list (verbatim, numbered for reference)

Preserved exactly as sent, only numbered. Item text is the driver's own
words; see §1-§5 for what each number maps to.

1. Build a database of smart score data and geolocation so I can
   determine the most profitable locations to be in
2. Collect all offers accepted declined and not responded so I can use
   them to build the smart score algorithm. Give me the option to omit
   or include each offer
3. Does screen recording working in general driving mode
4. Force me to acknowledge all dasher related [sentence incomplete in
   the original]
5. Add a suggested hotspot based on the last 5 deliveries. Have the
   button or icon take a copy of the coordinates where I can then paste
   them in a navigator. The hot spot should be weighted geopositionally
   on the quantity of offers
6. Add average $/km for accepted and declined offers
7. For each restaurant populated, show a breakdown of the last 10
   visits with dates times and ratings including average and standard
   deviation
8. In the trip history, list all stages of the delivery from: driving
   to pickup, waiting at restaurant including wait time rating, driving
   to drop off, completing drop off, deadhead time if applicable
9. Separate dasher and general trips from the report
10. Force the dasher app to appear in the foreground before the smart
    score can calculate the offer
11. Restaurant address book not populated
12. When I complete an order I want the app to show me the feedback
    page not in the notification
13. My review screen did not appear
14. Is there supposed to be a traffic ratio reported
15. Address book not populating
16. The status dot doesn't always appear on the top of the screen
17. Didn't see the navigate home icon appears let alone load a pre
    determined route
18. Read all notifications by default if no contacts are loaded
19. Does the diagnostic log indicate the app switching modes because I
    am also hearing it being read aloud
20. App keeps switching from dasher mode to general mode
21. Rejected offers or not being recorded
22. Accessibility access seemed to be turned off before I tried to
    start does the log detect this since the overlay/status dot
    appeared for less than 7 seconds and may have been interrupted by
    the notification announcement which happened very close to this
23. Add a report card for rejected offers to help the algorithm learn
    how to rate future offers
24. How does the app detect an offer when it's on a different screen
    from the dasher app. Sometimes the notification toast pops up as a
    genuine offer
25. Add a $/km metric and $/hr metric to the smart score recommendation
26. Improve the formatting of the report so it's easier to read
27. I don't know if all notifications are being read out by the app it
    missed the one being paused
28. [V] If the app heard a notification sound it should go to the
    dasher app and look for the offer screen
29. Add a screen recording function to learn how the app works
30. I want the app to know when I'm in dash mode automatically and let
    me know if the current dash is not being monitored. This is crucial
31. After I complete the order I want a rating and feedback to be
    completed by me to help the recommendation engine
32. When I get close to the delivery address I want the road warrior
    app to be available as a floating icon but disappear when I
    complete the order
33. Test app with a series of screenshots of past offers
34. What does the app do if I start monitoring before the dasher app is
    on
35. Can I test the notification reader yet
36. Is there a dasher report card and what does it do

## 9. Success criteria (this PRD as a whole)

- [ ] Every §4 item either fixed, or explicitly still blocked on a
      named piece of missing evidence (a diagnostic log, a driver
      answer) - never silently dropped
- [ ] Every §5 item either implemented, or explicitly still blocked on
      an open question or driver scoping conversation
- [ ] §3's three open questions answered by the driver
- [ ] Driver confirms the recommended priority order in §6, or gives a
      different one

## 10. #4 resolved and implemented (2026-09-03): force-acknowledge messages + tighter approach radius

Driver clarified #4 as two related but distinct asks:

1. "Force-acknowledging any customer messages, keep reading aloud every
   30 secs until i acknowledge."
2. "Also read aloud customer instructions including generic
   instructions when i approach the address within 50 meters."

### 10.1 Real code found for both, before writing anything new

- **(1)** maps to `AppNotificationListenerService`'s urgent-customer-
  message path - a delivery note or address correction extracted from a
  Dasher/SMS notification was already spoken ONCE
  (`VoiceAnnouncer.speak("Customer message: " + clean)`), no repeat, no
  acknowledgment concept anywhere.
- **(2)** maps to an ALREADY-EXISTING feature
  (`docs/dropoff_delivery_instruction_wiring/PRD.md`,
  `_check_approach_instruction`): fires once per stop, speaks the
  dropoff screen's own delivery instruction plus any matched chat
  message, AND shows a persistent tappable overlay
  (`OverlayHelper.showPersistentTappableMessage`) that already stays up
  until tapped. Two real gaps against the driver's exact wording: the
  trigger radius is `APPROACHING_RADIUS_METERS` = 500m, not the
  requested 50m; and the voice only speaks once, with no repeat.
  "Including generic instructions" is already satisfied -
  `DropoffScreenParser`'s delivery-instruction capture is a best-effort
  "any leftover free-text line" match, not filtered to exclude
  boilerplate/generic text.

### 10.2 Design

**New shared primitive** (`OverlayHelper.startAcknowledgeReminder`):
repeats `VoiceAnnouncer.speak(text)` every `intervalMs`, checking before
each repeat whether the persistent overlay it's paired with is still
showing (`instructionOverlayView != null`) - stops itself the instant
it's been tapped away, never speaks into a void with nothing left to
acknowledge. `clearPersistentMessage()` also cancels it directly (not
just relying on the runnable's own next-tick check), and
`showPersistentTappableMessage` gained a `boolean` return (was the
overlay actually shown - false if overlay permission isn't granted) and
an optional `onAcknowledged` callback.

**(1) Urgent customer messages**: now shows the same persistent overlay
(previously this path never showed one at all, voice-only) plus starts
the repeat reminder - both only if the overlay actually rendered
(permission check).

**(2) Approach instructions**: `INSTRUCTION_READ_RADIUS_METERS = 50`
added as a genuinely SEPARATE constant from `APPROACHING_RADIUS_METERS`
(500, unchanged) - that constant also drives the nav icon and the
coarse pre-filter this feature still uses before checking the real
distance, and narrowing it globally would have delayed the nav icon
too, which wasn't asked for. `_check_approach_instruction` now takes
`lat, lon` and computes the actual distance to the stop, only firing
within 50m. Also gained the same repeat-reminder as (1).

**Scope decision, disclosed rather than silent**: did NOT split "show
the overlay early at 500m, only delay the voice to 50m" into two
separate triggers - that would need a second, parallel per-stop state
machine in a function whose own comments document a real history of
subtle multi-stop/batch-order bugs. One combined trigger at the tighter
50m distance is simpler and lower-risk; if the driver wants the overlay
back to appearing earlier than the voice, that's a real, separately
doable follow-up, not assumed here.

### 10.3 Verification

No Android SDK/emulator/device in this environment (disclosed
limitation throughout this repo) - real runnable Python tests for the
pure-Python logic, code review plus brace/paren balance for Java.

- `drive_monitor.py` recompiles cleanly.
- `OverlayHelper.java`: 70/70 braces, 290/290 parens.
- `AppNotificationListenerService.java`: 75/75 braces, 311/311 parens.
- `TripForegroundService.java`: 189/189 braces, 859/859 parens.
- Real Python test (`test_instruction_read_radius.py`): 200m out (within
  the old 500m radius, outside the new 50m one) correctly does NOT
  fire; 20m out correctly fires with the delivery instruction included;
  already-shown-for-this-stop correctly does not re-fire even standing
  right on top of it.

## 11. Success criteria for §10

- [x] Root cause / existing-code mapping confirmed for both parts of #4
      before writing anything
- [x] Shared `startAcknowledgeReminder` primitive added, paired
      correctly with the persistent overlay's own lifecycle (tap OR
      programmatic clear both stop it)
- [x] Urgent customer messages: overlay + repeat reminder added
- [x] Approach instructions: radius narrowed to the requested 50m via a
      new, separate constant; repeat reminder added
- [x] "Including generic instructions" confirmed already satisfied by
      existing capture logic - no change needed for that clause
- [x] Real Python test covering the 50m gating (too far / within range /
      already-shown) - all three cases passed
- [ ] Driver confirms in real use: a customer message actually keeps
      re-announcing every 30s until tapped; approaching a dropoff within
      50m (not 500m) triggers the instruction read; the overlay's tap
      target correctly stops the repeat both times
- [ ] Driver sign-off.

## 12. #17 resolved (2026-09-03) - see §3/§4/§5's own inline updates

Driver confirmed reading (b) - see §3's struck-through entry for the
full writeup. No separate section here; the resolution is documented
directly at each place #17 was originally tracked, per this PRD's own
convention for the other two open questions.

## 13. #26 resolved (2026-09-03): Trip History time-detail + Address Book rate/score stats

Driver named both remaining screens (Trip History, Address Book) and,
while answering, raised a real accuracy concern: "I want to see all
details of where the time went as some don't look accurate." Asked a
direct follow-up (not guessed) - was this on stacked/batch-order trips
(where a known, already-documented bug exists,
`docs/deadhead_stacked_order_baseline/PRD.md` Part 2B) or single-order
trips (which would need fresh root-causing)? Driver answered "both /
not sure."

### 13.1 Design decision: give full detail rather than guess at a fix

Given "both/not sure," guessing which specific number is wrong (and for
which reason) would risk exactly what this PRD's own P4 premortem
warns against - building the wrong thing. Instead, implemented the
literal ask ("I want to see all details") in a way that also serves as
the diagnostic tool needed to eventually pin down which case is real:

1. **Raw phase clock-times added alongside the existing durations.**
   `_build_trip_summary_dict` now also returns `phase_timestamps`
   (trip start, pickup arrival/departure, dropoff arrival, walking
   confirmed, trip end - each included only if actually captured, same
   "omit rather than guess" rule the existing `phase_breakdown` already
   follows). `TripHistoryActivity` shows these as real clock times
   ("Arrived at pickup: 2:03:14 PM") under a new "Full time detail"
   block, so a duration that looks wrong can actually be checked against
   the two real moments it's computed from, instead of being taken on
   faith.
2. **Stacked-order trips are now flagged, not silently trusted.** New
   `job_count` (count of `offer_distance_accuracy` rows for the trip -
   already one-row-per-job since Part 2A of the deadhead PRD) is
   returned alongside `phase_timestamps`. When `job_count > 1`, the
   "Where The Time Went" section now shows an explicit warning that the
   breakdown may mix timestamps from different jobs, linking the
   already-known Part 2B limitation directly to the trip it actually
   affects, rather than leaving every driver to independently rediscover
   this from a PRD file. This directly serves both halves of "both/not
   sure": for a flagged (stacked) trip, the driver now knows why a
   number might be off; for an unflagged (single-order) trip, a real
   accuracy problem can now be pinpointed by looking at the actual raw
   times side by side with the driver's own memory of the delivery -
   which is real evidence this PRD's own discipline requires before
   attempting a fix.

**Not attempted here, and explicitly not guessed at**: fixing the
underlying Part 2B stacked-order linkage bug itself. That remains
blocked on the same real evidence `docs/deadhead_stacked_order_
baseline/PRD.md` §7.6.3 already asks for (a real stacked-order dropoff
screenshot) - this pass makes the problem visible and diagnosable, not
solved.

### 13.2 Address Book: avg $/km, avg $/hr, avg Smart Score + stdev

Driver asked for these three specifically. Added to `get_address_book()`
per restaurant, reading from `offer_outcomes` (the same table
`get_restaurant_visit_history` and the Rejected Offers Report's own
`rate_comparison` already use):

- **Scope decision, disclosed rather than assumed**: all three new
  fields are computed across ANY offer outcome (accepted, declined,
  timed out), not accepted-only - matching `get_restaurant_visit_history`'s
  own already-shipped definition of "visit" for this exact restaurant
  grouping, rather than inventing a second, differently-scoped meaning
  of "restaurant average" elsewhere in the same screen. (The existing
  wait-time/deadhead fields on this same screen stay accepted-only, since
  those can only ever be measured for a completed delivery - a real,
  structural difference, not an inconsistency.)
- $/km and $/hr reuse the same exclusion rules the Rejected Offers
  Report's `rate_comparison` already established: missing payout,
  zero/missing distance (would divide by zero), missing hourly_rate -
  each metric's sample count is independent, so a restaurant with a
  zero-distance outlier row still gets a correct $/hr and Smart Score
  average, just an excluded $/km one.
- New shared `_sample_stdev()` helper (module-level, next to
  `haversine_meters`) - `get_restaurant_visit_history`'s existing inline
  stdev calculation was refactored to use it too, rather than having two
  copies of the same formula in the same file.

### 13.3 Verification

Same disclosed limitation as every Java-side change in this repo - no
Android SDK/emulator/device, code review plus brace/paren balance for
Java; real, runnable Python tests for the pure-Python logic.

- `drive_monitor.py` recompiles cleanly.
- `TripHistoryActivity.java` brace/paren balance: 144/144 braces,
  921/921 parens.
- Real Python test (`test_address_book_rates_and_time_detail.py`, 3
  cases, all passed): known $/km/$/hr/Smart-Score averages computed
  correctly with a zero-distance row correctly excluded from $/km only
  (not the other two metrics) and a test-data row excluded from all
  three; a restaurant with zero `offer_outcomes` rows returns `None`
  fields rather than crashing or fabricating a number;
  `get_restaurant_visit_history` re-verified unaffected by the
  `_sample_stdev` refactor.
- Real Python test (`test_trip_summary_job_count_phase_timestamps.py`,
  3 cases, all passed): a single-order trip returns `job_count: 1` and
  all 6 raw phase timestamps; a synthetic 2-job trip returns
  `job_count: 2`; an older trip with no phase capture points returns
  only `trip_start_ts`/`trip_end_ts` (not fabricated intermediate
  values) and `job_count: 0`.
- Re-ran every existing scratchpad test touching the changed functions
  (`test_restaurant_visit_history.py`, `test_feedback_dialog_phase_
  timings.py`, `test_rate_comparison.py`, `test_hotspot_or_home.py`,
  and the full existing suite) - no regressions. One pre-existing,
  already-stale scratch test
  (`test_dropoff_instruction_wiring.py`, calling `_check_
  approach_instruction`'s OLD pre-#4-fix signature) fails, but that
  predates this pass entirely (the #4 fix's own real test,
  `test_instruction_read_radius.py`, already covers the current
  signature and passes) - not a regression from this change.

## 14. Success criteria for §13

- [x] Driver named both screens (Trip History, Address Book) and
      clarified the accuracy concern applies to both stacked and
      single-order trips ("both/not sure")
- [x] Raw phase clock-times (`phase_timestamps`) added, shown as a new
      "Full time detail" block in Trip History
- [x] Stacked-order trips (`job_count > 1`) now show an explicit warning
      linking to the known, already-documented Part 2B limitation
      instead of silently presenting a possibly-mixed number
- [x] Address Book: avg $/km, avg $/hr, avg Smart Score + stdev added
      per restaurant, with disclosed scope decision (any outcome, not
      accepted-only) and reused exclusion rules
- [x] Shared `_sample_stdev()` helper extracted, `get_restaurant_visit_
      history` refactored to use it (no duplicate formula)
- [x] Real Python tests written and run for both changes - no
      regressions in the existing suite
- [ ] Driver confirms in real use: the new "Full time detail" block
      helps identify which specific number(s) looked wrong (this is the
      real next step toward actually fixing whatever's inaccurate, not
      the fix itself)
- [ ] Driver sign-off.

## 15. #21 partially addressed (2026-09-03): hardened bounds-matching without a confirmed bug

Driver had no specific diagnostic log for #21 and, when asked directly,
reframed the goal rather than confirming a specific failure: "I'm not
sure. I just want to collect all data to build the smart score engine."
This PRD's own discipline (§7 P4, "real evidence before code") means a
specific unconfirmed bug can't be guessed at - but the driver's actual
goal pointed at something concrete and already named in the existing
code, not a guess.

### 15.1 What was found before touching anything

Read `DasherAccessibilityService.java`'s full accept/decline detection
chain (further along than the original §4/#21 triage description
suggested - substantial real-diagnostic-log-driven work already
happened here, predating this backlog, confirmed via `git log`):

1. Direct click-text match (`equalsIgnoreCase("Decline")`) - real
   evidence already on file (a deliberate test: 2 genuine declines, 0
   click events captured) shows this likely never fires for Dasher's
   own buttons at all.
2. **The real mechanism**: `scanAndRecordAcceptDeclineNodeBounds()`
   records the Accept/Decline buttons' exact screen bounds when an
   offer appears; `checkNodeBoundsMatch()` matches ANY later
   accessibility event landing on those bounds, gated by
   `NODE_MATCH_MIN_DELAY_MS` to rule out focus-on-load false positives.
3. **A safety net**: if the offer screen disappears with neither button
   detected, `handleOfferResult` schedules `record_offer_timeout` after
   a grace period - so a decline that slips past #2 doesn't vanish with
   zero record, it's just recorded as `OUTCOME: Timed out` instead of
   `OUTCOME: Declined`.

The gap: #2's own comment already named the exact risk -
"exact node-bounds equality could miss a decline... or a bounds-shift
edge case" - `checkNodeBoundsMatch` compared bounds with `Rect.equals()`,
byte-exact. And `recalculate_personal_calibration`'s own docstring
confirms the consequence is real, not hypothetical: "Timeouts are
deliberately excluded... not a real preference signal the way an
active decline is." So a decline that fell into the timeout bucket due
to a pixel-level bounds shift wouldn't just be mislabeled - it would be
silently EXCLUDED from the exact learning loop the driver said they
care about.

### 15.2 Fix: tolerant bounds matching, not exact equality

New `NODE_MATCH_BOUNDS_TOLERANCE_PX = 24` and `boundsRoughlyMatch(Rect
a, Rect b)` - requires every edge (left/top/right/bottom) to be within
24px of the recorded bounds, not byte-identical. Replaces both
`eventBounds.equals(acceptNodeBounds)` and
`eventBounds.equals(declineNodeBounds)` in `checkNodeBoundsMatch`.
Deliberately still requires ALL FOUR edges close (not just an overlap
or a contains-check) so Accept and Decline - always separate,
non-adjacent buttons - can't be confused with each other at this
tolerance.

**Honestly scoped, not oversold**: this is a defensive hardening of an
already-named risk, not a confirmed bug fix. Whether a real decline was
ever actually being lost in practice remains unconfirmed - no diagnostic
log or device evidence either way, same disclosed limitation as every
other Java-only change in this repo. If the driver notices anything
still missing after this ships, that's real evidence worth reopening
this with, not a sign the hardening didn't work.

**Not attempted**: reclassifying already-recorded `timed_out` rows that
might actually have been real declines - no reliable way to tell which
historical rows were affected, so no backfill was attempted (same
reasoning this PRD's own `docs/deadhead_stacked_order_baseline/PRD.md`
already used for a similar backfill question).

### 15.3 Verification

Same disclosed limitation as every Java-side change in this repo - no
Android SDK/emulator/device, code review plus brace/paren balance.
`android.graphics.Rect` has no pure-Python or plain-Java equivalent
reachable in this sandbox, so `boundsRoughlyMatch` itself couldn't be
unit-tested here (same class of limitation as every other Android-API-
dependent method in this file).

- `DasherAccessibilityService.java` brace/paren balance: 152/152 braces,
  556/556 parens.
- Confirmed both call sites (`acceptNodeBounds`/`declineNodeBounds`
  checks) updated consistently - no stray `.equals()` left behind.
- Confirmed the `else if` structure is preserved (an event can still
  only ever match Accept OR Decline, never attempt to process as both).

## 16. Success criteria for §15

- [x] Driver's actual goal clarified directly ("collect all data") when
      no specific diagnostic log was available, rather than guessing at
      an unconfirmed bug
- [x] Full accept/decline detection chain read end-to-end before
      touching anything - confirmed it's already a 3-layer system with
      real diagnostic-log-driven history, not the simpler mechanism the
      original triage description implied
- [x] The exact, already-named risk (byte-exact bounds equality) fixed
      with a disclosed, bounded tolerance - not a broader rewrite
- [x] Confirmed via `recalculate_personal_calibration`'s own docstring
      that a decline misclassified as a timeout is genuinely excluded
      from calibration learning, not just cosmetically mislabeled -
      real justification for this being worth fixing without a log
- [x] Explicitly NOT framed as a confirmed bug fix - honestly scoped as
      a hardening of a named risk, unconfirmed either way without real
      evidence
- [ ] Driver confirms in real use (or via a future diagnostic log) that
      declined offers show up correctly, ideally after a screen re-render
      delay that would previously have caused a mismatch
- [ ] Driver sign-off.

## 17. #22 part (a) implemented (2026-09-03): alert when a critical permission is already off at monitoring start

Driver's literal complaint: "Accessibility access seemed to be turned
off before I tried to start - does the log detect this?" Confirmed by
tracing the exact code path, not assumed: it did NOT.

### 17.1 Root cause, confirmed from the code as written

`checkAndLogPermissions(boolean forceLog)` has exactly one
`forceLog=true` caller in the whole file -
`startTracking()`'s very first line, BEFORE `monitoringActive = true`
is set on the next line. The existing alert block (`raisePermission
RevokedAlert`, added for a different, earlier PRD) has two conditions
that both silently defeat it at this exact call site:

1. It's gated on `if (monitoringActive)` - still `false` here, since
   `checkAndLogPermissions(true)` runs one line BEFORE
   `monitoringActive = true`.
2. Each permission's check requires a TRUE->FALSE transition
   (`lastLoggedX != null && lastLoggedX && !hasX`) - on the very first
   call in a fresh process, `lastLoggedX` is always `null`, so this
   condition can never be true regardless of (1).

Net effect: a permission (accessibility or any of the other 3 critical
ones) that's already off the moment monitoring starts was completely
silent - exactly the driver's own complaint, now root-caused rather
than just reproduced.

### 17.2 Fix

New block in `checkAndLogPermissions`, gated on `forceLog` directly
(not `lastLoggedX == null`, which would wrongly stay silent on a
SECOND `startTracking()` within the same process if a permission got
revoked while monitoring was off between two start/stop cycles) -
checks each of the 4 critical permissions' CURRENT state and fires the
same `raisePermissionRevokedAlert` mechanism (same notification
channel, sound, vibration, diagnostic log line) immediately if any are
off.

`raisePermissionRevokedAlert` gained a third `alreadyOffAtStart`
parameter (existing 2-arg calls unchanged via an overload) - "X turned
off" would be misleading wording for a permission that was never
granted to begin with this session, so the notification title and log
line read "X already off" instead for this path.

**Scope decision, per the driver's own explicit choice**: part (b)
(correlating the status dot's actual visible duration with
voice-announcement timing) was NOT built - asked directly via
AskUserQuestion, driver confirmed part (a) alone addresses what they
actually ran into, and declined the speculative instrumentation for a
one-off report.

### 17.3 Verification

Same disclosed limitation as every Java-side change in this repo - no
Android SDK/emulator/device, code review plus brace/paren balance.

- `TripForegroundService.java` brace/paren balance: 200/200 braces,
  897/897 parens.
- Confirmed all 8 pre-existing `raisePermissionRevokedAlert` call sites
  still compile against the 2-arg overload (unchanged); the 4 new call
  sites use the 3-arg form with `alreadyOffAtStart=true`.
- Confirmed `forceLog=true` has exactly one caller in the file
  (`startTracking()`), so the new block can't accidentally fire during
  the periodic heartbeat (`forceLog=false`).
- Confirmed the new block sits alongside, not inside, the existing
  `if (monitoringActive)` block - both run independently off the same
  computed `hasX` values, no duplicate alert on the very first
  heartbeat right after start (that block still requires a real
  `lastLoggedX != null` transition, which can't be true yet either).

## 18. Success criteria for §17

- [x] Root cause traced to the exact two conditions that both defeat
      the existing alert at the `forceLog=true` call site
- [x] New already-off-at-start alert added for all 4 critical
      permissions, reusing the existing alert mechanism with corrected
      wording
- [x] `raisePermissionRevokedAlert` overload added, all existing call
      sites unaffected
- [x] Part (b) explicitly asked about and declined by the driver, not
      silently skipped or silently built
- [x] Brace/paren balance re-verified after the change
- [ ] Driver confirms in real use: starting monitoring with
      accessibility (or another critical permission) already off now
      raises an immediate, correctly-worded alert
- [ ] Driver sign-off.

## 19. #16 partially addressed (2026-09-03): hardened an unguarded WindowManager.addView() in showStatusDot

No diagnostic log available for #16 ("the status dot doesn't always
appear"), which this PRD's own §4 entry already said was needed before
attempting anything. Rather than leave this fully blocked, read
`OverlayHelper.showStatusDot()` end-to-end looking for a real,
already-disclosed-adjacent risk to harden - same approach already used
successfully for #21.

### 19.1 What was found

`showStatusDot()`'s `windowManager.addView(dot, params)` call was
completely unguarded - no try/catch anywhere in the method. This
matters for two compounding reasons, both confirmed by reading the
actual code rather than assumed:

1. **`WindowManager.addView()` is a real, documented source of runtime
   exceptions** (`BadTokenException` and other `RuntimeException`s,
   under various OS/OEM conditions) - this app already has established,
   explicit reasoning for guarding exactly this class of risk elsewhere
   (`TripForegroundService`'s GPS-tick handler wraps its own
   overlay-touching work specifically because "real Java-side work...
   needs to catch more than PyException alone to avoid silently
   crashing the always-on foreground service"), just never applied to
   `OverlayHelper`'s own internal `addView` calls.
2. **Several of `refreshStatusDot()`'s own call sites in
   `TripForegroundService` have no surrounding try/catch of their own**
   (confirmed by reading all 8 call sites) - e.g. `stopTracking()` calls
   `refreshStatusDot()` several lines BEFORE its own watchdog-
   cancellation calls, with nothing between them to catch an exception.
   An unguarded `addView` failure here wouldn't just mean "the dot
   doesn't show" - it could silently abort whatever the caller was
   doing next.

**Why the status dot specifically, not this app's other overlays**:
`refreshStatusDot()` fires on nearly every GPS tick - far more often
than any other overlay in this app (offer badge, navigation icon, etc,
each tied to a much rarer event). Far more real-world exposure to any
transient `addView` failure, a plausible reason this specific overlay
would be the one reported as intermittent even if the same latent risk
technically exists in every other overlay method in the same file too
(confirmed by grep - none of them guard their own `addView` calls
either, but none were reported as flaky, consistent with this exposure
theory).

### 19.2 Fix

Wrapped `showStatusDot()`'s `addView` call in `try/catch
(RuntimeException)`, logging via `FallbackLogger.log()` (the
established pattern this exact class of static, engine-less helper
already uses elsewhere in this codebase - `NavigationHelper`,
`VoiceAnnouncer`, etc. all use it identically) rather than crashing.
The fix lives at the SOURCE (inside `OverlayHelper`), so it protects
every one of `refreshStatusDot()`'s call sites uniformly - no need to
also patch each individual call site in `TripForegroundService`.

**Deliberately NOT applied to this file's other overlay methods**
(navigation icon, message bubble, hotspot-or-home icon, etc.) even
though the identical unguarded pattern exists in all of them - scoped
tightly to what #16 actually asked about (the status dot specifically),
per this session's repeated "don't expand scope" discipline. Flagged
here as a real, disclosed follow-up opportunity if the driver wants the
same hardening applied everywhere, not silently done or silently
ignored.

**Honestly scoped, not oversold**: same disclosure as #21's hardening -
this addresses a real, plausible risk class, but no diagnostic log or
device evidence ever confirmed THIS was the driver's actual cause. If
the dot still goes missing after this ships, that's real evidence
worth reopening with (and would rule this specific cause out, narrowing
toward "OverlayHelper never got called" or an OS-level render failure -
the two possibilities this PRD's own original entry named).

### 19.3 Verification

Same disclosed limitation as every Java-side change in this repo - no
Android SDK/emulator/device.

- `OverlayHelper.java` brace/paren balance: 79/79 braces, 339/339
  parens.
- Confirmed `FallbackLogger.log(Context, String, String)`'s exact
  signature matches the call added here.
- Confirmed `statusDotView` is only assigned AFTER `addView` succeeds
  (inside the try block) - a failed `addView` leaves it at whatever
  `removeStatusDot()` already set it to (`null`), no stale-reference
  risk.
- Confirmed the early `return` on catch correctly skips the
  animation-setup code below it (pointless to animate a view that was
  never actually added to the window).
- Confirmed via grep that all `windowManager.addView(...)` call sites
  in this file were reviewed, not just the status dot's - the other 5
  are the disclosed, not-yet-touched follow-up noted in §19.2.

## 20. Success criteria for §19

- [x] Read `showStatusDot()` end-to-end, found a real, already-
      disclosed-adjacent risk (unguarded `addView`) rather than guessing
      blind
- [x] Fix applied at the source (inside `OverlayHelper`), protecting
      every `refreshStatusDot()` call site without needing to patch each
      one individually
- [x] Uses this codebase's own established `FallbackLogger` pattern for
      a static helper with no direct engine/logDiagnostic access
- [x] Deliberately scoped to the status dot only, not the other overlay
      methods sharing the same latent pattern - disclosed as a follow-up
      opportunity, not silently expanded into
- [x] Explicitly NOT framed as a confirmed bug fix - honestly scoped as
      a hardening of a named risk class, unconfirmed either way without
      real evidence
- [ ] Driver confirms in real use (or via a future diagnostic log) that
      the status dot now appears reliably, or reports it's still
      missing sometimes (which would rule this specific cause out)
- [ ] Driver sign-off.

