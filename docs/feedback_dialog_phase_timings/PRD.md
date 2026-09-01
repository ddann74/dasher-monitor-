# PRD — show phase timings on the feedback dialog, scored/ranked

Status: Part A (wiring phase timings into the automatic feedback
dialog) IMPLEMENTED and tested. Part B (scoring/ranking each phase) is
DRAFT design only — deliberately blocked, see §4B.

Driver-reported: "when I press the delivery complete button I want to
see the feedback page including timings for each phase of the
delivery. timing could be ranked and given a corresponding score."

## 1. What "delivery complete button" actually triggers today

There is no detector for DoorDash's own "Mark Delivered"/completion
button anywhere in this codebase (checked `DasherAccessibilityService`
for any Accept/Decline/unassign-style click handler matching that text
— none exists). What actually shows the feedback dialog is
`notifyRateThisDelivery()` (`TripForegroundService.java`), fired on
this app's own `TRIP_ACTIVE -> IDLE` transition (GPS-detected: parked
long enough) or a manual "Stop Monitoring" tap — see
`docs/feedback_prompt_never_shown/` for that trigger's own separate,
still-open reliability questions. This PRD is about what the dialog
SHOWS once it does appear, not about detecting the real button (a
different, harder problem already tracked elsewhere).

## 2. The real gap found

`_build_trip_summary_dict` (drive_monitor.py) already computes a full
`phase_breakdown` (driving to pickup, waiting at restaurant, driving to
dropoff, parking to walking, completing dropoff) for every trip. It's
already displayed — but only in `MainActivity.buildTripSummaryBody`,
used by `showTripSummaryDialog()`/`showLastTripSummaryThenPromptFeedback()`
(the MANUAL "Last Trip Summary" menu flow).

`notifyRateThisDelivery()` — the AUTOMATIC path that fires right after
a real delivery, the one the driver is actually describing — calls
`showFeedbackDialog(tripId)` directly (`MainActivity.onCreate`, via
`auto_show_feedback_trip_id`), which is a completely separate method:
just a star rating, five quick-tap category buttons, and a notes
field. It never calls `get_trip_summary_by_id`/`get_last_trip_summary`
at all, so `phase_breakdown` (or anything else about the trip) never
reaches it. The formatting code to show it (`formatMinutesSeconds`,
the phase-breakdown block) already exists and already works — it's
just never invoked from this specific dialog.

So: the driver presses what ends the delivery, the feedback dialog
appears (when `docs/feedback_prompt_never_shown/`'s own trigger works),
and it shows a bare rating form with zero context about the delivery
just completed — not because phase timing isn't tracked, but because
the one dialog that's actually shown automatically never asks for it.

## 3. Non-goals

- Not solving `docs/feedback_prompt_never_shown/`'s own open
  questions (why the dialog sometimes doesn't appear at all) — that's
  tracked separately; this PRD assumes the dialog DOES appear and
  fixes what's missing once it does.
- Not adding real detection of DoorDash's own completion button — out
  of scope, see §1.
- Not changing `buildTripSummaryBody`/the manual "Last Trip Summary"
  flow — it already works correctly.

## 4A. Design — wire phase timings into the feedback dialog (implemented)

`showFeedbackDialog(tripId)` now fetches `get_trip_summary_by_id(tripId)`
(not `get_last_trip_summary()` — this dialog is always given a specific
`tripId` by its caller, and using the ID-specific lookup keeps it
correct regardless of which of the two real call sites invoked it) and,
if a `phase_breakdown` exists, prepends the same formatted block
`buildTripSummaryBody` already builds (reusing `formatMinutesSeconds`
verbatim) as a `TextView` at the top of the dialog's layout, above the
rating bar. Failure to fetch it (old trip, JSON error) degrades to the
exact previous behavior — the rating form still works with no phase
timing shown, never a hard failure.

## 4B. Design — score/rank each phase's timing (NOT implemented, blocked)

The `trips` table already has every completed trip's phase timestamp
columns (`pickup_arrival_ts`, `pickup_departure_ts`,
`dropoff_arrival_ts`, `walking_confirmed_ts`, `start_time`, `end_time`)
— a real historical distribution of this driver's own past phase
durations is already queryable, no new capture needed. The natural
design mirrors `docs/market_relative_score_thresholds/` §4B (added in
the same session, same driver): compute this driver's own quartile
boundaries per phase from all past trips, gated on a minimum sample
count, and label THIS trip's phase duration against them (e.g. "Waiting
at restaurant: 18 min — slower than usual" vs. "top quartile for you").

**Why this is blocked, not just undesigned:** `phase_breakdown`'s own
underlying data has two known, already-documented accuracy problems
that would directly corrupt any score built on top of it:

1. It only ever reflects the FIRST job of a multi-stop/batch trip —
   `docs/deadhead_stacked_order_baseline/` §7 already flagged this.
2. `pickup_arrival_ts`/`pickup_departure_ts` write with NO `IS NULL`
   guard (last-wins), unlike `dropoff_arrival_ts`/`walking_confirmed_ts`
   (first-wins, guarded) — already documented in the same PRD's §7, and
   the diagnostic-visibility side of it was fixed in
   `docs/silent_failure_audit_2026_08_31/`, but the underlying write
   behavior itself was NOT changed.

Scoring a number that can already be silently wrong for stacked orders
would produce a confident-looking rank on top of bad data — worse than
not scoring at all. Per this session's own established
`docs/deadhead_stacked_order_baseline/` §7/§8 guardrail, that per-job
schema fix needs its own design pass before anything downstream of
`phase_breakdown` gets more sophisticated. Recorded here rather than
re-explained, so this PRD doesn't duplicate that one.

## 5. Open questions

- None for §4A — already implemented, reuses existing display code and
  data with no new judgment calls.
- §4B is gated on the joint per-job schema design already called out
  in `docs/deadhead_stacked_order_baseline/` §7/§8 and
  `docs/hourly_rate_actual_vs_estimated/` §5 — not a NEW open question,
  the same one, now with a third PRD depending on it.

## 6. Success criteria

- [x] `showFeedbackDialog` fetches the trip's real `phase_breakdown`
      and displays it above the rating bar.
- [x] Degrades gracefully (no phase data, fetch error) to the exact
      previous rating-only behavior.
- [x] Real executable test (Python side): confirms
      `get_trip_summary_by_id`'s `phase_breakdown` output is exactly
      what the dialog now reads — the Java-side display itself is
      reviewed only, no Android device available.
- [ ] §4B (blocked): per-phase quartile scoring, once the joint
      per-job schema design exists.
- [ ] On-device confirmation of §4A's dialog layout change — blocked,
      no Android emulator/device available in this environment.
- [ ] Driver sign-off.
