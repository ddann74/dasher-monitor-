# PRD: Deep-link the accessibility-down alert to the fix

Status: IMPLEMENTED (2026-09-15), scouting-pass finding (round 8, #3,
directed audit of Dasher-mode monitoring fail-safety).

## 1. What was wrong

Unlike the GPS watchdog (`MonitoringWatchdogReceiver.onReceive()`,
which can automatically call `startForegroundService(ACTION_START_TRACKING)`
to self-heal), Android gives this app no API to silently re-enable a
dead or unbound `AccessibilityService` -- true automatic recovery isn't
possible here, confirmed by checking Android's own documented API
surface (there is no `AccessibilityService` equivalent to
`NotificationListenerService.requestRebind()`).

Given that, the only thing actually within this app's control is making
the one fix that DOES work (the driver manually toggling the service
off and back on in Settings) as easy as possible to reach. Before this
fix, `raisePermissionRevokedAlert("Accessibility", ...)` built a
notification with no `setContentIntent` at all -- tapping it did
nothing, exactly the same gap `docs/screen_recording/PRD.md §21`
describes finding and fixing for the "Trip Capture" alert (a real,
confirmed 2.5-day diagnostic log showed that exact alert firing
repeatedly with a driver never able to fix it from the notification
itself). The generic escalating vibration (`startPermissionAlertVibration`,
already fires for every permission alert including this one) makes sure
the driver notices something's wrong, but noticing isn't the same as
knowing where to go fix it.

## 2. Design

Added an `else if ("Accessibility".equals(permissionName))` branch to
`raisePermissionRevokedAlert`, alongside the existing "Trip Capture"
branch, mirroring its exact `PendingIntent` construction pattern:
builds an `Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)` -- the same
intent `PermissionsActivity`'s own `accessibilityButton` already uses --
wrapped in a `PendingIntent.getActivity` with the same
`FLAG_UPDATE_CURRENT` / `FLAG_IMMUTABLE` flags already used for the Trip
Capture case, and sets it as the notification's `setContentIntent`.
Tapping the alert now takes the driver directly to Android's
Accessibility Settings screen -- one tap from the alert to the list
where they toggle this app's service off and back on, instead of
separately having to remember or find Permissions & Setup on their own.

No changes to the escalating-vibration mechanism (`startPermissionAlertVibration`)
-- it already runs unconditionally for every permission alert, Accessibility
included, so that half of "make sure the driver notices and can act" was
already in place; the gap was specifically the missing deep-link.

## 3. Verification

`raisePermissionRevokedAlert` depends on live `NotificationManager`/
`Notification.Builder`/`PendingIntent`/`Settings` APIs and can't be
compiled/run outside a device or emulator in this environment. Verified
instead by:

- `python3`-based brace/paren balance check on the edited file: final
  depth 0.
- A standalone, compiled-and-run Java program (`javac`/`java`) mirroring
  the exact `if ("Trip Capture"...) else if ("Accessibility"...)` branch
  dispatch (confirmed identical in structure to the shipped source via
  `grep` immediately before writing the test) -- since the actual
  `Intent`/`PendingIntent` construction can't be exercised outside
  Android, this verifies the DECISION (which permission name gets which
  deep-link target, or none) rather than the full object construction:
  - **The gap this closes**: an "Accessibility" alert now resolves to a
    real deep-link target (previously none -- tapping did nothing).
  - The pre-existing "Trip Capture" deep-link is unaffected by this
    change.
  - Every other permission alert (Location, Overlay, Notification
    Access, Notification Listener) still resolves to no content intent,
    exactly matching pre-fix behavior -- confirms this addition doesn't
    change behavior for permissions it wasn't meant to touch.

  6 checks, all passed on first run.
- Manually confirmed `Settings.ACTION_ACCESSIBILITY_SETTINGS` is the
  exact same intent action already used, verified working, at
  `PermissionsActivity.java:322`'s own `accessibilityButton` click
  listener -- not a new, unverified API choice.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  tap-through (notification -> Accessibility Settings screen ->
  driver finds and re-toggles this app's service) has not been observed
  on a device.
- `Settings.ACTION_ACCESSIBILITY_SETTINGS` opens the general
  accessibility services LIST, not a deep link directly to this app's
  specific service toggle -- Android does have a more specific
  `ACTION_ACCESSIBILITY_DETAILS_SETTINGS` on newer API levels, but it
  requires extras not all OEMs honor reliably, so the broader, universally-
  supported action (already proven working elsewhere in this exact
  codebase) was chosen over a more targeted but less certain one. The
  driver still needs to find "Dasher Monitor" in that list themselves --
  this closes the "tapping did nothing at all" gap, not "gets there in
  zero additional taps."
- This is explicitly NOT automatic recovery -- Android provides no API
  for that here, unlike GPS. The fix is entirely about reducing friction
  on the one manual action that does work, not eliminating the need for
  it.
- Does not change the underlying alert-firing condition (still fires per
  `docs/accessibility_liveness_heartbeat/PRD.md`'s `isHeartbeatStale()`
  check, or a genuine Settings revoke) -- purely a deep-link addition to
  an alert that already fires correctly.

## 5. Success criteria

- [x] Tapping the Accessibility alert now opens Android's Accessibility
      Settings screen instead of doing nothing
- [x] The pre-existing Trip Capture deep-link is unaffected
- [x] Every other permission alert's behavior (no content intent) is
      unchanged
- [x] Standalone compiled Java test (6 checks) of the exact dispatch
      logic, fully passed
- [x] `python3` brace/paren balance check clean
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver confirms in real use that tapping this alert reliably opens
      Accessibility Settings.
- [ ] Driver sign-off.
