# PRD: Reset engine-failure counters on a same-process restart, not just a fresh process

Status: IMPLEMENTED (2026-09-16). Round-14 scouting finding -- the
follow-up pass run after round 13's own verification pass closed R1-R22.
Added to the premortem as R23.

## 1. What was wrong

`TripForegroundService`'s `consecutiveEngineFailures` and
`engineFailureAlertRaised` (instance fields, incremented/latched in the
GPS-tick failure catch block, reset only on a genuinely successful
`on_gps_update` call) drive the edge-triggered "⚠ Delivery tracking
error" alert (`raiseEngineFailureAlert`) -- explicitly designed to fire
**once per failure streak**, not on every single failed tick.

This session's own R21 fix (`docs/watchdog_engine_failure_flag_session_reset/PRD.md`)
reset the durable `KEY_ENGINE_FAILURE_ACTIVE` `SharedPreferences` flag at
`startTracking()`'s success point, on the stated assumption that
`consecutiveEngineFailures` itself "already implicitly resets to 0 for
every fresh `TripForegroundService` instance." That assumption is only
true when the OS actually kills and recreates the process -- but
`TripForegroundService`'s own class doc (see `stopTracking()`) states
this service is deliberately designed to **keep the same instance
alive** across a stop→restart cycle, not destroy and recreate it. And
that same-instance restart path is the **more common** one in real
usage: `DasherAccessibilityService`'s routine Dash-Paused/Dash-Resumed
auto-pause detection fires `ACTION_STOP_TRACKING` then, moments later,
`ACTION_START_TRACKING` on the exact same live instance every single
time a driver pauses and resumes their dash -- an entirely ordinary
action taken multiple times per shift. The manual Stop/Start toggle in
`MainActivity` goes through the identical non-destroying path.

Concretely: if the engine/DB problem that originally tripped the
3-failure threshold was **still happening** after such a restart (a real
possibility -- the alert's own text acknowledges restarting doesn't
always fix it), `engineFailureAlertRaised` stayed latched `true` forever,
since nothing but a genuine success ever reset it. The specific
diagnostic alert could never fire again for the rest of that process's
life -- silently downgrading an edge-triggered, once-per-failure-streak
signal into a fires-once-per-process-lifetime one. A driver who saw and
dismissed the alert once, then paused/resumed their dash (possibly even
*because* of the alert, believing it "reset" things per its own
wording), got no further specific confirmation the same root cause was
still active -- only the generic watchdog staleness alert (which doesn't
distinguish a GPS problem from an engine/DB one) was left to cover it.

This was never a silent-staleness violation (invariant property 1
still held -- `KEY_ENGINE_FAILURE_ACTIVE` correctly flips back to `true`
at the very next heartbeat check if the stale in-memory counter is still
≥ threshold, so the driver was never left with zero signal), but it was
a real violation of property 2 ("observable recovery... tell apart"
what's actually happening) -- the same architectural class of bug R21
itself fixed, just on the in-memory side, for the restart path R21's fix
didn't cover.

## 2. Design

Added `consecutiveEngineFailures = 0;` and `engineFailureAlertRaised =
false;` immediately after R21's own `KEY_ENGINE_FAILURE_ACTIVE` reset in
`startTracking()`'s success path -- the same "a genuine new session has
just started" point already established for the durable flag, now also
covering the in-memory fields regardless of which restart path (process
death or same-instance stop→start) triggered it. Also corrected R21's
own comment, which had stated the now-known-incomplete claim about
implicit in-memory resets.

## 3. Verification

The GPS-tick callback and `startTracking()` depend on a live Android
runtime and can't be exercised outside a device. Verified instead by:

- `python3`-based brace/paren balance check on the edited file: final
  depth 0.
- A standalone, compiled-and-run Java program
  (`EngineFailureCountersRestartResetTest.java`, `javac`/`java`),
  replicating the exact increment/latch/reset state machine (verbatim-
  copied from the GPS callback's catch block and the new reset,
  confirmed identical to the real source via a fresh re-read immediately
  before writing the test):
  - **Normal streak behavior**: the alert fires exactly once when the
    threshold is first crossed, and does not re-fire on further failures
    within the same streak (confirms the edge-trigger design itself is
    understood correctly).
  - **THE ACTUAL BUG, shown for contrast**: without the fix, the alert
    never re-fires across a same-process restart even though the
    underlying failure streak continues.
  - **THE FIX**: with the new reset in place, both fields correctly
    return to their initial state at restart, and the alert correctly
    fires again if the same underlying problem is still genuinely
    occurring after the restart.
  - **Regression check**: a restart while the engine is genuinely
    healthy doesn't spuriously raise the alert, and a normal sub-
    threshold failure count after a healthy restart still doesn't fire
    it.

  10 checks, all passed on first run.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  `DasherAccessibilityService` Dash-Paused/Dash-Resumed timing relative
  to an in-flight engine/DB failure has not been observed on a device.
- Does not change `raiseEngineFailureAlert`'s own edge-triggered design
  (fire once per streak) -- this fix only ensures "per streak" is
  correctly bounded by genuine session boundaries (any restart, not only
  a process-death one), matching the design's own original intent more
  completely than R21's fix alone did.
- This is the second finding in two consecutive rounds involving the
  same pair of R7/R21 mechanisms (round 13 found R21 needed on
  `KEY_ENGINE_FAILURE_ACTIVE`; round 14 found the same restart-reset gap
  also applied to the in-memory fields R21's own fix assumed were
  already covered) -- worth keeping in mind for any future engine-
  failure-tracking change: check both the durable flag AND the in-memory
  fields move together at every point either one is touched.

## 5. Success criteria

- [x] The specific "Delivery tracking error" alert can fire again after
      a same-process restart (Dash-Paused/Resumed auto-pause, or manual
      Stop/Start) if the underlying engine/DB problem is still genuinely
      occurring
- [x] A restart while the engine is healthy never spuriously raises the
      alert
- [x] The alert's own edge-triggered, once-per-streak design is
      preserved within a single continuous session
- [x] `python3` brace/paren balance check clean
- [x] Standalone compiled Java test (10 checks) of the exact state
      machine, verified against the shipped source, fully passed
- [ ] HONEST LIMIT: no Android device/emulator available -- see §4.
- [ ] Driver/QA confirms on a real device that pausing and resuming a
      dash while an engine/DB problem is ongoing produces a fresh
      "Delivery tracking error" alert, not silence.
- [ ] Driver sign-off.
