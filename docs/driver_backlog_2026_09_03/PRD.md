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

- **#4** ("Force me to acknowledge all dasher related...") - the
  sentence is cut off in the driver's own message. Best guess given
  neighboring items is overlap with #10/#28's "force
  foreground/acknowledge before scoring" theme already implemented, but
  this is a guess, not a confirmed read. **Do not implement anything
  under #4's label until the driver finishes the sentence** - flagged
  here rather than silently assumed, per this session's established
  practice of not resolving a genuinely unclear driver request on its
  own initiative.
- **#17** genuinely could mean two different things: (a) the
  already-implemented RoadWarrior icon (§1/#32) simply not appearing
  that specific time - a bug report, needs a diagnostic log, not new
  code; or (b) a DISTINCT "navigate home with a saved/preset route"
  feature, which does not exist anywhere in this codebase (no home-
  address storage, no route-preloading) - a real, large new feature.
  Tracked as an open question in §4, not assumed either way.
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
- [ ] **#21 - rejected offers not being recorded.** Code exists and
      looks sound (`recordLastOfferOutcome`,
      `DasherAccessibilityService.java:369-391`, triggered on
      accessibility-node-bounds match for the Decline button), but no
      PRD tracks a failure report against it. Possible root cause: exact
      node-bounds equality could miss a decline via a different UI path
      (swipe/back gesture) or a bounds-shift edge case on some device/
      DoorDash-app-version combination. **Action: needs a diagnostic log
      from a session where declines were made, specifically checked for
      `OUTCOME: Declined` lines**, before guessing at a fix - same
      "real evidence before code" discipline as every other bug fix
      this session.
- [ ] **#22 - accessibility off before start; status dot visible <7s,
      possibly interrupted by the notification-read-aloud announcement
      happening almost simultaneously.** Partial answer already
      confirmed: yes, the log WOULD show the accessibility state itself
      - `checkAndLogPermissions(true)` force-logs it at
      `startTracking()` (`TripForegroundService.java:354`, current line
      number after this session's other changes may have shifted
      slightly - re-check when working this item). But there is NO
      alert for "already off at start" (alerts only fire on a
      true->false transition while monitoring is ALREADY active - an
      already-off-at-launch state is currently silent), and nothing
      correlates status-dot visibility duration with voice-announcement
      timing - that specific interaction isn't tracked anywhere.
      **Action: two separable pieces** - (a) add a start-of-monitoring
      accessibility-already-off alert (small, same alert channel as the
      permission-revoked and vibration-alarm infra
      `docs/permission_alert_vibration/`), (b) the dot-duration/
      announcement-timing correlation is a bigger, more speculative ask
      - needs the driver to confirm this is worth building before
      attempting it, since it's diagnostic instrumentation for a
      one-off report, not a confirmed recurring bug.
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
- [ ] **#16 - status dot doesn't always appear.** No dedicated PRD.
      Existing partial mitigation only covers "overlay permission never
      granted" (a notification nudge,
      `TripForegroundService.java:1616-1628`, line number approximate).
      Intermittent disappearance WHILE the permission is actually
      granted isn't tracked anywhere -
      `docs/watchdog_reliability/PRD.md` covers full-service-death
      recovery, a related but different failure mode. **Action: needs a
      diagnostic log from a session where the dot didn't show, to
      distinguish "OverlayHelper never got called" from "it was called
      but the OS didn't render it" (a different, less fixable class of
      problem) before attempting anything** - same evidence-first
      pattern as #21/#22 above.
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

- [ ] **#1 - geo-tagged smart-score database -> most profitable
      locations.** LARGE. No lat/lon on `offer_outcomes` today (only
      `restaurant_name`); geo data currently lives separately in
      `pickup_location_history`. Needs a schema change joining the two,
      plus a genuinely new location-profitability aggregation/analysis,
      plus new UI to present it. `docs/market_relative_score_thresholds/`
      (DRAFT, driver decision still pending) is adjacent (learned score
      thresholds) but doesn't cover this - a separate design pass.
- [ ] **#2 - per-offer omit/include toggle for calibration.**
      SMALL-MEDIUM. `offer_outcomes` collection already exists;
      `is_test_data` exists but is only auto-set by Developer Testing,
      not driver-toggleable per real offer. Needs a UI list (Address
      Book/Trip History-adjacent) + a new driver-settable flag + honoring
      it in `recalculate_personal_calibration`'s existing query.
- [ ] **#5 - suggested hotspot from the last 5 deliveries, weighted by
      offer quantity, with a copy-coordinates button.** MEDIUM.
      `get_pickup_sweet_spot_zone()` already exists
      (`drive_monitor.py:3418-3460` - zone-grid frequency over ALL
      history, shown as plain text in the Address Book dialog). Needs: a
      recency-windowed (last-5) query variant, and a clipboard-copy
      button (no existing clipboard-copy UI for this specific case, but
      RoadWarrior's own clipboard-copy feature is a directly reusable
      pattern to copy, not invent from scratch).
- [ ] **#6 - average $/km for accepted vs. declined offers.** SMALL.
      `payout`/`distance_km` already stored per offer in
      `offer_outcomes`. Needs one new `GROUP BY outcome` aggregate query
      + a UI line. `get_rejected_offers_report`'s existing
      accepted/declined/timed-out comparison structure is a direct
      template to extend, not a new pattern.
- [ ] **#7 - per-restaurant breakdown: last 10 visits with dates, times,
      ratings, average, and standard deviation.** MEDIUM. Builds on
      `get_address_book()` (`drive_monitor.py:4334-4364`), which
      currently returns only aggregates (avg wait, avg deadhead, parking
      difficulty) - not individual visit records or ratings. Needs a new
      per-restaurant visit-history query joining `trips`/`trip_feedback`
      by restaurant name, plus a stdev calculation.
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
- [ ] **#9 - separate Dasher vs. General trips in the report.** SMALL.
      `trips.mode` already exists and is already returned by
      `get_trip_history()` (`drive_monitor.py:3683-3706`) - no
      filter/grouping toggle exists in the UI yet. Pure UI addition.
- [ ] **#14 - surface the traffic ratio as a reported metric.** SMALL.
      `_live_traffic_ratio`/`_get_traffic_risk`
      (`drive_monitor.py:690-767`) already compute this internally and
      feed it into the score as a binary high/low-risk flag, but the raw
      ratio itself is never exposed to the driver anywhere. Expose the
      existing computed value; no new calculation needed.
- [ ] **#17b - "navigate home" with a pre-determined/saved route** (the
      "genuinely new feature" reading of #17 - see §3). If this is what
      the driver meant: LARGE, no existing infrastructure at all (no
      home-address storage, no route-preloading anywhere in the
      codebase). **Blocked on the same open question as #17a** - confirm
      which reading before sizing further or starting design.
- [ ] **#25 - add $/km and $/hr metrics to the smart score
      recommendation itself** (distinct from #6's accepted-vs-declined
      report - this is surfacing it live, in the recommendation UI, not
      just in a historical report). SMALL-MEDIUM. The underlying data
      (payout, distance, and `hourly_rate_actual_vs_estimated`'s
      already-implemented §4.A/§4.B live estimate) already exists;
      needs wiring into whatever view shows the live recommendation.
- [ ] **#26 - improve report formatting for readability.** SIZE
      DEPENDS ON THE OPEN QUESTION IN §3 (which report). Some formatting
      infrastructure already exists (`_format_table`,
      `drive_monitor.py:4162`, used by `export_full_report`) - this
      isn't starting from zero for at least the diagnostic-export case.
      **Blocked on the driver naming which specific report/screen
      before sizing.**
- [ ] **#29 - screen-recording-based tutorial/walkthrough to learn how
      the app works.** MEDIUM-LARGE, no existing infrastructure at all -
      no onboarding/tutorial/walkthrough system exists anywhere in this
      codebase (checked directly, zero hits). Note: `docs/screen_recording/`
      is a DIFFERENT feature entirely (in-trip recording for the
      driver's own review, not a teaching tool) - don't conflate the
      two when scoping this. `DeveloperTestingActivity`'s existing
      simulate-offer buttons could seed a "practice mode" rather than
      building from nothing, worth considering during design.

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

