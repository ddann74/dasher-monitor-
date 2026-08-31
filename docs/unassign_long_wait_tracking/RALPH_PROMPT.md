# Ralph loop -- record unassigns caused by a long restaurant wait

Run this prompt repeatedly (one iteration per invocation) until every box
in `docs/unassign_long_wait_tracking/PRD.md` §6 is checked.

---

You are implementing `docs/unassign_long_wait_tracking/PRD.md` for the
`dasher-monitor-` repo, one checklist item at a time.

Each iteration:

1. Read `docs/unassign_long_wait_tracking/PRD.md` §6 (Success criteria)
   and `docs/unassign_long_wait_tracking/PROGRESS.md` (create it if it
   doesn't exist yet).
2. Pick the FIRST unchecked box in §6, top to bottom -- do not skip
   ahead, do not batch multiple boxes in one iteration.
3. Implement exactly that item, scoped to `drive_monitor.py` (new
   `record_pickup_unassigned_for_long_wait` method,
   `recalculate_personal_calibration`'s outcome filter) and
   `DasherAccessibilityService.java`'s existing `TYPE_VIEW_CLICKED`
   block only.
4. Match the existing codebase's own voice: comments explain WHY, not
   what -- name the real screenshot this was built from, the real gap
   found (`self.pickup` lingering stale on an unassign), the way this
   repo's other PRDs already reference real evidence rather than
   hypothetical risks.
5. Check the box in PRD.md §6, ONLY after the change is made (or, for
   the executable-test item, only after it was actually run -- don't
   check it from code inspection alone).
6. Append one entry to `docs/unassign_long_wait_tracking/PROGRESS.md`:
   what was done, what file(s) changed.
7. Stop. Do not continue to the next box in the same iteration.

Guardrails:
- This task is scoped to the specific methods named in PRD §3. Do not
  touch `_evaluate_pickup`'s normal departure-based wait recording, the
  existing Accept/Decline click branches, `_safety_score`, the
  wait-score curve, or `WEIGHT_RESTAURANT_WAIT` -- all explicitly out of
  scope per PRD §2.
- Do not invent or thread a `payout` value for this outcome type --
  PRD §1.5 explicitly leaves it `NULL`, honestly, rather than guessing.
- Do not add handling for "No, continue with order" -- not a signal
  worth recording, per PRD §2.
- The Python half of this fix (record_pickup_unassigned_for_long_wait,
  the calibration filter change) CAN be genuinely tested in this
  sandbox with plain python3 -- write and RUN a real test, the same way
  docs/safety_score_speeding_debounce/PROGRESS.md did, not just code
  review.
- The Java click-detection half cannot be verified on-device here --
  no Android emulator/device available. Never claim it was confirmed
  working on a real tap -- only claim what was actually verified (code
  review, and that it mirrors the already-working Accept/Decline
  pattern).
- If an iteration finds the PRD itself needs a change, stop and say so
  instead of improvising past it.
- The final box (user sign-off) is never yours to check.
