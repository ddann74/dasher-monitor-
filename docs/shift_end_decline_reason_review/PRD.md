# PRD: Shift-end decline/no-response reason review

Status: IMPLEMENTED (2026-09-13), driver-requested feature.

## 1. What was requested

> "let me give a reason for all declined or not responded offers, only
> after my shift has finished"

Confirmed with the driver before building (two real design decisions,
not assumed):

1. **Input style**: quick-pick buttons, with an "Other" option that
   opens a text box for anything that doesn't fit -- not free text
   alone, not quick-pick alone.
2. **Trigger**: automatically right after "Stop Monitoring" -- if that
   shift had any declined/timed-out offers -- rather than only
   on-demand from Trip History. Skippable per-offer, not forced.

## 2. What already existed vs. what's new

Two subsystems already used offer-outcome history differently
(clarified for the driver in the same conversation this was requested
in, before building):

- The Smart Score quartile COLOR thresholds already draw from ALL
  offers regardless of outcome (accepted, declined, timed out).
- `recalculate_personal_calibration` already draws from accept/
  decline decisions (deliberately excluding timeouts, since a timeout
  isn't a clean preference signal).

Neither of those captures WHY a specific offer was declined or missed
-- this feature adds that, as a new, separate, purely descriptive
field (`decline_reason`) that isn't (yet) fed back into scoring or
calibration at all -- see ss6 Honest Limits.

## 3. Design

### 3.1 Data layer (`drive_monitor.py`)

- New `offer_outcomes.decline_reason TEXT` column, added via the same
  `ALTER TABLE ... ADD COLUMN` migration pattern every other column on
  this table already uses (`is_test_data`, `hourly_rate`,
  `weather_precip_mm`, etc.) -- NULL for every offer until answered,
  and for every offer recorded before this shipped.
- `get_offers_needing_reason(since_ts)` -- declined or timed-out
  offers (`outcome IN ('declined', 'timed_out')`) with
  `timestamp >= since_ts AND decline_reason IS NULL`. Deliberately
  does NOT include `unassigned_long_wait` -- the driver's own request
  was specifically "declined or not responded," a narrower scope than
  every negative outcome this app tracks.
- `set_decline_reason(offer_id, reason)` -- a plain `UPDATE`, storing
  a quick-pick label and an "Other: ..." free-text answer identically
  (this column doesn't enforce a fixed vocabulary; the quick-pick list
  is a Java-side UI convenience only). Returns whether a real row was
  actually updated, so the Java side can tell "saved" from "that
  offer_id no longer exists" instead of assuming success silently.

### 3.2 Knowing "this shift's" offers (`TripForegroundService`)

New `public static volatile long sessionStartMs`, set at the top of
`startTracking()` (after the already-tracking guard, so a redundant
call never resets it mid-shift) -- same established cross-component
static-field pattern this file already uses for `lastKnownLat`/
`lastKnownLon`/`hasValidLocation`, so `MainActivity` can read it
directly with no bound-service connection. Left as-is (not reset) in
`stopTracking()`, so a read that happens fractionally after the
stop-tracking intent is sent still gets the real session-start value.

### 3.3 The review flow (`MainActivity`)

`maybeReviewDeclinedOffersThenShowTripSummary()` replaces the direct
call to the existing `showLastTripSummaryThenPromptFeedback()` in the
Stop Monitoring button handler -- checks for offers needing a reason
first; if there are none (the common case, especially a short
GENERAL-mode session with no real offers at all), falls straight
through to the existing flow completely unchanged, no dialog
interrupts anything new.

If there ARE offers to review, `showDeclineReasonReview(offers, index)`
shows one at a time: restaurant name, payout (if known), and whether
it was declined or timed out, with the 5 quick-pick reason buttons
(`Too far`, `Pay too low`, `Bad area`, `Bad restaurant`, `Missed it /
too slow`) plus an `Other...` button. Tapping a quick-pick or saving
an "Other" answer calls `set_decline_reason` and recurses to the next
offer; **Skip** (or dismissing the dialog -- back button/tap-outside,
treated identically) recurses without saving. Once every offer's been
handled, falls through to the existing, unchanged trip-summary flow.

Any exception anywhere in this new flow (a malformed engine response,
a JSON error) logs and falls straight through to the existing
trip-summary flow too -- this new, optional feature can never block
the flow that already worked before it existed.

## 4. Verification

- **Real, executable Python test** (real sqlite3, not `:memory:`, same
  convention as every other Python-side change in this repo): built a
  realistic mixed `offer_outcomes` table (an offer from before this
  shift, a declined offer this shift, a timed-out offer this shift, an
  accepted offer this shift, and an `unassigned_long_wait` offer this
  shift) and confirmed `get_offers_needing_reason` returns exactly the
  2 real in-scope offers -- not the old one, not the accepted one, not
  the unassigned one. Confirmed `set_decline_reason` persists correctly,
  a reasoned offer no longer appears in a re-query, a nonexistent
  `offer_id` reports failure rather than silently succeeding, and free
  text ("Other: ...") stores identically to a quick-pick label. All 7
  checks passed.
- **Real, compiled, executed Java test** (`org.json` fetched from Maven
  Central, same technique used successfully in the geocoding-fix
  verification): exercised the exact JSON-navigation logic from
  `showDeclineReasonReview`/`maybeReviewDeclinedOffersThenShowTripSummary`
  against a realistic `get_offers_needing_reason` response shape --
  confirmed correct field extraction (id/name/payout/timed-out flag)
  for multiple offers, correct subtitle-string formatting, a sparse/
  malformed offer entry degrading cleanly instead of crashing, the
  empty-array (no-review-needed) case, and specifically the
  quick-pick-button child-index alignment (the `i + 1` offset past the
  subtitle `TextView`) for all 5 reasons -- the exact class of
  off-by-one bug this kind of manual `getChildAt` indexing risks. All
  5 checks passed.
- Brace/paren balance confirmed on all 3 modified Java files
  (`MainActivity.java`, `TripForegroundService.java`) and `py_compile`
  clean on `drive_monitor.py`.

## 5. Honest limits

- No Android device/emulator available in this environment (same
  disclosed limitation as every other Java-side change in this repo)
  -- the real dialog sequence (multiple `AlertDialog`s shown back to
  back, `getChildAt` indexing against real inflated `Button`/`TextView`
  views rather than the simulated array in the test above) has not
  been observed on a real screen.
- `decline_reason` is purely descriptive right now -- it is NOT fed
  into `recalculate_personal_calibration`, the Smart Score, or the
  quartile color thresholds. It's stored so it EXISTS and is visible
  (a future report/screen could surface "why you've been declining
  offers lately"), but nothing currently reads it back except the
  review flow's own "already answered" filter. Not something the
  driver asked for in this request -- explicitly out of scope here,
  not an oversight.
- The 5 quick-pick reasons are a judgment call, not something the
  driver specified individually -- reasoned from what a real Dasher
  most commonly cites for declining/missing an offer, same "judgment
  call, not a derived constant" honesty status as this app's other
  tuned thresholds and option sets.
- If "Stop Monitoring" is tapped, then "Start Monitoring" is tapped
  again, then "Stop Monitoring" a second time WITHOUT the app process
  restarting in between, `sessionStartMs` correctly reflects the
  SECOND session's start (overwritten on each real `startTracking()`)
  -- verified by reading the code, not observed on a device.
  UPDATE (2026-09-14): the WITH-a-process-restart case (an OEM
  silently killing and resurrecting the process mid-shift) was a real,
  separate bug -- `sessionStartMs` used to reset silently instead of
  resuming, quietly shrinking "this shift." Fixed in `docs/
  session_start_ms_oem_restart/PRD.md`.

## 6. Success criteria

- [x] Confirmed both open design questions (quick-pick + Other; auto-
      trigger after Stop Monitoring) with the driver before building,
      rather than guessing
- [x] `decline_reason` column added via the established migration
      pattern
- [x] `get_offers_needing_reason`/`set_decline_reason` scoped exactly
      to "declined or not responded" (timed out) -- not broader
      (`unassigned_long_wait` deliberately excluded)
- [x] `sessionStartMs` reuses the exact established cross-component
      static-field pattern already in `TripForegroundService`, not new
      plumbing
- [x] A shift with nothing to review falls straight through to the
      existing, unchanged trip-summary flow -- zero behavior change
      for the common case
- [x] Any failure in the new flow degrades to the existing flow,
      never blocks it
- [x] Real executable Python test (7 checks) and real compiled/executed
      Java test (5 checks, including the exact off-by-one risk in the
      button-index alignment) both fully passed
- [x] Brace/paren balance and `py_compile` clean on every modified file
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see ss5. The real on-screen dialog sequence is
      unconfirmed.
- [ ] Driver confirms in real use: after a shift with at least one
      declined or timed-out offer, the review prompt appears once Stop
      Monitoring is tapped, each quick-pick/Other/Skip choice advances
      correctly to the next offer, and a shift with nothing to review
      goes straight to the trip summary as before.
- [ ] Driver sign-off.
