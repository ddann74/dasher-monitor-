# Progress log — GPS-independent watchdog re-arm

## Implementation (2026-08-31)

`TripForegroundService.java`:

- Added `watchdogRearmHandler`/`watchdogRearmRunnable`, a self-repeating
  `Handler.postDelayed` pair mirroring the existing
  `accessibilityHeartbeatHandler`/`accessibilityHeartbeatRunnable`
  exactly (same `Looper`, same self-reschedule-while-`monitoringActive`
  shape). Fires `MonitoringWatchdogReceiver.scheduleWatchdog(this)` on a
  fixed `WATCHDOG_REARM_INTERVAL_MS` (5 min, unchanged) schedule -
  independent of whether any GPS callback has arrived.
- Started in `startTracking()`, right after the accessibility heartbeat's
  own `postDelayed` call.
- Stopped in both `stopTracking()` and `onDestroy()`, alongside the
  accessibility heartbeat's own `removeCallbacks` calls - same lifecycle,
  no new leak surface.
- Removed the old re-arm call from `maybeLogHeartbeat` (previously fired
  only when a GPS location callback arrived) and the now-unused
  `lastWatchdogRearmMs` field. `WATCHDOG_REARM_INTERVAL_MS` itself is
  unchanged, just relocated with an updated comment explaining the actual
  reason for the move.

No change to `MonitoringWatchdogReceiver.java` at all - confirmed via
`grep`, matching the PRD's own scope.

## Verification (2026-08-31)

Same disclosed limitation as `docs/watchdog_reliability/PRD.md`: no
Android SDK/emulator in this environment, verified by code review only.

- Brace-balance check on the full modified file: 154 `{` / 154 `}`,
  balanced.
- Confirmed no leftover reference to the removed `lastWatchdogRearmMs`
  field (`grep` returns nothing).
- Confirmed all 6 references to the new mechanism are wired correctly:
  declared once, started once (`startTracking`), stopped twice
  (`stopTracking`, `onDestroy`), referenced in 2 explanatory comments.
- Confirmed `MonitoringWatchdogReceiver.java` untouched (PRD's own scope
  guardrail).

Remaining PRD §6 box: user sign-off (real-device confirmation that the
watchdog now re-arms on schedule even during an extended GPS gap, e.g.
the deep-park tier's 30+ second polling interval, is out of scope for
this environment - same limitation as every other Java-side PRD here).

## Premortem (2026-08-31)

Requested by the driver. Re-traced the actual implementation, same
approach as the two premortem passes already done on
`docs/screen_recording/PRD.md`. Found one real, previously-undocumented
gap and one worth stating explicitly even though it's not new:

- **P1**: confirmed in code that `startTracking()` calls
  `MonitoringWatchdogReceiver.scheduleWatchdog(this)` directly, then
  separately schedules `watchdogRearmRunnable` for 5 minutes later - so
  the new GPS-independent backstop doesn't actually start covering
  anything until 5 minutes into each trip. A dropped first alarm with no
  second alarm before that mark is still exactly as unprotected as
  before this PRD. Documented as a real, disclosed gap (PRD.md §3a-P1),
  not fixed - shrinking it means either lowering
  `WATCHDOG_REARM_INTERVAL_MS` (explicit non-goal) or firing the new
  timer once immediately in addition to its normal schedule, flagged as
  a real option for a follow-up decision rather than done unilaterally.
- **P2**: "GPS-independent" only removes the GPS-specific dependency -
  the timer still runs on the main `Looper` and would be delayed by any
  other main-thread stall, same as the heartbeat pattern it mirrors.
  Inherited limitation, not introduced by this PRD, stated explicitly so
  it isn't mistaken for "unconditionally reliable."

Also explicitly reviewed and ruled out a `removeCallbacks`-vs-in-flight-
execution race between `stopTracking()` and the runnable - both run on
the same main thread, so they can't execute concurrently. No fix needed;
recorded so this doesn't get re-investigated later without cause.
