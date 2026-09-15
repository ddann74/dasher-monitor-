# PRD: get_state() lying TRIP_ACTIVE when _start_trip fails before trip_id is set

Status: IMPLEMENTED (2026-09-15), scouting-pass finding (round 11, #1,
directed audit of the monitoring process).

## 1. What was wrong

`TripManager._start_trip()` set `self.state = self.STATE_ACTIVE` as its
very FIRST statement, before ~55 more lines of unguarded work --
including a real DB read (`_learned_accel_brake_thresholds`, itself
calling `self.db.conn.execute(...)`) and the `INSERT INTO trips` that
finally assigns `self.trip_id`. None of this was wrapped in a
try/except.

If anything in that window threw -- most plausibly a locked or
corrupted sqlite3 connection, the exact real-world scenario
`docs/heartbeat_engine_health_gate/PRD.md`'s engine-failure-alert
machinery was built around -- `self.state` was left at `TRIP_ACTIVE`
while `self.trip_id` stayed `None`. `get_state()` would then lie
"TRIP_ACTIVE" indefinitely:

- Real GPS points/harsh-events/delays/messages kept accumulating
  normally in memory for the rest of the delivery (`state != IDLE`
  routes `on_gps_update` into `_process_point_during_trip` etc.), but
  `_persist_trip()` (`if self.trip_id is None: return None`) silently
  no-ops -- the entire trip's data was discarded with zero DB row ever
  written.
- `TripForegroundService.checkTripCaptureHealth()` trusts
  `get_state() == "TRIP_ACTIVE"` to decide whether screen recording
  should be running, so it was fooled into believing a real trip was in
  progress.
- In DASHER mode with an active, un-recorded pickup,
  `_evaluate_trip_end()`'s own guard (`has_active_pickup`) refuses to
  end the trip at all until the pickup resolves -- so this corrupted
  state, and the silent data loss it caused, could persist for the
  entire remaining delivery, not just a brief window.
- Worse, this actively DEFEATS round 9's own engine-failure alert: since
  the underlying DB issue is usually transient, the very next
  `on_gps_update` tick typically succeeds, which resets Java's
  `consecutiveEngineFailures`/`engineFailureAlertRaised` to a clean
  state -- silently clearing any pending "Delivery tracking error"
  alert even though the engine's internal state was now permanently
  corrupted for this specific trip.

## 2. Design

Moved `self.state = self.STATE_ACTIVE` to be the LAST statement in
`_start_trip`, executed only after `self.trip_id` has been successfully
assigned from a real, committed `INSERT`. Confirmed by checking every
`self.state` reference in the file that nothing earlier in
`_start_trip`'s own body reads `self.state` -- so this is a pure
reordering, no try/except needed:

- A failure anywhere in the method (the accel/brake DB read, the
  `INSERT`, or the `commit()`) now leaves `self.state` at whatever it
  already was -- `STATE_IDLE`, the only state this method is ever
  called from (`on_gps_update`/`_evaluate_trip_start` only invoke it
  when `state == STATE_IDLE`).
- The exception still propagates to the Java caller exactly as before,
  preserving round 9's engine-failure detection for the tick that
  actually failed.
- Since `state` correctly stays `IDLE`, the very next GPS tick naturally
  retries a full, clean trip start from scratch (via
  `_evaluate_trip_start`) instead of being stuck in a corrupted
  "ACTIVE with no trip_id" limbo for the rest of the delivery.

## 3. Verification

Real, executable Python test against the actual `drive_monitor.py`
engine. Since `sqlite3.Connection`'s methods are read-only (a C
extension type, can't be monkeypatched directly), the test wraps
`tm.db.conn` in a thin proxy object that fails only the FIRST
`execute()` call (simulating a locked/corrupted connection exactly
where `_learned_accel_brake_thresholds` reads) and delegates everything
else straight through to the real connection:

- Confirmed the injected DB failure genuinely propagates as an
  exception (proves the test actually exercises the failure path).
- **The bug scenario**: after the failed `_start_trip`, `state` is
  correctly `IDLE`, not incorrectly left `ACTIVE`.
- After the failed `_start_trip`, `trip_id` is correctly `None`, not set
  to a bogus/stale value.
- `get_state()` correctly reports `"IDLE"`.
- Once the DB issue clears (proxy removed), a subsequent, genuinely
  successful `_start_trip` correctly sets `state = ACTIVE` and assigns
  a real `trip_id` -- confirms the fix doesn't leave the engine
  permanently stuck, and a genuine retry works exactly as before.
- The genuinely-started trip persists to the database with a real
  `end_time` end-to-end -- confirms the fix doesn't break the normal,
  successful path.

7 checks, all passed on first run. `python3 -m py_compile` clean.

## 4. Honest limits

- The specific trigger (a locked/corrupted sqlite3 connection) was
  injected via a test proxy, not a genuine OS-level database lock --
  the underlying failure MODE (an exception thrown partway through
  `_start_trip`) is real and directly reproduced, but a true concurrent-
  access sqlite lock on a real device has not been observed.
- Does not add a try/except around `_start_trip`'s body to make the
  method itself "safe" in some broader sense -- the fix is specifically
  about not letting a failure corrupt `self.state`. An exception from
  this method still propagates to and must be handled by the caller
  exactly as it always has (Java's `on_gps_update` catch block,
  unaffected by this change).
- Other fields reset early in `_start_trip` (`gps_points`, `events`,
  `delays`, `messages`, etc.) are still reset BEFORE the point where a
  failure could occur, so a failed attempt leaves those already cleared
  -- harmless, since a successful retry resets them again from scratch,
  but noted for completeness (not itself a bug, just an accepted
  side-effect of not restructuring the whole method's ordering beyond
  the one line that mattered).

## 5. Success criteria

- [x] A failure during trip setup no longer leaves `get_state()`
      reporting `TRIP_ACTIVE` with no real trip underneath it
- [x] `trip_id` is never left set to a stale/bogus value on a failed
      attempt
- [x] The exception still propagates to the Java caller, preserving the
      existing engine-failure-alert detection
- [x] The engine naturally retries a clean trip start on the next tick,
      rather than being stuck in a corrupted state for the rest of the
      delivery
- [x] The normal, successful trip-start-through-persistence path is
      completely unaffected
- [x] Real executable Python test (7 checks) against the actual engine,
      fully passed
- [x] `python3 -m py_compile` clean
- [ ] Driver confirms in real use that a trip is never silently lost due
      to a transient DB hiccup right at trip start.
- [ ] Driver sign-off.
