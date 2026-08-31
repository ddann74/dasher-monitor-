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
