# PRD: Consolidated "is this actually detecting Dasher" status

Status: IMPLEMENTED (2026-09-14), driver-requested feature ("ensure the
monitoring app is actively monitoring a dasher session"), following
directly from the same session's `docs/dasher_package_verification/PRD.md`
audit and the driver's own follow-up question ("does installed mean
actively looking for offers or on a delivery").

## 1. What was requested, and what already existed

Confirmed with the driver in the same conversation: "installed" (the
prior fix) only proves the APK exists on the device -- it says nothing
about whether detection is actually working right now. The driver
wants real confidence, at a glance, that the app is genuinely watching
a live Dasher session.

Auditing what already existed before building anything: several of the
exact signals needed for this already exist in the code, just scattered
across one-time push alerts and a single voice announcement, never
shown together:

- `DasherAppInfo.isInstalled` (the prior fix)
- Accessibility permission enabled (`ENABLED_ACCESSIBILITY_SERVICES`,
  already checked in `checkAndLogPermissions` and `MainActivity`'s own
  `isAccessibilityServiceGranted`)
- `AppNotificationListenerService`'s LIVE connection state
  (`isListenerConnected`/`lastListenerConnectedMs`, from `docs/
  notification_listener_liveness/PRD.md`) -- distinct from the
  "Notification Access" permission already shown, which stays green
  even if the live binding silently drops
- `DasherAccessibilityService`'s auto-detected "Dash Paused" state
  (`pausedByAutoDetection`, from the existing `DashPauseDetector`,
  confirmed against a real screenshot) -- previously only ever produced
  a ONE-TIME voice announcement when it happened, with nothing
  persistent showing it was still true

No new detection mechanism was built -- this consolidates what already
exists into something visible and trustworthy at a glance, per the
driver's own framing of the ask.

## 2. Design

### 2.1 `MainActivity.buildDasherDetectionStatusLine()`

Called from `updateStatusText()`, only in the branch where monitoring
is confirmed running (the two earlier "fully off"/"not monitoring"
branches have nothing meaningful to add here). Checks the 3 real
problem signals (Dasher not installed, accessibility not enabled,
notification listener genuinely disconnected -- with the same cold-
start guard `checkAndLogPermissions` already uses, so "hasn't connected
yet" during the first few seconds of a fresh launch is never mistaken
for a real drop). If any are true, shows exactly which ones. If none
are true but a Dash Paused state is currently auto-detected, shows
that instead. Otherwise shows a plain positive confirmation: "Actively
watching for Dasher activity."

### 2.2 `TripForegroundService.appendDetectionWarning()`

Same signals, applied to the persistent foreground-service notification
(mirroring the existing `appendOverlayReminder` pattern) -- a driver who
has minimized the app, the normal state while actually driving, still
sees this without reopening it. Deliberately asymmetric with the in-app
line: only appends text when there's something worth noting (a real
problem, or Dash Paused) -- a persistent notification that always says
"everything's fine" on every rebuild is noise a driver learns to ignore,
unlike an in-app screen the driver actively glances at to check.

### 2.3 `DasherAccessibilityService.pausedByAutoDetection` widened

Was a private instance boolean; widened to `public static volatile`
(same cross-component static-field pattern this file already uses for
`isDasherForeground`) so both of the above can read it. No change to
its own read/write logic or meaning.

## 3. Verification

- Real, compiled, executed Java test (`DasherDetectionStatusTest.java`,
  pure logic mirroring both `buildDasherDetectionStatusLine` and
  `appendDetectionWarning` exactly, with fakes standing in for the 4
  real signals): confirmed the healthy case shows the positive
  confirmation with no notification noise; confirmed a missing Dasher
  app is surfaced on both surfaces; confirmed an auto-detected Dash
  Paused state is shown persistently on both, not just spoken once;
  confirmed the cold-start guard correctly does NOT flag a never-yet-
  connected listener as a genuine drop; confirmed a GENUINE listener
  disconnect (was connected, now isn't) IS correctly reported. 5
  checks, all passed.
- Brace/paren balance confirmed on all 3 modified Java files.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  status line and notification text have not been observed on a
  device.
- Still built entirely from the same underlying signals as before --
  this doesn't add any NEW way of detecting whether Dasher is truly
  being watched (e.g. it can't tell "accessibility is enabled but the
  live binding silently died," the exact class of gap `docs/
  notification_listener_liveness/PRD.md` disclosed as unconfirmed for
  the notification listener and never built an equivalent live-binding
  signal for accessibility at all). It's a consolidation and
  presentation fix, not a new detection capability.
- The persistent notification's warning text is appended to an already
  space-constrained `setContentText` line -- a driver with multiple
  simultaneous problems (e.g. Dasher not installed AND accessibility
  off) will see a longer, possibly truncated line on some launchers/
  notification shades. Not tested against real notification-shade
  truncation behavior.

## 5. Success criteria

- [x] Consolidates 4 already-tracked signals into one status line/
      notification addition, no new detection mechanism
- [x] Positive confirmation shown when everything's healthy, not just
      silence
- [x] The exact problem(s) named when something's wrong, not just a
      generic warning
- [x] Dash Paused shown persistently, not just as a one-time voice
      announcement
- [x] Cold-start guard correctly avoids a false "disconnected" report
      on a fresh launch
- [x] The persistent notification stays quiet (no added text) when
      everything's healthy, to avoid alert fatigue
- [x] Real compiled/executed Java test (5 checks) fully passed
- [x] Brace/paren balance confirmed on all modified files
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver confirms in real use: the status line and notification
      text read clearly, and correctly reflect real device state.
- [ ] Driver sign-off.
