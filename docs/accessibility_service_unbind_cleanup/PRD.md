# PRD: Cancel the foreground-check polling loop on real service teardown

Status: IMPLEMENTED (2026-09-11), driver's own feature-audit finding --
not a specific driver-reported bug. #1 of a fresh scouting pass run
after the report-formatting and notification-channel-helper audits
(see those PRDs for the earlier findings in this same ongoing audit).

## 0. What this is / isn't

`DasherAccessibilityService.onServiceConnected()` starts a self-
rescheduling poll: `foregroundCheckRunnable` calls
`checkCurrentForegroundWindow()` then reposts itself via
`foregroundCheckHandler.postDelayed(this, FOREGROUND_CHECK_INTERVAL_MS)`
(every 20 seconds) -- a deliberate, already-documented fix for a real
gap where Android's accessibility API only reports CHANGES, so mode
could get stuck without this periodic self-correction.

The bug: the only place in the entire file that ever called
`foregroundCheckHandler.removeCallbacks(foregroundCheckRunnable)` was
`onInterrupt()`. `onInterrupt()` is Android's "stop giving spoken/
haptic feedback right now" signal -- not the callback for the service
actually being unbound or destroyed, and not reliably invoked on every
real disable path. Neither `onUnbind(Intent)` nor `onDestroy()` was
overridden anywhere in this file (confirmed via grep -- zero matches
before this fix), so there was no real cleanup hook for the loop at
all.

## 1. The real failure this caused

A driver disabling "Dasher Monitor" under Settings -> Accessibility
(an entirely normal, expected action -- e.g. to save battery, or while
troubleshooting) triggers Android's real unbind path, which fires
`onUnbind(Intent)`, not `onInterrupt()`. The same applies if an OEM's
aggressive battery management force-unbinds the service. With no
`onUnbind` override, the already-posted `Runnable` stays in the main
thread's message queue and keeps firing every 20 seconds for the
remaining life of the app process -- kept alive independently by
`TripForegroundService`, a separate foreground service that doesn't
depend on the accessibility service being connected.

Each firing calls `checkCurrentForegroundWindow()`, which calls
`getWindows()` -- an `AccessibilityService`-only API that throws
`IllegalStateException` once the service is disconnected. That's
already caught by the existing `catch (RuntimeException e)` in
`checkCurrentForegroundWindow`, so this was never a crash -- but it
silently produced an "ERROR: checkCurrentForegroundWindow exception"
diagnostic-log entry roughly every 20 seconds, indefinitely, drowning
out genuinely useful diagnostics with a self-inflicted spam loop that
never stopped until the entire app process died. It also leaked the
`DasherAccessibilityService` instance itself for that same duration,
since `foregroundCheckRunnable` is a non-static inner class holding an
implicit reference to it.

## 2. Fix

Added the two real Android lifecycle overrides that were missing:

- `onUnbind(Intent intent)` -- the actual callback Android invokes on
  the disable/force-unbind path described above. Calls
  `removeCallbacks` before delegating to `super.onUnbind(intent)`.
- `onDestroy()` -- a second safety net for whichever teardown path
  actually fires first on a given OS version/OEM (Android's own docs
  don't guarantee `onUnbind` always precedes `onDestroy`, or that both
  always fire in every real-world teardown). Calling
  `removeCallbacks` a second time here (or having already called it in
  `onUnbind`) is a harmless no-op on an already-empty handler queue --
  `Handler.removeCallbacks` on a callback that isn't queued does
  nothing, doesn't throw.

`onInterrupt()`'s existing `removeCallbacks` call was left untouched --
still harmless, still correct for the narrower case where it does
fire, just no longer the ONLY cleanup path.

## 3. Verification

- Confirmed via grep that `onUnbind`/`onDestroy` were not already
  overridden anywhere in this file before this change (both greps
  returned zero matches).
- Confirmed `Intent` was already imported (needed for `onUnbind`'s
  parameter type) -- no new import required.
- Brace/paren balance check on the modified file -- clean.
- No Android device/emulator available in this environment (same
  disclosed limitation as every Java-side change in this repo) -- the
  actual on-device behavior (which of `onUnbind`/`onDestroy` fires,
  in what order, on a real disable action) is reasoned from Android's
  documented `Service`/`AccessibilityService` lifecycle contracts, not
  observed directly. See ss4.

## 4. Honest limits

- No Android device/emulator available in this environment -- cannot
  confirm which of `onUnbind`/`onDestroy` actually fires (or in what
  order, or whether both do) when a real driver disables this specific
  service on a real OEM's Android build. Both are covered specifically
  because Android's own lifecycle guarantees here are documented as
  inconsistent across OEMs -- this is a defense-in-depth fix, not one
  verified against a single confirmed real trigger.
- Does not address the underlying tradeoff of using a self-rescheduling
  Handler loop for this at all (a `WorkManager`/`AlarmManager`-based
  periodic check would survive process death differently) -- out of
  scope; the loop's existence and interval were already a deliberate,
  separately-documented design choice (see the existing comment at
  `DasherAccessibilityService.java` ~line 316-324), and this fix only
  closes the missing-cleanup gap, not a redesign of the mechanism.
- The `catch (RuntimeException e)` in `checkCurrentForegroundWindow`
  that was silently absorbing the repeated `IllegalStateException`
  every 20s is left as-is -- it's the correct behavior for a genuine,
  rare `IllegalStateException` from a still-connected service hitting
  a transient error; it just no longer needs to fire in an infinite
  loop after real teardown.

## 5. Success criteria

- [x] `onUnbind(Intent)` overridden, calling `removeCallbacks` before
      delegating to `super`
- [x] `onDestroy()` overridden as a second safety net for whichever
      teardown path a given OS/OEM actually takes
- [x] `onInterrupt()`'s existing cleanup left in place, not removed
- [x] Confirmed no pre-existing `onUnbind`/`onDestroy` override existed
      to conflict with or duplicate
- [x] Brace/paren balance check on the modified file -- clean
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see ss4. Real on-device teardown behavior is
      unconfirmed.
- [ ] Driver confirms in real use: disabling the accessibility service
      no longer produces a repeating "checkCurrentForegroundWindow
      exception" diagnostic-log entry every ~20 seconds afterward.
- [ ] Driver sign-off.

## 6. Direct follow-up (2026-09-12): the store-wait timer loop had the same gap

This PRD's own original fix only audited `foregroundCheckRunnable` --
it didn't check whether any OTHER self-reposting Handler loop in the
same file had the identical missing-cleanup gap. A follow-up scouting
pass, specifically looking for other instances of this exact bug
class, found one: `storeWaitTimerTickRunnable` (declared near
`startStoreWaitGracePeriod`) reposts itself every
`STORE_WAIT_TIMER_TICK_MS` (1 second) once a driver's store-wait timer
becomes visible, and before this fix, nothing cancelled it from real
teardown -- only from the in-app "Confirm Pickup"/unassign click
handlers (`stopStoreWaitTimer()`/`cancelStoreWaitTimer()`).

**Concrete failure scenario:** identical shape to ss1 above, but for
the store-wait timer specifically -- a driver taps "Arrived at Store",
the grace period elapses (timer overlay now visible), and the
accessibility service gets unbound while that wait is still in
progress. The tick loop keeps re-posting on the main Looper forever,
calling `OverlayHelper.showStoreWaitTimer(...)` every second on a
torn-down service instance, and leaking the whole service instance the
same way `foregroundCheckRunnable` did.

### 6.1 Fix

Extracted the Handler-callback-cancellation step -- previously
duplicated identically inside both `stopStoreWaitTimer()` and
`cancelStoreWaitTimer()` -- into a new shared
`removeStoreWaitTimerCallbacks()`, called from `onUnbind`/`onDestroy`
alongside the existing `foregroundCheckHandler` cleanup.

Deliberately did NOT call the broader `cancelStoreWaitTimer()` from
teardown -- that method ALSO clears the visible overlay and wipes the
persisted `SharedPreferences` arrival timestamp
(`KEY_ARRIVED_AT_STORE_MS`). That persisted value exists specifically
so `resumeStoreWaitTimerIfPending()` (called from `onServiceConnected`)
can resume an in-progress wait across a process/service restart --
wiping it on a mere unbind (the driver may re-enable the service
moments later, or the system may rebind it) would silently defeat the
exact persistence mechanism `docs/store_wait_timer/PRD.md` ss8-9 built
for this. `removeStoreWaitTimerCallbacks()` only cancels the Handler
loop itself, leaving persisted state and any already-visible overlay
untouched -- matching this PRD's own original, narrow scope exactly.

This also incidentally removed a small pre-existing duplication:
`stopStoreWaitTimer()` and `cancelStoreWaitTimer()` each had their own
identical copy of the same `removeCallbacks` block before this fix.

### 6.2 Verification

- Confirmed via grep that `removeStoreWaitTimerCallbacks()` is now
  called from all 4 real sites: `stopStoreWaitTimer()`,
  `cancelStoreWaitTimer()`, `onUnbind()`, `onDestroy()`.
- Confirmed `cancelStoreWaitTimer()`'s broader side effects (overlay
  clearing, SharedPreferences wipe) were deliberately NOT pulled into
  the new shared helper or called from teardown -- read the full body
  of `cancelStoreWaitTimer()` and `resumeStoreWaitTimerIfPending()`
  before deciding this, not assumed.
- Brace/paren balance check on the modified file -- clean.

### 6.3 Additional honest limit

Does not re-clear the overlay on teardown -- a driver who disables the
accessibility service mid-wait will see the timer overlay freeze at
its last value rather than disappear, until/unless the service
reconnects and the loop resumes naturally. Considered acceptable: an
overlay is drawn via `WindowManager`/`SYSTEM_ALERT_WINDOW`, not
accessibility-service-dependent, so a frozen-but-visible timer is a
much smaller issue than the infinite-loop leak this fix closes, and
actively clearing it would require reusing `cancelStoreWaitTimer()`'s
broader, riskier side effects (see ss6.1).

### 6.4 Success criteria

- [x] `storeWaitTimerTickRunnable`/`storeWaitTimerStartRunnable`
      cancelled on real `onUnbind`/`onDestroy`, matching the original
      `foregroundCheckRunnable` fix above
- [x] Did NOT wipe the persisted store-wait arrival timestamp from
      teardown -- `resumeStoreWaitTimerIfPending` still works as
      intended across a real restart
- [x] Removed a genuine small pre-existing duplication
      (`removeStoreWaitTimerCallbacks` shared by 4 sites instead of 2
      near-identical inline copies) as a side effect of the fix
- [x] Brace/paren balance check on the modified file -- clean
- [ ] HONEST LIMIT: no Android device/emulator available -- see ss4/ss6.3.
- [ ] Driver confirms in real use: disabling the accessibility service
      mid-store-wait no longer leaves a runaway per-second loop running
      afterward, and re-enabling the service still correctly resumes an
      in-progress wait.
- [ ] Driver sign-off.
