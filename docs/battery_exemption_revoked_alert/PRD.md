# PRD: Alert on battery-optimization-exemption loss

Status: IMPLEMENTED (2026-09-15). Ralph-loop iteration 4/N of
`docs/monitoring_uptime_guarantee/PREMORTEM.md`, closing risk R5 (round-9
scouting pass finding, directed audit of the monitoring process).

## 1. What was wrong

`TripForegroundService.checkAndLogPermissions` already computed
`hasBatteryExemption` (`PowerManager.isIgnoringBatteryOptimizations`)
every heartbeat, right alongside the other 3 critical permissions
(Location, Overlay, Notification Access) it monitors for a mid-session
true->false transition. But unlike those three, a battery-exemption
loss never triggered `raisePermissionRevokedAlert` -- the boolean was
tracked (`lastLoggedBatteryExempt` already existed, already fed the
diagnostic log and the `changed` re-log trigger) but never actually
acted on.

A driver losing this exemption mid-shift (an OEM battery manager
silently re-enabling optimization, or the driver toggling it themselves)
got no signal at all until Android's own background throttling
eventually degraded GPS tracking enough to trip the generic watchdog
staleness alert -- if it did at all; exemption loss alone doesn't always
produce a heartbeat gap large enough to cross that threshold, so this
could silently degrade tracking reliability with zero indication of the
actual cause.

## 2. Design

Added a mid-session transition block identical in shape to the existing
Location/Overlay/Notification Access/Accessibility blocks:

```java
if (lastLoggedBatteryExempt != null && lastLoggedBatteryExempt && !hasBatteryExemption) {
    raisePermissionRevokedAlert("Battery Optimization Exemption",
            "Android may delay or throttle GPS tracking in the background");
}
```

Added a new deep-link branch to `raisePermissionRevokedAlert`'s
`setContentIntent` chain for `"Battery Optimization Exemption"`, mirroring
`PermissionsActivity`'s own `batteryOptimizationButton` click handler
exactly: `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with a
`package:` URI triggers Android's direct "allow this app to ignore
battery optimizations" system dialog in one tap, rather than dropping the
driver into a settings list to find the app themselves. Re-granting
can't be automated (a real Android restriction requiring explicit user
action), but this makes the manual step as close to one-tap as the OS
allows -- the same tradeoff already accepted for the Accessibility alert
(which has the same "no self-heal API" limitation).

The hash-based notification ID formula already used by
`raisePermissionRevokedAlert` (`9100 + Math.abs(permissionName.hashCode() % 100)`)
needed no manual assignment or audit -- passing the new, distinct string
places it automatically inside the already-reserved 9100-9199 band.

Deliberately did NOT add a `forceLog` "already off at start" block for
this permission (unlike Location/Overlay/Notification Access/Accessibility,
which all got one for the driver-backlog #22 fix) -- the premortem
finding this fix closes (R5) specifically named the missing
true->false transition alert as the gap, not the already-off-at-start
case. Scoped the fix to exactly what was found rather than extending it
to a related but separately-motivated case; see Honest Limits.

## 3. Verification

`checkAndLogPermissions` and `raisePermissionRevokedAlert` depend on live
`PowerManager`/`NotificationManager`/`PendingIntent` Android services and
can't be exercised end-to-end outside a device or emulator in this
environment. Verified instead by:

- `python3`-based brace/paren balance check on the edited file: final
  depth 0.
- A standalone, compiled-and-run Java program
  (`BatteryExemptionAlertTest.java`, `javac`/`java`), verbatim-copying the
  same two checks used for `docs/location_services_toggle_check/PRD.md`'s
  own verification (confirmed identical to the real source via `grep`
  immediately before writing the test):
  1. **Notification ID collision check**: the exact hash formula, run
     against all 7 `permissionName` strings now in the file plus the new
     `Battery Optimization Exemption` -- confirms it lands inside
     9100-9199 and doesn't collide with any of them or any fixed ID used
     elsewhere in the app.
  2. **Transition-detection state machine**: the exact
     `lastLoggedX != null && lastLoggedX && !x` condition (gated on
     `monitoringActive`, matching every other permission here), run
     against 5 scenarios -- cold start, genuine transition, staying off,
     recovering, and monitoring inactive. Confirms the alert fires only
     on a genuine drop while monitoring is active.

  8 checks, all passed on first run.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  notification appearing, and the real `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  deep-link opening the correct system dialog, have not been observed on
  a device.
- No `forceLog` "already off at start" alert was added for this
  permission (see §2) -- a driver who has simply never granted this
  exemption gets no alert when starting a shift, only when losing it
  mid-session after having had it. If a future scouting pass or driver
  report identifies the already-off-at-start case as also worth
  surfacing, it's a small, mechanical addition following the exact
  pattern the other 4 permissions already use.
- Re-granting genuinely cannot be automated -- this is a hard Android OS
  restriction (the same one every other app faces), not a gap in this
  fix. The deep-link minimizes the manual step to one tap + one system
  dialog confirmation, which is the best available outcome.

## 5. Success criteria

- [x] Losing battery-optimization exemption mid-session now triggers a
      specific, actionable alert instead of staying completely silent
- [x] The alert deep-links directly to the OS system dialog for
      re-granting the exemption (mirrors the existing in-app button's
      own intent exactly)
- [x] No new notification ID collision introduced -- verified against
      every existing `permissionName` and every fixed ID in the app
- [x] `python3` brace/paren balance check clean
- [x] Standalone compiled Java test (8 checks) of the exact ID formula
      and transition-detection logic, verified against the shipped
      source, fully passed
- [ ] HONEST LIMIT: no Android device/emulator available -- see §4.
- [ ] Driver/QA confirms on a real device that losing this exemption
      mid-shift triggers the alert within one heartbeat cycle, and
      tapping it opens the correct system re-grant dialog.
- [ ] Driver sign-off.
