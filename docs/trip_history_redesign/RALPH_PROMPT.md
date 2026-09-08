# Ralph loop -- Trip History redesign (detail screen + trip list)

All 3 open questions in `docs/trip_history_redesign/PRD.md` §5 are
resolved -- not blocked. Run this prompt repeatedly (one iteration per
invocation) until every box in `docs/trip_history_redesign/PRD.md` §6
is checked.

---

You are implementing `docs/trip_history_redesign/PRD.md` for the
`dasher-monitor-` repo, one checklist item at a time.

Each iteration:

1. Read `docs/trip_history_redesign/PRD.md` in full (especially §1's
   two-call-site investigation, §3.4's corrected feedback-chaining
   design, and §4's premortem) and `docs/trip_history_redesign/
   PROGRESS.md` if it exists yet.
2. Pick the FIRST unchecked box in §6, top to bottom -- do not skip
   ahead, do not batch multiple boxes in one iteration.
3. Implement exactly that item:
   - The field-mapping re-verification and the `notifyRateThisDelivery()`
     re-read (boxes 1-2) are real, standalone steps -- do them before
     writing any code, per PRD §4 P1/P2. If either finds a discrepancy
     from what the PRD describes, fix the PRD's own text first, then
     continue.
   - `activity_trip_detail.xml`/`TripDetailActivity.java`: mirror
     `TripHistoryActivity.java`'s existing house style (explicit
     `findViewById`, `PythonBridge.getEngine(this)`,
     `JSONException | PyException` catches). Every `MaterialCardView`
     gets explicit `app:cardCornerRadius`/`app:cardElevation` (PRD §4
     P3 -- don't trust theme defaults under this app's actual
     `Theme.AppCompat.DayNight` parent).
   - The `notifyRateThisDelivery()` retarget (§3.4) is the highest-risk
     single change in this PRD (P1) -- change ONLY the `Intent`'s target
     class and extras (`TripDetailActivity` +
     `EXTRA_PROMPT_FEEDBACK_ON_CLOSE=true` instead of `MainActivity` +
     `auto_show_feedback_trip_id`). Do NOT touch the surrounding
     BAL-exemption launch sequence (the overlay-tap-through, the direct
     `startActivity` attempt, the full-screen-intent notification
     fallback) -- that mechanism is already proven and explicitly out
     of scope per PRD §2's own non-goals.
   - `MainActivity.onCreate()`'s `auto_show_feedback_trip_id` handling:
     delete it as its own checklist step, only once nothing sends that
     extra anymore -- confirm via `grep` first.
   - `activity_trip_list.xml`/`TripListActivity.java` (§3.5): same
     `ScrollView > LinearLayout` pattern, filter chips reusing
     `showTripHistoryFiltered()`'s existing three-way client-side
     filter logic (just re-rendered, not re-designed), row tap launches
     `TripDetailActivity` WITHOUT the feedback extra.
   - Deleting the two `buildTripSummaryBody()` copies and the old
     `AlertDialog.setItems()` trip-picker body are each their own
     checklist boxes -- confirm via `grep` that nothing else references
     them before deleting.
4. Match the existing codebase's own voice: comments explain WHY, not
   what -- name the real thing being fixed ("previously duplicated in
   two Activities...", "notifyRateThisDelivery() used to open
   MainActivity directly to the feedback dialog, skipping any summary
   -- now opens...").
5. Check the box in PRD.md §6, ONLY after the change is made (or, for
   the manual-verification items, only after they were actually
   exercised -- don't check them from code inspection alone).
6. Append one entry to `docs/trip_history_redesign/PROGRESS.md` (create
   it on the first iteration): what was done, what file(s) changed, and
   (for verification items) what was actually observed/tested.
7. Stop. Do not continue to the next box in the same iteration.

Guardrails:
- Scoped to `activity_trip_detail.xml`, `TripDetailActivity.java`,
  `activity_trip_list.xml`, `TripListActivity.java` (all new),
  `AndroidManifest.xml` (two new Activity declarations),
  `MainActivity.java` (delete `buildTripSummaryBody()` +
  `auto_show_feedback_trip_id` handling, retarget
  `showLastTripSummaryThenPromptFeedback()`), `TripHistoryActivity.java`
  (delete `buildTripSummaryBody()` + `showTripHistoryFiltered()`'s
  dialog body), and `TripForegroundService.java` (`notifyRateThisDelivery()`'s
  intent target ONLY, per above). Do NOT touch the other 5 report
  dialogs or any Python file -- explicitly out of scope per PRD §2.
- No new Gradle dependency.
- No Android SDK/emulator/device in this environment. Verification is
  code review, XML well-formedness checks, and a manual
  `findViewById`-to-layout-id cross-check -- never claim a card or the
  feedback-chaining flow was confirmed working on-device unless it
  actually was.
- If an iteration finds the PRD itself needs a change, stop and say so
  instead of improvising past it.
- The final box (driver sign-off) is never yours to check.
