# Ralph loop — market-relative Smart Score thresholds

DO NOT RUN THIS YET. `docs/market_relative_score_thresholds/PRD.md` §5
has open questions that need a real driver decision (percentile/window
choice, which sample population, how this coexists with
`recalculate_personal_calibration`, and whether the anchor needs a
floor) — none of these are coding judgment calls. Do not start §6 until
PROGRESS.md records the driver's actual answers to §5.

Once those answers exist, run this prompt repeatedly (one iteration
per invocation) until every box in
`docs/market_relative_score_thresholds/PRD.md` §6 is checked.

Each iteration:

1. Read `docs/market_relative_score_thresholds/PRD.md` §5/§6 and
   `docs/market_relative_score_thresholds/PROGRESS.md` (create it if
   missing). If §5 isn't answered yet, stop and say so instead of
   guessing an answer.
2. Pick the FIRST unchecked box, top to bottom.
3. Implement exactly that item, scoped to `SmartScoreEngine` in
   `app/src/main/python/drive_monitor.py` only — new small helper
   methods (e.g. `_learned_base_rate_anchor`) mirroring the existing
   three-tier-fallback pattern (`_estimate_deadhead_km`,
   `_restaurant_wait_info`, `_learned_delivery_speed_kmh` are the
   reference shape: query real history, gate on a minimum sample
   count, fall back to the current fixed constant below that count).
4. Match the codebase's own voice: comments explain WHY (cite that
   `offer_outcomes` already captures payout/distance_km for every
   scored offer, not just completed ones, and that the fixed anchors
   were the same for every driver/market before this), not what.
5. Check the box only after the change is made (or, for the
   executable-test item, only after it was actually run — don't check
   it from code inspection alone).
6. Append one entry to `docs/market_relative_score_thresholds/PROGRESS.md`.
7. Stop. Do not continue to the next box in the same iteration.

Guardrails:

- Do not touch `weather_score` or `time_score` — PRD §3 non-goals.
- Do not touch `recalculate_personal_calibration`'s own logic — only
  build the new anchor-learning alongside it; how the two interact is
  whatever the driver answered in §5, not something to redesign here.
- Every new learned anchor MUST be gated on a minimum sample count and
  fall back to the EXACT current fixed value below that count — a
  driver with little history must see the same score they'd have seen
  before this PRD, not a noisy anchor from 2 data points.
- Surface every learned anchor in `calculate()`'s return dict (PRD §4
  point 4) — never let a threshold move silently.
- The Python half of this is genuinely testable in this sandbox with
  plain python3 (`drive_monitor.py` has zero Android/Chaquopy
  dependency) — write and RUN a real test, not just code review.
- If an iteration finds the PRD itself needs a change, stop and say so
  instead of improvising past it.
- The final box (driver sign-off) is never yours to check.
