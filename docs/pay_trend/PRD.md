# PRD: pay trend over time

Status: IMPLEMENTED and tested (2026-09-03).

## 0. The real question this answers

Driver asked directly: gig workers are widely reported to have their
pay reduced over time based on their own acceptance history (DoorDash
and other platforms have faced lawsuits/investigative reporting over
this - a driver who accepts lower offers may start seeing lower base
pay, with the gap shifted into "tips" so the total looks similar; an
algorithm can learn the minimum a specific driver will accept). Has
this app solved that?

**No, and it structurally can't.** That decision happens entirely on
DoorDash's own backend. This app is a client-side tool - it reads the
screen via Android's accessibility service and reads notifications. It
never talks to DoorDash's servers and has no visibility into DoorDash's
internal pricing logic. It cannot detect, prove, or counteract
platform-side pay steering, because doing so would require access this
app fundamentally doesn't have.

**What IS buildable**: every offer's payout, distance, and computed
hourly rate are already captured in `offer_outcomes` regardless of
outcome (accepted, declined, timed out) - a trend view showing the
driver's own recorded $/km and $/hr over time, so if their own pay is
quietly declining, they have their own evidence of it, independent of
anything DoorDash's app tells them. This does NOT prove the cause -
explicitly disclosed, not glossed over, both in this doc and directly
in the dialog text the driver sees.

## 1. Design

**Population**: ALL scored offers (accepted, declined, timed out),
`is_test_data = 0` - same choice already established this session for
the Address Book, Profitability Map, and market-relative thresholds.
This is the more direct signal for "what is DoorDash offering me
lately," not just what was driven.

**Buckets**: 8 rolling 7-day windows anchored to now (`PAY_TREND_
DEFAULT_WEEKS = 8`, roughly 2 months) - most recent first. Rolling
buckets, not calendar weeks, to avoid partial-week edge cases at the
start/end of the window.

**Trend summary**: a recent-half-vs-earlier-half percentage comparison
(weeks 0-3 vs. weeks 4-7), computed as a POOLED average directly from
the raw rows in each half's date range - not by averaging the 8 weekly
bucket averages, which would let one low-sample week skew the
comparison as much as a high-sample week. Withheld entirely (`trend:
null`) unless BOTH halves clear `PAY_TREND_MIN_SAMPLES_PER_HALF = 3` -
a comparison built on 1-2 offers per half would be a misleadingly
confident number.

**Honesty, not just disclosure**: the dialog text itself (not just this
PRD) tells the driver directly that this shows their own recorded
numbers, not a diagnosis of why they moved - a decline could be
platform-side steering, but could equally be market seasonality, fewer
restaurants active, a city/zone change, or plain chance. This app
cannot tell those apart and does not claim to.

## 2. Non-goals

- Not attempting to detect, infer, or prove platform-side pay steering
  specifically - genuinely impossible from this app's vantage point,
  not a scope choice.
- Not comparing against any external benchmark (other drivers, other
  markets, published DoorDash pay data) - only the driver's own history
  against their own earlier history.
- Not a chart/graph - this app has no charting library; a plain text
  weekly breakdown, matching every other report screen in this app.

## 3. Verification

Same disclosed limitation as every Java-side PRD in this repo - no
Android SDK/emulator/device; real, runnable Python test for the
pure-Python logic.

- `drive_monitor.py` recompiles cleanly.
- Real Python test (`test_pay_trend.py`, 4 cases, all passed): empty
  database doesn't crash and correctly withholds a trend; a known,
  deliberately-seeded 50% decline (recent half exactly half the
  earlier half's rate) is computed exactly correctly, with test-data
  and out-of-window rows correctly excluded; a half below the minimum
  sample count correctly withholds the trend rather than showing a
  misleading percentage; individual weekly buckets are populated
  correctly and independently of the half-comparison.
- `TripHistoryActivity.java` brace/paren balance: 154/154 braces,
  1004/1004 parens.
- `activity_trip_history.xml`/`strings.xml` re-validated as well-formed
  XML.
- Re-ran the full existing scratchpad test suite - no regressions.

## 4. Success criteria

- [x] `get_location_profitability`-adjacent method (`get_pay_trend`)
      implemented: weekly buckets + recent/earlier-half comparison,
      gated on a minimum sample count per half
- [x] New "Pay Trend" button on Trip History, showing a plain-text
      report matching this app's existing dialog style
- [x] The honest framing (this shows YOUR data, not a diagnosis of
      cause) is in the dialog text itself, not just documentation
- [x] Real Python test written and run - 4 cases, all passed
- [x] No regressions in the existing test suite
- [ ] Driver confirms in real use: the weekly numbers and trend
      percentage look right against their own memory of recent weeks
- [ ] Driver sign-off.
