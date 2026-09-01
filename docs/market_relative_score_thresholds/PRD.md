# PRD — make the Smart Score's fixed thresholds market-relative (learned)

Status: DRAFT. Investigation only, grounded in the real code and real
schema. Nothing implemented yet — do not start coding from this PRD
until the driver says "yes implement it." §4B (added at the driver's
request) is a second, alternative design — see §5 for how the two
relate; this PRD does not yet pick one over the other.

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

## 4B. Alternative design (added at driver's request): quartile the composite score, not each factor

A different, simpler shape for the same underlying idea — instead of
making each of the four factor-level anchors in §4 market-relative,
leave `calculate()`'s formulas exactly as they are and only change how
the final composite `final_score` gets its **label and badge color**.

### How labeling/color actually works today (confirmed by reading the real code)

`SmartScoreEngine._label(score)` (drive_monitor.py) buckets the
composite `final_score` at fixed breakpoints:

| final_score | label |
|---|---|
| >= 85 | Excellent |
| >= 70 | Good |
| >= 50 | Fair |
| < 50 | Poor |

`DasherAccessibilityService.colorForLabel(label)` (Java) then maps
that label straight to the live badge's color (deep green / lighter
green / amber / red) — the same label also drives the badge text and
`HapticFeedback.vibrateForLabel`. Both the 85/70/50 breakpoints and the
four colors are fixed, identical for every driver and every market.

### The alternative

`offer_outcomes.smart_score` already stores the real composite
`final_score` for **every** offer this app has ever scored (accepted,
declined, or timed out — not just completed trips, same broad, low-
bias sample §2 already relies on for `base_score`/`hourly_score`).
Instead of the fixed 85/70/50 breakpoints, compute the driver's own
recent quartile boundaries from that column (e.g. 25th/50th/75th
percentile of `smart_score` over the same window §5 picks) and label
against those instead: "Excellent" becomes "top quartile of what
you've actually been offered lately," "Poor" the bottom quartile.
`_label()`'s three-way branch structure doesn't need to change shape,
only what breakpoints it compares against — same "gate on a minimum
sample count, fall back to the fixed constants below that" pattern as
everything else in this file.

### How this relates to §4 (not both at once)

§4 makes the six *inputs* to `final_score` market-relative. §4B makes
the *label placed on the output* market-relative, leaving every input
formula untouched. Doing both simultaneously would be confusing for
the reason §5's calibration question already flags: a score could
drift for two independent reasons at once (each factor's own anchor
moving, AND the label thresholds on top of that also moving), with no
way to tell which one changed. This PRD does not pick between them —
see the new open question below.

## 5. Open questions (need a driver decision before implementation)

- **§4 vs. §4B, or a staged combination**: build the per-factor anchors
  (§4), the composite-label quartiles (§4B), both, or start with just
  one and revisit? §4B is the smaller, more self-contained change (one
  function's breakpoints, not four formulas) and reuses data already
  proven to work for §4's own `base_score`/`hourly_score` case — a
  reasonable candidate to build first if the driver wants to start
  somewhere concrete, but that's a product call, not a coding one.
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

- [ ] §5's open questions answered by the driver, INCLUDING which of
      §4/§4B (or both, or a staged order) to actually build (recorded
      in PROGRESS.md before any of the boxes below are started).

§4 (per-factor anchors), if chosen:
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

§4B (composite-score quartile labeling), if chosen:
- [ ] `_label()`'s breakpoints learnable from `offer_outcomes.smart_score`'s
      real quartile distribution, gated on a minimum sample count,
      falling back to the exact fixed 85/70/50 breakpoints below that
      count.
- [ ] `colorForLabel`/the badge/haptics need no change — they already
      key off the label string, not a raw score number.
- [ ] Real executable test: same `final_score`, label changes once
      enough synthetic historical `offer_outcomes` rows shift the
      quartile boundaries around it.

- [ ] Driver sign-off.
