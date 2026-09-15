# PRD: Detect when the app's own notifications are disabled

Status: IMPLEMENTED (2026-09-15), scouting-pass finding (round 10, #5,
directed audit of monitoring auto-correction).

## 1. What was wrong

Every alert this app can raise -- `raisePermissionRevokedAlert`,
`MonitoringWatchdogReceiver.raiseAlert`/`raiseEscalatedAlert`,
`raiseRecordingVerificationFailedAlert`, `raiseEngineFailureAlert`,
`raiseMonitoringNotActiveAlert` -- ends in a bare
`manager.notify(...)` call, with nothing anywhere checking whether the
driver could actually see it. A driver who disables this app's
notifications entirely (Settings -> Apps -> Dasher Monitor ->
Notifications) gets zero visible symptom: `notify()` does not throw or
report failure in that case, it just silently delivers nothing. Every
fail-safe mechanism this whole session built -- the accessibility
heartbeat, the watchdog staleness alert, the restart circuit breaker,
the engine-health gate, the screen-recording liveness check -- ultimately
terminates in this same unverified call, making disabled notifications a
single point of silent failure for the entire alerting layer.

## 2. Design

Added `NotificationChannelHelper.areNotificationsDisabled(NotificationManager)`
-- a thin wrapper around the real, documented, authoritative platform
API `NotificationManager.areNotificationsEnabled()` (added API 24; this
app's minSdk is 26, no version gate needed), answering "can this app
post anything the user will ever see," independent of any specific
channel.

A notification-based alert obviously can't be trusted to reach a driver
who has disabled notifications, so this can't be surfaced as another
notification -- that would be self-defeating. Instead:

- `TripForegroundService.checkAndLogPermissions()` (already running on
  the existing 15s heartbeat) computes this once per check and sets a
  new `public static volatile boolean notificationsAppearDisabled`
  field -- the same cross-component static-flag pattern already used
  for `isRunning`/`lastGpsUpdateMs` -- and includes it in the existing
  `PERMISSIONS` diagnostic log line.
- `MainActivity.buildDasherDetectionStatusLine()` (the in-app status
  text, read directly by the driver, not delivered as a notification)
  now adds "Notifications disabled -- alerts won't reach you" to its
  problems list when the flag is set.
- `TripForegroundService.appendDetectionWarning()` (the text appended to
  the PERSISTENT foreground-tracking notification -- one of the few
  surfaces that keeps showing even when every notification-based ALERT
  channel has been separately silenced, since Android generally still
  requires the foreground-service notification itself) adds the same
  warning.

Both surfaces reuse the exact same `problems` list pattern already
established for every other detection-status check in this codebase, so
this warning combines naturally with whatever else might also be wrong.

## 3. Verification

`NotificationManager`/`TripForegroundService`/`MainActivity` depend on
live Android system services and can't be compiled/run outside a device
or emulator in this environment. Verified instead by:

- `python3`-based brace/paren balance check on all three edited files:
  final depth 0 for each.
- A standalone, compiled-and-run Java program (`javac`/`java`)
  containing a **verbatim copy** of `areNotificationsDisabled` (confirmed
  identical to the shipped source via `grep` immediately before writing
  the test) and the exact "add to problems" gating now used at both
  surfaces, run against a minimal fake `NotificationManager` (the real
  class requires a live system service binding):
  - A manager reporting notifications enabled is correctly NOT flagged
    as disabled.
  - **The bug scenario**: a manager reporting notifications disabled is
    correctly flagged -- previously nothing anywhere checked this.
  - A `null` manager (`getSystemService` can return `null`) is treated
    as "can't confirm either way," not a false positive -- matches every
    other null-manager guard already established elsewhere in this
    codebase.
  - When the flag is set, the warning is correctly added to the problems
    list at both surfaces; when it isn't, no warning appears.

  5 checks, all passed on first run.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  "driver disables notifications, status line/persistent notification
  correctly warns" flow has not been observed on a device.
- Only checks the APP-LEVEL notification permission
  (`areNotificationsEnabled()`), not individual channel-level silencing
  (a driver could disable just one specific alert channel, e.g. the
  watchdog's, while leaving the app's notifications broadly enabled).
  The round-10 finding named both cases; this fix addresses the
  broader, almost certainly more common one (the app-level toggle) as a
  deliberately scoped first pass -- checking every individual channel
  ID across this app's ~10 distinct channels would be a materially
  larger, more failure-prone change (each channel ID would need its own
  check, and new channels added later would need to remember to opt in)
  left as a possible follow-up if channel-level silencing turns out to
  matter in practice.
- Relies on the persistent foreground-tracking notification itself still
  being visible to carry this warning -- if a driver has somehow
  suppressed even that (a rare, non-default Android configuration), this
  fix's persistent-notification surface wouldn't reach them either; the
  in-app status line is the more reliable fallback in that edge case,
  since it doesn't depend on any notification being shown at all.

## 5. Success criteria

- [x] The app now detects when its own notifications are disabled at the
      OS level
- [x] This is surfaced through two non-notification-dependent channels
      (in-app status line, persistent tracking-notification text) rather
      than as another notification, which would be self-defeating
- [x] A `null` NotificationManager doesn't produce a false positive
- [x] The new check integrates into the existing 15s heartbeat and
      diagnostic log, no new polling loop needed
- [x] Standalone compiled Java test (5 checks) of the exact detection and
      surfacing logic, fully passed
- [x] `python3` brace/paren balance check clean on all three edited
      files
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver/QA confirms on a real device that disabling this app's
      notifications produces the warning on both the in-app status line
      and the persistent tracking notification.
- [ ] Driver sign-off.
