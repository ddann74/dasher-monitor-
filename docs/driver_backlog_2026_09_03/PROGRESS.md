# Progress log — driver backlog triage (2026-09-03)

## Triage (2026-09-03)

Driver sent 36 items in one raw message - bug reports, feature
requests, and plain questions, mixed together, no prioritization, no
numbering. Rather than guess at what was already built versus
genuinely new, spawned a general-purpose agent to investigate each item
against the real code first: check `docs/<topic>/PRD.md` Status headers
for existing coverage, spot-check the actual Java/Python behind any
"IMPLEMENTED" claim rather than trust the doc (this repo has had stale
PRD status headers before), and classify each item.

Result: 15 items already answered/implemented (no new work - listed in
PRD §1 with file:line citations), 3 duplicates (PRD §2), 3 items with a
genuine open question blocking further work (PRD §3 - one incomplete
sentence in the driver's own message, one item with two plausible but
different meanings, one unnamed "the report"), 6 real bugs with no
existing tracking or blocked tracking (PRD §4), and 12 genuinely new
feature requests with no existing design (PRD §5).

Wrote this as a full PRD (`PRD.md`) rather than just relaying the
triage in chat, per the driver's own request, including:
- A recommended priority order (§6) - my own judgment call, disclosed
  as such, not driver-specified.
- A premortem (§7) written for the shape of THIS backlog specifically
  (many independent items across many subsystems, worked one at a time
  over multiple sessions) rather than reusing a single-feature PRD's
  premortem shape - flags schema-drift risk between related items
  (P1), unverified citations the "small" size estimates lean on (P2),
  scope-creep risk on the two large items (P3), the specific danger of
  silently resolving §3's open questions by guessing instead of asking
  (P4), and the accumulating unverified-on-device surface area from
  building this many UI-touching items without a real device in this
  environment (P5).
- A `RALPH_PROMPT.md` adapted from this repo's existing per-feature
  ralph-loop convention, but for a multi-item checklist: follows §6's
  priority order rather than top-to-bottom, requires re-reading sibling
  items before starting anything in P1's schema-drift group, requires
  spot-checking a cited "already exists" function before building on
  top of it (per P2), and explicitly forbids resolving §3's open
  questions by assumption (per P4) - stronger language than this
  repo's usual "use the PRD's own stated recommendation" default,
  because guessing wrong on #4/#17/#26 means building the wrong thing
  entirely, not picking a defensible option among equivalent ones.

No code touched. This PRD's own §9 checklist is the tracking mechanism
for the ralph loop's eventual work through §4/§5 - nothing to check yet.

## #27 implemented (2026-09-03): voice-announce a dash pause

First item per §6's recommended order. `DasherAccessibilityService.java`'s
existing `is_dash_paused_screen` detection block (around line 629)
already stopped GPS tracking and logged `AUTO_PAUSE` on detecting the
Dash Paused screen, but never spoke anything - added one
`VoiceAnnouncer.speak("Dash paused. Monitoring stopped.")` call right
where the pause is detected and acted on, matching this class's
existing voice-announcement style used elsewhere (e.g. the smart-score
readout at line ~935).

**Scope note, per RALPH_PROMPT.md's guardrail against expanding beyond
the chosen item**: while implementing this, noticed the RESUME path
(`attemptAutoStartMonitoring`, called when the Dash Paused screen
clears) is ALSO silent - no voice announcement there either. The
driver's own #27 wording was specifically about missing the "paused"
announcement, and the PRD's own #27 entry scoped the fix to "one
`VoiceAnnouncer.speak()` call at the existing detection point" -
singular, referring to pause. Left resume untouched rather than
silently expanding scope; noted here and in PRD.md's #27 entry as a
candidate for its own, separately-scoped item if the driver wants
symmetric coverage.

**Verification**: same disclosed limitation as every Java-side PRD in
this repo - no Android SDK/emulator/device, so code review plus static
checks. `DasherAccessibilityService.java` brace/paren balance: 151/151
braces, 541/541 parens. Confirmed `VoiceAnnouncer` is in the same
package (`com.drivingefficiency.app`) as this file, no import needed.
Confirmed the new call only fires when `TripForegroundService.isRunning`
is true (same guard as the existing pause-handling code), meaning
`VoiceAnnouncer.init()` (called from `TripForegroundService.onCreate()`)
has always already run by the time this fires - no init-order risk.

PRD §4 box checked. Remaining §6-ordered items: #8, then #6/#9, per the
recommended order.

## #8 implemented (2026-09-03): trip history full stage breakdown

Second item per §6. Both pieces the PRD flagged as missing from the
already-existing `phase_breakdown` display in
`TripHistoryActivity.buildTripSummaryBody()`:

1. **Wait-time rating.** `drive_monitor.py`'s `get_trip_summary()` (the
   function backing this whole dialog) already returns
   `feedback_merchant_wait` (`"Fast"`/`"Okay"`/`"Slow"`, confirmed by
   checking `MainActivity.java:724`'s rating-dialog options) in the same
   JSON `phase_breakdown` comes from - it was simply never read in this
   view. Added as `"(rated: X)"` next to the existing wait-duration line.
2. **Deadhead time.** Confirmed via a full `grep -i deadhead` across
   `drive_monitor.py` that "deadhead" is exclusively a DISTANCE concept
   in this codebase (`deadhead_km`, `actual_deadhead_km`) - no
   timestamp-based deadhead duration is computed or stored anywhere.
   Rather than inventing a new computation, reused
   `phase_breakdown["driving_to_pickup_seconds"]`: for the single/
   first-job scope `phase_breakdown` already documents as its own
   limitation (only the FIRST pickup/dropoff of a trip, a known,
   already-disclosed gap for stacked/multi-stop orders - see
   `docs/deadhead_stacked_order_baseline/PRD.md`), driving-to-pickup
   time and deadhead time are the same interval. Added as a
   parenthetical next to the existing "Deadhead: X km" line (which
   lives in the offer-snapshot section) rather than as a second,
   confusingly-duplicate "Driving to pickup" line in the phase-breakdown
   section further down - same underlying number, one place to see it
   tied to the concept the driver actually asked about.

**Honest caveat, disclosed rather than silently assumed**: this reuse
is only correct because of `phase_breakdown`'s own pre-existing single-
job scope limitation. If that limitation is ever fixed (tracked
separately, `docs/deadhead_stacked_order_baseline/PRD.md` Part 2B,
currently blocked on real evidence about the dropoff screen), this
deadhead-time display would need re-checking - it was built assuming
today's known scope, not guessed at as if it were a general truth.

**Refactor note**: `phaseBreakdown` was previously fetched only inside
the "Where The Time Went" section further down the method; hoisted its
`summary.optJSONObject("phase_breakdown")` lookup earlier (fetched
once, reused by both the new deadhead-time line and the existing
section) rather than fetching the same key twice.

**Verification**: same disclosed limitation as every Java-side PRD in
this repo - no Android SDK/emulator/device, code review plus static
checks. `TripHistoryActivity.java` brace/paren balance: 96/96 braces,
636/636 parens. Confirmed `optString(key, "")`'s null/missing-key
fallback behavior matches this exact file's own established pattern
(e.g. `pickup_address`, `verdict_sentence` already use it identically).

PRD §4 box checked. Next per §6: #6 and #9 (accepted/declined $/km,
Dasher/General separation).

## #6 and #9 implemented (2026-09-03)

Third/fourth items per §6, both small pure additions to already-returned
data as the PRD predicted.

**#6 - average $/km by outcome.** Added a `rate_comparison` block to
`get_rejected_offers_report()` (`drive_monitor.py`). Deliberately a
SEPARATE SQL query from the existing per-factor `comparison` block
(which filters to `components_json IS NOT NULL`) - $/km doesn't depend
on the components snapshot at all, and reusing that query would have
silently excluded any offer with valid payout/distance but a missing or
malformed components snapshot for an unrelated reason. Also excludes
`distance_km <= 0` (would divide by zero) and `payout IS NULL` rows.
Wired into `TripHistoryActivity.showRejectedOffersReport()`, shown one
line above the existing per-factor comparison, using the same "n/a for
missing" pattern already established there.

Verified with a real, runnable Python test
(`/tmp/.../test_rate_comparison.py` - pure `drive_monitor.py`, zero
Android/Chaquopy dependency, matching this repo's established testing
approach): seeded `offer_outcomes` with accepted/declined/timed-out
rows plus a zero-distance row, a missing-payout row, and an
`is_test_data=1` row, confirmed all three are correctly excluded and
the remaining averages are exactly right. All assertions passed.

**#9 - separate Dasher vs. General trips.** `trips.mode` was already
returned by `get_trip_history()` and already shown as a per-row suffix,
but there was no way to filter the list to one mode. Added a simple
up-front chooser dialog ("All Trips" / "Dasher Only" / "General Only")
in `TripHistoryActivity.showTripHistory()`, filtering the already-
fetched trip list client-side (`showTripHistoryFiltered(String
modeFilter)`) - no Python change needed, the full list was already in
memory either way. Confirmed the single existing call site
(`viewTripHistoryButton`'s click listener) needed no changes, since
`showTripHistory()` kept its original name/signature.

**Verification (both)**: same disclosed limitation as every Java-side
change in this repo - no Android SDK/emulator/device, code review plus
static checks. `TripHistoryActivity.java` brace/paren balance: 103/103
braces, 677/677 parens. `drive_monitor.py` re-compiled cleanly
(`python3 -m py_compile`) after the #6 change.

PRD §5 boxes for #6 and #9 checked. Next per §6: #14 (surface traffic
ratio), then #2 (per-offer omit/include toggle).

## #14 implemented (2026-09-03): surface the traffic ratio

`_get_traffic_risk()` (`drive_monitor.py`) previously returned only
`(is_high_risk, source)` - a binary flag plus which of four fallback
tiers produced it (live/zone/personal/generic). Added the raw ratio as
a third return value, but ONLY populated when `source == "live"`: the
other three tiers (`_get_traffic_risk_by_zone`, the personal-history
proxy, the generic guess) are binary risk flags from entirely different
methods with no underlying ratio - returning a fabricated number for
those would misrepresent them as more precise than they are. Updated
the single call site (`calculate()`) and added `traffic_ratio` to its
returned dict, rounded to 2 decimals.

`TripHistoryActivity`'s existing "Traffic: [label]" line now appends
"(X% of typical)" when `traffic_ratio` is present, omitted entirely
(not shown as "0%" or similar) when it's `null` - confirmed
`JSONObject.isNull()` correctly treats a JSON `null` value the same as
a missing key here, matching this file's other nullable-field patterns.

**Verification**: real, runnable Python test
(`/tmp/.../test_traffic_ratio.py`) covering all three real code paths:
no live traffic ever recorded (ratio `None`, falls back to
personal/generic), fresh live traffic recorded (ratio present, rounded,
source `"live"`), and STALE live traffic (older than
`LIVE_TRAFFIC_FRESHNESS_SECONDS`) correctly falling back and reporting
`None` again rather than a stale number. All three assertions passed.
Confirmed no other call site of `_get_traffic_risk()` exists (single
caller, already updated for the new 3-tuple). `drive_monitor.py`
recompiles cleanly; `TripHistoryActivity.java` brace/paren balance:
103/103 braces, 684/684 parens.

PRD §5 box for #14 checked. Next per §6: #2 (per-offer omit/include
toggle).

## #2 implemented (2026-09-03): per-offer omit/include toggle for calibration

`recalculate_personal_calibration`'s Source 2 (accept/decline decisions,
`drive_monitor.py` around line 1158) already filtered on `is_test_data =
0`, but that flag is auto-set by Developer Testing only - never
driver-controlled, and reusing it for "I chose to exclude this real
offer" would have conflated two different concepts. Added a genuinely
new column instead:

- `offer_outcomes.omitted_from_calibration INTEGER DEFAULT 0` - added to
  both the fresh-install `CREATE TABLE` and as an `ALTER TABLE`
  migration for existing databases, same pattern as the existing
  `is_test_data` migration right above it.
- `get_calibration_offers_list(limit=100)` - lists every real (non-test)
  offer, any outcome, most recent first, with its current omitted state.
- `set_offer_omitted_from_calibration(offer_id, omitted)` - toggles one
  offer.
- `recalculate_personal_calibration`'s Source-2 query now adds `AND
  omitted_from_calibration = 0`.

**UI**: rather than a new top-level button, added "Edit Offers Used" as
a third button on the EXISTING Personal Calibration dialog
(`showPersonalCalibration()`) - the driver's own stated purpose was
"so I can use them to build the smart score algorithm," which is
exactly what that screen already shows. Opens a checklist dialog
(`AlertDialog.setMultiChoiceItems` - checked = included, the default)
listing each offer with date/restaurant/payout/distance/outcome; each
checkbox toggle calls `set_offer_omitted_from_calibration` immediately,
matching the existing "Reset to Base Weights" button's own
immediate-effect pattern on the same screen (no separate "Save" step
to remember).

**Verification**: real, runnable Python test
(`/tmp/.../test_omit_calibration.py`) covering: the list correctly
excludes `is_test_data=1` rows; toggling one offer persists and does
NOT affect any other offer; `recalculate_personal_calibration`'s own
exact WHERE clause (re-run directly in the test) correctly excludes the
omitted offer while keeping the rest; toggling back to included
restores it. All four cases passed. `drive_monitor.py` recompiles
cleanly. `TripHistoryActivity.java` brace/paren balance: 111/111
braces, 738/738 parens.

PRD §5 box for #2 checked. Next per §6: #5 and #7 (hotspot-from-last-5,
per-restaurant visit breakdown).

## #5 and #7 implemented (2026-09-03)

**#5 - recency-windowed hotspot.** `get_pickup_sweet_spot_zone()`'s
zone-grid-frequency logic (round to `PICKUP_SWEET_SPOT_GRID_DECIMALS`,
count occurrences per rounded zone, average the real coordinates within
the winning zone) was factored out into a shared
`_best_zone_from_pickup_rows(rows, min_samples)` helper, so the new
`get_recent_pickup_hotspot()` (restricted to `RECENT_HOTSPOT_WINDOW`
= 5 most recent `pickup_location_history` rows, `RECENT_HOTSPOT_MIN_
SAMPLES` = 3) doesn't duplicate that logic. Wired into
`showAddressBook()`: shown as a second summary line alongside the
existing all-history sweet spot, and a "Copy Recent Hotspot"
`setNeutralButton` (only added to the dialog when a suggestion actually
exists - nothing to copy otherwise) using this app's existing plain
`ClipboardManager`/`ClipData` pattern (same shape as
`DiagnosticsActivity`'s log-copy button).

Verified with a real, runnable Python test
(`/tmp/.../test_recent_hotspot.py`): seeded 10 OLD pickups in zone A
and 5 RECENT pickups (4 in zone B, 1 in zone A) - confirmed
`get_recent_pickup_hotspot()` correctly picks zone B (the recent
majority) while `get_pickup_sweet_spot_zone()` still correctly picks
zone A (the all-history majority) from the SAME underlying data - proof
the two are genuinely answering different questions, not accidentally
returning the same thing.

**#7 - per-restaurant visit history.** Real finding during
implementation, not assumed away: the PRD's own original framing
("joining trips/trip_feedback by restaurant name") isn't actually
possible with the current schema. Checked directly - `trips` has no
`restaurant_name` column, and `trips.offer_score_snapshot_json` (the
only other candidate) is populated from `SmartScoreEngine.calculate()`'s
own returned dict, which doesn't include `restaurant_name` either (read
the full dict to confirm). So a driver's star rating (`trip_feedback`,
keyed by `trip_id`) has no reliable path back to "which restaurant was
this for." Considered a timestamp-proximity join (offer accepted at
time T, trip started shortly after) and rejected it - close-together
offers, or a driver who accepts well before actually starting to
drive, could make it attribute the wrong rating to the wrong
restaurant, which is worse than not showing one at all.

Implemented `get_restaurant_visit_history(restaurant_name)` using each
visit's own Smart Score (from `offer_outcomes`, always available and
genuinely tied to that specific restaurant) instead of a rating, with
average and SAMPLE standard deviation (n-1, since this is always a
bounded recent sample, never "all visits that will ever happen") -
and an explicit `rating_note` field naming the substitution plainly,
surfaced directly in the Java dialog text too, not just in a code
comment nobody but a future developer would see.

UI: new "Restaurant Visit History" button (new string resource +
layout button, following this screen's existing button pattern) opens
a restaurant chooser (reusing `get_address_book()`'s own entries - no
separate "list restaurant names" query needed), then the per-restaurant
breakdown dialog.

Verified with a real, runnable Python test
(`/tmp/.../test_restaurant_visit_history.py`): no-visits case (empty,
no averages); single-visit case (avg = that score, stdev correctly
`None` rather than 0 - a stdev of one point isn't meaningful); known
3-score case with the exact sample stdev cross-checked against Python's
own `statistics.stdev`; confirmed test-data rows and a different
restaurant's rows are excluded; confirmed the `LIMIT 10` +
most-recent-first ordering with 15 seeded visits.

**Verification (both)**: same disclosed limitation as every Java-side
change in this repo - no Android SDK/emulator/device, code review plus
static checks. `TripHistoryActivity.java` brace/paren balance: 130/130
braces, 859/859 parens. `activity_trip_history.xml`/`strings.xml`
re-validated as well-formed XML (`xml.etree.ElementTree`).
`drive_monitor.py` recompiles cleanly.

PRD §5 boxes for #5 and #7 checked. Remaining §5/§4 items per §6:
#25 (live $/km, $/hr in the recommendation), the three open questions
(#4, #17, #26), the evidence-blocked bugs (#21, #22, #16), and the two
large items (#1, #29) last.

## #25 (2026-09-03): found already implemented, no new code

Before writing anything, checked `DasherAccessibilityService.java`
where the live Smart Score badge is built (per PRD §5's own note that
this item's underlying data already exists) - the badge already shows
`$X.XX/km   $X.XX/hr` in both its compact text (line ~902) and its
expanded/tap-to-see-more text (line ~925), for every real offer. An
existing inline comment there even documents this as a deliberate,
previous addition: "Restored to the live badge per explicit request:
$/km and $/hr specifically -- everything else (deadhead, wait, traffic,
weather) still stays out of the live view, only in the post-trip
summary."

This session's own earlier triage (the agent investigation that
produced §1-§5) missed this - it was classified as a new SMALL-MEDIUM
feature request rather than caught as already done. Corrected in
PRD.md §5 rather than shipping duplicate/redundant code on top of
something that already works. No commit needed for this item beyond
the PRD/PROGRESS correction itself.

Remaining per §6: the three open questions (#4, #17, #26) need the
driver's own input before they're actionable; #21/#22/#16 need a fresh
diagnostic log each; #1 and #29 are deliberately last, pending a
scoping conversation. Nothing left in the ralph loop's queue that can
proceed without one of those first.

## #4 resolved and implemented (2026-09-03)

Driver answered #4's cut-off sentence directly: force-acknowledge
customer messages (repeat every 30s until tapped) plus read delivery
instructions aloud within 50m of the address (not the existing 500m).
Full design writeup in PRD.md §10/§11 - summarized here:

- New `OverlayHelper.startAcknowledgeReminder(spokenText, intervalMs)` -
  a repeating `VoiceAnnouncer.speak()` that checks before each repeat
  whether its paired persistent overlay is still showing, stopping
  itself the instant it's been tapped. `showPersistentTappableMessage`
  changed to return `boolean` (was it actually shown) and gained an
  optional `onAcknowledged` callback; `clearPersistentMessage()` now
  also cancels any pending reminder directly.
- `AppNotificationListenerService`'s urgent-customer-message path
  (previously voice-only, single announcement) now also shows the
  persistent overlay and starts the repeat reminder - only if the
  overlay actually rendered (permission-gated, same as the existing
  approach-instruction path already was).
- New `INSTRUCTION_READ_RADIUS_METERS = 50` (`drive_monitor.py`),
  deliberately separate from `APPROACHING_RADIUS_METERS` (500,
  unchanged - still drives the nav icon and the coarse pre-filter).
  `_check_approach_instruction` now takes `lat, lon` and gates on the
  real distance to the stop before firing; gained the same repeat
  reminder as the customer-message path.
- Confirmed "including generic instructions" was already satisfied -
  `DropoffScreenParser`'s delivery-instruction capture is a best-effort
  "any leftover free-text line" match with no generic/boilerplate
  filtering to remove.

**Scope decision, disclosed**: did not split the approach-instruction
overlay's appearance (could stay at 500m) from its voice (delayed to
50m) into two separate triggers - that would need a second, parallel
per-stop state machine in a function whose own comments already
document real historical multi-stop/batch-order bugs. One combined
trigger at 50m is simpler and lower-risk; flagged as a real, doable
follow-up if the driver wants the overlay to still appear earlier than
the voice.

**Verification**: same disclosed limitation as every Java-side change
in this repo - no Android SDK/emulator/device, code review plus static
checks for Java; a real, runnable Python test for the pure-Python
radius logic. `drive_monitor.py` recompiles cleanly. Brace/paren
balance: `OverlayHelper.java` 70/70 braces, 290/290 parens;
`AppNotificationListenerService.java` 75/75 braces, 311/311 parens;
`TripForegroundService.java` 189/189 braces, 859/859 parens. Python
test (`test_instruction_read_radius.py`) covered: 200m out (within the
old 500m radius but outside the new 50m one) correctly doesn't fire;
20m out correctly fires with the real delivery instruction text
included; already-shown-for-this-stop correctly doesn't re-fire even
standing right on top of it. All three cases passed.

PRD §11 boxes for #10 checked except driver confirmation/sign-off.
Two open questions remain: #17 and #26.

## #17 resolved (2026-09-03): confirmed reading (b), confirmed already satisfied

Asked the driver directly which of the two possible readings they meant
(§3 explicitly forbade guessing here - P4's premortem risk). Driver
confirmed reading (b): the "navigate home with a saved/preset route"
feature, not the RoadWarrior-icon-didn't-appear bug reading (a).

No new code needed: `docs/hotspot_or_home_routing/` (a different,
concurrent driver request from earlier the same day - shift-rate-based
routing) already built the exact mechanism #17 asked for as a side
effect - a tappable icon that appears on trip completion and navigates
(via the existing `NavigationHelper`/Waze integration) to a stored home
address. Driver confirmed this covers it when shown the connection.

Flagged one honest, disclosed difference rather than silently declaring
a perfect match: the hotspot-or-home icon is CONDITIONAL on the
shift-rate algorithm (only suggests home when the recent $/hr is below
the driver's threshold; suggests the hotspot instead when above) - not
an unconditional "take me home right now" button independent of shift
performance. Not built, not assumed wanted - left as a real, small,
separately-doable follow-up if the driver ever wants it.

PRD.md §3/§4/§5 updated: #17 struck through as resolved, #17a (the
RoadWarrior-icon bug reading) explicitly left as NOT what was meant and
NOT touched by this, #17b checked off in §5 pointing at the shipped
feature.

Only #26 remains open (which report needs formatting improved).

## #26 resolved (2026-09-03): Trip History full time detail + Address Book rate/score stats

Driver named both screens (Trip History, Address Book) and raised a
real accuracy concern alongside it: "I want to see all details of where
the time went as some don't look accurate." Asked directly whether
this was on stacked/batch-order trips (a known, already-documented
bug, `docs/deadhead_stacked_order_baseline/PRD.md` Part 2B - blocked on
real evidence) or single-order trips (would need fresh root-causing) -
driver answered "both/not sure." Rather than guess which case applied
and risk building the wrong fix (this PRD's own P4 premortem risk),
gave full diagnostic detail instead - see PRD.md §13/§14 for the full
design writeup. Summarized here:

- `_build_trip_summary_dict` (`drive_monitor.py`) now returns
  `phase_timestamps` (raw clock times for trip start, pickup arrival/
  departure, dropoff arrival, walking confirmed, trip end - each
  included only if actually captured) and `job_count` (count of
  `offer_distance_accuracy` rows for the trip, already one-per-job).
- `TripHistoryActivity`'s "Where The Time Went" section now shows a new
  "Full time detail" block with real clock times next to the existing
  durations, plus an explicit warning when `job_count > 1` linking
  directly to the known Part 2B stacked-order limitation, instead of
  silently presenting a possibly-mixed number as reliable.
- `get_address_book()` gained avg $/km, avg $/hr, and avg Smart Score +
  sample stdev per restaurant, from `offer_outcomes` - scoped to ANY
  offer outcome (not accepted-only), matching `get_restaurant_visit_
  history`'s own already-shipped definition of "visit" for the same
  restaurant grouping, disclosed as a deliberate consistency choice
  rather than assumed. Reused the same $/km exclusion rules (missing
  payout, zero/missing distance) the Rejected Offers Report's
  `rate_comparison` already established.
- New shared `_sample_stdev()` helper (module-level, next to
  `haversine_meters`) - `get_restaurant_visit_history`'s existing inline
  stdev calculation refactored to use it, removing a duplicate formula.

**Verification**: same disclosed limitation as every Java-side change
in this repo - no Android SDK/emulator/device, code review plus
brace/paren balance for Java (`TripHistoryActivity.java`: 144/144
braces, 921/921 parens); real, runnable Python tests for the pure-Python
logic. `drive_monitor.py` recompiles cleanly.
`test_address_book_rates_and_time_detail.py` (3 cases): known $/km/
$/hr/Smart-Score averages correct with a zero-distance row excluded
from $/km only (not the other two metrics, since they don't depend on
distance) and a test-data row excluded from all three; a restaurant
with zero `offer_outcomes` rows returns `None` fields rather than
crashing; `get_restaurant_visit_history` re-verified unaffected by the
`_sample_stdev` refactor.
`test_trip_summary_job_count_phase_timestamps.py` (3 cases): a
single-order trip returns `job_count: 1` + all 6 raw timestamps; a
synthetic 2-job trip returns `job_count: 2`; an older trip with no
phase capture returns only start/end (not fabricated intermediate
values) and `job_count: 0`. Re-ran every existing scratchpad test
touching the changed functions - no regressions (one pre-existing,
already-stale scratch test unrelated to this pass, calling an older
pre-#4-fix function signature, was already broken before this and is
not a regression from it).

**Honest scope note**: this does NOT fix the underlying Part 2B
stacked-order timestamp-mixing bug itself - that's still genuinely
blocked on real evidence (a stacked-order dropoff screenshot), same as
before. This pass makes the problem visible and diagnosable (raw times
+ an explicit warning) rather than solved, and gives the driver what's
needed to pinpoint a real single-order accuracy problem if one exists,
which the "both/not sure" answer couldn't rule out.

PRD.md §13/§14 boxes checked except driver confirmation/sign-off. All
three of §3's original open questions (#4, #17, #26) are now resolved -
remaining backlog items are the evidence-blocked bugs (#21, #22, #16,
plus the deadhead PRD's own Part 2B) and the two large, deliberately-
deferred items (#1, #29).

## #21 partially addressed (2026-09-03): tolerant bounds matching, no confirmed bug found

Driver had no diagnostic log for #21 and, when asked directly, reframed
the goal: "I'm not sure. I just want to collect all data to build the
smart score engine." Rather than guess at a specific unconfirmed bug
(this PRD's own P4 premortem risk), read the full accept/decline
detection chain in `DasherAccessibilityService.java` end-to-end first -
found it's already a 3-layer system (direct click match, real evidence
already on file that clicks likely never fire for Dasher's own buttons
at all; node-bounds matching, the real mechanism; a timeout fallback so
nothing is ever silently lost entirely) built through substantial prior
real-diagnostic-log-driven work that predates this backlog.

The gap that IS real and already named in the existing code's own
comments: `checkNodeBoundsMatch` required byte-exact `Rect.equals()`
between bounds recorded at scan time and the actual tap event - a
"bounds-shift edge case" the code already flagged as a risk. Confirmed
via `recalculate_personal_calibration`'s own docstring that this isn't
just cosmetic: a decline that falls into the timeout bucket due to a
pixel-level shift gets genuinely EXCLUDED from calibration learning
("timeouts... not a real preference signal the way an active decline
is") - real data loss for the driver's stated goal.

**Fix**: new `boundsRoughlyMatch()` (24px tolerance per edge) replaces
the exact-equality checks for both Accept and Decline bounds matching.
Deliberately still requires all four edges close (not just overlap), so
the two buttons - always separate, non-adjacent - can't be confused
with each other.

**Honestly scoped, not oversold**: this hardens a real, already-named
risk - it is NOT a confirmed bug fix, since no log or device evidence
ever confirmed a decline was actually being lost in practice. Marked
"partially addressed" in PRD.md, not fully closed - if the driver
notices anything still missing after this ships, that's the real
evidence to reopen this with.

**Verification**: same disclosed limitation as every Java-only change
in this repo - no Android SDK/emulator/device, code review plus
brace/paren balance (`DasherAccessibilityService.java`: 152/152 braces,
556/556 parens). `android.graphics.Rect` has no reachable pure-Python or
plain-Java equivalent in this sandbox, so `boundsRoughlyMatch` itself
couldn't be executed as a real test here - disclosed, not glossed over.

PRD.md §15/§16 boxes checked except driver confirmation/sign-off.

## #22 part (a) implemented (2026-09-03): alert when a critical permission is already off at monitoring start

Driver's literal complaint: "Accessibility access seemed to be turned
off before I tried to start - does the log detect this?" Traced the
exact code path rather than assumed - confirmed it did NOT. Root cause:
`checkAndLogPermissions(true)` (the one call that runs at monitoring
start) runs BEFORE `monitoringActive = true` is set, and the existing
alert block is gated on `monitoringActive` AND requires a real
true->false transition (`lastLoggedX` starts `null` on a fresh
process) - both conditions silently defeat the existing alert exactly
at this call site.

**Fix**: new block in `checkAndLogPermissions`, gated on `forceLog`
directly (has exactly one caller in the file - `startTracking()` -
confirmed by grep) rather than `lastLoggedX == null`, so a SECOND
`startTracking()` in the same process still correctly re-checks rather
than wrongly trusting a stale prior state. Checks all 4 critical
permissions' current state and fires the existing `raisePermission
RevokedAlert` mechanism immediately if any are off.
`raisePermissionRevokedAlert` gained a third `alreadyOffAtStart`
parameter (2-arg overload keeps all 8 pre-existing call sites
unchanged) so the notification says "already off" rather than the
misleading "turned off" for a permission that was never granted to
begin with this session.

**Scope decision, per the driver's own explicit answer**: asked
directly via `AskUserQuestion` whether part (b) (correlating the
status dot's visible duration with voice-announcement timing - always
flagged as speculative, one-off-report instrumentation, not a
confirmed recurring bug) was also wanted. Driver confirmed part (a)
alone is enough. Not built.

**Verification**: same disclosed limitation as every Java-side change
in this repo - no Android SDK/emulator/device, code review plus
brace/paren balance (`TripForegroundService.java`: 200/200 braces,
897/897 parens). Confirmed all 8 pre-existing `raisePermissionRevoked
Alert` call sites still compile against the unchanged 2-arg overload;
confirmed `forceLog=true` has exactly one caller so the new block can't
fire during the periodic heartbeat; confirmed the new block runs
independently alongside (not inside) the existing `monitoringActive`
block with no duplicate-alert risk on the first heartbeat after start.

PRD.md §17/§18 boxes checked except driver confirmation/sign-off. Only
#16 remains open in the original evidence-blocked bug list (needs a
diagnostic log from a session where the status dot didn't show).
