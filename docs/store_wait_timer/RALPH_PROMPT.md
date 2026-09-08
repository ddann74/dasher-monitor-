# Ralph loop -- store wait timer (Arrived at Store -> Confirm Pickup)

Run this prompt repeatedly (one iteration per invocation) until every box
in `docs/store_wait_timer/PRD.md` §7 is checked.

---

You are implementing `docs/store_wait_timer/PRD.md` for the
`dasher-monitor-` repo, one checklist item at a time.

Each iteration:

1. Read `docs/store_wait_timer/PRD.md` in full (especially §2 non-goals,
   §3's disclosed button-text risk, and §5's premortem) and
   `docs/store_wait_timer/PROGRESS.md` if it exists yet.
2. Pick the FIRST unchecked box in §7, top to bottom -- do not skip
   ahead, do not batch multiple boxes in one iteration.
3. Implement exactly that item, per §4's design:
   - Schema/persistence work (`drive_monitor.py`): the new
     `trips.store_wait_over_grace_seconds` column + migration, and
     `record_store_wait_timer(over_grace_seconds)` on
     `DriveMonitorEngine`, following the existing
     `_update_current_trip_phase_timestamp` /
     `_update_current_trip_text_column` shape exactly -- do not invent a
     different persistence pattern.
   - Overlay work (`OverlayHelper.java`): `showStoreWaitTimer` /
     `clearStoreWaitTimer`, a small persistent `TextView` updated via
     `setText()` in place (never remove/re-add on every tick), same
     `addView()` `try/catch(RuntimeException)` hardening `showStatusDot`
     already has.
   - Detection + state machine (`DasherAccessibilityService.java`): two
     new `else if` branches in the EXISTING `TYPE_VIEW_CLICKED`
     text-matching block (do not build a parallel event-handling path);
     grace-period Handler/Runnable pair mirroring the existing
     `timeoutHandler`/`pendingTimeoutRunnable` field shape; the tick
     Runnable calls `OverlayHelper.showStoreWaitTimer` once a second
     only after the grace period has actually elapsed.
   - `record_pickup_unassigned_for_long_wait`'s Java call site: also
     cancel any in-progress store-wait timer (grace-period runnable +
     visible overlay), per PRD §5 P4 -- do not leave this as a silent
     gap.
   - `TripHistoryActivity.java`: one new line in the existing "Where The
     Time Went" section, omitted (not shown as "0m 0s") when the field
     is `None`, matching this screen's own established nullable-field
     convention (see how `traffic_ratio`/`feedback_merchant_wait` are
     already handled there).
   - `STORE_WAIT`-tagged diagnostic logging at every real decision point
     (tap detected, grace period elapsed, over-grace duration recorded,
     confirmed within grace period) -- this is PRD §5 P1's own
     mitigation for the disclosed button-text risk, not optional
     polish.
4. Match the existing codebase's own voice: comments explain WHY, not
   what -- name the specific real requirement (the driver's own request,
   quoted or paraphrased) the way this file's existing comments do
   ("Driver-requested (date): ...").
5. Check the box in PRD.md §7, ONLY after the change is made (or, for
   the manual-verification items, only after they were actually
   exercised -- don't check them from code inspection alone).
6. Append one entry to `docs/store_wait_timer/PROGRESS.md` (create it on
   the first iteration): what was done, what file(s) changed, and (for
   verification items) what was actually observed/tested.
7. Stop. Do not continue to the next box in the same iteration.

Guardrails:
- This task is scoped to `DasherAccessibilityService.java`,
  `OverlayHelper.java`, `drive_monitor.py`, and `TripHistoryActivity.java`
  only. Do not touch Accept/Decline's own node-bounds mechanism, the
  GPS-based `pickup_arrival_ts`/`pickup_departure_ts` fields, or
  `merchant_wait_rating` -- all explicitly out of scope per PRD §2.
- Do NOT build the node-bounds-scanning fallback mechanism for "Arrived
  at Store"/"Confirm Pickup" preemptively -- PRD §2 and §5 P2 are
  explicit that this is deferred until real evidence (a diagnostic log
  showing zero click events for these two buttons) says it's needed.
  Building it now without that evidence would be exactly the kind of
  unrequested scope expansion this repo's other PRDs consistently avoid.
- No voice/spoken output anywhere in this feature -- the driver
  explicitly chose overlay + silent log only.
- No physical device available in this environment. Verification is
  code review, brace/paren balance, and real runnable Python tests for
  the pure-Python persistence/summary logic -- same disclosed limitation
  as every other Java-touching PRD in this repo. Never claim the overlay
  was confirmed visible on-device, or that a real tap on Dasher's actual
  button was confirmed detected, unless it actually was.
- If an iteration finds PRD §3's disclosed risk has become concrete --
  i.e. you find independent evidence (not required to go looking for it,
  but if it surfaces) that suggests the button text guess is wrong --
  stop and say so rather than silently proceeding as if it were
  confirmed.
- If an iteration finds the PRD itself needs a change (missed case,
  wrong assumption), stop and say so instead of improvising past it.
- The final box (driver sign-off) is never yours to check.
