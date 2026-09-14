# PRD: Block Restore/Reset while a trip is actively being tracked

Status: IMPLEMENTED (2026-09-14), follow-up scouting-pass finding.

## 1. What was wrong

`DataManagementActivity`'s Restore Database and Reset All Data buttons
were reachable at any time, including while `TripForegroundService` was
actively tracking a delivery. Neither destructive operation checked for
this, and neither touches `TripManager` -- a completely separate,
persistent in-memory object that keeps writing to the live trip's own
`trip_id` on every GPS tick (`_maybe_save_partial_progress`, `WHERE
id=?`) regardless of what happens to the database file underneath it.

- **Restore mid-trip**: `close_database_for_restore()` /
  `reopen_database_after_restore()` only swap the SQLite file and
  reconnect -- `TripManager`'s `trip_id` is never reconciled against the
  newly-restored file. If that id doesn't exist in the restored backup
  (common -- an older backup's own id sequence is usually behind the
  live session's), every subsequent write becomes a silent no-op and
  the entire live trip's progress and eventual summary are lost with no
  error shown anywhere. If the id DOES happen to exist in the restored
  file (also plausible, since both files often share id history up to
  the backup point), the live trip's data silently overwrites an
  unrelated historical trip instead.
- **Reset mid-trip**: `reset_all_data`'s `DELETE FROM trips` has no
  scoping clause, so it also deletes the currently-open trip's own row.
  Every later write is then a guaranteed no-op -- worse than a crash,
  since there's no orphaned `end_time IS NULL` row left for
  `_recover_interrupted_trips` to even catch on the next launch.

## 2. Design

Simplest safe fix, not attempted: reconciling `TripManager`'s live state
against a database that changed out from under it (re-deriving whether
the current trip's row still exists, whether to re-open a new one,
whether any in-flight pickup/stop state is still meaningful) is a much
larger, riskier surface than the actual ask. Blocking both operations
while monitoring is active sidesteps all of that -- the driver can
always tap Stop Monitoring first (which finalizes the trip through its
normal, already-correct path) and come back.

`blockIfMonitoringActive(actionName)`, called at the top of both button
handlers before their existing confirm dialogs: checks
`TripForegroundService.isRunning` (confirmed, by reading every
assignment site in that file, to be true only between a real
`startTracking()` and `stopTracking()`/`onDestroy()` -- unlike the
idle-mode "service alive to show the status dot" state, which never
sets it). If true, shows an explanatory dialog and returns without
proceeding to the existing destructive-action confirm dialog.

## 3. Verification

- Real, compiled, executed Java test (`DataManagementGuardTest.java`,
  pure logic, a fake `isRunning` boolean standing in for the real
  static field): confirmed both Restore and Reset are blocked (and
  never reach the destructive action) while monitoring is active -- the
  actual bug scenario -- and confirmed both proceed completely normally
  when monitoring is off, so the existing, already-working flow has no
  regression. 3 checks, all passed.
- Brace/paren balance confirmed on `DataManagementActivity.java`.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  dialog appearing at the right moment has not been observed on a
  device.
- The block is coarse: ANY active monitoring (including plain GENERAL-
  mode driving-efficiency tracking, not just an actual Dasher delivery)
  blocks Restore/Reset, even though only a genuinely in-progress trip
  row is at risk. Chosen deliberately over a narrower "only block during
  DASHER mode" check -- a GENERAL-mode trip's data is exactly as real
  and exactly as vulnerable to the same silent-loss mechanism, so
  narrowing the guard would just reintroduce the same bug for a
  different mode.

## 5. Success criteria

- [x] Restore Database blocked while monitoring is active, with a clear
      explanation and a path forward (Stop Monitoring first)
- [x] Reset All Data blocked the same way
- [x] Neither action's existing, already-working flow is affected when
      monitoring is NOT active
- [x] Real compiled/executed Java test (3 checks) fully passed
- [x] Brace/paren balance confirmed
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see ss4.
- [ ] Driver confirms in real use that both buttons are correctly
      blocked mid-trip and correctly available otherwise.
- [ ] Driver sign-off.
