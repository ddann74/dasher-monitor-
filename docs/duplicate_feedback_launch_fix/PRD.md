# Duplicate feedback-launch bug

STATUS: IMPLEMENTED (2026-09-06)

## 0. Origin

Driver uploaded a real ~2.5-day diagnostic log export (Sept 4-6) covering
a live shift on their actual device (OPPO CPH2591). Investigating it
surfaced several previously-invisible issues; this PRD covers the one
the driver asked to be fixed first: the "Rate this delivery" feedback
prompt firing TWICE for the same trip, a few seconds apart.

## 1. Root cause, confirmed from the real log

Raw evidence (trip 32, 2026-09-06):

```
10:22:26 AUTO_PAUSE: Dash Paused screen detected -- GPS tracking paused   (x8, debounced screen reads)
10:22:27 SERVICE: stopTracking() -- monitoring turned off
10:22:27 BUTTON: Requested feedback-page foreground ... for trip 32       <- FIRST prompt
10:22:29 AUTO_PAUSE: Dash Paused screen no longer showing -- GPS tracking resumed
10:22:29 SERVICE: startTracking() -- monitoring turned on
10:22:30 STATE: Trip state: TRIP_ACTIVE -> IDLE
10:22:30 BUTTON: Requested feedback-page foreground ... for trip 32       <- SECOND prompt, same trip
```

`DasherAccessibilityService`'s Dash-Paused-screen detector sends
`TripForegroundService.ACTION_STOP_TRACKING` when it sees the Dasher
app's "Dash Paused" screen -- the SAME action a genuine manual "Stop
Monitoring" tap sends. `stopTracking()` has its own fallback (added for
a real earlier bug, docs comment still present): if a trip was active
when monitoring stops, fire the feedback prompt immediately, since a
genuine manual stop means no more GPS ticks are ever coming to drive
the natural TRIP_ACTIVE -> IDLE completion detection in
`handleGpsResult`.

That reasoning is correct for a real manual stop, but wrong for an
auto-pause stop: GPS ticks resume within ~2-3 seconds once the Dash
Paused screen clears, so the natural completion path gets its normal
chance to fire too -- and, per the log above, it does, for the exact
same trip. Two real, independently-justified code paths were both
firing for one physical event neither knew the other was also
handling.

## 2. Fix

`TripForegroundService.ACTION_STOP_TRACKING` now carries a new boolean
extra, `EXTRA_AUTO_PAUSE_STOP`. `DasherAccessibilityService`'s auto-pause
trigger sets it to `true`; every other caller (a real manual "Stop
Monitoring" tap, `quitCompletely()`) leaves it `false` (the default).
`stopTracking(boolean isAutoPauseStop)` skips its own fallback
`notifyRateThisDelivery()` call specifically when `isAutoPauseStop` is
true -- the natural completion path (further down in `handleGpsResult`)
still fires normally once ticks resume, exactly as the log shows it
already does. Every other behavior of an auto-pause stop (GPS pausing,
the "Dash paused" voice announcement, wakelock release) is unchanged.

`DeveloperTestingActivity.simulateDashPausedResumed()` (the dev-testing
button that exercises this same real code path) updated to set the same
extra, so it keeps matching the real accessibility-service trigger
exactly.

## 3. Why not fix it a different way

Considered and rejected:
- **Removing `stopTracking()`'s fallback entirely** -- would silently
  reintroduce the ORIGINAL bug this fallback was added for (a real
  manual stop mid-trip never getting a feedback prompt at all, since
  no more GPS ticks would ever come).
- **De-duplicating by trip ID at the notification layer** (e.g. only
  ever notify once per `tripId`, tracked in a set) -- would mask the
  real double-trigger rather than fix it, and risks silently
  swallowing a legitimate SECOND trip that reuses an ID after a crash
  recovery (the same log shows real crash-recovery cases with
  `outcome UNKNOWN`, so trip IDs are not guaranteed unique-forever in
  every edge case already visible in this exact log). Fixing which
  code path fires, rather than filtering symptoms after the fact,
  matches this repo's own established preference (see e.g. the
  MODE_CHANGE_DEBOUNCE_MS fix's own comment, same class).

## 4. Verification

No Android SDK/emulator in this environment (disclosed limitation,
consistent with every other Java-only change in this repo):
- Traced the exact real timestamps from the driver's own log against
  the code paths involved, confirming the fix removes exactly the
  spurious early firing and leaves the legitimate one intact (the
  legitimate firing depends on `handleGpsResult`'s own
  `lastKnownTripState` var, which auto-pause's `force_end_trip()` call
  does NOT update directly -- only a subsequent real GPS tick does, so
  the natural-path firing is unaffected by this change).
- `git diff` reviewed line by line; both real call sites of
  `stopTracking()` (the auto-pause path and `quitCompletely()`) updated
  consistently, no bare no-arg calls left.
- Brace/paren balance confirmed on all three touched files after the
  edit (`TripForegroundService.java` 200/200 braces, 907/907 parens;
  `DasherAccessibilityService.java` 152/152, 558/558;
  `DeveloperTestingActivity.java` 48/48, 235/235).

## 5. Other findings from the same log, not yet actioned

Documented here for traceability, not fixed in this PR:
- The accessibility service reconnected ~823 times over 2.5 days,
  clustering into bursts every 15-20s during active dashes -- almost
  certainly OPPO ColorOS's own aggressive background management
  (device logs `knownAggressiveOem=true`; standard Android battery
  optimization exemption IS already granted per the log, ruling that
  part out). This is what causes the mode-flapping and the driver
  needing to manually re-tap "Start Monitoring" repeatedly, and is a
  likely device-settings issue more than a pure code bug -- the app
  already has ColorOS-specific handling (`OemBackgroundHelper`), but
  ColorOS is known to silently revert it.
- Two offers recovered as `outcome UNKNOWN` after what the log calls a
  crash/restart, and screen-recording consent lost 13 times
  ("process likely restarted") -- consistent with the same underlying
  instability, occasionally taking the whole process down rather than
  just the accessibility service component.
- Zero `Declined` outcomes logged in the Sept 6 window despite ~9 real
  trips -- inconclusive on its own (the driver may simply not have
  declined anything that day); not enough evidence either way to
  reopen backlog #21 from this alone.
