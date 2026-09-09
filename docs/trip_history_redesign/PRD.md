# PRD: Trip History redesign (replace the text-dump dialog with real views)

Status: IMPLEMENTED (all §6 boxes checked except on-device confirmation
and driver sign-off, which are never mine to check). Driver answered
all 3 original open questions (§5), and corrected a real, mistaken
assumption in this PRD's first draft about WHEN the feedback prompt
should chain in (§3.4, rewritten below). §3.4 was further revised
during implementation once `showFeedbackDialog` turned out to be a
large, already-working method not worth moving/duplicating -- see its
own note.

## 0. Origin

Driver asked to see a mockup of "the layout for all the data collected
in trip and history." First pass used an unrelated dark/cyan visual
identity and an invented display typeface -- driver said it "looks
nothing like mine," corrected against the app's REAL theme resources
(`Theme.DasherMonitor.TripHistory`: teal, light-teal background, real
`values-night` dark variants -- see the mockup artifact). Driver then
asked to scope the real redesign into a PRD.

First PRD draft asked 3 open questions. Driver answered:
1. **Major delays** -> fold into the Trip Stats card (matches this
   PRD's own recommendation). Resolved, see §3.2.
2. **Done button vs. back gesture for the feedback-chaining trigger**
   -- NOT yet answered; still open, see §5.
3. **Redesign the trip picker too?** -> "design it" -- yes, now IN
   scope. See §3.5 (new).

Driver also corrected a real mistake in the first draft's design: §3.4
originally chained the feedback prompt to the "Stop Monitoring" flow
(`MainActivity.showLastTripSummaryThenPromptFeedback()`), because that
was the only chained summary-then-feedback call site this PRD's
investigation had traced. Driver's correction: "it should come up
after i press complete delivery not stop monitoring." Re-investigated
(§1) and found the ACTUAL "delivery completed" trigger is a separate,
pre-existing mechanism (`TripForegroundService.notifyRateThisDelivery()`)
that today skips any summary entirely and jumps straight to the star-
rating dialog -- a real, different integration point than the one
originally designed against. §3.4 below is rewritten around the
correct trigger.

## 1. Investigation

- `buildTripSummaryBody()` -- the method that builds the giant
  plain-text string shown in a single `AlertDialog.setMessage()` --
  is duplicated, nearly verbatim, in BOTH `TripHistoryActivity.java`
  (line 943) and `MainActivity.java` (line 887). Not previously
  flagged anywhere in this repo's docs.
- `com.google.android.material:material:1.12.0` is ALREADY a Gradle
  dependency -- `MaterialCardView` is available with ZERO new
  dependency. No `RecyclerView` exists anywhere in this codebase today;
  every list-like screen uses `ScrollView > LinearLayout` with static
  children -- this PRD's designs (including the now-in-scope trip list,
  §3.5) follow that same established pattern rather than introducing a
  new one.
- `Theme.DasherMonitor.TripHistory` (teal `#00897B` / `#00695C`, light
  tint `#E0F2F1`, real `values-night` dark variants) already exists and
  is already applied to `TripHistoryActivity` -- reused as-is, not
  redesigned.
- **There are TWO, not one, existing "show the trip summary" call
  sites that matter here -- tracing both was necessary to place the
  feedback-chaining logic correctly:**
  1. `MainActivity.showLastTripSummaryThenPromptFeedback()`, fired when
     the driver taps "Stop Monitoring." Shows `get_last_trip_summary`
     via `buildTripSummaryBody`, and its `AlertDialog` "OK" button THEN
     calls `showFeedbackDialog(tripId)` -- but only if
     `tripId >= 0 && isDasherTrip`. This is a **driver-initiated,
     end-of-shift action** -- it can fire long after the actual last
     delivery completed, and for a trip that (per the finding below)
     may already have been rated.
  2. `TripForegroundService.notifyRateThisDelivery()` (lines
     1066-1162, `docs/feedback_page_direct/PRD.md`), fired automatically
     the instant a real delivery completes (called from two places in
     `TripForegroundService`: natural GPS-driven completion, and the
     auto-pause-adjacent completion path). This is the ACTUAL "I just
     finished a delivery" moment. Today it does NOT show any summary at
     all -- it uses a Background-Activity-Launch workaround (an overlay
     tap-through, or a full-screen-intent notification as fallback,
     mirroring `AppNotificationListenerService.launchDasherApp()`'s
     already-proven mechanism) to bring `MainActivity` to the
     foreground with `auto_show_feedback_trip_id` set, which
     `MainActivity.onCreate()` reads and calls `showFeedbackDialog()`
     DIRECTLY -- no trip summary is ever shown on this path today.
  Driver's correction (§0) means mechanism 2, not mechanism 1, is where
  `TripDetailActivity` (and its feedback-chaining) belongs. See §3.4.

## 2. Definition of "functional"

- [x] Completing a real delivery (`notifyRateThisDelivery()`'s existing
      trigger conditions, unchanged) opens `TripDetailActivity` --
      themed `Theme.DasherMonitor.TripHistory` -- showing that trip's
      full detail, with a clear primary action that then shows the
      feedback dialog (exact mechanism per §5 #2's answer) before
      returning to normal use.
- [x] "Stop Monitoring" still shows the last trip's detail via the same
      `TripDetailActivity`, for reference -- but does NOT re-trigger the
      feedback prompt (see §3.4's reasoning: that trip was already
      prompted for at the moment it actually completed, or never
      applies because it wasn't a Dasher trip).
- [x] Tapping a trip in the (now redesigned, §3.5) trip list also opens
      `TripDetailActivity` for that trip, no feedback prompt.
- [x] Every field `buildTripSummaryBody()` currently renders is shown,
      organized into the card sections the corrected mockup
      demonstrates: Original Offer Assessment, Pickup Address, Store
      Wait Timer (when present), Full Time Detail, Where The Time Went,
      Deadline, Trip Stats (now including Major Delays, per driver's
      answer), Safety Events, Stops, Customer Instructions, Your Rating
      -- each card omitted entirely when its underlying data is
      null/empty.
- [x] The stacked-order warning (`job_count > 1`) still shows, in the
      Where The Time Went card.
- [x] The trip picker (`showTripHistoryFiltered()`) becomes a real
      `TripListActivity` screen -- filter chips (All/Dasher/General) +
      a scrollable list of trip rows (date, distance, mode, composite
      score, colored per the real Smart Score-adjacent teal accent) --
      matching the corrected mockup's "01" panel, tapping a row opens
      `TripDetailActivity`.
- [x] The three duplicated/scattered pieces this replaces are deleted,
      not left as dead code: both `buildTripSummaryBody()` copies, and
      `showTripHistoryFiltered()`'s `AlertDialog.setItems()` body.

Non-goals (explicit):
- The other five Trip History report dialogs (Rejected Offers Report,
  Pay Trend, Address Book, Weather vs. Pay, Restaurant Visit History)
  are NOT touched -- none was flagged as a problem, none was part of
  the driver's "design it" answer (which was specifically about the
  trip picker). A separate PRD each, if ever wanted.
- No new library (`RecyclerView`, a charting package, etc.).
- No change to what data is collected or how it's computed
  (`drive_monitor.py` untouched) -- presentation-layer only.
- `notifyRateThisDelivery()`'s own trigger conditions (DASHER mode
  only, valid `trip_id`, the BAL-launch/overlay/full-screen-intent
  fallback chain) are NOT changed -- only WHAT it launches changes
  (`TripDetailActivity` instead of `MainActivity` going straight to the
  feedback dialog).

## 3. Design

### 3.1 New files

- `app/src/main/res/layout/activity_trip_detail.xml` -- `ScrollView >
  LinearLayout` of `MaterialCardView` sections (matching
  `activity_trip_history.xml`'s established shape), one fixed
  `android:id` per bound field.
- `app/src/main/java/com/drivingefficiency/app/TripDetailActivity.java`
  -- reads a required `trip_id` int extra and an optional
  `EXTRA_PROMPT_FEEDBACK_ON_CLOSE` boolean extra, calls
  `get_trip_summary_by_id`, populates every card. Themed
  `Theme.DasherMonitor.TripHistory`.
- `app/src/main/res/layout/activity_trip_list.xml` +
  `TripListActivity.java` -- see §3.5.

### 3.2 Card-by-card field mapping

| Card | Source fields |
|---|---|
| Original Offer Assessment | `offer_score_snapshot.{verdict_sentence, final_score, label, base_rate_per_km, hourly_rate, deadhead_km, restaurant_wait_minutes, traffic_risk, traffic_ratio, weather}` + `phase_breakdown.driving_to_pickup_seconds` (deadhead time) |
| Pickup Address | `pickup_address` |
| Store Wait Timer | `store_wait_over_grace_seconds` (card omitted when null) |
| Full Time Detail | `phase_timestamps.*` |
| Where The Time Went | `phase_breakdown.*` + `job_count` (stacked-order warning) + "as % of total trip time" |
| Deadline | `deadline_comparison.{deadline_text, was_late, seconds_relative_to_deadline}` |
| Trip Stats | `distance_km, time_efficiency_score, safety_score, geofence_hit_ratio, composite_score, fuel_cost_estimate`, **plus** `delay_count, total_delay_seconds` (folded in here per driver's answer -- own row, "Major delays: N (M min)", omitted when `delay_count == 0`, not a separate card) |
| Safety Events | `event_counts` |
| Stops | `stops[]` |
| Customer Instructions | `instructions[]` |
| Your Rating | `feedback_rating, feedback_notes` |

### 3.3 Score label colors

The score pill in Original Offer Assessment reuses `OverlayHelper.
baseColorForScoreLabel(label)` / `isPoorScoreLabel(label)` /
`stripedDrawable(...)` directly -- not reimplemented.

### 3.4 Feedback chaining -- corrected per driver's answer

**The trigger is `TripForegroundService.notifyRateThisDelivery()`, not
"Stop Monitoring."** That method's own existing gating (DASHER mode
only, valid `trip_id`) is untouched -- only its TARGET changes:

- The `Intent` it builds currently targets `MainActivity` with
  `auto_show_feedback_trip_id`. Change the target to
  `TripDetailActivity`, with `trip_id` and
  `EXTRA_PROMPT_FEEDBACK_ON_CLOSE = true` set. The existing BAL
  -exemption launch sequence (overlay-tap-through attempt, direct
  `startActivity` attempt, full-screen-intent notification fallback) is
  otherwise UNCHANGED -- this PRD only changes which Activity class
  that already-proven mechanism opens.
- `TripDetailActivity` shows the full trip detail (the driver's actual
  "what just happened" moment -- this is now the primary screen a
  driver sees right after completing a delivery, not a secondary
  reference view). A `MaterialButton` at the bottom of the scroll
  content is the one action that closes the screen: labeled "Rate This
  Delivery" when `EXTRA_PROMPT_FEEDBACK_ON_CLOSE` is true, "Done"
  otherwise. The system back button/gesture always just closes the
  screen with no feedback prompt, regardless of the extra -- resolved
  per §5 #2.
- **Revised during implementation, lower-risk than the original plan**:
  `MainActivity.showFeedbackDialog(int tripId)` turns out to be a large,
  already-working method (parking-gap context lookup, 5 quick-tap
  category rows, notes, save logic) -- moving or duplicating it into
  `TripDetailActivity` would be real, avoidable risk for no benefit.
  Instead, `TripDetailActivity`'s "Rate This Delivery" button
  `startActivity`s `MainActivity` with the SAME existing
  `auto_show_feedback_trip_id` extra it already reads in `onCreate()`,
  then `finish()`es itself -- reusing that entire mechanism completely
  unchanged, one hop later than before. `MainActivity.onCreate()`'s
  `auto_show_feedback_trip_id` handling is therefore **kept, not
  deleted** -- it's still live, just reached via `TripDetailActivity`'s
  button now instead of directly from `notifyRateThisDelivery()`'s
  launch. (§6's checklist corrected to match.)
  **Disclosed, deliberate non-fix**: `showFeedbackDialog` still
  contains its own small embedded "where the time went" recap (added
  for `docs/feedback_dialog_phase_timings/PRD.md`, back when this was
  the ONLY place that info was ever shown on the completion path) --
  now redundant, since `TripDetailActivity`'s own "Where The Time Went"
  card already showed the same thing one screen earlier. Left as-is
  rather than touched: removing it means editing the same large,
  complex method this revision specifically avoided touching, for a
  minor, low-cost duplication (the driver sees one section twice, not a
  functional problem). A real, small follow-up if ever wanted, not
  assumed in scope here.
- `MainActivity.showLastTripSummaryThenPromptFeedback()` (Stop
  Monitoring) and `TripListActivity`'s row-tap (§3.5) both launch
  `TripDetailActivity` WITHOUT `EXTRA_PROMPT_FEEDBACK_ON_CLOSE` (or
  explicitly `false`) -- pure viewing. Reasoning: by the time either of
  those fires, a real Dasher trip was either already prompted for via
  `notifyRateThisDelivery()` at the moment it actually completed, or
  never gets prompted at all (General mode) -- re-prompting from either
  of these would risk a confusing double-prompt for the same trip.

### 3.5 Trip list redesign (now in scope, driver said "design it")

Replaces `showTripHistoryFiltered()`'s `AlertDialog.setItems()`.
`TripListActivity` (themed `Theme.DasherMonitor.TripHistory`): the
same `ScrollView > LinearLayout` pattern, filter chips (All Trips /
Dasher Only / General Only -- same three options + same client-side
filtering `showTripHistoryFiltered()` already does, just re-rendered
as real `Chip`/`ToggleButton` views instead of a second `AlertDialog`),
and one row per trip (date/time, distance, mode, composite score) --
matching the corrected mockup's "01" panel. Tapping a row launches
`TripDetailActivity` with that `trip_id`, no feedback extra (§3.4).
`get_trip_history()` (Python, unchanged) already returns everything
this needs -- no Python change for this piece either.

## 4. Premortem

- **P1 -- the feedback-chaining rework (§3.4) is gotten wrong a second
  time.** This PRD already had to be corrected once on exactly this
  point. Mitigation: §3.4 is now anchored to the real trigger
  (`notifyRateThisDelivery()`), traced end-to-end including both of its
  call sites in `TripForegroundService`, not assumed from one call site
  alone. The ralph loop's own first move on this box should be
  re-reading `notifyRateThisDelivery()` in full again, not trusting
  this summary.
- **P2 -- a field silently dropped during the reorg.** §3.2's table was
  built by reading the CURRENT `buildTripSummaryBody()` field-by-field.
  The ralph loop's first checklist item should re-diff it against the
  live method before writing any layout XML.
- **P3 -- MaterialCardView under `Theme.AppCompat.DayNight` (not a
  `Theme.MaterialComponents.*` parent) can render with unexpected
  defaults** (wrong corner radius, missing elevation). Needs explicit
  `app:cardCornerRadius`/`app:cardElevation` on every card, not left to
  theme defaults.
- **P4 -- no Android SDK/emulator in this environment.** A change this
  size (3 new files, 2 deleted methods, `TripForegroundService`'s
  BAL-launch intent retargeted) has real risk of a subtle mistake only
  a build/device could catch. Verification stays code review + XML
  well-formedness + a manual `findViewById`-to-layout cross-check, same
  disclosed limitation as every other Java/XML PRD here -- but flagged
  as elevated risk for THIS PRD specifically given the BAL-launch retarget
  (§3.4) is exactly the kind of Android-version/OEM-sensitive mechanism
  this repo has been burned by before (`AppNotificationListenerService.
  launchDasherApp`'s own class docs).
- **P5 -- scope creep into the other 5 report dialogs.** Still
  explicitly out of scope (§2) even though `TripListActivity` (§3.5)
  makes them visually adjacent to more redesigned screens now than the
  first draft had.

## 5. Open questions

1. ~~Major delays: fold into Trip Stats or own card?~~ **RESOLVED --
   fold into Trip Stats**, per driver's answer. See §3.2.
2. ~~The primary action in `TripDetailActivity` that triggers the
   feedback dialog: explicit button, or firing on back-gesture?~~
   **RESOLVED -- an explicit button.** ("Rate This Delivery" when
   `EXTRA_PROMPT_FEEDBACK_ON_CLOSE` is true, "Done" otherwise -- both
   just `finish()` after; only the feedback-true case shows the dialog
   first.) The system back button/gesture just closes the screen with
   no feedback prompt either way, matching how dismissing the current
   `AlertDialog` by tapping outside it already skips the OK-only
   callback.
3. ~~Redesign the trip picker too?~~ **RESOLVED -- yes.** See §3.5.

## 6. Success criteria (ralph-loop checklist)

- [x] §3.2's field-mapping table re-verified against the CURRENT
      `buildTripSummaryBody()` before any layout XML is written (P2)
- [x] `notifyRateThisDelivery()` re-read in full again before touching
      its intent-building code (P1)
- [x] `activity_trip_detail.xml` + `TripDetailActivity.java` created
      per §3.1/§3.2, `Theme.DasherMonitor.TripHistory` applied, explicit
      `app:cardCornerRadius`/`app:cardElevation` on every card (P3)
- [x] Score pill reuses `OverlayHelper.baseColorForScoreLabel`/
      `isPoorScoreLabel`/`stripedDrawable` directly
- [x] §3.4 implemented: `notifyRateThisDelivery()`'s intent retargeted
      to `TripDetailActivity` with `EXTRA_PROMPT_FEEDBACK_ON_CLOSE=true`;
      `TripDetailActivity`'s "Rate This Delivery" button relaunches
      `MainActivity` with the existing `auto_show_feedback_trip_id`
      extra (kept, not deleted -- see §3.4's revised design)
- [x] `MainActivity.showLastTripSummaryThenPromptFeedback()` updated to
      launch `TripDetailActivity` WITHOUT the feedback extra
- [x] `activity_trip_list.xml` + `TripListActivity.java` created per
      §3.5; `showTripHistoryFiltered()`'s `AlertDialog` body replaced
      with a launch of this Activity
- [x] Both existing `buildTripSummaryBody()` copies deleted
- [x] `AndroidManifest.xml`: `TripDetailActivity` and `TripListActivity`
      declared with `Theme.DasherMonitor.TripHistory`
- [x] XML well-formedness check on both new layouts; every
      `findViewById` cross-checked against a real id (P4)
- [ ] Driver confirms on-device: completing a real delivery opens the
      new detail screen directly (not the old star-rating-only dialog),
      rating from there works and doesn't double-prompt later at Stop
      Monitoring; the trip list filters and opens the right trip;
      General-mode trips never show a rating prompt
- [ ] Driver sign-off

## 7. Driver-requested (2026-09-09): diagnostic logging for TripHistoryActivity's own silent failures

Follow-on to `docs/screen_recording/PRD.md` §19's field-test-checklist
logging audit -- the driver asked whether the same "does the log cover
everything" question applied elsewhere too. It didn't: `TripHistoryActivity`
(this screen's own hub -- distance accuracy, hourly rate accuracy,
address book, acceptance stats, personal calibration, rejected offers
report, pay trend, weather vs. pay) had 16 failure paths, every single
one Toast-only. A Toast disappears once dismissed; nothing about why a
screen failed to load was ever recoverable afterward from this app's
own log.

Fixed by adding a `logDiagnostic(String)` wrapper (this Activity's own
single-category convenience form -- everything here logs under
`TRIP_HISTORY`) and a call at all 16 existing catch blocks, plus two
data-mutating actions that previously had no trace either way:
`reset_personal_calibration` (now logs on success too, not just
failure) and `set_offer_omitted_from_calibration` (logs which specific
offer's toggle failed to persist, since a driver toggling several in
one sitting couldn't otherwise tell which one silently didn't save).

Deliberately did NOT add positive-confirmation logging for a normal,
successful screen view (unlike screen_recording's own after-delivery
verification) -- these are read-only stat/history screens a driver may
open repeatedly out of curiosity, not once-per-delivery events; logging
every successful view would be noise, not signal. Only the two
mutating actions above got a success line, since those are real state
changes worth being able to confirm later.

### Verification

- Brace/paren balance confirmed
- `python3 -m py_compile app/src/main/python/drive_monitor.py` --
  unaffected (Python untouched)
- All 17 `catch (` blocks (16 original + the new `logDiagnostic`
  wrapper's own) cross-referenced against 17 real `logDiagnostic(...)`
  call sites (18 total occurrences of the method name, minus its own
  one-line definition)
- HONEST LIMIT: no Android device/emulator available in this
  environment -- these log lines were not observed firing on a real
  failure, only read as correct from the code itself

## 8. Success criteria for §7

- [x] `logDiagnostic(String)` wrapper added
- [x] All 16 pre-existing Toast-only catch blocks now also log
- [x] `reset_personal_calibration` logs both success and failure
- [x] `set_offer_omitted_from_calibration` failure names which offer
- [x] Brace/paren balance and Python compile confirmed
- [ ] Driver confirms: a forced failure on any of these screens (e.g.
      airplane mode mid-load) now shows a real line in the diagnostic
      log, not just a Toast that's already gone
- [ ] Driver sign-off

## 9. Driver-requested (2026-09-09): diagnostic logging for TripDetailActivity and TripListActivity

Same audit as §7, extended to this screen's two sibling Activities
(both created by this same PRD, §3.1/§3.5). Both had zero
`logDiagnostic` calls.

`TripDetailActivity` didn't previously have `engine` as a field at all
(it was a local variable in `onCreate()`, since nothing else needed
it) - promoted to a field so the new `logDiagnostic` wrapper can use
it, same shape as every other Activity in this app. Now logs: no valid
trip id passed in, the requested trip not found, and the summary-load
failure.

`TripListActivity` now logs its one failure path: the trip-history
list load.

### Verification

- Brace/paren balance confirmed on both files
- HONEST LIMIT: no Android device/emulator available - not observed
  firing on a real device.

## 10. Success criteria for §9

- [x] `TripDetailActivity`: no-trip-id, trip-not-found, and load-failure
      all log
- [x] `TripListActivity`: load-failure logs
- [x] `engine` promoted to a field in `TripDetailActivity` (previously
      local-only), no behavior change
- [x] Brace/paren balance confirmed
- [ ] Driver confirms these lines appear in the diagnostic log
- [ ] Driver sign-off.
