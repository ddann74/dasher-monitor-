# Ralph loop — hourly-rate estimate vs. actual result

DO NOT RUN THIS YET. `docs/hourly_rate_actual_vs_estimated/PRD.md` is a
DRAFT — it has open questions in §5 that need a driver decision (most
importantly: what timestamp marks "offer accepted," since that decides
what "actual" even means) and part B needs its own schema-design
sign-off before code should be written for it. This prompt exists
because it was asked for directly; it is not authorization to start.

Once the driver says "yes implement it" (or explicitly answers §5),
run this prompt repeatedly (one iteration per invocation) until every
box in `docs/hourly_rate_actual_vs_estimated/PRD.md` §6 is checked.

Each iteration:

1. Read `docs/hourly_rate_actual_vs_estimated/PRD.md` §6 and
   `docs/hourly_rate_actual_vs_estimated/PROGRESS.md` (create it if
   missing).
2. Pick the FIRST unchecked box, top to bottom.
3. **If the box is under §4.A** (the wait-time fix to
   `estimate_minutes_from_distance`/`calculate()`): implement exactly
   that item, scoped to those two methods in
   `app/src/main/python/drive_monitor.py` only. This part has no open
   questions — it's safe to implement without further sign-off.
4. **If the box is under §4.B** (payout capture / actual-vs-estimated
   persistence): STOP and confirm the driver has actually answered
   PRD §5's open questions (the accepted-ts definition, and whether
   this should be designed jointly with
   `docs/deadhead_stacked_order_baseline/` §7's per-job timing work)
   before touching schema or `add_pickup`/`_start_trip`. If there's no
   record of that answer in PROGRESS.md yet, do not implement — say so
   instead and stop the loop.
5. Match the codebase's own voice: comments explain WHY (cite the real
   gap found — the estimate already has `avg_wait` available in the
   same function call and doesn't use it; the JSON snapshot doesn't
   even retain payout, so nothing could be compared even if someone
   tried), not what.
6. Check the box only after the change is made (or, for the
   executable-test item, only after it was actually run — don't check
   it from code inspection alone).
7. Append one entry to `docs/hourly_rate_actual_vs_estimated/PROGRESS.md`.
8. Stop. Do not continue to the next box in the same iteration.

Guardrails:

- Do not touch `WEIGHT_HOURLY_RATE`, `hourly_score`'s formula, or
  `_get_calibrated_weights` — PRD §3 non-goals, this is an estimate-
  accuracy fix, not a reweighting.
- Do not touch `deadhead_score`, `wait_score`, or any other factor in
  `calculate()` — only `hourly_rate`/`est_minutes`.
- The Python half of this is genuinely testable in this sandbox with
  plain python3 (`drive_monitor.py` has zero Android/Chaquopy
  dependency) — write and RUN a real test for §4.A proving the wait
  time actually changes `hourly_rate`, not just code review.
- For §4.B: do not invent an answer to the "what timestamp counts as
  accepted" open question yourself — it changes what the feature
  actually measures, and PRD §5 already says this needs a real driver
  decision, not a coding judgment call.
- If an iteration finds the PRD itself needs a change, stop and say so
  instead of improvising past it.
- The final box (driver sign-off) is never yours to check.
