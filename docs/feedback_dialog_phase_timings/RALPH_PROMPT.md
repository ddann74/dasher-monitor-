# Ralph loop — phase-timing scoring on the feedback dialog (§4B only)

§4A (wiring phase timings into the automatic feedback dialog) is
already IMPLEMENTED — see PROGRESS.md. This prompt is for §4B (per-
phase quartile scoring) only.

DO NOT RUN THIS YET. `docs/feedback_dialog_phase_timings/PRD.md` §4B
is explicitly blocked on the same joint per-job schema design already
called out in `docs/deadhead_stacked_order_baseline/` §7/§8 and
`docs/hourly_rate_actual_vs_estimated/` §5. That design has NOT
happened yet in any of the three PRDs that now depend on it. Do not
build phase-duration scoring on top of `phase_breakdown` data that's
already known to be wrong for stacked/batch orders (first-job-only,
and the pickup timestamp last-wins bug) — check
`docs/deadhead_stacked_order_baseline/PROGRESS.md` for whether that
joint design exists before doing anything here.

Once it does, run this prompt repeatedly (one iteration per
invocation) until every remaining box in
`docs/feedback_dialog_phase_timings/PRD.md` §6 is checked.

Each iteration:

1. Read `docs/feedback_dialog_phase_timings/PRD.md` §4B/§6 and
   `docs/feedback_dialog_phase_timings/PROGRESS.md`. If the joint
   per-job schema design still doesn't exist, stop and say so.
2. Pick the FIRST unchecked box, top to bottom.
3. Implement exactly that item, mirroring
   `docs/market_relative_score_thresholds/` §4B's own pattern (gate on
   a minimum sample count, quartile the real historical distribution,
   fall back to "not enough data" below that count, surface the
   ranking transparently rather than silently).
4. Match the codebase's own voice: comments explain WHY (cite that
   `trips`' phase timestamp columns already exist for every completed
   trip, no new capture needed — only the scoring on top is new), not
   what.
5. Check the box only after the change is made (or, for the
   executable-test item, only after it was actually run).
6. Append one entry to `docs/feedback_dialog_phase_timings/PROGRESS.md`.
7. Stop. Do not continue to the next box in the same iteration.

Guardrails:

- Do not touch §4A's already-implemented dialog wiring.
- Do not invent the joint per-job schema yourself — it's shared with
  two other PRDs and needs to be designed once, consistently, not
  three times differently.
- Every learned quartile boundary MUST be gated on a minimum sample
  count and fall back to something honest (e.g. "not enough history
  yet") below that count.
- The Python half is genuinely testable in this sandbox with plain
  python3 — write and RUN a real test, not just code review.
- If an iteration finds the PRD itself needs a change, stop and say so
  instead of improvising past it.
- The final box (driver sign-off) is never yours to check.
