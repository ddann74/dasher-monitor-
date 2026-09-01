# Ralph loop — hourly-rate estimate vs. actual result

DO NOT RUN §4.B YET. §5's two open questions ARE now answered (see
PRD §5 / PROGRESS.md, 2026-08-31) — but that only settled *what* to
build (a real `accepted_ts` field; a per-job schema shared with
`docs/deadhead_stacked_order_baseline/` §7), not *how*. The actual
per-job schema (columns/table shape, how it plugs into both this PRD
and the deadhead PRD's per-job timing work) has not been designed yet
— §7/§8 of that PRD explicitly forbids starting without one, and the
same applies here. §4.A has no such blocker and is already implemented.

Once that joint per-job schema design exists (a new PRD section, or
its own PRD, covering both this file's payout capture and
`docs/deadhead_stacked_order_baseline/` §7's phase timing together),
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
   persistence): STOP and confirm PROGRESS.md (this file's, or
   `docs/deadhead_stacked_order_baseline/`'s) records an actual joint
   per-job schema design, not just the §5 answers. §5 says an
   `accepted_ts` field and a per-job (not per-trip) shape are the right
   direction — it does NOT specify the real table/columns. If that
   design doesn't exist yet, do not implement — say so instead and stop
   the loop.
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
- For §4.B: §5's answers are settled; do not re-litigate them. What's
  still missing is the actual joint per-job schema design (shared with
  `docs/deadhead_stacked_order_baseline/` §7) — do not invent that
  schema yourself either, it needs its own design pass.
- If an iteration finds the PRD itself needs a change, stop and say so
  instead of improvising past it.
- The final box (driver sign-off) is never yours to check.
