# PRD — make the Smart Score's fixed thresholds market-relative (learned)

Status: DRAFT. Investigation only, grounded in the real code and real
schema. Nothing implemented yet — do not start coding from this PRD
until the driver says "yes implement it."

## 1. What "fixed thresholds" means here

`SmartScoreEngine.calculate()` (`app/src/main/python/drive_monitor.py`)
turns each raw factor into a 0-100 sub-score using a hardcoded scale
point, not anything learned:

| Factor | Formula | Hardcoded anchor |
|---|---|---|
| `base_score` | `min(100, (base_rate/2.0)*100)` | $2.00/km == 100 |
| `hourly_score` | `min(100, (hourly_rate/60.0)*100)` | $60/hr == 100 (comment: "$30/hr baseline == ~50") |
| `deadhead_score` | `max(0, 100 - deadhead_km*10)` | 10km deadhead == 0 |
| `wait_score` | `max(0, min(100, 100-(avg_wait-3)*8))` | 3min wait == 100, ~15.5min == 0 |
| `weather_score` | `100 - precip*15`, `- (wind-30)*1.5` | fixed mm/kmh slopes |

These are the same for every driver, every market, every day this app
has ever run. Separately, `recalculate_personal_calibration` already
learns something real — but it only adjusts the *weight* each factor
carries in the final average (bounded ±15%), never *where the anchor
sits*. A driver in a market where $1.50/km is actually a great offer
still sees `base_score` capped as if $2.00/km were the norm everywhere.

## 2. What's actually learnable from data already being captured

Checked `offer_outcomes` (`app/src/main/python/drive_monitor.py:367`):
it stores `payout` and `distance_km` as their own columns, for **every
scored offer shown**, accepted or declined or timed out — not just
completed trips. This is a much larger, less-biased sample than
`trips`/`offer_distance_accuracy` (which only exist for offers you
accepted and drove).

- **`base_score` ($/km)**: directly learnable. `payout/distance_km`
  can be computed for every row already in `offer_outcomes`; an Nth
  percentile of that distribution (e.g. your market's own real
  75th-percentile $/km over a recent window) replaces the fixed $2.00.
- **`hourly_score` ($/hr)**: approximately learnable. `distance_km` is
  there, but the est_minutes used at the time isn't stored per-row —
  only today's `_learned_delivery_speed_kmh()` is available.
  Recomputing each historical offer's $/hr using the *current* learned
  speed (not the speed that was actually in effect when that offer was
  shown) is an approximation, not a backtest, but a reasonable one.
- **`deadhead_score`**: learnable from `offer_distance_accuracy.actual_deadhead_km`
  (same table `docs/deadhead_stacked_order_baseline/` just fixed) —
  smaller sample (accepted+completed only), but real.
- **`wait_score`**: learnable from `restaurant_wait_history`'s own
  aggregate distribution across restaurants.
- **`weather_score`**: NOT learnable this way — weather is only ever
  read live (`_get_weather_score`), never persisted per-offer anywhere
  in the schema. Out of scope; see §3.

## 3. Non-goals

- Not touching `weather_score` — no historical weather data exists to
  learn a threshold from; would need a new capture point, out of scope
  here.
- Not touching `time_score` — already fixed this session (see
  `docs/math_calculation_audit/`); it's a binary risk flag, not a
  continuous threshold, and isn't part of this PRD's problem.
- Not removing or replacing `recalculate_personal_calibration` — see
  the open question in §5 on how the two should coexist; this PRD
  doesn't presuppose an answer.

## 4. Proposed design (for review, not yet approved)

For each learnable factor (`base_score`, `hourly_score`,
`deadhead_score`, `wait_score`):

1. Gate on a minimum sample count from the relevant table, same
   pattern as every other learned value in this file (deadhead,
   restaurant wait, delivery speed all already do this) — falls back
   to the exact current fixed anchor until enough real data exists.
2. Compute a percentile (a specific number needs picking — see §5) of
   the real historical distribution as the new "== 100" (or "== 0" for
   the penalty-shaped ones) anchor, recomputed periodically (e.g. on
   the same cadence `recalculate_personal_calibration` already runs,
   or lazily with a cached staleness check — not per-call from a
   4-column table scan, though `offer_outcomes` is small enough that
   this is a minor concern either way).
3. Feed that anchor into the existing formula shape unchanged — i.e.
   `base_score = min(100, (base_rate/learned_anchor)*100)` instead of
   `/2.0` — not a new scoring model, just a variable anchor.
4. Surface the anchor transparently in `calculate()`'s return dict
   (the same "why" transparency principle every other learned value in
   this file already follows) so a driver can see "$2.10/km == 100
   (based on your last N offers)" instead of a bare number.

## 5. Open questions (need a driver decision before implementation)

- **Interaction with `recalculate_personal_calibration`**: once an
  anchor is itself market-relative, does per-factor weight calibration
  still mean the same thing? A factor could simultaneously have its
  anchor drift toward "typical" (making everything average score near
  50) AND its weight drift based on correlation with your
  satisfaction. Recommend deciding whether these should run on
  visibly separate, clearly labeled numbers (an "anchor" and a
  "weight," never silently combined) rather than trying to have this
  PRD unify them into one mechanism.
- **Percentile choice and window**: a specific value (e.g. "75th
  percentile of the last 90 days" vs. "median of all-time") needs to
  be picked deliberately, not left as an implementation detail —
  changes what "100" means to the driver.
- **Which sample population for `base_score`/`hourly_score`**: all
  scored offers (`offer_outcomes`, includes declined/timed-out — a
  fuller picture of the local market) vs. only accepted ones (smaller,
  but reflects what you actually chose to drive). These can give
  meaningfully different anchors.
- **The circularity risk flagged when this was first raised**: if a
  market has a genuinely bad week, a percentile-based anchor quietly
  redefines "good" downward instead of showing you the market got
  worse. Worth deciding whether the anchor should have a floor (never
  drift below some fraction of the current fixed constant) so "100"
  can't silently mean less than it used to.

## 6. Success criteria (not started — nothing here is implemented yet)

- [ ] §5's open questions answered by the driver (recorded in
      PROGRESS.md before any of the boxes below are started).
- [ ] `base_score` anchor learnable from `offer_outcomes`, gated on a
      minimum sample count, falling back to the fixed $2.00/km anchor
      below that count.
- [ ] `hourly_score` anchor learnable the same way.
- [ ] `deadhead_score` anchor learnable from `offer_distance_accuracy`.
- [ ] `wait_score` anchor learnable from `restaurant_wait_history`.
- [ ] The learned anchor is surfaced in `calculate()`'s return dict,
      not just used silently.
- [ ] Real executable test proving at least one factor: same raw
      input, anchor shifts after seeding synthetic historical data,
      and the resulting sub-score changes accordingly.
- [ ] Driver sign-off.
