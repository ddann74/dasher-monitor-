# Progress log — phase timings on the feedback dialog

## Investigation (2026-08-31)

Driver asked to see phase timings on the feedback page shown when
pressing "delivery complete," with timing ranked/scored. Investigated
the real trigger and display code.

First finding: no detector for DoorDash's own completion button exists
anywhere — the feedback dialog's real trigger is this app's own
GPS-based trip-end detection (`notifyRateThisDelivery`), a separate,
already-tracked reliability question (`docs/feedback_prompt_never_shown/`).
This PRD assumes that trigger fires and focuses on what the dialog
shows once it does.

Second, the real gap: `phase_breakdown` was already computed
(`_build_trip_summary_dict`) and already displayed -- but only in the
MANUAL "Last Trip Summary" flow (`buildTripSummaryBody`). The
AUTOMATIC feedback dialog shown right after a real delivery
(`showFeedbackDialog`, reached via `auto_show_feedback_trip_id`) is a
completely separate method that never fetched trip data at all -- just
a bare rating form. The formatting code to show phase timings already
existed and already worked; it was simply never invoked from the one
dialog that's actually shown automatically.

## Implementation (2026-08-31) — §4A only

`showFeedbackDialog(tripId)` now fetches `get_trip_summary_by_id(tripId)`
at the top (before building the layout) and, if `phase_breakdown` is
non-empty, prepends a `TextView` with the same five-phase breakdown
`buildTripSummaryBody` already formats (reusing `formatMinutesSeconds`
verbatim), above the rating bar. Wrapped in the same
`try { ... } catch (JSONException | RuntimeException e)` defensive
pattern used everywhere else in this file -- any failure (old trip,
JSON error) degrades silently to the exact previous rating-only
behavior, never a hard failure blocking the actual feedback form.

Used `get_trip_summary_by_id(tripId)`, not `get_last_trip_summary()`
-- this dialog always receives a specific `tripId` from its caller
(either the auto-show path or the manual "Last Trip Summary" -> OK
chain), so the ID-specific lookup is correct regardless of which real
call site invoked it, rather than assuming "the dialog is always for
the most recent trip."

## §4B — deliberately NOT started

Per-phase quartile scoring is designed at a high level (PRD §4B,
mirroring `docs/market_relative_score_thresholds/` §4B's own quartile
pattern) but explicitly blocked: `phase_breakdown`'s own data already
has two documented accuracy problems (first-job-only for batch orders;
the pickup-timestamp last-wins bug), both tracked in
`docs/deadhead_stacked_order_baseline/` §7/§8, and scoring on top of
data that can already be silently wrong would produce a confident-
looking number built on bad input. This is the third PRD now waiting
on that same joint per-job schema design (alongside
`docs/deadhead_stacked_order_baseline/` §7/§8 itself and
`docs/hourly_rate_actual_vs_estimated/` §4.B) — not duplicated here.

## Verification (2026-08-31) — ACTUALLY EXECUTED, not just reviewed

Wrote `test_feedback_dialog_phase_timings.py` (scratchpad, throwaway)
and ran it directly against the real, modified `drive_monitor.py` via
plain `python3`. Real output:

```
phase_breakdown: {'driving_to_pickup_seconds': 600.0, 'wait_at_restaurant_seconds': 480.0, 'driving_to_dropoff_seconds': 900.0, 'completing_dropoff_seconds': 120.0}
PASS: all five phase_breakdown keys the dialog reads are exactly correct
PASS: an uncaptured phase (no walking_confirmed_ts) is omitted, not invented
PASS: dialog's exact expected key set matches reality for a trip with a normal (no walking) dropoff
PASS: a trip with no phase data returns an empty phase_breakdown (dialog shows no block, not an error)

ALL ASSERTIONS PASSED
```

This confirms the exact JSON shape/keys the new Java code reads
(`isNull()` checks against each of the five phase keys) match what
`get_trip_summary_by_id` actually returns, including the two edge
cases that matter most: a phase genuinely not captured (omitted, not
guessed at) and a trip with no phase data at all (empty dict, dialog
shows no block rather than erroring).

Also verified: brace/paren counts in `MainActivity.java` balanced
(diff 0/0) before and after the edit.

The Java-side dialog layout change itself could not be verified on-
device -- no Android emulator/device available in this environment.
Verified by code review only, reusing the exact same formatting logic
(`formatMinutesSeconds`, the same five `isNull()` checks) already
proven working in `buildTripSummaryBody`.

Remaining PRD §6 boxes: §4B (blocked, as above), on-device confirmation
(blocked), and driver sign-off.
