# Progress log — alert the driver if a dash is happening without active monitoring

## Implementation (2026-09-02)

Per PRD §4, built from §5's own stated recommendations (no driver
override given by the time the ralph-loop continuation reached this
PRD):

- `TripForegroundService.raiseMonitoringNotActiveAlert(Context, String
  reason)` (new, `static`): the shared alert mechanism §5 point 1
  recommended, matching `raisePermissionRevokedAlert`'s existing
  pattern -- its own notification channel
  (`monitoring_not_active_alert`, `IMPORTANCE_HIGH`, vibration
  enabled), sound + vibration + high priority + auto-cancel, plus a
  `log_diagnostic("ALERT", ...)` call through the Python engine with a
  `FallbackLogger` fallback if the engine isn't reachable -- the same
  safety-net pattern already used by every other standalone
  caller in this codebase.
- `DasherAccessibilityService.attemptAutoStartMonitoring(String
  action, String logCategory, String successMessage, String
  failureReasonForAlert)` (new, private helper): calls
  `startForegroundService()`, logs success, then schedules a
  `MONITORING_VERIFY_DELAY_MS` (5s) delayed check via a `Handler` --
  the "give it a moment, then check for real" pattern §5 point 2
  asked for, using 5s as the deliberately-chosen value (same order of
  magnitude as this codebase's other re-arm/re-check timers, e.g.
  `FOREGROUND_CHECK_INTERVAL_MS` = 20s). If `TripForegroundService.isRunning`
  is still false after the delay, or if `startForegroundService()`
  itself threw (the synchronous `ForegroundServiceStartNotAllowedException`
  case PRD §2 documents), the shared alert fires.

### A real finding beyond what the PRD's own investigation named

The PRD's §1 investigation named two auto-start call sites:
`checkCurrentForegroundWindow` and `DrivingDetectionReceiver`. While
implementing, a **third** real call site was found in
`DasherAccessibilityService`: the Dash-Paused auto-resume path (GPS
tracking resumes automatically once the Dash-Paused screen is no
longer showing). This is exactly the same silent-failure shape §2
describes -- a driver whose dash resumes from a paused state has the
same platform-level risk of a rejected background
`startForegroundService()` call, with the same "no trace but a silent
diagnostic log line" gap. All three call sites in
`DasherAccessibilityService` were rewired through the same shared
`attemptAutoStartMonitoring` helper, not just the two the PRD named --
otherwise this PRD would have left one of the three real gaps
unfixed while claiming "single shared implementation" coverage.

### `DrivingDetectionReceiver`: same alert, deliberately without the delayed half

`DrivingDetectionReceiver`'s own auto-start attempt got its own inner
`try/catch` around `startForegroundService()`, raising
`raiseMonitoringNotActiveAlert` directly on a thrown exception -- but
deliberately WITHOUT the 5s delayed re-verification half. Reasoning,
documented in the code comment: a `BroadcastReceiver` is meant to be
short-lived (the system does not guarantee it survives past
`onReceive()` returning), so scheduling a delayed check from inside it
is unreliable in a way it isn't from an `AccessibilityService`, which
stays alive for the life of the accessibility connection. The
synchronous throw is the primary real failure mode here either way, so
this still covers the actual documented risk (§2's
`ForegroundServiceStartNotAllowedException` case) without relying on a
receiver outliving its callback.

## Verification (2026-09-02)

No Python component exists in this PRD -- it's Java/Android-only, so
no executable `python3` test applies (unlike most other PRDs this
session). Verification is code review plus brace/paren-balance checks
only, same approach used for `boot_resume_monitoring`:

- Brace and paren counts confirmed balanced (0/0 diff) in all three
  modified files -- `TripForegroundService.java`,
  `DasherAccessibilityService.java`, `DrivingDetectionReceiver.java`
  -- before and after every edit.
- Code review: all three real auto-start call sites in
  `DasherAccessibilityService` (service-connect/foreground-transition
  detection, the debounced foreground transition, and Dash-Paused
  auto-resume) now go through the one shared helper; the
  `pausedByAutoDetection = false; return;` line that followed the
  third call site's original code was preserved immediately after the
  new helper call, so the paused-state bookkeeping is unchanged.
  `DrivingDetectionReceiver`'s existing outer `try/catch` (its own
  general exception log) was left in place around the new inner one --
  the inner catch only narrows what happens specifically when
  `startForegroundService()` itself throws.

Remaining PRD §6 boxes: on-device confirmation (blocked -- no Android
emulator/device available in this environment; per §6's own note, the
actual trigger condition is an OS-level background-start rejection
that can't be forced from a code review alone) and driver sign-off.
