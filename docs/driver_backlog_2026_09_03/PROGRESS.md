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
