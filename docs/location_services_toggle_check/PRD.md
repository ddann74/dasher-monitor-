# PRD: Detect the device's system-wide Location toggle being off

Status: IMPLEMENTED (2026-09-15). Ralph-loop iteration 3/N of
`docs/monitoring_uptime_guarantee/PREMORTEM.md`, closing risk R3 (round-9
scouting pass finding, directed audit of the monitoring process).

## 1. What was wrong

`TripForegroundService.checkAndLogPermissions` already checked
`ACCESS_FINE_LOCATION` (`hasLocation`), but that's a permission GRANT
check only. Android has a separate, independent state: the device's
system-wide Location services toggle (Settings > Location). A driver can
have the permission granted and still have Location switched off
entirely at the OS level -- GPS tracking silently produces nothing, and
the app had no way to tell this apart from every other cause of
staleness. It fell back to the generic watchdog "monitoring may have
stopped" alert (a stale-heartbeat symptom, not a named cause), instead of
a specific, actionable "your phone's Location is turned off" message.

No `LocationManager`, `isProviderEnabled`, `GPS_PROVIDER`,
`NETWORK_PROVIDER`, or `PROVIDERS_CHANGED` reference existed anywhere in
the app before this fix (confirmed by a full-repo grep) -- this state was
never checked at all.

## 2. Design

Added a new `hasLocationServicesEnabled` boolean to
`checkAndLogPermissions`, computed via
`LocationManagerCompat.isLocationEnabled(locationManager)`
(`androidx.core.location`, already available via the app's existing
`androidx.core:core-ktx:1.13.1` dependency) -- this compat call handles
the pre/post-API-28 difference (the deprecated `Settings.Secure.LOCATION_MODE`
setting vs. `LocationManager.isLocationEnabled()`) so the check behaves
identically across the app's supported OS range without any manual
version branching.

Wired into the exact same, already-established machinery every other
critical permission in this method uses -- no new alerting pattern
introduced:

- Added to the `changed` OR-chain (so a transition triggers a diagnostic
  log line, same as every other tracked flag).
- Added a mid-session true->false transition block: fires
  `raisePermissionRevokedAlert("Location Services", ...)` the instant a
  drop is detected while monitoring is active -- mirrors the existing
  `hasLocation`/`hasAccessibility`/etc. blocks exactly.
- Added to the `forceLog` block (mirrors the driver-backlog #22 "already
  off at start" fix for the other 4 permissions): if Location Services is
  already off the moment monitoring starts, the driver is told
  immediately rather than staying silent until a false heartbeat-staleness
  alert eventually fires.
- Added to the `PERMISSIONS` diagnostic log line and the final
  `lastLoggedX` assignment block.
- `raisePermissionRevokedAlert` already derives both the notification
  channel ID and a hash-based notification ID from `permissionName`
  alone -- passing the new, distinct string `"Location Services"` (kept
  deliberately separate from the existing `"Location"` permission-grant
  alert, which has its own channel/ID and different wording) means no
  new ID had to be manually assigned or audited against the reserved
  ranges in `docs/notification_id_collision_audit/PRD.md` -- the existing
  formula (`9100 + Math.abs(permissionName.hashCode() % 100)`) places it
  automatically inside the already-reserved 9100-9199 band.
- Added a new deep-link branch to `raisePermissionRevokedAlert`'s
  `setContentIntent` `if/else if` chain, for `"Location Services"` ->
  `Settings.ACTION_LOCATION_SOURCE_SETTINGS` -- there's no in-app request
  flow for this OS-level toggle (unlike `ACCESS_FINE_LOCATION`, which
  `PermissionsActivity` can request directly), so the alert deep-links
  straight to Android's own Location settings screen, the same pattern
  already used for the Accessibility alert's deep-link.

## 3. Verification

`checkAndLogPermissions` and `raisePermissionRevokedAlert` depend on live
`LocationManager`/`NotificationManager`/`PendingIntent` Android services
and can't be exercised end-to-end outside a device or emulator in this
environment. Verified instead by:

- `python3`-based brace/paren balance check on the edited file: final
  depth 0.
- A standalone, compiled-and-run Java program
  (`LocationServicesToggleTest.java`, `javac`/`java`), verbatim-copying
  two pieces of logic confirmed identical to the real source via `grep`
  immediately before writing the test:
  1. **Notification ID collision check**: the exact
     `9100 + Math.abs(permissionName.hashCode() % 100)` formula, run
     against every `permissionName` string actually passed to
     `raisePermissionRevokedAlert` anywhere in the file (`Location`,
     `Overlay`, `Notification Access`, `Accessibility`,
     `Notification Listener`, `Trip Capture`, plus the new
     `Location Services`) -- confirms the new ID lands inside the
     reserved 9100-9199 band and doesn't collide with any of them, or
     with any fixed ID used elsewhere in the app.
  2. **Transition-detection state machine**: the exact
     `lastLoggedX != null && lastLoggedX && !x` (mid-session) and
     `forceLog && !x` (already-off-at-start) conditions, run against 6
     scenarios -- cold start, genuine on->off transition, staying off,
     recovering off->on, forceLog-already-off, forceLog-already-on.
     Confirms the alert fires only on a genuine drop or a genuine
     already-off-at-start, matching every other permission's behavior
     exactly.

  9 checks, all passed on first run.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  `LocationManagerCompat.isLocationEnabled()` return value, the real
  notification appearing with a working deep-link tap target, and the
  real cross-OS-version behavior (pre/post API 28) have not been observed
  on a device.
- Does not add a `PROVIDERS_CHANGED` broadcast receiver for instant
  detection -- this check runs on the existing heartbeat cadence (same as
  every other permission check in this method), not the moment the
  driver flips the toggle. Consistent with how every other permission in
  this file is already checked (polled, not event-driven), so this isn't
  a new limitation, just not an improvement beyond the existing pattern.
- Only checks the toggle, not which underlying provider
  (`GPS_PROVIDER`/`NETWORK_PROVIDER`) is actually enabled -- Android's
  unified "Location" toggle controls both together on the app's
  supported OS range, so this distinction wasn't judged worth the added
  complexity for this fix.

## 5. Success criteria

- [x] A device with Location services toggled off system-wide (while
      `ACCESS_FINE_LOCATION` remains granted) is now detected and
      surfaced with a specific, actionable alert, not the generic
      staleness alert
- [x] The alert fires both on a genuine mid-session drop and when already
      off at monitoring start (mirrors the existing 4-permission pattern)
- [x] The alert deep-links directly to Android's Location settings screen
- [x] No new notification ID collision introduced -- verified against
      every existing `permissionName` and every fixed ID in the app
- [x] `python3` brace/paren balance check clean
- [x] Standalone compiled Java test (9 checks) of the exact ID formula
      and transition-detection logic, verified against the shipped
      source, fully passed
- [ ] HONEST LIMIT: no Android device/emulator available -- see §4.
- [ ] Driver/QA confirms on a real device that toggling Location off
      mid-shift triggers this specific alert (not the generic one) within
      one heartbeat cycle, and tapping it opens Location settings.
- [ ] Driver sign-off.
