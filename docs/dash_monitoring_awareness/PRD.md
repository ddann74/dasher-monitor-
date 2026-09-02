# PRD — alert the driver if a dash is happening without active monitoring

Status: IMPLEMENTED, built from §5's own stated recommendations (no
driver override was given when the ralph-loop continuation reached
this PRD) — a single shared static alert method, reused by all three
real auto-start call sites (§5 point 1's open question), with a 5s
delayed re-verification (§5 point 2's open question). See PROGRESS.md,
including a real finding that `DasherAccessibilityService` has THREE
auto-start call sites, not the two this PRD's own investigation named.

Driver-reported, marked "crucial": "I want the app to know when I'm in
dash mode automatically and let me know if the current dash is not
being monitored."

## 1. What already exists

Auto-detection of dash mode already exists and is more complete than
the request implies:

- `DasherAccessibilityService.checkCurrentForegroundWindow()` already
  detects Dasher in the foreground (both on service connect and every
  `FOREGROUND_CHECK_INTERVAL_MS` = 20s afterward) and already
  auto-starts monitoring if it isn't running:
  ```java
  if (!TripForegroundService.isRunning) {
      Intent autoStartIntent = new Intent(this, TripForegroundService.class);
      autoStartIntent.setAction(TripForegroundService.ACTION_START_TRACKING);
      startForegroundService(autoStartIntent);
      logDiagnostic("AUTO_START", "Dasher already open at service connect, "
              + "monitoring was off -- started automatically");
  }
  ```
- Confirmed working in a real diagnostic log the driver provided:
  `AUTO_START` and `MODE` entries fire correctly on real Dasher
  foreground transitions.
- `DrivingDetectionReceiver` provides a second, independent auto-start
  path from real driving motion alone (Activity Recognition), even if
  Dasher is never opened.

So "know when I'm in dash mode automatically" is already real. The gap
is the second half: **"and let me know if it's not being monitored."**
Today, if the auto-start attempt itself fails, or succeeds but the
service doesn't actually end up running, the ONLY trace is a silent
diagnostic log line (`ERROR`, from `checkCurrentForegroundWindow`'s
outer `catch (RuntimeException e)`) — nothing the driver would ever see
without opening the in-app diagnostic log.

## 2. Why the auto-start can fail silently from the driver's perspective

`startForegroundService()` called from a `BroadcastReceiver`/
`AccessibilityService` context (not a direct user tap) is exactly the
kind of background-start Android 12+ can reject with a
`ForegroundServiceStartNotAllowedException` under some conditions (app
not in an allowed state, too many recent starts, etc.) — a real,
documented platform constraint, not a hypothetical. When that happens
here, it's caught by the broad `try/catch` around
`checkCurrentForegroundWindow` and logged as an `ERROR` — never
surfaced any louder. A driver who opens the real Dasher app, sees
nothing alarming, and starts driving would have no way to know this
dash isn't being tracked at all, until they check the trip history
afterward and find nothing recorded — the exact scenario the driver is
describing.

This app already has the right pattern for exactly this kind of
"something critical silently isn't working" alert:
`TripForegroundService.raisePermissionRevokedAlert(String, String)` —
a loud, high-priority notification (sound + vibration, its own
channel) already used for a revoked Accessibility/Screen Recording
permission mid-trip. This PRD is about extending that same alerting
discipline to "Dasher is open, but monitoring isn't running,"
currently the one silent gap in an otherwise well-alerted app.

## 3. Non-goals

- Not touching the existing auto-start logic itself (both
  `checkCurrentForegroundWindow`'s and `DrivingDetectionReceiver`'s) —
  it already works when nothing blocks it; this PRD only adds the
  missing "and tell me if it didn't" half.
- Not duplicating `MonitoringWatchdogReceiver`'s existing stale-
  heartbeat alerting — that already covers "monitoring WAS running and
  then went stale." This PRD covers the different case: monitoring
  never successfully started in the first place while Dasher is
  active.
- Not building a new notification/alert mechanism from scratch —
  reusing `raisePermissionRevokedAlert`'s existing pattern (see §4's
  open question on how, given it currently lives on a different class).

## 4. Proposed design (for review, not yet approved)

1. After `checkCurrentForegroundWindow()`'s auto-start attempt, verify
   it actually took (`TripForegroundService.isRunning`) on a short
   delay (a few seconds — the same class of "give it a moment, then
   check for real" pattern watchdog re-arm already uses elsewhere) —
   not just "was `startForegroundService()` called without throwing,"
   since that alone doesn't guarantee `onCreate()`/`startForeground()`
   actually completed.
2. If Dasher is confirmed in the foreground and monitoring still isn't
   running after that check, raise a loud alert — same shape as
   `raisePermissionRevokedAlert`'s existing notification (sound +
   vibration, high priority, its own channel), with text along the
   lines of "Dasher is open but this dash is not being tracked."
3. Also cover `DrivingDetectionReceiver`'s own auto-start attempt the
   same way, for the "driving detected, Dasher never opened" path.

## 5. Open questions

- `raisePermissionRevokedAlert` currently lives on
  `TripForegroundService` (a `Service`), but the auto-start attempts
  that need to trigger it live in `DasherAccessibilityService` and
  `DrivingDetectionReceiver` — different components. Does this need to
  become a shared static utility, or should each of those components
  build its own equivalent alert (more duplication, but no
  cross-component coupling)? Recommend making it a small static
  utility (e.g. on a shared alert-helper class, or `static` on
  `TripForegroundService` itself) reused by all three call sites,
  matching this codebase's general "one mechanism, not three copies"
  discipline (the exact lesson `ScreenRecordingController`'s
  `StopListener` refactor already learned this session) — but this is
  a real structural choice, not purely a coding detail.
- How long should the "give it a moment" delay in §4 point 1 be before
  concluding auto-start genuinely failed, rather than just being slow?
  Recommend a few seconds, mirroring the order of magnitude already
  used elsewhere in this codebase's own re-arm/re-check timers (not a
  hard number yet — needs a specific value chosen deliberately, not
  guessed silently during implementation).

## 6. Success criteria

- [x] Auto-start failure (or "called but didn't actually end up
      running") from `checkCurrentForegroundWindow` is verified, not
      just assumed from a lack of a thrown exception.
- [x] A loud, driver-visible alert (not just a diagnostic log line)
      fires when Dasher is confirmed active but monitoring isn't.
- [x] The same coverage extended to `DrivingDetectionReceiver`'s
      auto-start path.
- [x] The alert mechanism is a single shared implementation, not three
      independently-written copies (per §5's recommendation).
- [ ] On-device confirmation — blocked, no Android emulator/device
      available in this environment. This item in particular needs a
      real device to confirm, since the actual trigger condition
      (Android rejecting a background `startForegroundService` call)
      is itself an OS-level behavior that can't be forced from a code
      review alone.
- [ ] Driver sign-off.
