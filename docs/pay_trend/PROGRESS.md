# Progress log — pay trend over time

## Implemented (2026-09-03)

Driver asked directly: gig workers commonly report their pay being
reduced over time based on their own acceptance history (a well-
documented, litigated practice at other platforms) - has this app
solved that? Answered honestly first, before building anything: no,
and it structurally can't - that decision happens entirely on
DoorDash's own backend, invisible to a client-side accessibility/
notification-reading app. What's genuinely buildable instead: make the
driver's own recorded pay trend visible to them, since every offer's
payout/distance/hourly_rate is already captured regardless of outcome.
Driver said "yes build it."

New `DriveMonitorEngine.get_pay_trend(weeks=8)`: 8 rolling 7-day
buckets (most recent first), each with avg $/km, avg $/hr, and sample
count, from ALL scored offers (accepted/declined/timed out,
`is_test_data=0` - same population choice already established this
session for the Address Book/Profitability Map/market-relative
thresholds). Plus a recent-half-vs-earlier-half percentage comparison,
computed as a POOLED average directly from each half's raw rows (not by
averaging the 8 weekly bucket averages, which would let a low-sample
week skew things unfairly) - withheld entirely unless both halves clear
`PAY_TREND_MIN_SAMPLES_PER_HALF = 3`.

New "Pay Trend" button on Trip History, plain-text dialog matching this
app's existing report style. The dialog text itself states directly
that this is the driver's own recorded numbers, not a diagnosis of why
they moved - a decline could be platform steering, but could equally be
market seasonality, fewer active restaurants, or plain chance. Framed
honestly in the actual UI text, not just in code comments/docs.

**Verification**: real, runnable Python test (`test_pay_trend.py`, 4
cases, all passed) - most importantly, a deliberately-seeded exact 50%
decline (recent half at $1.00/km vs. earlier half at $2.00/km) computed
correctly, with test-data and out-of-window rows correctly excluded,
and a below-minimum-samples half correctly withholding the trend rather
than showing a misleading percentage. `drive_monitor.py` recompiles
cleanly. `TripHistoryActivity.java` brace/paren balance: 154/154
braces, 1004/1004 parens. XML re-validated well-formed. Re-ran the full
existing scratchpad test suite - no regressions.

PRD.md §4 boxes checked except driver confirmation/sign-off.

## Self-caught bug fixed (2026-09-03): empty-state check never actually fired

Driver asked a real follow-up question ("what if I don't work for
several weeks") that prompted re-tracing the code rather than just
answering from memory. Multi-week gaps themselves are handled
correctly (each week is independent, a gap week contributes zero ROWS
to an average, not zero-dollar rows, and the trend comparison is
correctly withheld if a gap drops either half below the minimum sample
count) - but tracing it surfaced a real, separate bug: `showPayTrend()`'s
empty-state check was `weekly.length() == 0`, but `get_pay_trend()`
always returns exactly 8 buckets regardless of whether any have data
(empty weeks are included on purpose, so a gap shows as "no offers
recorded" rather than silently vanishing) - meaning that check could
never actually be true, even for a driver with zero offer history ever.
Fixed: now checks whether every bucket's own `sample_count` is 0,
rather than the array's length.

**Verification**: brace/paren balance re-verified
(`TripHistoryActivity.java`: 157/157 braces, 1011/1011 parens). No
Python change needed - `get_pay_trend()`'s own behavior (always
returning 8 buckets, empty ones included) was correct as designed; only
the Java-side empty-state check was wrong.
