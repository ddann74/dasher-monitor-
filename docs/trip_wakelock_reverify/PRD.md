# PRD: Re-verify the trip wake lock during an active trip

Status: IMPLEMENTED (2026-09-15). Ralph-loop iteration 5/N of
`docs/monitoring_uptime_guarantee/PREMORTEM.md`, closing risk R4 (round-9
scouting pass finding, directed audit of the monitoring process).

## 1. What was wrong

`acquireTripWakeLock()` (a `PARTIAL_WAKE_LOCK`, held only while a
delivery is actively in progress -- see its own docstring for the real
incident that motivated it) was only ever called from `handleGpsResult`
on the `TRIP_ACTIVE` state-**entry** edge (a `tripState` transition into
`TRIP_ACTIVE`). Nothing checked it again for the rest of that trip --
unlike every other resource this file monitors (permissions, screen
recording, notification listener), there was no periodic health check
for the wake lock at all.

An early release -- a documented real edge case on some OEM skins, where
aggressive battery management can drop a held `PARTIAL_WAKE_LOCK` --
would silently degrade GPS tracking reliability during Doze for the
remainder of that trip, with nothing detecting or correcting it until
the trip ended (the normal release path) or the 90-minute safety timeout
independently expired the lock anyway. No log entry, no alert, no
self-heal.

## 2. Design

Added `verifyTripWakeLock()`, called from `maybeLogHeartbeat` immediately
after `checkAndLogPermissions(false)` -- the same periodic cadence every
other health check in this file already runs on:

```java
private void verifyTripWakeLock() {
    if ("TRIP_ACTIVE".equals(lastKnownTripState) && tripWakeLock != null && !tripWakeLock.isHeld()) {
        logDiagnostic("WAKELOCK", "Was unexpectedly not held during an active trip (early release) -- re-acquiring");
        acquireTripWakeLock();
    }
}
```

This is a self-heal, not an alert -- deliberately following the "detected
and auto-corrected" branch of the invariant in
`docs/monitoring_uptime_guarantee/PRD.md`'s property 1, rather than the
"detected and surfaced to the driver" branch. `acquireTripWakeLock()`
itself already handles a non-null-but-unheld `tripWakeLock` correctly
(its own `isHeld()` early-return guard simply falls through, and it
creates and assigns a fresh `WakeLock`), so calling it again here is a
safe, idempotent re-acquire with no new code path -- the diagnostic log
line is the only new observable, giving a diagnostic-log reviewer direct
evidence of exactly when and how often this happens in the field (the
invariant's property 2, "observable recovery").

No driver-facing notification was added -- unlike a permission or
Location-toggle loss, an early wake-lock release doesn't necessarily mean
GPS tracking has actually stopped (many devices keep GPS ticking without
it under normal, non-Doze conditions); this is a preventive safety net
against a background-CPU-sleep risk, not itself a failure the driver
needs to act on. If GPS tracking genuinely does stall as a downstream
consequence, that's already covered independently by
`docs/watchdog_stalled_gps_reacquire/PRD.md`.

## 3. Verification

`acquireTripWakeLock`/`verifyTripWakeLock` depend on a live
`PowerManager.WakeLock`, which can't be instantiated outside a device.
Verified instead by:

- `python3`-based brace/paren balance check on the edited file: final
  depth 0.
- A standalone, compiled-and-run Java program
  (`TripWakeLockReverifyTest.java`, `javac`/`java`) using a minimal
  `StubWakeLock` (implements the same `isHeld()`/`acquire()`/`release()`
  shape as the real `PowerManager.WakeLock`), with `verifyTripWakeLock()`
  and `acquireTripWakeLock()`'s exact logic verbatim-copied and confirmed
  identical to the real source via `grep` immediately before writing the
  test:
  - **The actual bug scenario**: acquire on trip start, simulate an early
    release (the OS silently dropping it, not via the app's own
    `release()`), call `verifyTripWakeLock()` -- confirms it correctly
    re-acquires, and confirms this happens exactly once (no runaway
    re-acquire loop).
  - **Regression check**: a still-held wake lock during an active trip
    triggers no redundant re-acquire call.
  - **Scope check**: no re-acquire is attempted when the trip is not
    `TRIP_ACTIVE`, even if the wake lock happens to be unheld (the
    normal, correct state after a trip ends).
  - **Null-safety check**: a `null` `tripWakeLock` during `TRIP_ACTIVE`
    doesn't throw.

  8 checks, all passed on first run.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  `PowerManager.WakeLock.isHeld()` behavior after a genuine OEM-triggered
  early release has not been observed on a device; this fix is verified
  against the exact conditional logic and re-acquire call sequence, not
  against real OS wake-lock behavior.
- No driver-facing alert for this specific event (see §2's design
  rationale) -- if a future scouting pass or field diagnostic log shows
  this firing frequently enough to be worth surfacing to the driver
  directly (rather than only self-healing), that would be a small,
  separate addition following the same `raisePermissionRevokedAlert`
  pattern used elsewhere in this file.
- Runs on the same heartbeat cadence as every other periodic check here
  (`HEARTBEAT_INTERVAL_MS`), not instantly on release -- consistent with
  how every other health check in this file already works, not a new
  limitation introduced by this fix.

## 5. Success criteria

- [x] An early wake-lock release during an active trip is now detected
      and automatically re-acquired within one heartbeat cycle, instead
      of going completely undetected until the trip ends
- [x] The re-acquire is idempotent and doesn't loop or re-fire when the
      wake lock is genuinely still held
- [x] No re-acquire is attempted outside an active trip
- [x] `python3` brace/paren balance check clean
- [x] Standalone compiled Java test (8 checks) of the exact conditional
      logic and re-acquire sequence, verified against the shipped
      source, fully passed
- [ ] HONEST LIMIT: no Android device/emulator available -- see §4.
- [ ] Driver/QA confirms on a real device (ideally one with a documented
      history of aggressive OEM battery management) that a WAKELOCK
      re-acquire log entry appears if/when this condition is ever
      genuinely triggered in the field.
- [ ] Driver sign-off.
