# Progress log — record it when you unassign an order due to a long wait

## Implementation (2026-08-31)

Three changes, exactly per PRD §3:

- `TripManager.record_pickup_unassigned_for_long_wait()` (new,
  `drive_monitor.py`, right after `_evaluate_pickup`): no-ops safely if
  `self.pickup` is `None`; computes `wait_minutes = (now - arrived_at) /
  60.0` only if `arrived_at` was actually set (no invented duration
  otherwise); clears `self.pickup` immediately so it can't linger stale
  or be double-counted; returns the raw pickup fields needed by the
  caller.
- `DriveMonitorEngine.record_pickup_unassigned_for_long_wait()` (new,
  placed next to `record_offer_outcome`/`record_offer_timeout` since
  it's the third writer of `offer_outcomes`): calls
  `TripManager`'s method above, feeds `SmartScoreEngine.record_restaurant_wait`
  when a real wait duration exists, extracts `final_score`/`components`
  from the pickup's stored `score_snapshot_json` (same "components"
  sub-object shape `record_offer_outcome`/`record_offer_timeout` already
  use), and inserts one `offer_outcomes` row with `outcome=
  'unassigned_long_wait'`, `accepted=0`, `payout=NULL` (per PRD §1.5 --
  honestly not recoverable, not threaded through just to fill a column).
  This needed its own `DriveMonitorEngine` method rather than living
  entirely on `TripManager`, since `TripManager` has no reference to
  `self.smart_score` -- the same split `_evaluate_pickup`'s existing
  wait-recording already uses (`TripManager` returns a dict,
  `DriveMonitorEngine.on_gps_update` is the one that actually calls
  `smart_score.record_restaurant_wait`).
- `recalculate_personal_calibration`'s Source 2 query (`WHERE
  outcome IN (...)`): extended to also include `'unassigned_long_wait'`
  — no other change needed, the existing `satisfaction = 100.0 if
  row["outcome"] == "accepted" else 0.0` already maps it to 0.0.
- `DasherAccessibilityService`'s `TYPE_VIEW_CLICKED` handler
  (Java): added an `else if` branch matching the confirmed real button
  text `"Yes, I want to unassign"`, calling the new engine method and
  logging the result under the existing `"OUTCOME"` category, wrapped
  in the same `try/catch (RuntimeException e)` pattern every other
  `engine.callAttr` call site in this file already uses (the PRD's own
  §3.1 snippet omitted this for brevity; added it for consistency with
  the rest of the file). Deliberately not gated on
  `lastSeenRestaurantName != null` per PRD §3.1's own reasoning.

No change to `_evaluate_pickup`, the existing Accept/Decline detection,
`_safety_score`, the wait-score curve, or `WEIGHT_RESTAURANT_WAIT` --
confirmed via diff review, matching PRD §2's non-goals.

## Verification (2026-08-31) — ACTUALLY EXECUTED, not just reviewed

Wrote `test_unassign_long_wait.py` (scratchpad, not committed --
throwaway, same pattern as every other executable test this session)
and ran it directly against the real, modified `drive_monitor.py` via
plain `python3` (`DriveMonitorEngine(tmpdir)`, a real temp SQLite DB --
no Android/Chaquopy involved). Real output:

```
PASS: no-op when there's no active pickup
PASS: unassign-after-arrival recorded with real wait_minutes=14.0
PASS: self.pickup cleared after recording
PASS: offer_outcomes row correct (payout NULL, outcome distinct, smart_score/components extracted)
PASS: Restaurant Wait History fed with the real unassign wait duration
PASS: unassign-before-arrival recorded with no invented wait duration
PASS: unassign-before-arrival still recorded as an offer_outcomes row
PASS: recalculate_personal_calibration's extended outcome filter picks up both rows

ALL ASSERTIONS PASSED
```

Covers all four PRD §2 functional requirements directly: the
arrived/not-arrived split, the `offer_outcomes` row shape (payout
`NULL`, distinct outcome value), Restaurant Wait History being fed,
and `self.pickup` being cleared.

Also verified: `ast.parse(drive_monitor.py)` clean after every edit;
brace/paren counts in `DasherAccessibilityService.java` balanced
before and after the edit (145/145 braces, 520/520 parens).

The Java click-detection half (§3.1) could NOT be verified on-device --
no Android emulator/device available in this environment, per PRD §4's
own acknowledgment. Verified by code review only: mirrors the existing,
already-working Accept/Decline `equalsIgnoreCase` pattern in the exact
same method, using the exact button text confirmed from the driver's
real screenshot.

Remaining PRD §6 boxes: on-device confirmation (blocked, as above) and
user sign-off.
