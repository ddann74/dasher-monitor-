# PRD: Widen `verifyTripWakeLock()`'s guard to cover a null wake lock

Status: IMPLEMENTED (2026-09-16). Round-15 scouting finding -- reopens
risk R4 (`docs/trip_wakelock_reverify/PRD.md`), which round 13's and
14's own fixes to a *different* subsystem (engine-failure tracking, R21/
R23) suggested a pattern worth re-checking elsewhere. Updated in the
premortem as R4 (re-mitigated).

## 1. What was wrong

R4's original fix added `verifyTripWakeLock()`, called every heartbeat,
to self-heal an early OS-triggered wake-lock release during an active
trip:

```java
private void verifyTripWakeLock() {
    if ("TRIP_ACTIVE".equals(lastKnownTripState) && tripWakeLock != null && !tripWakeLock.isHeld()) {
        acquireTripWakeLock();
    }
}
```

The `tripWakeLock != null` guard was narrower than what
`acquireTripWakeLock()` (the method it calls) actually supports --
that method is explicitly null-safe (its own `isHeld()` guard just falls
through and builds a fresh `WakeLock` when `tripWakeLock` is `null`).
R4's original PRD even tested the null case, but only asserted it
"doesn't throw" -- not recognizing it as a real, reachable state that
genuinely needed the self-heal.

It is reachable, through the single most common restart path in real
usage: `DasherAccessibilityService`'s routine Dash-Paused/Dash-Resumed
auto-pause detection calls `stopTracking(true)` (an auto-pause stop),
which calls `releaseTripWakeLock()` **unconditionally** as its own
"safety net," setting `tripWakeLock = null` -- even when the underlying
trip is *not* actually ending. For a DASHER trip with a pending dropoff
stop, `force_end_trip(allow_mid_delivery_end=false)` correctly refuses
to truncate it (`docs/force_end_trip_auto_pause_truncation/PRD.md`'s own
documented, correct behavior) and the engine stays in `TRIP_ACTIVE`.
Because `lastKnownTripState` never actually transitions in this case (it
was `TRIP_ACTIVE` before the pause and still is after), the normal
acquire-on-transition logic in `handleGpsResult` -- which only fires on
a genuine state *change* -- never re-fires either. The wake lock stayed
unacquired for the rest of that delivery, with the old, narrower guard
in `verifyTripWakeLock()` itself permanently unable to self-heal it: it
required `tripWakeLock` to be non-null, which was exactly what this
scenario could no longer guarantee.

Same architectural shape as R21/R23 (a same-process restart not
resetting/re-establishing state that a fresh process gets "for free"),
found in a different subsystem that hadn't been re-audited since round
9's original scouting pass.

## 2. Design

Widened the guard to also catch the null case, matching what
`acquireTripWakeLock()` has always safely supported:

```java
if ("TRIP_ACTIVE".equals(lastKnownTripState) && (tripWakeLock == null || !tripWakeLock.isHeld())) {
    acquireTripWakeLock();
}
```

No change to `acquireTripWakeLock()` or `releaseTripWakeLock()`
themselves -- both were already correct; only `verifyTripWakeLock()`'s
own re-check condition was too narrow. This is a minimal, surgical
widening, not a redesign: the self-heal still runs on the same heartbeat
cadence, still calls the same already-null-safe method, and still only
acts while a trip is genuinely `TRIP_ACTIVE`.

## 3. Verification

`PowerManager.WakeLock` can't be instantiated outside a device.
Verified instead by:

- `python3`-based brace/paren balance check on the edited file: final
  depth 0.
- A standalone, compiled-and-run Java program
  (`TripWakeLockNullGuardTest.java`, `javac`/`java`), extending the same
  `StubWakeLock` harness R4's own original test used, with both
  `acquireTripWakeLock()`'s and the fixed `verifyTripWakeLock()`'s exact
  conditions verbatim-copied and confirmed identical to the real source
  via a fresh re-read immediately before writing the test:
  - **The actual bug scenario**: a normal trip-start acquire, then an
    auto-pause-style unconditional release (`tripWakeLock` becomes
    `null`) while `lastKnownTripState` stays `TRIP_ACTIVE` throughout
    (confirming the setup matches the real trigger exactly).
  - **Pre-fix behavior, shown for contrast**: the old, narrower guard
    never re-acquires from a null `tripWakeLock`, confirmed permanently
    stuck.
  - **THE FIX**: the widened guard correctly re-acquires from the null
    state.
  - **Regression check (R4's original scenario)**: a non-null-but-unheld
    wake lock (an early OS-triggered release) still correctly
    re-acquires -- confirms this fix doesn't regress the case R4's
    original test already covered.
  - **Regression check**: no re-acquire outside an active trip, even
    with a null wake lock (the normal, correct post-trip-end state).
  - **Regression check**: a still-held wake lock triggers no redundant
    re-acquire.

  8 checks, all passed on first run.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  Dash-Paused/Dash-Resumed timing relative to the heartbeat cadence (up
  to one full heartbeat interval of the trip running without a held wake
  lock, worst case, before this self-heal catches it) has not been
  observed on a device.
- Does not change `stopTracking()`'s own unconditional
  `releaseTripWakeLock()` call -- that call is correct as a safety net
  for the case where the trip genuinely IS ending; the gap was entirely
  in `verifyTripWakeLock()`'s own re-check being too narrow to notice
  and correct the case where it wasn't.
- Same honest limit as R4's original fix: this runs on the existing
  heartbeat cadence, not instantly on the auto-pause resume -- consistent
  with every other periodic check in this file, not a new limitation.

## 5. Success criteria

- [x] A wake lock cleared to `null` by an auto-pause stop (while the
      trip itself stays genuinely `TRIP_ACTIVE`) is now detected and
      re-acquired within one heartbeat cycle
- [x] R4's original scenario (a non-null-but-unheld wake lock from an
      early OS-triggered release) still correctly self-heals -- no
      regression
- [x] No re-acquire attempted outside an active trip, or when the wake
      lock is already genuinely held
- [x] `python3` brace/paren balance check clean
- [x] Standalone compiled Java test (8 checks) of both the bug scenario
      and every regression case, verified against the shipped source,
      fully passed
- [ ] HONEST LIMIT: no Android device/emulator available -- see §4.
- [ ] Driver/QA confirms on a real device that a Dash-Paused/Dash-Resumed
      cycle during an active DASHER delivery with a pending dropoff does
      not leave the device without a held wake lock for the rest of the
      delivery.
- [ ] Driver sign-off.
