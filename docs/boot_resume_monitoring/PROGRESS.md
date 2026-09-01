# Progress log — resume monitoring after a reboot

## Implementation (2026-09-01)

Per PRD §3:

- `MonitoringWatchdogReceiver.wasIntendedActive(Context)` (new): reads
  the existing `KEY_INTENDED_ACTIVE` SharedPreferences flag, the exact
  same one `markIntendedActive` already writes from
  `TripForegroundService.startTracking()`/`stopTracking()` -- no new
  persistence added, just a reader for data that already meant the
  right thing.
- `BootAndUpdateReceiver` (rewritten): still logs the existing
  `"Device rebooted"`/`"App was updated/reinstalled"` message
  unconditionally, then -- for those two actions specifically -- checks
  `wasIntendedActive`. If true, calls `context.startForegroundService(...)`
  with `TripForegroundService.ACTION_START_TRACKING`, the same call
  `DrivingDetectionReceiver` already makes successfully elsewhere in
  this codebase (not a new, unproven mechanism). Logs the outcome
  either way (resumed, or "monitoring was off before this, not
  resuming"). On a real resume, also shows a real notification (new
  `"monitoring_auto_resumed"` channel, `IMPORTANCE_DEFAULT`) rather
  than only the diagnostic log -- a Toast would only be seen if the
  phone happened to be unlocked and awake at that exact moment, which
  right after a reboot it usually isn't.

## §4's open questions — built from the PRD's own recommendation

Neither was explicitly answered by the driver before implementation.
Per RALPH_PROMPT.md's own instruction for this case, both were built
from the PRD's stated recommendation rather than left blocking or
guessed at differently:

1. `ACTION_MY_PACKAGE_REPLACED` triggers resume the same as
   `ACTION_BOOT_COMPLETED` -- an app update kills the running process
   the same way a reboot does, so the same "was it intended active"
   check applies equally.
2. A real notification is shown on resume, not a Toast.

If the driver wants either behavior changed, that's a one-line
adjustment (drop the `MY_PACKAGE_REPLACED` branch, or swap the
notification for something quieter), not a redesign.

## Verification (2026-09-01)

No Python changes in this PRD, so no executable test applies. Verified
by code review: brace/paren counts in both modified Java files
(`BootAndUpdateReceiver.java`, `MonitoringWatchdogReceiver.java`)
balanced (0/0) before and after every edit. Cross-checked that
`wasIntendedActive` reads the exact same `PREFS_NAME`/`KEY_INTENDED_ACTIVE`
constants `markIntendedActive` writes (not a mismatched key that would
silently always read the default `false`).

The on-device confirmation box is explicitly NOT claimed here, per PRD
§5's own note: `startForegroundService` from `BOOT_COMPLETED` has real
Android-version-specific and OEM-specific edge cases (the 5-second
`startForeground()` deadline, delayed/batched boot broadcasts on some
OEM skins) that code review cannot rule out, even though the call
pattern itself is proven elsewhere in this codebase. This needs a real
device reboot to confirm.

Remaining PRD §5 boxes: on-device confirmation (blocked) and driver
sign-off.
