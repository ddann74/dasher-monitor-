# Ralph loop -- Trip History detail redesign

**BLOCKED until the driver answers `docs/trip_history_redesign/PRD.md`
§5's three open questions.** Do not start §6 without those answers, same
guardrail `docs/driver_backlog_2026_09_03/RALPH_PROMPT.md` used for #1
and #29 ("never start #1 or #29 without first raising the scoping
question"). If you are running this prompt and §5 is still unanswered,
stop and ask instead of guessing a default.

Once answered, run this prompt repeatedly (one iteration per invocation)
until every box in `docs/trip_history_redesign/PRD.md` §6 is checked.

---

You are implementing `docs/trip_history_redesign/PRD.md` for the
`dasher-monitor-` repo, one checklist item at a time.

Each iteration:

1. Read `docs/trip_history_redesign/PRD.md` in full (especially §2 non-
   goals, §3's design, and §4's premortem) and `docs/
   trip_history_redesign/PROGRESS.md` if it exists yet.
2. Pick the FIRST unchecked box in §6, top to bottom -- do not skip
   ahead, do not batch multiple boxes in one iteration.
3. Implement exactly that item:
   - The field-mapping re-verification (box 1) is a real, standalone
     step -- diff §3.2's table against the CURRENT `buildTripSummaryBody()`
     line by line BEFORE writing any XML, per PRD §4 P2. If it finds a
     discrepancy, fix the PRD's own table first, then continue.
   - `activity_trip_detail.xml`: `ScrollView > LinearLayout`, matching
     `activity_trip_history.xml`'s own established structural pattern
     (see that file for the real convention this app already uses --
     don't invent a different container shape). Every `MaterialCardView`
     gets an explicit `app:cardCornerRadius` and `app:cardElevation` --
     PRD §4 P3 is explicit that theme defaults can't be trusted under
     this app's actual `Theme.AppCompat.DayNight` parent.
   - `TripDetailActivity.java`: mirror this codebase's own established
     Activity style (explicit `findViewById` per view, `PythonBridge.
     getEngine(this)`, `JSONException | PyException` catch blocks) --
     look at `TripHistoryActivity.java`'s existing methods for the
     house style, don't introduce a different pattern (no data-binding
     library, per PRD §2's own non-goal).
   - Score pill: call `OverlayHelper.baseColorForScoreLabel`/
     `isPoorScoreLabel`/`stripedDrawable` directly. Do not reimplement
     any part of that logic a third time.
   - §3.4's feedback-chaining rework: implement per whatever the driver
     answered for PRD §5 open question #2 -- re-read that answer before
     writing this box, don't default to the PRD's own "recommended"
     framing if the driver picked the alternative.
   - Deleting the two existing `buildTripSummaryBody()` copies is not
     optional cleanup -- it's its own checklist box. Confirm via `grep`
     that no other call site references either copy before deleting.
4. Match the existing codebase's own voice: comments explain WHY, not
   what -- name the real thing being fixed ("previously duplicated in
   two Activities...", "the AlertDialog's single OK button previously
   gated this...").
5. Check the box in PRD.md §6, ONLY after the change is made (or, for
   the manual-verification items, only after they were actually
   exercised -- don't check them from code inspection alone).
6. Append one entry to `docs/trip_history_redesign/PROGRESS.md` (create
   it on the first iteration): what was done, what file(s) changed, and
   (for verification items) what was actually observed/tested.
7. Stop. Do not continue to the next box in the same iteration.

Guardrails:
- Scoped to `activity_trip_detail.xml` (new), `TripDetailActivity.java`
  (new), `AndroidManifest.xml` (one new Activity declaration),
  `MainActivity.java` and `TripHistoryActivity.java` (their
  `buildTripSummaryBody()` copies removed, their 3 call sites updated to
  launch the new Activity). Do NOT touch the trip picker
  (`showTripHistoryFiltered`'s `AlertDialog`), the other 5 report
  dialogs, or any Python file -- all explicitly out of scope per PRD §2.
- No new Gradle dependency -- `com.google.android.material:material:1.12.0`
  already provides `MaterialCardView`; no `RecyclerView`, no
  data-binding/view-binding library.
- No Android SDK/emulator/device in this environment. Verification is
  code review, XML well-formedness checks, and a manual
  `findViewById`-to-layout-id cross-check (PRD §4 P4) -- never claim a
  card was confirmed rendering correctly on-device unless it actually
  was.
- If an iteration finds the PRD itself needs a change (a field §3.2
  missed, a call site not accounted for), stop and say so instead of
  improvising past it.
- The final box (driver sign-off) is never yours to check.
