# PRD: Make a wrong/outdated Dasher package name fail loudly, not silently

Status: IMPLEMENTED (2026-09-14), driver question ("is there any
possibility that the monitor app would not detect the dasher app")
triggered a direct audit of the detection mechanism itself.

## 1. What was wrong

Every detection path in this app -- offer parsing, screen recognition
(`DasherAccessibilityService`), mode switching (DASHER vs. GENERAL),
notification-based offer/message detection (`AppNotificationListenerService`)
-- depends entirely on one hardcoded string, `"com.doordash.driverapp"`,
exactly matching a real installed app's package name.

Two real problems, found by direct audit:

1. **Duplicated, not shared**: the constant was independently hardcoded
   in TWO places (`DasherAccessibilityService.DASHER_PACKAGE` and
   `AppNotificationListenerService.DASHER_PACKAGE`) -- a real drift risk
   if either were ever updated (a DoorDash rebrand, a regional variant,
   a different build channel) without the other.
2. **Never verified, and no way to find out if it's wrong**: one copy
   carried its own comment, `// Update with the real Dasher app package
   name` -- a live signal this value was assumed, not independently
   confirmed against a real installed app. Nothing anywhere in the app
   ever checked whether a package by that exact name is actually
   installed on the device. If the value is wrong for any reason,
   EVERY detection path fails completely and silently -- a driver would
   just experience "nothing ever happens," with zero diagnostic trace
   pointing at the real cause.

## 2. Design

### 2.1 `DasherAppInfo` (new file)

Single shared source of truth: `PACKAGE_NAME` constant, plus
`isInstalled(Context)` -- a real `PackageManager.getPackageInfo` check,
not an assumption. Both `DasherAccessibilityService` and
`AppNotificationListenerService` now reference
`DasherAppInfo.PACKAGE_NAME` instead of their own independent copies.

### 2.2 `TripForegroundService.checkAndLogPermissions` (wired in)

`dasherAppInstalled = DasherAppInfo.isInstalled(this)` computed
alongside the existing 4 critical-permission checks (location, overlay,
notification access, accessibility), reusing that same, already-proven
change-detection shape (`lastLoggedDasherInstalled`, included in the
`changed`/PERMISSIONS log line):

- **At monitoring start** (`forceLog`): if not found, alerts
  immediately -- the moment a driver would most want to know, before a
  whole shift passes with nothing working.
- **A genuine mid-session transition** while monitoring is active (was
  found, now isn't -- e.g. DoorDash got uninstalled or updated to a
  different package mid-shift): alerts on the transition, not
  continuously.
- Repeated checks while ALREADY known missing do NOT re-alert every
  tick -- same no-spam behavior the other 4 permission checks already
  have.

`raiseDasherPackageNotFoundAlert` is a dedicated alert, not a reuse of
the existing `raisePermissionRevokedAlert` -- that method's wording
("already off"/"turned off"/"until this is re-enabled") is specific to
a togglable permission, which this isn't (it's either the constant
itself being wrong, or DoorDash genuinely not being installed).
Deliberately does NOT include `buildInstallTimingNote()` (Monitor's own
install/update timing, useful for "did reinstalling MONITOR reset a
permission," but a non-sequitur for a missing DASHER package -- a real
mismatch caught and removed before shipping, not shipped and fixed
later).

## 3. Verification

- Real, compiled, executed Java test (`DasherPackageAlertTest.java`,
  pure logic mirroring the real `checkAndLogPermissions` branches with
  a fake `isInstalled` boolean): confirmed an alert fires immediately
  when the package is missing at monitoring start (the actual bug this
  fixes), confirmed NO alert when it's correctly found (no regression
  to the common case), confirmed a genuine mid-session found->missing
  transition alerts once, and confirmed repeated still-missing checks
  do NOT spam additional alerts. 4 checks, all passed.
- Brace/paren balance confirmed on all 3 modified/new Java files
  (`DasherAppInfo.java`, `DasherAccessibilityService.java`,
  `AppNotificationListenerService.java`, `TripForegroundService.java`).

## 4. Honest limits

- **The package name itself was NOT changed or independently verified
  against DoorDash's real, current Play Store listing** -- this fix
  cannot confirm from inside this environment whether
  `"com.doordash.driverapp"` is actually correct today. It makes a
  WRONG value loud instead of silent; it does not (and cannot, without
  external verification) guarantee the value is right.
- No Android device/emulator available in this environment -- the real
  alert notification appearing at the right moment has not been
  observed on a real device.
- This only detects "no package by this exact name is installed." It
  does NOT detect a package that exists under the right name but is a
  broken/corrupted install, disabled by the user, or genuinely a
  different (e.g. counterfeit) app using the same package id -- those
  are different failure modes this check doesn't distinguish.
- `DeveloperTestingActivity`'s own hardcoded
  `"com.doordash.driverapp"` literals (used only to simulate a test
  notification) were deliberately left as-is -- they're test fixtures
  for a dev-only screen, not part of the real detection path this fix
  addresses.

## 5. Success criteria

- [x] `DASHER_PACKAGE` consolidated from two independent hardcoded
      copies into one shared `DasherAppInfo.PACKAGE_NAME`
- [x] `DasherAppInfo.isInstalled` is a real `PackageManager` check, not
      an assumption
- [x] Alerts immediately if missing at monitoring start
- [x] Alerts on a genuine mid-session found->missing transition, not
      continuously
- [x] No alert spam on repeated still-missing checks
- [x] Deliberately does NOT reuse `buildInstallTimingNote()` (a real
      mismatch caught before shipping)
- [x] Real compiled/executed Java test (4 checks) fully passed
- [x] Brace/paren balance confirmed on all modified/new files
- [ ] HONEST LIMIT: the package name's actual correctness against
      DoorDash's real current app was not independently verified -- see
      §4.
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver confirms in real use: no false alert on a normal, healthy
      install, and (if ever genuinely triggered) the alert correctly
      points at a real detection failure.
- [ ] Driver sign-off.
