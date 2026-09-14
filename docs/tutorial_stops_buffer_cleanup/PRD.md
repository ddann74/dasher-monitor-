# PRD: Clear the Road Warrior stops buffer when Tutorial state is discarded

Status: IMPLEMENTED (2026-09-14), scouting-pass finding (round 4).

## 1. What was wrong

`TutorialActivity.showStepDriving()`'s simulated driving step calls
`add_stop_to_buffer(...)` with a fake address -- which writes into
*two* separate places: `TripManager.stops` (via `add_stop`, cleared by
`discard_pending_pickup_and_stops`) AND the engine-level `StopsBuffer`
(`self.stops_buffer`, a completely separate, longer-lived cache with a
24-hour TTL that powers the "Road Warrior" one-tap clipboard-copy
feature).

The Tutorial's cleanup (`discard_pending_pickup_and_stops`, called from
both `showStepCompletion()` and `cleanupSimulatedState()`) only ever
clears `TripManager`'s own `pickup`/`stops` -- never touches
`stops_buffer`. Confirmed directly: after calling
`discard_pending_pickup_and_stops`, the fake tutorial address was still
returned by `get_stops_buffer_json()` as the most recent entry. Any
driver who runs the Tutorial and then taps the Road Warrior clipboard
icon before their next real dropoff is parsed gets the fake address (or
a real restaurant name paired with a delivery that isn't happening,
per the tutorial's own "source: real" location logic) copied to their
clipboard -- up to 24 hours later.

## 2. Design

Not a blanket clear of `stops_buffer`: the buffer legitimately also
holds real addresses from real prior deliveries, useful convenience
data the Tutorial running must not destroy.

- `StopsBuffer.remove(address)` (new) -- removes by exact address
  match, the same filter idiom `add()` already uses.
- `DriveMonitorEngine.remove_stop_from_buffer(address)` (new) -- thin
  wrapper.
- `TutorialActivity` tracks the exact address string it added
  (`simulatedStopBufferAddress`, set in `showStepDriving()`) and calls
  `remove_stop_from_buffer` with that exact value at both real cleanup
  sites (`showStepCompletion()`'s normal-completion path, and
  `cleanupSimulatedState()`'s shared interrupted-exit/normal-finish
  path), via a new `clearSimulatedStopBufferEntry()` helper --
  idempotent (resets the tracked address to `null` after use, and is a
  no-op if `showStepDriving` was never reached this run).

## 3. Verification

- Real, executable Python test (real `DriveMonitorEngine`, not
  reimplemented logic): added a real address (simulating a real prior
  delivery) and the exact fake tutorial address string to the buffer;
  confirmed the underlying bug directly (`discard_pending_pickup_and_stops`
  alone leaves the fake address in the buffer); confirmed the fix
  (`remove_stop_from_buffer` with the tracked address) removes exactly
  the fake entry; confirmed the REAL address is untouched (a targeted
  removal, not a blanket clear); confirmed removing a never-added
  address (the Tutorial exited before reaching the driving step) is a
  safe no-op. 5 checks, all passed.
- `python3 -m py_compile` clean; brace/paren balance confirmed on
  `TutorialActivity.java`.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  Tutorial flow's interrupted-exit paths (back button, Skip, the
  Activity being reclaimed by the system) have not been observed
  triggering this cleanup on a real device.
- If `remove_stop_from_buffer` itself fails (caught, logged nowhere
  specific -- best-effort, matching the existing `discard_pending_
  pickup_and_stops` cleanup's own best-effort catch), the fake address
  still falls out of the buffer naturally once its 24-hour TTL expires
  (`StopsBuffer._clear_expired`) -- the same bound that existed before
  this fix, just no longer the ONLY bound.
- Scoped to the one real writer of a simulated address into this
  buffer (`TutorialActivity`). `DeveloperTestingActivity`'s own
  simulation does not call `add_stop_to_buffer` at all (confirmed by
  reading its `simulateDriveAndArrival`), so it has no equivalent gap.

## 5. Success criteria

- [x] The fake tutorial address is removed from the Road Warrior buffer
      by both real cleanup paths (normal completion and interrupted exit)
- [x] A real address from a real prior delivery already in the buffer
      is left untouched -- targeted removal, not a blanket clear
- [x] Idempotent and safe to call even if the driving step was never
      reached
- [x] Real executable Python test (5 checks, reproducing the actual
      bug and confirming the fix) fully passed
- [x] `python3 -m py_compile` clean; brace/paren balance confirmed
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver confirms in real use: running the Tutorial doesn't leave a
      fake address reachable via Road Warrior afterward.
- [ ] Driver sign-off.
