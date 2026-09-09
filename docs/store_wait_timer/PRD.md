# PRD: Store wait timer (Arrived at Store -> Confirm Pickup, 1-minute grace period)

Status: IMPLEMENTED (all §2/§5 checklist boxes checked except
on-device confirmation and driver sign-off, which are never mine to
check). **Corrected 2026-09-09**: this header was stale, still reading
"DRAFT -- not yet implemented" from before the code was written -- a
documentation-drift audit found the feature fully built (see §2/§5
below) with the header never updated to match.

## 0. Origin

Driver asked directly: "can you build an automatic timer that starts 1
minute after i press arrived at store and stops when i press confirm
pickup." Two clarifying questions were asked before writing any code
(via `AskUserQuestion`):

1. What the timer should actually DO while running (visible overlay /
   voice announcements / silent logging -- multi-select).
2. Whether to confirm DoorDash's exact button text first, or proceed on
   standard/best-guess wording with the risk flagged.

Driver answered the first as "both 1 and 3" -- a visible overlay AND
silent logging (no voice announcements, option 2 explicitly not
wanted). The second question went unanswered; per its own "recommended"
framing, this PRD proceeds using the driver's own literal wording from
the request ("Arrived at Store", "Confirm Pickup") as the best
available button text, with the risk that it's unconfirmed disclosed
plainly in §3, not silently assumed correct.

## 1. Investigation (done before writing this)

- `OfferScreenParser` / `DasherAccessibilityService.collectVisibleText()`
  confirmed to read ONLY `node.getText()` from the accessibility tree --
  no images, no content-descriptions, nothing else. Any new detection
  has to be text-based, consistent with everything else in this file
  (also directly answers a related question the driver asked earlier
  this session about approximating an offer's location from a map
  thumbnail -- not reachable via this same mechanism, for the same
  reason).
- This class ALREADY has a proven, working click-text-detection
  mechanism for at least one of Dasher's own buttons:
  `clicked.equalsIgnoreCase("Yes, I want to unassign")`, confirmed
  against a real screenshot
  (`docs/unassign_long_wait_tracking/PRD.md`). Plain `TYPE_VIEW_CLICKED`
  events DO carry real button text for at least some Dasher UI elements
  -- the earlier, separate finding that Accept/Decline never fire a
  usable click event is real, but not a universal rule for every button
  in this app.
- Accept/Decline's own history is the cautionary tale here: their exact
  click-text assumption was wrong (confirmed via a real diagnostic log:
  zero CLICK events across 5 real offers), and the actual working
  mechanism ended up being node-bounds scanning + tolerant matching
  (`scanAndRecordAcceptDeclineNodeBounds` / `checkNodeBoundsMatch` /
  `boundsRoughlyMatch`). This PRD deliberately does NOT build that
  heavier mechanism for the two new buttons up front -- see §2 non-goals
  -- there's no evidence yet it's needed here, and duplicating that
  machinery speculatively would be real, possibly wasted effort.
- Existing wait-time tracking is a DIFFERENT signal from what the driver
  is asking for: `pickup_arrival_ts` / `pickup_departure_ts` are
  GPS-geofence-based (`ARRIVAL_GEOFENCE_METERS = 50`), fully automatic,
  no button involved; `merchant_wait_rating` is a subjective post-trip
  Fast/Okay/Slow rating. Neither is a real measured duration from the
  driver's own button presses -- this PRD adds a third, genuinely new
  field, not a duplicate of either.
- `_update_current_trip_phase_timestamp` / `_update_current_trip_text_column`
  are the existing, established pattern for writing one value onto the
  currently-active `trips` row -- this PRD follows that same shape for
  the new column rather than inventing a different persistence
  mechanism.
- `OverlayHelper` has no existing overlay type built for a
  LIVE-UPDATING, ticking counter (the status dot is static color-only,
  the message bubble is a one-shot auto-dismissing message, the
  instruction overlay is persistent-but-static) -- this needs a new,
  dedicated overlay element.

## 2. Definition of "functional" / non-goals

Definition of functional:
- [x] Tapping Dasher's "Arrived at Store" button starts a 1-minute
      internal grace period; nothing is shown or logged if "Confirm
      Pickup" is tapped before that minute elapses. **Re-verified
      2026-09-09**: `startStoreWaitGracePeriod()`/`STORE_WAIT_GRACE_
      PERIOD_MS = 60_000`; `stopStoreWaitTimer()` persists nothing when
      the overlay never became visible.
- [x] If the grace period elapses before "Confirm Pickup" is tapped, a
      small floating overlay appears showing elapsed time past the
      grace period, ticking once a second, until "Confirm Pickup" is
      tapped. **Re-verified 2026-09-09**: `storeWaitTimerTickRunnable`
      calls `OverlayHelper.showStoreWaitTimer` and reschedules itself
      every `STORE_WAIT_TIMER_TICK_MS = 1000`ms.
- [x] On "Confirm Pickup", the overlay is removed and the measured
      over-grace duration is persisted (silently -- no voice, no toast)
      against the current trip. **Re-verified 2026-09-09**:
      `stopStoreWaitTimer()` clears the overlay then calls
      `record_store_wait_timer`, no voice/toast call present.
- [x] The measured duration is visible later in Trip History, alongside
      this app's other measured phase durations, clearly labeled as
      distinct from the existing GPS-based wait duration and the
      subjective wait rating.

Non-goals (explicit, per the driver's own answer and this repo's
existing scope-discipline):
- No voice/spoken announcements at any point in this flow -- driver
  explicitly chose overlay + silent log, not the voice option.
- No node-bounds-scanning fallback mechanism built preemptively for
  these two buttons -- only plain click-text matching, per §1's own
  reasoning. If a future diagnostic log shows zero CLICK events for
  these buttons (the same failure Accept/Decline had), THAT is the
  evidence needed before building the heavier mechanism -- not built
  speculatively now.
- Does not touch or replace the existing GPS-based
  `pickup_arrival_ts` / `pickup_departure_ts` fields or the subjective
  `merchant_wait_rating` -- purely additive, a third, separate signal.
- Does not attempt a per-job breakdown for batch/multi-stop pickups --
  one timer per pickup stop, the same single-pickup scope this
  codebase's other pickup-adjacent features already carry (see
  `docs/deadhead_stacked_order_baseline/PRD.md`'s own disclosed Part 2B
  limitation).

## 3. Honest risk, disclosed up front

The exact literal text of Dasher's "Arrived at Store" and "Confirm
Pickup" buttons has NOT been confirmed against a real screenshot --
unlike "Yes, I want to unassign" (screenshot-confirmed) or the
Accept/Decline node-bounds fallback (built only after a real
diagnostic log proved the plain click assumption wrong for those two).
This PRD proceeds on the driver's own literal wording from their
request as the best available evidence, the same trust level this app
already gives the driver's own direct answers elsewhere -- but if
DoorDash's actual button text differs even slightly (capitalization
aside, since matching is case-insensitive), detection will simply never
fire, silently, with no timer ever starting. Disclosed here rather than
guessed past -- and exactly the kind of thing the next real diagnostic
log (`STORE_WAIT`-tagged lines, or their absence) will be able to
confirm or correct.

## 4. Design

### 4.1 Detection (`DasherAccessibilityService.java`)

Reuse the EXISTING `TYPE_VIEW_CLICKED` text-matching block (the same
one already handling Accept/Decline/unassign) -- add two more
`else if (clicked.equalsIgnoreCase(...))` branches for "Arrived at
Store" and "Confirm Pickup". No new event-handling machinery.

### 4.2 Grace period + timer state

- New fields: `arrivedAtStoreTapMs` (`Long`), `storeWaitTimerVisible`
  (`boolean`), a dedicated `Handler` + two `Runnable`s (a one-shot
  delayed "grace period elapsed, start showing" runnable, and a
  repeating 1-second tick runnable) -- mirrors this class's own
  existing `timeoutHandler` / `pendingTimeoutRunnable` pattern rather
  than inventing a new shape.
- `startStoreWaitGracePeriod()`: records the tap time, schedules the
  delayed-start runnable at `STORE_WAIT_GRACE_PERIOD_MS = 60_000`.
- `stopStoreWaitTimer()`: always cancels the delayed-start runnable
  first (so a pickup confirmed INSIDE the grace period shows nothing,
  per §2); if the timer had already become visible, stops the tick
  runnable, clears the overlay, computes the over-grace duration, and
  persists it.

### 4.3 Overlay (`OverlayHelper.java`)

New `showStoreWaitTimer(Context, String text)` /
`clearStoreWaitTimer(Context)` pair -- a small persistent `TextView`,
positioned top-END (clear of the existing top-START status dot and the
centered message/instruction overlays), updated in place via
`setText()` on each tick rather than removed/re-added every second.
Same disclosed `addView()` hardening (`try/catch RuntimeException`) as
`showStatusDot` already has, per driver backlog #16's own established
reasoning for this exact class of risk.

### 4.4 Persistence (`drive_monitor.py`)

- New nullable column `trips.store_wait_over_grace_seconds REAL`,
  migrated via the existing `PRAGMA table_info` + `ALTER TABLE` pattern
  already used for every other schema addition in this file.
- New `DriveMonitorEngine.record_store_wait_timer(over_grace_seconds)`,
  following the exact shape of `_update_current_trip_phase_timestamp` /
  `_update_current_trip_text_column` -- writes onto whichever trip has
  `end_time IS NULL`.
- `_build_trip_summary_dict` / `get_trip_summary` gains this field in
  its returned dict, `None` when never recorded (a trip with no
  "Arrived at Store"/"Confirm Pickup" taps -- e.g. every trip before
  this ships, or one where detection didn't fire).

### 4.5 Trip History display (`TripHistoryActivity.java`)

Shown alongside the existing "Wait at restaurant" (GPS-based) line in
the already-existing "Where The Time Went" section, clearly labeled to
distinguish it (e.g. "Store wait beyond 1 min (measured): Xm Ys") --
omitted entirely (not shown as "0m 0s") when `None`, matching this
screen's own established nullable-field convention.

## 5. Premortem -- assume this ships and doesn't work

- **P1 -- the button text guess is wrong, so nothing ever fires.** The
  single largest risk (see §3). Mitigation: `STORE_WAIT`-tagged
  diagnostic lines log both the tap detection AND enough raw click
  context (matching this file's own existing `CLICK`-tag logging
  pattern, already gated to only fire while relevant, to avoid spamming
  every unrelated tap) that the next real log can directly confirm or
  rule out whether these two specific taps were ever seen at all --
  observable, not just silently absent.
- **P2 -- these buttons behave like Accept/Decline (no click event
  fires at all), not like "Yes, I want to unassign."** Real possibility
  per §1's own finding -- some Dasher UI elements apparently don't emit
  `TYPE_VIEW_CLICKED` reliably. If the next log shows zero
  `STORE_WAIT: Arrived at Store tapped` lines despite the driver
  confirming they did tap it, that's the evidence needed to build the
  heavier node-bounds fallback (§2 non-goal, deferred, not abandoned).
- **P3 -- the overlay collides visually with Dasher's own UI or another
  of this app's overlays.** No device to verify placement in this
  environment. Mitigation: positioned in a corner unused by every other
  existing overlay in this file (top-END), but this is a code-review
  judgment call, not a confirmed clash-free position.
- **P4 -- a pickup is unassigned
  (`"Yes, I want to unassign"`) or the app restarts mid-wait, leaving a
  stale `arrivedAtStoreTapMs` with no matching "Confirm Pickup" ever
  coming.** Mitigation: `record_pickup_unassigned_for_long_wait`'s own
  existing handler is a natural second place to also cancel the
  store-wait timer (grace-period runnable + any visible overlay) --
  wired in explicitly as part of this PRD, not left as a silent leak. A
  full process restart clears all in-memory Java state naturally
  (fields reset on a fresh service instance), so that case self-heals;
  a leaked `WindowManager` overlay surviving a service restart isn't
  possible since the overlay lives in the same process as the service.
- **P5 -- multi-stop/batch orders have more than one real "Arrived at
  Store" per trip.** Out of scope per §2 (one timer per pickup stop,
  the same single-pickup limitation this codebase already discloses
  elsewhere) -- if this turns out to matter in practice, that's real
  evidence for a follow-up, not guessed at here.

## 6. Open questions

None blocking implementation -- §3's disclosed button-text risk is a
known, accepted uncertainty (the driver's own wording stands in for
confirmation, per their own answer to the second clarifying question),
not a blocking unknown; if it's wrong, the next diagnostic log will
show it plainly (P1's own mitigation).

## 7. Success criteria (ralph-loop checklist)

- [x] `trips.store_wait_over_grace_seconds` column + migration added
- [x] `record_store_wait_timer(over_grace_seconds)` added to
      `DriveMonitorEngine`, following the existing
      `_update_current_trip_*` pattern
- [x] `OverlayHelper.showStoreWaitTimer` / `clearStoreWaitTimer` added,
      positioned clear of existing overlays, `addView` hardened per
      #16's own established pattern
- [x] `DasherAccessibilityService`: grace-period + tick-timer state
      machine, wired to the existing click-text-matching block for
      "Arrived at Store" / "Confirm Pickup"
- [x] `record_pickup_unassigned_for_long_wait`'s call site also cancels
      any in-progress store-wait timer (P4)
- [x] `_build_trip_summary_dict` / `get_trip_summary` returns the new
      field
- [x] `TripHistoryActivity` shows it in "Where The Time Went", omitted
      when `None`
- [x] Real, runnable Python test for `record_store_wait_timer` + the
      trip-summary field (present/None cases)
- [x] Brace/paren balance + code review for all touched Java files
- [ ] Driver confirms in real use: tapping "Arrived at Store" then
      waiting past a minute shows the overlay; tapping "Confirm Pickup"
      stops it and the duration shows up in Trip History afterward
- [ ] Driver sign-off
