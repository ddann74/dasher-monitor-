# Progress log -- stop per-second speeding samples from crushing Safety%

## Investigation (2026-08-30)

Traced a real "Full Report" export (26 trips) showing Safety% = 0 on
nearly every trip to `TripManager._detect_harsh_events` logging a new
`speeding` event on every GPS tick above `DEFAULT_SPEED_LIMIT_KMH` (60),
not once per violation. Math-checked against the report's own
`composite_score` values (trip 15: `0.4*95 + 0.3*0 + 0.3*100 = 68`,
matching the reported Overall% exactly) to confirm this bug is
responsible for up to 30 points off the headline score, not just the
Safety% column. Wrote `docs/safety_score_speeding_debounce/PRD.md`.

Confirmed `drive_monitor.py` has zero Android/Chaquopy dependencies and
imports cleanly in plain `python3` -- the first fix in this repo that
can be genuinely executed and tested in this sandbox, not just reviewed
by eye.

## Implementation (2026-08-30)

Made the code changes for PRD §6 items 1-5:

- `TripManager.__init__`: added `self._open_speeding_event = None`.
- `TripManager._start_trip`: reset `self._open_speeding_event = None`
  alongside the other per-trip state (a single `TripManager` instance
  handles many trips over its lifetime, so this needed resetting per
  trip, not just at construction).
- `TripManager._detect_harsh_events`: rewrote the `speeding` branch from
  unconditional per-tick logging to edge-triggered logging -- opens a
  new event dict on the first tick over the limit, updates its
  `magnitude` to the peak speed while the period continues, and closes
  the period (sets the reference back to `None`) the moment speed drops
  back to/under the limit. A later tick crossing the threshold again
  starts a genuinely new, separate event. `harsh_accel`/`harsh_brake`
  logging (the two branches directly above) untouched. `_safety_score`
  itself untouched, per the PRD's explicit non-goal.

## Verification (2026-08-30) -- ACTUALLY EXECUTED, not just reviewed

Wrote `test_speeding_debounce.py` (scratchpad, not committed to the
repo -- a throwaway verification script, not a permanent test suite
addition, since this repo has no JVM/instrumented test source set to
add it to) and ran it directly against the real, modified
`drive_monitor.py` via plain `python3` (`Database(":memory:")` +
`TripManager`, no Android/Chaquopy involved at all). Real output:

```
PASS: one event logged for period 1 (40 ticks collapsed to 1), magnitude=95.6 (peak of 95.6)
PASS: speeding period closed when speed dropped back under the limit
PASS: a second, separate speeding period after the first closed produced a second distinct event (magnitude=72.0)
PASS: harsh_accel detection still works (3 logged)
At trip-15's real distance (65.77 km): OLD per-tick logging (real count from the report, 2359 events) = 0.0, vs. this test's 2 debounced events = 99.5

ALL ASSERTIONS PASSED
```

The period-1 test data (62.0 -> 95.6 -> settling km/h, 1 tick/sec) was
modeled directly on the real trip-6 sample rows in the earlier uploaded
diagnostic log, not arbitrary numbers. One real bug in the test itself
was found and fixed while writing it: an initial sanity-check assertion
incorrectly expected the OLD-logging score for this test's own small
2-period synthetic scenario to floor at 0.0 -- it doesn't at that scale
(43 total old-style ticks over 65.77 km scores 90.2, not 0.0; trip 15's
REAL 2,359-tick count is what floors it). Fixed by comparing against
the report's actual old-logging event count instead of this test's own
smaller sample, which is what the assertion was actually trying to
demonstrate.

Also confirmed by repo-wide search: no other code (Python or Java) reads
`speeding` events assuming per-tick granularity. The two Java call
sites (`MainActivity`, `TripHistoryActivity`) only map the raw
`event_type` string to a display label ("speeding" -> "Speeding") for a
count already shown to the driver -- that count becomes more meaningful
with this fix (real violation periods instead of raw tick counts), not
broken by it.

Verified by direct review too: brace/syntax check via
`ast.parse(drive_monitor.py)`, confirmed clean.

**Not done, by explicit PRD non-goal**: `DEFAULT_SPEED_LIMIT_KMH`
remains a flat, non-road-type-aware constant; the `15.0` penalty
multiplier is unchanged; the 26 trips already in the uploaded report
keep their bug-affected historical scores. All three are flagged in
§5 as separate, non-blocking follow-up decisions for the user. Final
user sign-off is the only remaining PRD §6 box.
