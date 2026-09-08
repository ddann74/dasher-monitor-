# PRD: Trip History detail redesign (replace the text-dump dialog with real views)

Status: SCOPED, not implemented -- driver asked to scope this into a
PRD, per this repo's usual convention for a genuinely new subsystem
(same "large item" treatment as `location_profitability_map` and
`tutorial_mode` from `docs/driver_backlog_2026_09_03/PRD.md` §6 got).
Not started without driver confirmation on §5's open questions -- same
guardrail those two PRDs followed.

## 0. Origin

Driver asked to see a mockup of "the layout for all the data collected
in trip and history." First pass used an unrelated dark/cyan visual
identity and an invented display typeface -- driver said it "looks
nothing like mine" and asked for it to be identical. Corrected against
this app's REAL theme resources (`res/values/colors.xml`,
`themes.xml`'s `Theme.DasherMonitor.TripHistory`, and the real
`OverlayHelper` Smart Score palette) rather than guessed at a second
time -- see the mockup artifact for the corrected version. Driver then
asked to scope the actual redesign (not just the mockup) into a PRD.

## 1. Investigation

- `buildTripSummaryBody()` -- the method that builds the giant
  plain-text string shown in a single `AlertDialog.setMessage()` --
  is duplicated, nearly verbatim, in BOTH `TripHistoryActivity.java`
  (line 943) and `MainActivity.java` (line 887). Not previously
  flagged anywhere in this repo's docs. A real, confirmed instance of
  the "kept in sync manually" drift risk this codebase has hit before
  (e.g. the Smart Score color duplication fixed in `docs/
  smart_score_quartile_colors/PRD.md`).
- `com.google.android.material:material:1.12.0` is ALREADY a Gradle
  dependency (`app/build.gradle`) -- `MaterialCardView` is available
  with ZERO new dependency. Confirmed via `build.gradle`'s real
  `dependencies` block.
- No `RecyclerView` usage exists anywhere in this codebase today --
  every list-like screen (`activity_trip_history.xml` included) uses
  the same `ScrollView > LinearLayout` pattern with static children.
  The detail screen's design below follows that SAME established
  pattern (a `ScrollView` of stacked `MaterialCardView` sections) rather
  than introducing `RecyclerView`, a real new dependency and a new
  pattern this codebase doesn't otherwise use -- consistent with this
  repo's own preference for reusing established patterns over adding
  new ones without a stated need.
- `Theme.DasherMonitor.TripHistory` (teal `#00897B` / `#00695C`, light
  tint `#E0F2F1`, real `values-night` dark variants) already exists and
  is already applied to `TripHistoryActivity` in the manifest -- the
  new Activity this PRD adds reuses this exact theme, not a new one.
- **The one real integration hazard, found by tracing every call site**:
  `MainActivity.showLastTripSummaryThenPromptFeedback()` (fired right
  after "Stop Monitoring") shows the trip summary, and its `AlertDialog`
  `setPositiveButton("OK", ...)` callback THEN calls `showFeedbackDialog
  (tripId)` -- but only if `tripId >= 0 && isDasherTrip`. Replacing the
  `AlertDialog` with a separate Activity means this sequencing (summary
  dismissed -> feedback dialog appears) has to be re-threaded
  deliberately; see §3.4. This is the one piece of this redesign that
  is NOT simply "move the same text into cards" -- everything else is.

## 2. Definition of "functional"

- [ ] Tapping a trip in the trip picker, or "Last Trip Summary," or the
      auto-shown "Rate this delivery" flow, opens a real `TripDetail
      Activity` (not an `AlertDialog`) themed with `Theme.DasherMonitor.
      TripHistory` (teal app bar, light-teal background, matching every
      other screen already using this theme).
- [ ] Every field `buildTripSummaryBody()` currently renders is shown,
      organized into the same card sections the corrected mockup
      demonstrates: Original Offer Assessment, Pickup Address, Store
      Wait Timer (when present), Full Time Detail, Where The Time Went,
      Deadline, Trip Stats, Safety Events, Stops, Customer Instructions,
      Your Rating -- each card omitted entirely when its underlying data
      is null/empty, same "omit rather than show empty" rule the text
      version already follows.
- [ ] The stacked-order warning (`job_count > 1`) still shows, in the
      Where The Time Went card.
- [ ] The post-"Stop Monitoring" flow still prompts for feedback at the
      right moment for a Dasher-mode trip, matching current behavior
      exactly (see §3.4) -- not silently dropped or delayed to a
      confusing moment.
- [ ] The two existing `buildTripSummaryBody()` copies (`MainActivity`,
      `TripHistoryActivity`) are deleted, not left as dead code --
      both real call sites now launch the same `TripDetailActivity`
      instead.

Non-goals (explicit):
- The trip PICKER (`showTripHistoryFiltered()`'s plain `AlertDialog.
  setItems()` list) is NOT redesigned here -- the corrected mockup
  deliberately leaves it as-is, annotated "not part of the redesign."
  It's a real, working, low-friction native picker; nothing evidenced
  it needs replacing. A future PRD if the driver wants it.
- The other five Trip History report dialogs (Rejected Offers Report,
  Pay Trend, Address Book, Weather vs. Pay, Restaurant Visit History)
  are NOT touched -- each is its own separate `AlertDialog`, not part of
  `buildTripSummaryBody()`, and none was flagged as a problem. A
  separate PRD each, if ever wanted -- not assumed in scope here just
  because the mockup's "more reports" strip showed them for context.
- No new library (`RecyclerView`, a charting package, etc.) -- see §1's
  own reasoning.
- No change to what data is collected or how it's computed
  (`drive_monitor.py` untouched) -- this is a presentation-layer
  redesign only, moving already-correct data into real views.

## 3. Design

### 3.1 New files

- `app/src/main/res/layout/activity_trip_detail.xml` -- `ScrollView >
  LinearLayout` (vertical, matching `activity_trip_history.xml`'s own
  established shape) containing one `MaterialCardView` per section from
  §2, each with a fixed `android:id` per bound field (`view_offer_score`,
  `view_offer_verdict`, etc.) so `TripDetailActivity` can bind by
  `findViewById`, the same explicit style every other Activity in this
  codebase already uses (no data-binding/view-binding library currently
  in this project -- not introduced here either, consistent with §2's
  "no new library" non-goal).
- `app/src/main/java/com/drivingefficiency/app/TripDetailActivity.java`
  -- reads a required `trip_id` int extra, calls
  `engine.callAttr("get_trip_summary_by_id", tripId)`, and populates
  each card's views. Themed `Theme.DasherMonitor.TripHistory` in the
  manifest, same as `TripHistoryActivity`.

### 3.2 Card-by-card field mapping

Directly transcribed from the CURRENT `buildTripSummaryBody()` (both
copies are identical field-for-field, confirmed by diffing them), so
this is a reorganization, not a redesign of what's shown:

| Card | Source fields |
|---|---|
| Original Offer Assessment | `offer_score_snapshot.{verdict_sentence, final_score, label, base_rate_per_km, hourly_rate, deadhead_km, restaurant_wait_minutes, traffic_risk, traffic_ratio, weather}` + `phase_breakdown.driving_to_pickup_seconds` (deadhead time) |
| Pickup Address | `pickup_address` |
| Store Wait Timer | `store_wait_over_grace_seconds` (card omitted entirely when null -- most trips today, until the store-wait-timer feature accumulates real data) |
| Full Time Detail | `phase_timestamps.*` |
| Where The Time Went | `phase_breakdown.*` + `job_count` (stacked-order warning) + the "as % of total trip time" line already computed from `start_time`/`end_time` |
| Deadline | `deadline_comparison.{deadline_text, was_late, seconds_relative_to_deadline}` |
| Trip Stats | `distance_km, time_efficiency_score, safety_score, geofence_hit_ratio, composite_score, fuel_cost_estimate` |
| Safety Events | `event_counts` |
| Major delays | `delay_count, total_delay_seconds` (own small card or folded into Trip Stats -- open question, §5) |
| Stops | `stops[]` |
| Customer Instructions | `instructions[]` |
| Your Rating | `feedback_rating, feedback_notes` |

### 3.3 Score label colors

The score pill in Original Offer Assessment reuses `OverlayHelper.
baseColorForScoreLabel(label)`/`isPoorScoreLabel(label)`/
`stripedDrawable(...)` directly -- these are already public static
methods built for exactly this purpose (`docs/
smart_score_quartile_colors/PRD.md`), not reimplemented.

### 3.4 The feedback-chaining problem (§1's one real hazard)

Recommended approach: `TripDetailActivity` gains an optional boolean
extra, `EXTRA_PROMPT_FEEDBACK_ON_CLOSE`. A single primary action
control at the bottom of the scroll content (a `MaterialButton`, "Done"
-- not relying on the toolbar back arrow, so there's one unambiguous
moment this fires, matching the current `AlertDialog`'s single "OK"
button exactly) does what the OK button's callback currently does:
if the extra is true AND the trip's mode is DASHER, shows the existing
feedback dialog (`showFeedbackDialog`'s logic, moved -- not copied a
third time -- into `TripDetailActivity` or a small shared helper) before
calling `finish()`.
`MainActivity.showLastTripSummaryThenPromptFeedback()` becomes: fetch
`get_last_trip_summary` (unchanged), then `startActivity` with
`EXTRA_PROMPT_FEEDBACK_ON_CLOSE = true` instead of building an
`AlertDialog`. `TripHistoryActivity`'s two call sites
(`showTripSummaryById`, and the button behind "Last Trip Summary")
launch the same Activity with the extra `false` (or omitted) -- browsing
history should never surprise-prompt for feedback.
The system back button/gesture still just closes the screen with no
feedback prompt, matching how dismissing an `AlertDialog` by tapping
outside it today also skips the OK-only callback -- consistent, not a
regression.

## 4. Premortem

- **P1 -- the feedback-chaining re-thread (§3.4) is gotten wrong**,
  either firing the feedback dialog when it shouldn't (browsing old
  General-mode trips) or silently dropping it after Stop Monitoring
  (the actual, currently-working case this whole flow exists for).
  Mitigation: §3.4's design keeps the exact same two-condition gate
  (`tripId >= 0 && isDasherTrip`) verbatim, just relocated -- and this
  is flagged as the single highest-risk piece of the whole PRD, not
  buried in the field-mapping table.
- **P2 -- a field silently dropped during the reorg.** §3.2's table was
  built by reading the CURRENT `buildTripSummaryBody()` field-by-field,
  not from memory -- the ralph loop's own first checklist item (§6)
  should be a literal side-by-side diff of the table against the
  current method before writing any layout XML, to catch anything this
  first pass missed.
- **P3 -- MaterialCardView behaves differently than expected under
  `Theme.AppCompat.DayNight`** (the app's actual theme parent, not
  `Theme.MaterialComponents` -- `material:1.12.0` is a dependency, but
  the THEME itself was never switched to a Material Components base).
  Real, disclosed risk: some Material widgets assume a
  `Theme.MaterialComponents.*` parent for their default styling and can
  render with unexpected attributes (wrong corner radius, missing
  elevation) under plain AppCompat. Needs an explicit style override on
  the `MaterialCardView` (`app:cardCornerRadius`, `app:cardElevation`
  set directly in the layout XML, not left to theme defaults) rather
  than assumed to look right automatically -- called out explicitly so
  the ralph loop doesn't skip it as "just works."
- **P4 -- no Android SDK/emulator in this environment, same as every
  other Java/XML PRD in this repo.** A layout this size (11+ cards) has
  real, elevated risk of a subtle XML mistake (a missing `android:id`,
  a card that doesn't actually render due to a `ScrollView` nesting
  bug) that only code review can catch, not a build. Flagged, not
  solved -- the ralph loop's own verification section should be
  unusually thorough here (XML well-formedness checks via
  `xml.etree.ElementTree`, and a manual trace of every `findViewById`
  call against the layout's real ids) given the size.
- **P5 -- scope creep into the picker or the other 5 report dialogs.**
  §2's non-goals are explicit for a reason -- the ralph loop must not
  "while I'm in here" touch either, even though they're visually
  adjacent and use the same theme.

## 5. Open questions (driver confirmation needed before implementation)

1. **Major delays**: fold into the Trip Stats card, or its own small
   card? No strong reason either way -- recommend folding into Trip
   Stats (it's a single line today, doesn't warrant its own card), but
   this is a real judgment call, not resolved here.
2. **The "Done" button vs. relying on the toolbar back arrow** (§3.4):
   recommended because it's the one unambiguous moment to hook the
   feedback-prompt logic, matching the AlertDialog's single OK button.
   Alternative: keep only the back arrow, and always show the feedback
   prompt on `onPause()`/`finish()` when the extra is set, regardless
   of how the screen was left (back gesture, system back button, or a
   button) -- simpler in one sense, but fires on a swipe-back gesture
   too, not just a deliberate tap the way OK currently requires. No
   default assumed -- driver's call.
3. Does the driver want this to also replace `TripHistoryActivity`'s
   trip PICKER (§2 non-goal) as a follow-up once this ships, or leave
   it as the plain `AlertDialog` list indefinitely? Not blocking this
   PRD either way, but worth knowing before the ralph loop finishes, in
   case a shared component (e.g. a reusable card style) would be worth
   designing for both up front rather than only this screen.

## 6. Success criteria (ralph-loop checklist)

- [ ] §3.2's field-mapping table re-verified line-by-line against the
      CURRENT `buildTripSummaryBody()` before any layout XML is written
      (P2)
- [ ] `activity_trip_detail.xml` created: `ScrollView > LinearLayout` of
      `MaterialCardView` sections, `Theme.DasherMonitor.TripHistory`
      applied, explicit `app:cardCornerRadius`/`app:cardElevation` set
      (not left to theme defaults, per P3)
- [ ] `TripDetailActivity.java` created: reads `trip_id` extra, calls
      `get_trip_summary_by_id`, binds every field from §3.2's table,
      each card hidden (not empty) when its data is null/absent
- [ ] Score pill reuses `OverlayHelper.baseColorForScoreLabel`/
      `isPoorScoreLabel`/`stripedDrawable` directly -- not reimplemented
- [ ] §3.4's feedback-chaining design implemented per the driver's
      answer to open question #2 -- `EXTRA_PROMPT_FEEDBACK_ON_CLOSE`
      gate matches the CURRENT `tripId >= 0 && isDasherTrip` condition
      exactly
- [ ] `MainActivity.showLastTripSummaryThenPromptFeedback()` and
      `TripHistoryActivity`'s two call sites updated to launch
      `TripDetailActivity` instead of building an `AlertDialog`
- [ ] Both existing `buildTripSummaryBody()` copies deleted (not left
      as dead code)
- [ ] `AndroidManifest.xml`: `TripDetailActivity` declared with
      `Theme.DasherMonitor.TripHistory`, parented under
      `TripHistoryActivity`/`MainActivity` per Android's standard
      up-navigation convention
- [ ] XML well-formedness check (`xml.etree.ElementTree`) on the new
      layout; every `findViewById` call in `TripDetailActivity`
      cross-checked against a real id in the layout (P4)
- [ ] Driver confirms on-device: every card shows the right data for a
      real trip, the stacked-order warning still appears when expected,
      and the post-Stop-Monitoring feedback prompt still fires at the
      right moment for a Dasher trip (not General, not trip-history
      browsing)
- [ ] Driver sign-off
