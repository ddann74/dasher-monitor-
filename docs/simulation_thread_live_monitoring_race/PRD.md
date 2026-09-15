# PRD: Stop simulation threads from racing the live engine singleton

Status: IMPLEMENTED (2026-09-15). Ralph-loop iteration 6/N of
`docs/monitoring_uptime_guarantee/PREMORTEM.md`, closing risk R6
(round-8 scouting pass finding, directed audit of the monitoring
process).

## 1. What was wrong

`TutorialActivity.showStepDriving()` and
`DeveloperTestingActivity.simulateDriveAndArrival()` both spawn a
background thread that makes many sequential calls
(`on_gps_update`, `add_stop_to_buffer`, `add_pickup`-adjacent state,
etc.) into `engine` -- the exact same process-wide
`DriveMonitorEngine`/`TripManager` singleton (`PythonBridge.getEngine`)
that `TripForegroundService` uses for real monitoring. Both methods
already had a guard -- `TripForegroundService.isRunning` in Tutorial,
`blockedByLiveMonitoring()` (which checks the same flag) in
DeveloperTestingActivity -- but it was only checked **once, synchronously,
before the thread was spawned**. Nothing re-checked it for the rest of
the thread's lifetime, and nothing cancelled the thread if the screen was
backgrounded mid-simulation.

If real monitoring started at any point after that one check -- the
driver backgrounds the Tutorial/DevTesting screen mid-simulation and
opens the real Dasher app -- the simulation thread kept running,
silently interleaving fake GPS ticks, a fake pickup/stop, and fake
notification data into the same live trip state a real, in-progress
delivery was now also writing to. Worse, `DeveloperTestingActivity`'s
`finally` block **unconditionally** called `engine.callAttr("force_end_trip")`
once its loop finished -- so if the loop happened to run to completion
(or exit for any other reason) while a real trip had since started, this
cleanup call would have silently ended the driver's genuinely active
real delivery.

## 2. Design

Re-checks `TripForegroundService.isRunning` on **every iteration** of
both simulation threads' loops, not just once before spawning:

- The moment it becomes `true`, the loop breaks immediately and sets a
  local `interruptedByRealMonitoring` flag -- no further engine calls of
  any kind are made for the rest of that thread's run.
- Every subsequent step (in `DeveloperTestingActivity`: the
  `add_stop_to_buffer`/`on_notification` calls and the second loop; in
  `TutorialActivity`: the `add_stop_to_buffer` call and both loops) is
  gated on `!interruptedByRealMonitoring`, so once interrupted, nothing
  further executes at all.
- **Critical fix inside the fix**: `DeveloperTestingActivity`'s `finally`
  block's `force_end_trip()` call is now also gated on
  `!interruptedByRealMonitoring`. Without this, closing the race in the
  loops alone would have left a strictly worse bug in place -- the
  cleanup path would still silently end a genuinely real, in-progress
  delivery the moment it detected the exact condition ("a real trip
  might now be active") that should have made it do the opposite.
- Both activities now show the driver a clear message when this happens
  (`TutorialActivity` reuses its existing pre-check message text exactly;
  `DeveloperTestingActivity` adds a matching Toast) instead of silently
  stopping mid-walkthrough with no explanation.

This closes the race to, at most, whatever single engine call was
already in flight at the exact moment real monitoring started -- the
same bound every other check-then-act guard in this codebase already
accepts (e.g. the permission checks in
`TripForegroundService.checkAndLogPermissions`), not a claim of
perfect atomicity. A full fix would require either a lock shared between
the simulation threads and `TripForegroundService`, or routing
simulated calls through a separate engine instance entirely -- both
significantly larger changes than this finding's severity (LOW,
Developer/Tutorial-only surface, not reachable during a real delivery
unless the driver deliberately navigates to one of these screens)
justifies. See Honest Limits.

Did **not** touch `is_test_data` tagging or extend it into an
engine-side skip mechanism (an alternative design considered and
rejected) -- `TutorialActivity`'s simulated calls don't currently pass
`is_test_data` at all (a separate, already-tracked, explicitly deferred
gap, task #39), so a fix that depended on that tagging being correct
everywhere would not have actually closed this race for Tutorial's own
calls. The `TripForegroundService.isRunning` re-check used here doesn't
depend on that tagging at all, so it closes the race for both call sites
identically regardless of task #39's status.

## 3. Verification

Both threads depend on a live `PyObject` engine (Chaquopy) and Android
`Toast`/`runOnUiThread`, none of which can be exercised outside a device
or emulator in this environment. Verified instead by:

- `python3`-based brace/paren balance check on both edited files: final
  depth 0 for each.
- A standalone, compiled-and-run Java program
  (`SimulationThreadRaceTest.java`, `javac`/`java`) that verbatim-copies
  both threads' exact loop conditions, break points, and
  `interruptedByRealMonitoring` bookkeeping (confirmed identical to the
  real source via a fresh re-read immediately before writing the test),
  against a fake `isRunning` flag and a call-recording stub in place of
  `engine.callAttr`:
  - **Normal path (both activities)**: an uninterrupted run makes every
    expected call, in order, including `force_end_trip` for
    DeveloperTestingActivity's finally block.
  - **The actual bug scenario, worst case**: `isRunning` already `true`
    before the loop even starts -- confirms **zero** engine calls are
    made at all, for both activities.
  - **Mid-loop interruption**: `isRunning` flips `true` partway through
    the first loop (after 3 of 12 drive ticks) -- confirms the loop stops
    at exactly that point, and none of the later steps
    (`add_stop_to_buffer`, `on_notification`, the arrival-poll loop) ever
    run.
  - **The critical guard**: in every interrupted scenario,
    `force_end_trip` is confirmed NOT called -- directly verifying the
    "fix inside the fix" described in §2.

  10 checks, all passed on first run.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  thread-scheduling behavior, the real `Toast`/`stepBody` UI updates, and
  the real Chaquopy `PyObject` call semantics have not been observed on a
  device.
- Not a complete elimination of the race, only a tight bound on it (see
  §2) -- a single engine call issued in the exact instant between the
  `isRunning` check and the call itself can still land while real
  monitoring is starting. Judged an acceptable, standard check-then-act
  tradeoff for a LOW-severity, developer/tutorial-only surface, matching
  the bound already accepted elsewhere in this codebase's permission and
  state checks.
- Does not add a `Lifecycle`-based cancellation (`onPause`/`onStop`
  stopping the thread when the screen is merely backgrounded, as opposed
  to a real trip actually starting) -- neither activity had this before,
  and the `isRunning` re-check directly closes the specific corruption
  vector R6 named (a real trip starting mid-simulation), which is a
  narrower and more directly relevant condition than "the screen left the
  foreground" alone.
- Does not touch task #39 (Tutorial's missing `is_test_data=true`) --
  deliberately out of scope, per explicit standing instruction to leave
  that item alone; see §2 for why this fix doesn't depend on it anyway.

## 5. Success criteria

- [x] A real trip starting after a simulation thread has already begun
      (in either TutorialActivity or DeveloperTestingActivity) now stops
      that thread from making any further engine calls, within one loop
      iteration
- [x] `DeveloperTestingActivity`'s cleanup `force_end_trip()` call no
      longer fires when interrupted by real monitoring, closing what
      would otherwise be a worse bug (silently ending a real active
      delivery)
- [x] The driver sees a clear message when a simulation is stopped this
      way, in both activities
- [x] The normal, uninterrupted simulation path is unaffected -- every
      expected call still happens, in order
- [x] `python3` brace/paren balance check clean on both edited files
- [x] Standalone compiled Java test (10 checks) of the exact loop and
      guard logic, verified against the shipped source, fully passed
- [ ] HONEST LIMIT: no Android device/emulator available -- see §4.
- [ ] Driver/QA confirms on a real device that backgrounding the
      Tutorial or Developer Testing screen mid-simulation and starting a
      real delivery does not corrupt that real delivery's trip data.
- [ ] Driver sign-off.
