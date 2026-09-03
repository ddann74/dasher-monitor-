# PRD — make the Smart Score's fixed thresholds market-relative (learned)

Status: IMPLEMENTED and tested (2026-09-03) — driver said "yes
implement it" and then answered all of §5's open questions directly
(see §7). §4B (composite-score quartile labeling) was built, NOT §4
(per-factor anchors) — driver's own choice, see §7.1.

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

## 5. Open questions — ANSWERED (2026-09-03), see §7 for the writeup

- ~~§4 vs. §4B, or a staged combination~~ → **§4B only**, driver's
  direct choice.
- **Interaction with `recalculate_personal_calibration`**: not
  re-asked separately — §4B doesn't touch any factor-level anchor or
  weight, so this PRD's own recommendation (keep them visibly separate)
  was moot for the design actually chosen; personal calibration is
  completely untouched by this change.
- ~~Percentile choice and window~~ → **75th/50th/25th percentile, last
  90 days.**
- ~~Which sample population~~ → **all scored offers** (accepted,
  declined, timed out) — matches §4B's own text, which already
  proposed this same population; driver confirmed directly rather than
  it being assumed.
- ~~Circularity floor~~ → **yes, add a floor** — see §7.2 for the exact
  mechanism chosen.

## 6. Success criteria

§4 (per-factor anchors) — NOT built, driver chose §4B only:
- [ ] N/A — see §5.

§4B (composite-score quartile labeling) — built:
- [x] `_label()`'s breakpoints learnable from `offer_outcomes.smart_score`'s
      real quartile distribution, gated on a minimum sample count,
      falling back to the exact fixed 85/70/50 breakpoints below that
      count.
- [x] `colorForLabel`/the badge/haptics needed no change — confirmed,
      they key off the label string, not a raw score number.
- [x] Real executable test: 7 cases, all passed (see §7.3).
- [x] Floor mechanism implemented and tested (§7.2) — a uniformly bad
      market can't collapse the thresholds below
      `LABEL_QUARTILE_FLOOR_FRACTION` of the fixed constants.
- [x] Learned-vs-fixed transparency surfaced in `calculate()`'s return
      dict (`label_is_learned`, `label_sample_count`), not just used
      silently — same "why" principle every other learned value in
      this file already follows.

- [ ] Driver sign-off.

## 7. Implementation writeup (2026-09-03)

### 7.1 §4B chosen, §4 not built

Driver picked §4B (recommended) directly when asked. §4 (per-factor
anchors on `base_score`/`hourly_score`/`deadhead_score`/`wait_score`)
remains exactly as designed in §4 above if ever wanted later — not
started, not half-built.

### 7.2 Design actually implemented

New `SmartScoreEngine._learned_label_thresholds()`: queries
`offer_outcomes.smart_score` for `is_test_data = 0`, any outcome,
`timestamp >=` a 90-day cutoff (`LABEL_QUARTILE_WINDOW_DAYS`). Below
`LABEL_QUARTILE_MIN_SAMPLES = 25` (same scale as the existing
`CALIBRATION_MIN_SAMPLES` precedent — quartiles of a tiny sample are
unstable), returns the original fixed `(85, 70, 50)` with
`is_learned=False`. At or above that count, computes the 75th/50th/25th
percentile via a new module-level `_percentile()` helper (linear
interpolation, no new dependency — Chaquopy's bundled Python version
isn't guaranteed to include `statistics.quantiles`, added in 3.8), then
applies the driver-approved floor: each learned breakpoint is
`max(percentile, FIXED_BREAKPOINT * LABEL_QUARTILE_FLOOR_FRACTION)`
(`LABEL_QUARTILE_FLOOR_FRACTION = 0.7`, a deliberately disclosed,
round-number choice — not independently re-confirmed with the driver
beyond "yes, add a floor," open to adjustment if 0.7 turns out wrong in
practice). A cheap safety clamp afterward (`good = min(good,
excellent)`, `fair = min(fair, good)`) keeps the three thresholds
strictly ordered even in an edge-case distribution where a floor and a
percentile might otherwise disagree on ordering.

`_label(score)` (previously `@staticmethod`, now a real instance
method — confirmed both existing call sites already called it via an
instance, `self._label(...)`/`self.smart_score._label(...)`, so this
needed no call-site changes) now fetches the learned thresholds and
buckets against them via new `_bucket_label(score, excellent, good,
fair)`. `calculate()` computes the thresholds ONCE directly (not via
`self._label()`, which would re-run the same query) and reuses them for
both the label itself and two new transparency fields on its return
dict: `label_is_learned`, `label_sample_count` — the same "why"
principle every other learned value in this file already follows (e.g.
`deadhead_is_restaurant_specific`).

### 7.3 Verification

Real, runnable Python test (`test_market_relative_label_thresholds.py`,
7 cases, all passed):

1. Below `LABEL_QUARTILE_MIN_SAMPLES` → exact fixed 85/70/50,
   `is_learned=False`, and `_label()` still buckets correctly against
   the fixed constants.
2. 25 samples, a real spread distribution → learned thresholds match
   `_percentile()` called directly on the same sorted data (cross-
   checked independently, not just re-deriving the same formula).
3. **The floor, the whole point of this PRD's driver-flagged risk**: 30
   samples, every single offer scoring 20 (a uniformly bad market) →
   without a floor, "Excellent" would collapse to 20; confirmed it
   instead holds at exactly `85 * 0.7 = 59.5`, and all three thresholds
   stay correctly ordered.
4. Samples older than 90 days are excluded from the window.
5. `is_test_data = 1` rows are excluded.
6. `_bucket_label()` boundary correctness at each of the four buckets.
7. `_percentile()` itself: known linear-interpolation values, including
   the single-element edge case.

Re-ran the full existing scratchpad test suite after this change — no
regressions (the one pre-existing failure, `test_dropoff_instruction_
wiring.py`, predates this pass entirely and is already tracked as
stale). `drive_monitor.py` recompiles cleanly. No Java changes were
needed for this PRD — confirmed directly, `colorForLabel`/the live
badge/haptics all key off the label STRING (`"Excellent"`/`"Good"`/
etc.), never a raw score number, so a driver whose market has genuinely
shifted just sees the label move under otherwise-identical Java code.
