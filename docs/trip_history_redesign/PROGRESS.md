# Progress log -- Trip History redesign

## Full implementation pass (2026-09-08)

All PRD §6 boxes implemented in one continuous pass (not literally one
ralph-loop invocation per box, since this session already held full
context -- but each step was still verified individually, in the order
RALPH_PROMPT.md specifies, before moving to the next).

### 1-2. Re-verification steps (PRD §4 P1/P2)

- Re-diffed §3.2's field-mapping table against the live
  `buildTripSummaryBody()` before writing any XML. Found ONE real gap:
  the very first line ("Mode: Dasher Delivery / General Driving") was
  never in the table. Resolved as a design decision, not a dropped
  field: shown in `TripDetailActivity`'s `ActionBar` subtitle
  ("Dasher Delivery"/"General Driving"), not a card -- screen chrome,
  not scrollable content.
- Re-read `notifyRateThisDelivery()` in full (including its own
  BAL-exemption launch sequence: overlay-tap-through, direct
  `startActivity` attempt, full-screen-intent notification fallback)
  before touching it. Confirmed via `grep` that both its real call
  sites in `TripForegroundService` (natural completion, auto-pause
  -adjacent completion) reference the same method -- one retarget
  covers both.

### 3. `activity_trip_detail.xml` + `TripDetailActivity.java`

11 `MaterialCardView` sections (Original Offer Assessment through Your
Rating), each with explicit `app:cardCornerRadius="6dp"`/
`app:cardElevation="2dp"`/`app:cardBackgroundColor` and
`style="@style/Widget.MaterialComponents.CardView"` -- per PRD §4 P3,
none left to theme defaults, since this app's actual theme
(`Theme.DasherMonitor.TripHistory` extends `Theme.AppCompat.DayNight`)
never defines the `materialCardViewStyle` attribute a `MaterialCardView`
would otherwise implicitly look for. Same explicit-style treatment
applied to the one `MaterialButton` (primary action) for the same
reason. **This is the first real use of any Material Components widget
anywhere in this codebase's Java or XML** -- `material:1.12.0` has been
a Gradle dependency but was never actually imported/used before this
PRD. A real, disclosed elevated-risk area (PRD §4 P4) no build in this
environment can confirm.

Card sections with a genuinely variable row count (Full Time Detail,
Where The Time Went, Trip Stats, Safety Events, Stops, Customer
Instructions) use an empty `LinearLayout` container in the XML,
populated at runtime with plain label/value `TextView` rows -- matches
this app's own established "no RecyclerView anywhere" convention (PRD
§1), and matches the old text version's own "omit rather than guess"
rule (a row simply isn't added if its underlying data is absent, never
shown as a fake zero).

**Deliberate simplification from the mockup**: "Where The Time Went"
renders as plain text rows ("Driving to pickup: 7m 45s - 24%"), not the
mockup's visual duration bars. Nothing in PRD §2's definition of
functional required a custom-drawn bar, and building one would be new,
unverified custom-View code in an environment with no way to confirm it
renders correctly -- the mockup was illustrative concept art, not a
literal spec for every pixel.

New `res/values/styles.xml` (didn't exist before): `CardTitle`,
`StatTile`, `StatTileLabel`, `StatTileValue` -- small shared styles so
the same section-title/stat-tile look isn't hand-copied onto 11+
TextViews.

Score pill background: `OverlayHelper.backgroundForScoreLabel(this,
label)` called directly -- returns a complete `Drawable` (solid color,
or the real striped pattern for "Poor") in one call, no need to
separately combine `baseColorForScoreLabel`/`isPoorScoreLabel`/
`stripedDrawable` by hand.

**A real bug caught during the field re-verification, fixed before it
shipped**: `friendlyEventTypeLabel`'s real `switch` cases are
`"harsh_brake"`/`"harsh_accel"`/`"speeding"` -- my first draft of the
moved copy guessed `"harsh_braking"`/`"harsh_acceleration"`, which
would have silently fallen through to the `default: return eventType`
branch (showing the raw, unfriendly database string) for every real
safety event. Caught by reading the ORIGINAL method's source directly
before deleting it, not from memory -- exactly the kind of gap PRD §4
P2 was written to catch.

### 3.4 Feedback chaining -- revised design, implemented

While moving `MainActivity.showFeedbackDialog(int tripId)`, found it's
a large method (parking-gap context lookup, 5 quick-tap category rows,
notes field, save logic) -- moving or duplicating it into
`TripDetailActivity` would have been real, avoidable risk for zero
benefit. **Revised the PRD's own design mid-implementation** (documented
there, not just here): `TripDetailActivity`'s "Rate This Delivery"
button instead re-`startActivity`s `MainActivity` with the exact same
`auto_show_feedback_trip_id` extra it already reads in `onCreate()`,
then `finish()`es itself. `showFeedbackDialog` and the
`auto_show_feedback_trip_id` handling in `MainActivity.onCreate()` are
therefore UNCHANGED, not deleted -- reused completely intact, one hop
later than before.

`TripForegroundService.notifyRateThisDelivery()`'s `Intent` retargeted
from `MainActivity`+`auto_show_feedback_trip_id` to
`TripDetailActivity`+`EXTRA_TRIP_ID`+`EXTRA_PROMPT_FEEDBACK_ON_CLOSE=true`.
The surrounding BAL-exemption launch sequence (overlay-tap-through,
direct `startActivity`, full-screen-intent fallback) is byte-for-byte
unchanged -- only the target class/extras differ, per PRD §2's own
non-goal.

Three comments referencing the old MainActivity-direct behavior were
found and corrected for accuracy while making this change (not left
pointing at now-wrong behavior): `notifyRateThisDelivery()`'s own class
doc, the natural-completion call site's comment, and
`MainActivity.onCreate()`'s comment above the (kept)
`auto_show_feedback_trip_id` handling.

**Disclosed, deliberate non-fix**: `showFeedbackDialog` still contains
its own small embedded "where the time went" recap
(`docs/feedback_dialog_phase_timings/PRD.md`), now redundant since
`TripDetailActivity`'s own card already shows the same breakdown one
screen earlier on this exact flow. Left as-is -- fixing it means
editing the same large method this revision specifically avoided
touching, for a minor duplication cost (driver sees one section twice),
not a functional problem. Its own doc comment was updated to disclose
this rather than left stale.

`MainActivity.showLastTripSummaryThenPromptFeedback()` (Stop
Monitoring) rewritten to launch `TripDetailActivity` WITHOUT
`EXTRA_PROMPT_FEEDBACK_ON_CLOSE` -- pure viewing, no re-prompt.

### 3.5 `activity_trip_list.xml` + `TripListActivity.java`

Real screen replacing BOTH of `showTripHistory()`'s nested dialogs (a
mode-chooser `AlertDialog` THEN `showTripHistoryFiltered()`'s
`AlertDialog.setItems()` picker) with one screen -- a net simplification
(2 dialogs to 1 screen), not just a 1:1 port. `ToggleButton` filter row
reuses `get_trip_history()`'s existing client-side filtering logic
exactly (no Python change). Row tap launches `TripDetailActivity` with
only `EXTRA_TRIP_ID` set, per PRD §3.4's no-double-prompt reasoning.

**Deliberate simplification from the mockup**: rows show plain text
(date, distance, mode, composite score), no colored status dot. The
mockup's dot colors were illustrative -- `composite_score` (post-trip
driving efficiency) has no established color-threshold system anywhere
in this codebase, unlike the Smart Score label palette
(`OverlayHelper.baseColorForScoreLabel`), which is a DIFFERENT metric
(pre-trip offer assessment). Inventing arbitrary thresholds for
`composite_score` would have been ungrounded guessing, not something
this PRD's own investigation supports.

### Call-site + cleanup work

- `TripHistoryActivity.java`: `viewTripHistoryButton` now launches
  `TripListActivity` directly (same `Intent`-launch shape
  `locationProfitabilityMapButton` already used). `showLastTripSummary()`
  rewritten to launch `TripDetailActivity`. Deleted: `showTripHistory()`,
  `showTripHistoryFiltered()`, `showTripSummaryById()`,
  `showTripSummaryDialog()`, `buildTripSummaryBody()`,
  `formatMinutesSeconds()`, `formatPercentOfTotal()`,
  `friendlyEventTypeLabel()` -- 407 lines removed. Now-unused `EditText`
  import also removed (confirmed via `grep` it had no other use in this
  file).
- `MainActivity.java`: deleted its own separate `buildTripSummaryBody()`
  copy, `formatMinutesSeconds()`, `friendlyEventTypeLabel()` -- 159
  lines removed. (This copy had already silently drifted from
  `TripHistoryActivity`'s -- it was missing the "as % of total trip
  time" section entirely, a real, concrete example of the exact
  duplication-drift risk PRD §1 flagged. Confirms deleting both in favor
  of one source was the right call.)
- `AndroidManifest.xml`: `TripListActivity` and `TripDetailActivity`
  declared, both themed `Theme.DasherMonitor.TripHistory`, parented per
  §3.1's reasoning (`TripDetailActivity`'s declared parent is
  `TripListActivity`, matching how `LocationProfitabilityMapActivity`'s
  parent is `TripHistoryActivity` even though it's really reached via a
  button there).

### Verification

No Android SDK/emulator/device in this environment -- same disclosed
limitation as every other Java/XML PRD in this repo.

- Brace/paren balance confirmed on every touched Java file after every
  edit: `TripDetailActivity.java` 63/63 braces, 341/341 parens;
  `TripListActivity.java` 17/17, 124/124; `TripHistoryActivity.java`
  105/105, 731/731 (after 407-line removal); `MainActivity.java`
  127/127, 535/535 (after 159-line removal + comment fixes);
  `TripForegroundService.java` 207/207, 948/948.
- XML well-formedness (`xml.etree.ElementTree`) confirmed on
  `activity_trip_detail.xml`, `activity_trip_list.xml`, `styles.xml`,
  and `AndroidManifest.xml`. **Real mistake caught and fixed here**:
  this repo's own Java-comment convention (" -- " as a dash) is illegal
  inside an XML `<!-- -->` comment body (the XML spec forbids `--`
  anywhere in a comment except the closing `-->`) -- every new XML
  comment had to be corrected to single hyphens. A first automated fix
  attempt also briefly corrupted the `<!--` opening delimiters
  themselves (same `--` substring); caught by re-running the
  well-formedness check immediately after, not assumed fixed.
- Full `R.id.*`/`android:id` cross-check between each Java file and its
  layout: every id `TripDetailActivity`/`TripListActivity` reference
  exists in the corresponding XML, confirmed by diffing the two id sets
  directly rather than eyeballing (PRD §4 P4). One XML id
  (`tripStatsCard`) is declared but never referenced in Java -- not a
  bug, that card has no conditional visibility logic (Trip Stats always
  shows), so nothing needs to touch it at runtime.
- `friendlyEventTypeLabel`'s case values re-verified against the
  original source directly before deleting it (see the caught-bug note
  above).

**Not done, and can't be from here**: on-device confirmation that
completing a real delivery actually opens `TripDetailActivity` (not the
old direct-to-rating-dialog flow), that rating from there works and
doesn't double-prompt later at Stop Monitoring, that the trip list
filters correctly, and that General-mode trips never show a rating
prompt. Per PRD §6, these and final sign-off are the driver's own to
confirm, not checked here.
