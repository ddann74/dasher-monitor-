# PRD: Zero-interaction delivery completion

Status: IMPLEMENTED (2026-09-14), driver-requested feature.

## 1. What was requested

> "is there minimal app interaction between the use and the app while in
> dasher mode or driver mode... i dont want to be pushing buttons or
> reading notification"

Confirmed with the driver before building (three real design decisions):

1. Stop the "Rate This Delivery" flow from forcing itself to the
   foreground after every individual delivery, mid-shift.
2. Parking difficulty: default to auto-inferring easy/hard from the
   measured park-to-walk gap, with a manual fallback only if the driver
   actively wants to add something -- available right after that
   delivery, not batched.
3. Auto-dismiss the persistent delivery-instruction overlay once a
   delivery genuinely completes, instead of requiring a manual tap.

## 2. What was actually wrong vs. already fine

Audited before building, not assumed:

- **Notification reading was already minimal.**
  `AppNotificationListenerService` only ever speaks 3 filtered
  categories (work messages, offer detection, trusted-contact personal
  messages) -- everything else is silently ignored. No change needed
  here.
- **Parking difficulty was ALREADY auto-inferred with zero interaction.**
  `_auto_parking_difficulty_label`/`_record_park_to_walk_gap_sample`
  already write an auto-labeled `parking_difficulty_feedback` row for
  every stop, immediately, no prompt. The only thing forcing an actual
  interruption was the manual-correction UI being bundled into a
  force-opened dialog.
- **The real gap**: `TripForegroundService.notifyRateThisDelivery()`,
  per an EARLIER explicit driver request (2026-08-30, docs/
  feedback_page_direct/PRD.md), used a Background Activity Launch (BAL)
  direct-launch attempt plus a full-screen-intent HIGH-importance
  notification to force `TripDetailActivity` to the foreground after
  EVERY completed real-Dasher delivery, mid-shift -- requiring at least
  a dismiss/interaction each time. This directly conflicted with the
  new request and is what this PRD actually fixes.
- The delivery-instruction acknowledge overlay (`OverlayHelper.
  showPersistentTappableMessage` + `startAcknowledgeReminder`) was ALSO
  an earlier explicit driver request ("force me to acknowledge," docs/
  driver_backlog_2026_09_03/PRD.md #4) -- kept as a manual option, not
  removed, only supplemented with an automatic clear (ss3.3 below).

## 3. Design

### 3.1 Passive per-delivery notification (`TripForegroundService.java`)

`notifyRateThisDelivery()` no longer attempts a direct BAL launch or
posts a full-screen-intent notification. It now posts one ordinary,
default-priority, tap-to-open notification -- same `TripDetailActivity`
destination as before, just never forced onto the screen.

A NEW notification channel id (`rate_delivery_prompt_passive`, not the
old `rate_delivery_prompt`) is used deliberately: Android makes
`createNotificationChannel()` a no-op for an ALREADY-EXISTING channel
id's importance, even when the code requests a different one (a
documented platform restriction against apps silently re-escalating a
channel a user already downgraded). Reusing the old id would have left
every existing install stuck on the old `IMPORTANCE_HIGH` channel
forever, even after this fix shipped.

### 3.2 Shift-end batched rating review (`drive_monitor.py`, `MainActivity.java`)

New `get_deliveries_needing_rating(since_ts)`: real Dasher trips
completed since `since_ts` (the real Start-Monitoring timestamp, same
convention as `get_offers_needing_reason`) with no `trip_feedback` row
yet -- a delivery rated immediately via the still-available passive
notification is correctly excluded, since that already wrote the row.

HONESTY NOTE found while building this: a trip's restaurant NAME is
never actually persisted onto the `trips` table anywhere in this schema
(only the geocoded `pickup_address` is) -- `get_trip_history` (the
existing Trip List screen) already identifies trips by `start_time` for
the same reason, not by restaurant. This method returns `pickup_address`
and `start_time` for the same identifying purpose, rather than
fabricating a `restaurant_name` this data doesn't actually have.

`maybeReviewDeliveryRatingsThenReviewDeclinedOffers()` (replacing the
direct call to `maybeReviewDeclinedOffersThenShowTripSummary()` in the
Stop Monitoring button handler) checks this list first; if empty, falls
straight through unchanged. If not, `showDeliveryRatingReview(deliveries,
index)` shows one delivery at a time -- star rating + Navigation/
Merchant Wait/Customer/Overall categories + notes, Save or Skip,
recursing to the next -- then falls through to the existing decline-
reason review, then the trip summary. Deliberately has NO parking
category: parking is already auto-recorded (ss2), and a driver who wants
to correct a specific delivery's parking rating right away still can, by
tapping that delivery's own passive notification, which opens the full
original dialog (parking category included) unchanged.

### 3.3 Auto-clearing the instruction overlay (`TripForegroundService.java`, `OverlayHelper.java`)

`OverlayHelper.clearPersistentMessage(this)` is now called at both real
"a delivery just completed" sites in `TripForegroundService` -- the
manual-stop path and the natural `TRIP_ACTIVE`-to-`IDLE` transition --
the exact same signal `notifyRateThisDelivery()` itself already trusts
as "genuinely complete." A tap still dismisses it immediately if the
driver wants it gone sooner; it's no longer the ONLY way it clears.

## 4. Verification

- **Real, executable Python test** (real sqlite3, not `:memory:`): built
  a realistic mixed `trips` table (a trip before this shift, a DASHER
  trip this shift with no feedback, a DASHER trip this shift already
  rated via the passive notification, a GENERAL-mode trip this shift,
  and a still-in-progress trip this shift) and confirmed
  `get_deliveries_needing_rating` returns exactly the 1 real in-scope
  delivery -- not the old one, not the already-rated one, not the
  GENERAL one, not the in-progress one. Confirmed it correctly drops out
  after being rated, and that `since_ts=0` correctly picks up the
  older unrated trip too (proving the filter itself works, not just
  coincidentally always empty). All 5 checks passed.
- **Real, compiled, executed Java test** (`org.json` from Maven Central,
  same technique as the geocoding and decline-reason fixes): exercised
  the exact JSON-navigation/formatting logic from
  `showDeliveryRatingReview` against a realistic
  `get_deliveries_needing_rating` response shape -- field extraction,
  subtitle formatting, a sparse/malformed entry degrading cleanly, the
  empty-array case, and the recursion-termination check. All 5 checks
  passed.
- Brace/paren balance confirmed on all 3 modified Java files
  (`MainActivity.java`, `TripForegroundService.java`,
  `OverlayHelper.java`) and `py_compile` clean on `drive_monitor.py`.

## 5. Honest limits

- No Android device/emulator available in this environment -- the real
  on-screen sequence (an ordinary notification actually staying
  non-intrusive, the batched dialogs appearing back-to-back, the overlay
  actually auto-clearing at the right moment) has not been observed on a
  real screen.
- The passive per-delivery notification still uses
  `NotificationManager.IMPORTANCE_DEFAULT`, which can still make a sound/
  show on-screen briefly depending on the driver's own system
  notification settings for that channel -- "non-intrusive" here means
  "no forced foreground launch, no full-screen intent," not "silent."
  Genuinely silent would mean `IMPORTANCE_LOW`/`IMPORTANCE_MIN`, which
  was not what was asked for (the driver still wants ready access to
  rate a specific delivery immediately if they choose to).
- `showDeliveryRatingReview`'s dialogs are still real `AlertDialog`s the
  driver has to Save/Skip through one at a time at shift end -- this
  moves the interaction out of mid-shift, it doesn't eliminate rating
  interaction altogether, which the driver didn't ask for (they still
  get value from `recalculate_personal_calibration` learning from real
  answers).
- Multi-stop (stacked) trips: the instruction overlay auto-clear fires
  on the same trip-level `TRIP_ACTIVE`-to-`IDLE` signal `notifyRateThisDelivery`
  already uses, which is a whole-trip completion, not a per-stop one --
  an instruction overlay for an EARLIER stop in a still-active multi-
  stop trip will not auto-clear until the whole trip ends. Not
  independently re-derived or fixed here; inherits the same granularity
  every other consumer of this signal already has.

## 6. Success criteria

- [x] Confirmed the three open design questions with the driver before
      building, rather than guessing
- [x] `notifyRateThisDelivery` no longer force-launches
      `TripDetailActivity` after each delivery -- ordinary notification
      only
- [x] A new notification channel id used specifically because Android
      won't retroactively change an existing channel's importance
- [x] Parking difficulty confirmed already auto-recorded with zero
      interaction -- not re-built, just left as the correct default
- [x] `get_deliveries_needing_rating` scoped to real Dasher trips this
      shift with no feedback yet, correctly excluding already-rated and
      GENERAL-mode trips
- [x] Shift-end batch review has no parking category -- that path stays
      available via the per-delivery notification instead
- [x] Instruction overlay now auto-clears on genuine delivery
      completion, tap-to-dismiss still available
- [x] Real executable Python test (5 checks) and real compiled/executed
      Java test (5 checks) both fully passed
- [x] Brace/paren balance and `py_compile` clean on every modified file
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see ss5.
- [ ] Driver confirms in real use: no forced screen after a delivery,
      the shift-end batch review appears when expected, the instruction
      overlay clears itself after a delivery completes.
- [ ] Driver sign-off.
