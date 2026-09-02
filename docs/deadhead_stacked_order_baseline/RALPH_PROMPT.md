# Ralph loop — fix deadhead measurement for a stacked/batch-order pickup

Run this prompt repeatedly (one iteration per invocation) until every box
in `docs/deadhead_stacked_order_baseline/PRD.md` §6 is checked.

**Part 1 (§6) is done** - all boxes checked except user sign-off.
**Part 2A (§7.4-§7.5) is also done** (2026-09-02) - the design pass this
guardrail required was completed (§7.4) and then implemented per its
own recommendation (no driver override given), fixing a real data-loss
bug found during that pass (§7.4.1: a stacked order's earlier job never
got persisted to `offer_distance_accuracy` at all). **Do NOT start on
§8 (Part 2B, dropoff-side per-job phase timing) from this prompt** - §7.6
is now a completed design pass (2026-09-02), and it explains exactly
why §8 is STILL blocked: two real linkage heuristics were identified
and put to the driver directly, who chose to gather real evidence (a
stacked-order dropoff screenshot/diagnostic log) rather than build
either one blind - see §7.6.2/§7.6.3. Do not build the ordinal-pairing
heuristic (or any other) on your own initiative even under a blanket
"continue" instruction - this was already put to the driver and
answered "gather evidence first," which is a real, already-given
answer, not an unanswered open question to fall back to a stated
recommendation on. Wait for that evidence (a new screenshot/log
attached to a future message) before resuming §8.

---

You are implementing `docs/deadhead_stacked_order_baseline/PRD.md` for
the `dasher-monitor-` repo, one checklist item at a time.

Each iteration:

1. Read `docs/deadhead_stacked_order_baseline/PRD.md` §6 and
   `docs/deadhead_stacked_order_baseline/PROGRESS.md` (create it if
   missing).
2. Pick the FIRST unchecked box, top to bottom.
3. Implement exactly that item, scoped to `TripManager.add_pickup`,
   `TripManager._evaluate_pickup`, and `TripManager._start_trip` in
   `drive_monitor.py` only.
4. Match the codebase's own voice: comments explain WHY (cite the real
   asymmetry found - `actual_delivery_km` already does this correctly,
   `actual_deadhead_km` didn't), not what.
5. Check the box only after the change is made (or, for the executable-
   test item, only after it was actually run - don't check it from code
   inspection alone).
6. Append one entry to `docs/deadhead_stacked_order_baseline/PROGRESS.md`.
7. Stop. Do not continue to the next box in the same iteration.

Guardrails:

- Do not touch `deadhead_score`'s formula, `WEIGHT_DEADHEAD`, or
  `SmartScoreEngine._estimate_deadhead_km`'s fallback logic - PRD §2 non-
  goals, this is a measurement fix only.
- Do not add a minimum-sample-count gate to `_estimate_deadhead_km` -
  PRD §1.6/§2 flags this as real but explicitly out of scope for this
  PRD.
- Do not touch `_distance_at_departure_km`/`actual_delivery_km`'s
  existing logic - it's the reference implementation this fix mirrors,
  not something to change.
- Do not backfill or modify any existing `offer_distance_accuracy` rows -
  PRD §2 non-goals, no reliable way to tell which historical rows are
  affected.
- The Python half of this fix is genuinely testable in this sandbox with
  plain python3 (same as `docs/safety_score_speeding_debounce/PROGRESS.md`)
  - write and RUN a real test proving both the single-pickup case is
  unchanged AND the stacked-order case is corrected, not just code review.
- If an iteration finds the PRD itself needs a change, stop and say so
  instead of improvising past it.
- The final box (user sign-off) is never yours to check.
