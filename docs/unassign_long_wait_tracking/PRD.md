# PRD: Record it when you unassign an order due to a long wait

Status: IMPLEMENTED and tested (Python half) -- awaiting user sign-off
and on-device confirmation of the Java click detection. See PROGRESS.md.
Scope: this one feature only. Not a general codebase pass.

## 0. What this is / isn't

This is a **new detection + recording** PRD, built from a real
screenshot the driver provided of DoorDash's own "You've been waiting a
while, would you like to unassign from this order?" prompt -- not a
guess. It follows the exact same pattern every other screen/click
detector in this app already uses (built from real screenshots or real
confirmed button text, never assumed). It is **not** a change to how
wait time is scored (`docs/safety_score_speeding_debounce/PRD.md` and
the wait-weighting conversation are separate, already-addressed topics)
-- this PRD is about making sure an unassign-due-to-wait is captured at
all, since right now it silently isn't.

Driver-reported: "add any unassigned offers due to long wait times" --
clarified in conversation: feed it into personal calibration (same
treatment as a decline) AND into restaurant wait history, so a
restaurant that causes an actual unassign shows up the same way a long
recorded wait already does.

## 1. Why (root-cause investigation, code-verified against the real
   screenshot)

Read `DasherAccessibilityService`'s click-handling block (L509-526,
the existing Accept/Decline pattern), `TripManager._evaluate_pickup`
(L1909-1940), `add_pickup` (L1866 in `drive_monitor.py`), and
`recalculate_personal_calibration`'s outcome query (L1062-1065).

1. **Confirmed via the real screenshot**: DoorDash shows a real prompt
   titled "You've been waiting a while, would you like to unassign from
   this order?" with two buttons, confirmed exact text: **"Yes, I want
   to unassign"** and **"No, continue with order"**. The screen also
   states unassigning here forfeits pay/tip but does NOT affect
   Completion Rate -- context, not something this app needs to act on.
2. **Real gap, confirmed in code: an unassign is currently invisible to
   this app entirely.** `_evaluate_pickup` only computes `wait_minutes`
   (and calls `record_restaurant_wait`, feeding Restaurant Wait
   History/the Address Book) when the driver physically **drives out of
   the pickup geofence** (L1930: `if not within_geofence and
   self.pickup["arrived_at"] is not None`). If the driver unassigns
   instead -- often while still sitting in the same parking lot, still
   within the geofence -- that branch never fires. `self.pickup` just
   sits with `arrived_at` set and `recorded: False` forever: no wait
   time gets learned, and nothing marks that pickup as resolved.
3. **The exact data needed to fix this already exists, unused for this
   purpose.** `self.pickup["arrived_at"]` (L1925, set the moment the
   driver enters the pickup geofence) is exactly the timestamp needed to
   compute a real wait duration at the moment of unassigning --
   `(now - arrived_at) / 60.0`, the same formula `_evaluate_pickup`
   already uses for a normal departure.
4. **`recalculate_personal_calibration` already has the right shape for
   this** (L1061-1075): it treats `accepted`/`declined` outcomes as
   satisfaction 100/0. Adding a new outcome value to that same `WHERE
   outcome IN (...)` filter, mapped to the existing `else 0.0` branch,
   requires no new logic -- an unassign-due-to-wait is at least as
   strong a negative signal as an active decline (the driver forfeits
   real earned pay specifically to escape it).
5. **Honest limit: `payout` is not recoverable at this point.**
   `add_pickup` (the call that creates `self.pickup`, L949 in
   `DasherAccessibilityService.java`) is passed `claimed_distance_km` and
   the score snapshot JSON, but never the offer's payout -- and the score
   snapshot dict returned by `SmartScoreEngine.calculate()` (L885-915)
   has no raw `payout` key either (only derived rates like
   `base_rate_per_km`/`hourly_rate`). This PRD does **not** thread
   payout through `add_pickup` just to fill one column -- `offer_outcomes.payout`
   is left `NULL` for this outcome type, honestly, rather than storing a
   value that isn't actually known.

## 2. Definition of "functional" for this task

- [ ] Tapping "Yes, I want to unassign" on the real DoorDash prompt is
      detected and recorded -- tapping "No, continue with order" does
      nothing (it's a dismiss, not a signal).
- [ ] If the driver had already arrived at the restaurant
      (`self.pickup["arrived_at"]` is set), the real wait duration is
      computed and fed into `record_restaurant_wait` -- the same
      Restaurant Wait History/Address Book mechanism a normal completed
      pickup already feeds, so this restaurant's entry reflects it.
- [ ] If the driver unassigns before ever registering arrival
      (`arrived_at` still `None`), no wait duration is invented -- the
      outcome is still recorded, just without a duration.
- [ ] A new `offer_outcomes` row is inserted with a distinct outcome
      value (not folded into `declined`, since it's a materially
      different real-world action), restaurant name, claimed distance,
      and the smart score snapshot's components (for calibration) --
      `payout` honestly left `NULL` per §1.5.
- [ ] `recalculate_personal_calibration` treats this new outcome as a
      satisfaction=0 signal, same as a decline.
- [ ] `self.pickup` is cleared/marked resolved once this fires -- it
      must not linger stale for the rest of the trip.
- [ ] No change to the existing Accept/Decline click detection, or to
      `_evaluate_pickup`'s normal departure-based wait recording --
      both stay exactly as they are.

Non-goals (explicitly out of scope for this task):
- Threading `payout` through `add_pickup` to fill `offer_outcomes.payout`
  for this outcome type -- see §1.5, left honestly `NULL`.
- Any UI change (no new screen, no new button in this app) -- this is
  pure background detection, matching how Accept/Decline detection works
  today.
- Handling "No, continue with order" -- a dismiss, not a signal worth
  recording.
- Changing `_safety_score`, the wait-score curve, or `WEIGHT_RESTAURANT_WAIT`
  -- separate, already-discussed topics.

## 3. Design

### 3.1 Click detection, same pattern as Accept/Decline

In `DasherAccessibilityService`'s existing `TYPE_VIEW_CLICKED` block
(L509-526), add a branch matching the confirmed real button text:
```java
} else if (clicked.equalsIgnoreCase("Yes, I want to unassign")) {
    String resultJson = engine.callAttr("record_pickup_unassigned_for_long_wait").toString();
    logDiagnostic("OUTCOME", "Unassigned due to long wait: " + resultJson);
}
```
Deliberately NOT gated on `lastSeenRestaurantName != null` (that field
is specifically for the brief offer-pending window and is already
cleared by the time this screen can appear, well after acceptance) --
the new Python method below handles "nothing to record" safely on its
own if `self.pickup` is `None`.

### 3.2 New engine method: `record_pickup_unassigned_for_long_wait`

New method on `DriveMonitorEngine`/`TripManager` in `drive_monitor.py`:
- No-ops safely (returns a JSON result saying so) if `self.pickup` is
  `None` -- nothing active to record.
- If `self.pickup["arrived_at"]` is set: computes `wait_minutes = (now -
  arrived_at) / 60.0` and calls the existing
  `SmartScoreEngine.record_restaurant_wait(restaurant_name,
  wait_minutes)` -- reusing the exact mechanism a normal pickup already
  feeds, not a parallel one.
- Inserts one `offer_outcomes` row: `outcome='unassigned_long_wait'`,
  `accepted=0`, `restaurant_name` and `distance_km` from `self.pickup`,
  `smart_score`/`components_json` extracted from
  `self.pickup["score_snapshot_json"]` if present, `payout=NULL` (§1.5).
- Clears `self.pickup = None` so it can't linger stale or be
  double-counted by a later, unrelated `_evaluate_pickup` call.

### 3.3 Calibration

In `recalculate_personal_calibration`'s outcome query (L1062-1065),
extend `WHERE outcome IN ('accepted', 'declined')` to also include
`'unassigned_long_wait'`. No other change needed -- the existing
`satisfaction = 100.0 if row["outcome"] == "accepted" else 0.0` already
maps anything else in that filtered set to 0.0.

## 4. Testing / verification approach

Like `docs/safety_score_speeding_debounce/PRD.md`, the Python half of
this (§3.2, §3.3) can be genuinely executed and tested in this sandbox
(`drive_monitor.py` has no Android dependency) -- a synthetic test can
set up `self.pickup` with a real `arrived_at`, call the new method, and
assert the `offer_outcomes` row and `personal_calibration` behavior
directly. The Java click-detection half (§3.1) cannot be verified
on-device in this environment (no Android emulator/device available,
same limitation as every other Java-side PRD here) -- confirmed by code
review and by the fact it mirrors the exact, already-working
Accept/Decline pattern in the same method.

## 5. Open questions

None blocking. Both destinations (calibration signal, restaurant wait
history) were confirmed by the driver in conversation before this PRD
was written; the exact new-outcome-value name
(`unassigned_long_wait`) and the payout-left-NULL decision (§1.5) are
implementation details, not judgment calls requiring further input.

## 6. Success criteria (implementation-phase checklist)

- [x] `record_pickup_unassigned_for_long_wait` added to
      `drive_monitor.py`, handling both the arrived/not-arrived cases
      and the no-active-pickup no-op
- [x] `record_restaurant_wait` called with a real computed duration when
      `arrived_at` is set
- [x] New `offer_outcomes` row inserted with the fields specified in
      §3.2, `payout` honestly `NULL`
- [x] `self.pickup` cleared after recording
- [x] `recalculate_personal_calibration`'s outcome filter extended to
      include the new outcome value
- [x] `DasherAccessibilityService`'s click handler wired to the new
      method on the confirmed real button text, mirroring the existing
      Accept/Decline pattern exactly
- [x] Executable test written and RUN in this sandbox for the Python
      half (§4)
- [ ] On-device confirmation of the real click detection -- **blocked**:
      no Android emulator/device available in this environment
- [ ] User sign-off
