# Progress log — hourly-rate estimate vs. actual result

## Implementation (2026-08-31) — §4.A only

`SmartScoreEngine.estimate_minutes_from_distance` gained an optional
`restaurant_name` parameter. When given, it now adds that restaurant's
learned average wait (`_restaurant_wait_info` -- the exact same
three-tier fallback already used for `wait_score`: restaurant-specific
-> overall average -> hardcoded 6.0min default) on top of the existing
drive-time estimate. Omitting `restaurant_name` leaves the method's
behavior byte-for-byte identical to before (pure drive-time-only) --
backward compatible for any caller that doesn't pass it.

Both real call sites (`parse_offer_screen` and
`parse_offer_notification`, both in `drive_monitor.py`) already had
`restaurant_name` available in scope and now pass it through. No other
caller exists.

No change to `WEIGHT_HOURLY_RATE`, `hourly_score`'s formula, or any
other factor in `calculate()` -- confirmed via diff review, matching
PRD §3's non-goals.

## §4.B — deliberately NOT started

PRD §5's open questions (what timestamp counts as "accepted," and
whether this should be designed jointly with
`docs/deadhead_stacked_order_baseline/` §7's per-job timing work) have
not been answered by the driver. Per RALPH_PROMPT.md's explicit
guardrail, §4.B was not started without that answer -- no schema
change, no payout capture, no `get_hourly_rate_accuracy_summary()`.

## Verification (2026-08-31) — ACTUALLY EXECUTED, not just reviewed

Wrote `test_hourly_rate_wait_time.py` (scratchpad, throwaway) and ran
it directly against the real, modified `drive_monitor.py` via plain
`python3`. Real output:

```
PASS: no restaurant_name passed -> pure drive-time-only, unchanged (12.0 min)
PASS: restaurant_name given but no wait history anywhere -> drive-time + the existing 6.0min hardcoded default (18.0 min)
est_minutes for a restaurant with a real learned 20min wait: 32.0
PASS: a restaurant with a real learned long wait produces a meaningfully higher, more realistic estimate
hourly_rate with the NEW realistic estimate (wait included): $28.12/hr
hourly_rate with the OLD drive-time-only estimate (for comparison): $75.00/hr
PASS: hourly_rate now reflects Slow Place's real learned wait time, not a zero-wait best case

ALL ASSERTIONS PASSED
```

The last two lines are the real-world payoff: for a $15 offer, 5km
away, at a restaurant with a genuine learned 20-minute wait, the OLD
estimate showed **$75/hr** (assuming an instant pickup) -- the NEW
estimate shows **$28/hr**, the actual realistic rate. Same offer, same
payout, same distance -- only the previously-ignored wait time now
counted.

A test-design bug was caught and fixed while writing this: the first
version compared a "known slow restaurant" against an "unknown
restaurant" and expected the unknown one to show zero added wait --
but `_restaurant_wait_info`'s existing fallback (by design, unchanged
here) uses the OVERALL average across all restaurants when a specific
one has no history, which in a test DB with only one seeded restaurant
correctly returns that restaurant's own value. Not a bug in the fix --
a wrong assumption in the test, corrected by testing an empty-DB
baseline separately from a specific-restaurant case.

Also verified: `ast.parse(drive_monitor.py)` clean after every edit.

Remaining PRD §6 boxes: everything under §4.B (blocked on §5's open
questions), plus driver sign-off.
