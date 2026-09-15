# PRD: Fix `dropoff_arrival_ts` silent, permanent loss on a DB hiccup

Status: IMPLEMENTED (2026-09-15). Ralph-loop iteration 2/N of
`docs/monitoring_uptime_guarantee/PREMORTEM.md`, closing risk R2 (round-12
scouting finding, directed audit of the monitoring process).

## 1. What was wrong

`TripManager._evaluate_arrivals(lat, lon, ts)` finds the nearest unmatched
stop within the arrival geofence and, on a hit, used to run in this
order:

```python
nearest["matched"] = True
nearest["arrival_time"] = ts
cursor = self.db.conn.execute(
    "UPDATE trips SET dropoff_arrival_ts = ? WHERE end_time IS NULL AND dropoff_arrival_ts IS NULL",
    (ts,),
)
self.db.conn.commit()
```

The in-memory flag (`stop["matched"] = True`) was set **before** the DB
write that was supposed to durably record the arrival. If that
`execute()`/`commit()` failed -- a transient DB hiccup, same class of
issue as risk R1 (SQLite's `busy_timeout` is never configured anywhere in
this file) -- the exception propagated out of `_evaluate_arrivals`, but
`stop["matched"]` had already been mutated to `True` in memory.

`_evaluate_arrivals` (and the sibling `_check_approaching_stop`) both
filter candidate stops with `if stop["matched"]: continue`. Once a stop
is marked matched, it's permanently excluded from every future geofence
check for the rest of the trip -- there is no other code path that ever
re-evaluates it. So a single transient DB failure at exactly the moment
of a genuine arrival **permanently** lost that stop's `dropoff_arrival_ts`
for the life of the trip, with no retry, and no error surfaced to the
driver (this field only feeds phase-breakdown timing displayed in the
delivery feedback dialog and reports -- not a driver-facing alert path).

Lower severity than R1 (one supplementary timing field used for phase-
breakdown display, not core trip/event/delay/message data), but same
root-cause family: a persistence write with an in-memory "done" flag
that isn't actually gated on the write's success.

## 2. Design

Reordered `_evaluate_arrivals` so `nearest["matched"]`/`arrival_time` are
only set **after** `self.db.conn.commit()` returns successfully. If the
write fails, the stop stays unmatched, and the very next GPS tick's
`_evaluate_arrivals` call naturally re-finds the same stop as the
nearest unmatched candidate and retries the whole match+write --
identical shape to `_evaluate_arrivals`'s own already-correct handling
of the *first vs. Nth stop in a batch* case (the `WHERE dropoff_arrival_ts
IS NULL` guard already made the write itself idempotent; the bug was
purely that the in-memory flag could get ahead of the write it was
supposed to describe).

No change was needed to the write itself (already idempotent via the
`WHERE ... dropoff_arrival_ts IS NULL` guard) or to the message-matching
logic below it, which already only runs after the commit succeeds.

This is the same fix shape as `docs/start_trip_state_lie_on_failure/PRD.md`
(round 11) and `docs/trip_end_persistence_idempotency/PRD.md` (this
session's Ralph-loop iteration 1, R1): a flag is never set to describe an
operation before that operation has verifiably succeeded (invariant
property 4 in `docs/monitoring_uptime_guarantee/PRD.md`).

## 3. Verification

Real reproduction against the actual engine, instantiated directly via
`dm.DriveMonitorEngine(tempfile.mkdtemp())` (not the `get_engine()`
singleton). Script: scratchpad `test_dropoff_arrival_ts_retry.py`, 9/9
checks passed.

- Started a real trip, added a stop, drove `_below_stop_speed_since` the
  same way a real below-speed GPS tick would (via `_evaluate_trip_end`),
  then called `_evaluate_arrivals` at a genuine arrival position past the
  hold time, with the connection swapped for a `FlakyConnProxy` (wraps
  the real `sqlite3.Connection` -- a C extension type whose methods are
  read-only and can't be monkeypatched directly -- delegating everything
  via `__getattr__` except the first `execute()` call, which raises).
- Confirmed the failure genuinely propagates, **the stop is NOT left
  matched=True in memory**, and `dropoff_arrival_ts` is still `NULL` in
  the DB.
- Restored the real connection and called `_evaluate_arrivals` again on a
  later tick (the natural retry) -- confirmed the stop correctly becomes
  matched, `arrival_time` is set, and `dropoff_arrival_ts` is correctly
  persisted this time.
- A third call after the successful match confirmed no further write
  fires (regression check for the normal, single-success path).

`python3 -m py_compile app/src/main/python/drive_monitor.py` clean.

## 4. Honest limits

- No Android device/emulator available in this environment -- verified
  entirely against the real Python engine directly.
- Does not address the same root-cause shape in `_check_approaching_stop`
  or the pickup-side arrival tracking (`_evaluate_pickup`) -- those were
  not in scope for this fix. `_evaluate_pickup`'s own DB writes (`_persist_pickup_job_row`
  and friends) were already covered by R1's fix via `_persist_trip`'s
  `already_persisted` guard; whether pickup-side in-memory flags have
  this same ordering issue independent of that guard was not audited
  here and would need its own pass if a future scouting round flags it.
- The delay this field's loss stays undetected for (until a driver
  notices a missing phase-breakdown timing in their feedback dialog, if
  ever) is itself unbounded -- this fix prevents the loss, it doesn't add
  alerting for the case where a *different*, not-yet-found bug causes the
  same symptom.

## 5. Success criteria

- [x] A transient DB failure exactly at the moment of a genuine dropoff
      arrival no longer permanently loses that stop's `dropoff_arrival_ts`
- [x] The next GPS tick naturally retries and correctly persists the
      arrival after the DB issue clears
- [x] The normal, single-success path is unaffected -- no duplicate writes
      once a stop is genuinely matched
- [x] `python3 -m py_compile` clean
- [x] Real reproduction test against the actual engine, 9/9 checks passed
- [ ] HONEST LIMIT: no Android device/emulator available -- see §4.
- [ ] Driver/QA confirms on a real device that phase-breakdown timings
      (driving-to-dropoff, parking-to-walking) are never silently missing
      after a normal delivery.
- [ ] Driver sign-off.
