# PRD: Fix trip-end persistence duplication on retry

Status: IMPLEMENTED (2026-09-15). Ralph-loop iteration 1/N of
`docs/monitoring_uptime_guarantee/PREMORTEM.md`, closing risk R1 (round-12
scouting finding #1, directed audit of the monitoring process).

## 1. What was wrong

`TripManager._end_trip(ts)` runs three separable steps in sequence:

```python
def _end_trip(self, ts):
    summary = self._compute_summary(ts)
    delivery_speed_event = self._persist_trip(summary)
    self._merge_accel_samples_into_history()
    self.state = self.STATE_IDLE
    ...
```

`_persist_trip()` runs its own complete transaction (the `trips` row
UPDATE, every `stops`/`events`/`delays`/`messages` INSERT, and the
`offer_distance_accuracy` row via `_persist_distance_accuracy` /
`_persist_pickup_job_row`) and commits it. `_merge_accel_samples_into_history()`
is a **separate** method with its **own**, later `commit()`. Only after
both return does `self.state` finally get reset to `STATE_IDLE`.

If `_merge_accel_samples_into_history()`'s commit fails -- a plausible
transient DB hiccup, since SQLite's `busy_timeout` is never configured
anywhere in this file, and a live DB backup can hold a second connection
open against the same file while monitoring keeps running -- the
exception escapes `_end_trip` **before** `self.state` resets. `state`
stays `TRIP_ACTIVE`, even though `_persist_trip()`'s entire transaction
had already committed successfully moments earlier.

The engine has no separate "trip is over, still cleaning up" state --
only `TRIP_ACTIVE` and `STATE_IDLE` -- so the very next GPS tick sees
`state == TRIP_ACTIVE` and "parked long enough" still true, and naturally
retries the whole `_end_trip -> _persist_trip` sequence. None of
`_persist_trip`'s INSERT loops (stops, events, delays, messages) or
`_persist_pickup_job_row`'s own INSERT had any uniqueness/idempotency
guard, so the retry **duplicated every child row** for a trip that was
already correctly saved -- doubled harsh-brake counts, doubled delay
minutes, doubled customer messages, and a duplicate
`offer_distance_accuracy` row, all visible in Trip History, Trip Detail,
and every export.

This is the trip-END mirror of the trip-START bug round 11 already fixed
(`docs/start_trip_state_lie_on_failure/PRD.md`) -- same architectural root
cause (a state flag not atomic with respect to the operation it
describes), opposite end of the trip lifecycle, and a different failure
**shape** (duplication instead of a lying "active" flag, because
`_persist_trip`'s own writes are not purely re-run from scratch the way
`_start_trip`'s were).

## 2. Design

`_persist_trip` now reads `SELECT end_time FROM trips WHERE id = ?`
**before any writes** to compute `already_persisted` (true only when a
prior successful commit already set `end_time` for this trip).

- The `UPDATE trips SET end_time=..., ...` statement always runs,
  unconditionally, on every call -- it is naturally idempotent (re-writing
  the same summary values on a retry is harmless).
- The four INSERT loops (`stops`, `events`, `delays`, `messages`) are now
  wrapped in `if not already_persisted:` -- they run exactly once, on the
  trip's first successful persist.
- `_persist_distance_accuracy(summary, already_persisted)` now takes the
  same flag. Its own `_persist_pickup_job_row(...)` call (the
  `offer_distance_accuracy` INSERT) is gated on `not already_persisted`
  the same way.
- Crucially, `delivery_speed_event`'s computation inside
  `_persist_distance_accuracy` is **not** gated -- it's a pure, read-only
  computation (no DB write), and it must still run and be returned on a
  retry: `_end_trip`'s caller (Java, for `SmartScoreEngine.record_delivery_speed()`)
  never received this value from the failed first attempt, since the
  exception happened before `_end_trip` could return anything. Skipping
  its computation on retry would silently drop that learning signal
  every single time this failure path is hit.

No change was made to move `_merge_accel_samples_into_history()`'s commit
inside `_persist_trip`'s own transaction/failure boundary -- the chosen
fix (idempotent retry) is simpler, doesn't require restructuring two
independently-evolved methods, and directly satisfies invariant property
5 in `docs/monitoring_uptime_guarantee/PRD.md` ("idempotent persistence")
without needing property 4 ("atomic state transitions") to also hold at
this boundary.

## 3. Verification

Real reproduction against the actual engine (no mocks of `TripManager`
itself), instantiated directly via `dm.DriveMonitorEngine(tempfile.mkdtemp())`
(not the `get_engine()` singleton, to avoid state bleed across scenarios).
Script: scratchpad `test_trip_end_persistence_idempotency.py`, 12/12
checks passed.

**Test 1 -- the actual bug scenario:** started a real trip, added a stop,
a pickup (with departure tracking set so `_persist_distance_accuracy`'s
`offer_distance_accuracy` row is exercised), a harsh-brake event, a
delay, and a customer message. Called `_end_trip()` with
`_merge_accel_samples_into_history` temporarily replaced with a stub that
raises -- simulating its own later, separate commit failing, exactly as
described in §1. Confirmed:
- the exception genuinely propagates (state is left `TRIP_ACTIVE`, the
  pre-existing trigger for a natural retry) -- reproducing the bug's
  precondition, not just asserting the fix in isolation;
- the trip's `end_time` and exactly one row each of stops/events/delays/messages/offer_distance_accuracy
  are already correctly persisted after the failed first attempt;
- restored the real `_merge_accel_samples_into_history` and called
  `_end_trip()` again (the natural retry) -- **state correctly resets to
  IDLE, and no child row is duplicated** (still exactly one of each);
- `delivery_speed_event` is still computed and returned on the retry.

**Test 2 -- regression check for the other failure shape:** used a
`FlakyConnProxy` (wraps the real `sqlite3.Connection` -- a C extension
type whose methods are read-only and can't be monkeypatched directly --
delegating everything via `__getattr__` except a chosen `execute()` call
index, which raises) to fail the very *first* `execute()` inside
`_persist_trip`'s own transaction (the new `already_persisted` SELECT
itself). Confirmed nothing is persisted from the failed attempt, and a
clean retry after restoring the real connection persists exactly one
stop row -- confirms the fix doesn't regress the already-covered
"failure inside `_persist_trip`'s own commit" case round 12's scouting
pass also verified.

`python3 -m py_compile app/src/main/python/drive_monitor.py` clean.

## 4. Honest limits

- No Android device/emulator available in this environment -- verified
  entirely against the real Python engine directly; the Java side
  (`SmartScoreEngine.record_delivery_speed()` receiving
  `delivery_speed_event` from `PythonBridge`) was not exercised
  end-to-end on a device.
- Does not address `_merge_accel_samples_into_history()`'s own commit
  failure mode itself (e.g. making it retry-safe or merging it into
  `_persist_trip`'s transaction) -- that method's own idempotency was not
  in scope for this fix, only preventing ITS failure from causing
  duplicate writes in `_persist_trip`. If `_merge_accel_samples_into_history`
  itself has a non-idempotent retry hazard, that's a separate, not yet
  audited risk.
- `already_persisted` is inferred from `end_time IS NOT NULL`, which is
  correct for this app's actual usage (a trip's `end_time` is set
  exactly once, by this method, and never cleared) but would need
  revisiting if some other code path ever sets/clears `trips.end_time`
  outside `_persist_trip`.

## 5. Success criteria

- [x] A DB failure in `_merge_accel_samples_into_history()` (or any other
      step after `_persist_trip` returns) no longer duplicates
      stops/events/delays/messages/offer_distance_accuracy rows on the
      natural next-tick retry
- [x] `delivery_speed_event` is still correctly computed and returned on
      a retry, so delivery-speed learning isn't silently dropped
- [x] A failure inside `_persist_trip`'s own transaction still retries
      cleanly with no duplication (regression check)
- [x] The normal, single-attempt success path is unaffected (unconditional
      `UPDATE trips` still runs every time; exactly one of each child row
      results from a normal, non-retried trip end)
- [x] `python3 -m py_compile` clean
- [x] Real reproduction test against the actual engine, 12/12 checks
      passed
- [ ] HONEST LIMIT: no Android device/emulator available -- see §4.
- [ ] Driver/QA confirms on a real device that a transient DB hiccup at
      trip end never produces doubled harsh-brake counts, delay minutes,
      or customer messages in Trip History/Trip Detail/exports.
- [ ] Driver sign-off.
