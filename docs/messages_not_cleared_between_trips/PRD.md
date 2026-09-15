# PRD: TripManager.messages never cleared between trips

Status: IMPLEMENTED (2026-09-15), scouting-pass finding (round 7, #1).

## 1. What was wrong

`TripManager.__init__` sets `self.messages = []` once (`app/src/main/python/drive_monitor.py:2535`)
and `on_message` appends to it for the life of the `TripManager` object
(i.e. the life of the app process/session). `_start_trip` resets every
other per-trip collection -- `self.gps_points = []`, `self.events = []`,
`self.delays = []`, and even filters `self.stops` down to unmatched
ones -- but never touched `self.messages`.

`_persist_trip` iterates the ENTIRE `self.messages` list on every single
trip end (`for m in self.messages: INSERT INTO messages (trip_id, ...)`),
not just messages received during the trip being persisted. So for a
driver running one continuous shift (the app process staying alive
across many deliveries -- the normal case): a message received during
trip 1 gets correctly persisted under trip_id=1, but then gets
RE-persisted again under trip_id=2 when trip 2 ends (even if trip 2
received zero messages of its own), and again under trip_id=3, and so
on for every trip in the shift. Row count grows unboundedly
(O(trips x cumulative messages)), and every trip after the first shows
stale customer instructions from a completely different, earlier
delivery in Trip Detail, the Full Report, and CSV export.

The live TTS/overlay announcement path (`_check_approach_instruction`,
`_evaluate_arrivals`) was NOT affected -- both already filter candidate
messages by `self._last_message_cutoff < m["timestamp"] <= ts`, and
`_last_message_cutoff` IS correctly reset to the new trip's start
timestamp in `_start_trip`, so a stale message's timestamp is always
before the new cutoff and never gets read aloud for the wrong delivery.
This bug was purely in the persisted-history/reporting path.

## 2. Design

One-line fix, matching the exact pattern already used for
`gps_points`/`events`/`delays`: add `self.messages = []` to
`_start_trip`. Nothing downstream needs to change -- `on_message`
already appends fresh entries per-trip, and `_persist_trip` already
iterates the full (now correctly trip-scoped) list.

## 3. Verification

Real, executable Python test against the actual `drive_monitor.py`
engine (`TripManager._start_trip`/`_end_trip`/`on_message` called
directly on a single `DriveMonitorEngine` instance, simulating one
continuous shift):

- Trip 1 receives one real message via `on_message` (the actual
  classify/extract-instruction pipeline, package
  `com.doordash.driverapp`, a real recognized instruction phrase).
- Trips 2 and 3 receive zero messages.
- After all 3 trips end: exactly ONE row exists in the `messages`
  table total (not 3) -- confirms the bug's exact re-persistence
  behavior is gone.
- That one row is attributed to trip 1, not trip 2 or 3.
- A genuine message received during a later trip (trip 4, after two
  prior message-free trips) is still correctly captured and attributed
  to the right trip -- confirms the fix doesn't just drop messages
  outright, only clears stale carry-over.

4 checks, all passed on first run. `python3 -m py_compile` clean.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  multi-delivery-shift scenario (app process staying alive across many
  real deliveries) has not been observed on a device, only reproduced
  by directly driving the engine's trip lifecycle methods in the same
  sequence that scenario would produce.
- Does not retroactively clean up any already-duplicated `messages` rows
  from before this fix, for a driver who has been running an
  already-affected build -- purely forward-looking. A driver noticing
  old, wrong-delivery instructions in trip history from before this fix
  would need those specific rows manually reviewed/removed if it
  matters to them; not addressed here.
- `stop_id` on a message (used to match a chat-derived instruction to
  the correct stop in a multi-stop/batch trip) is assigned via Python's
  `id(closest_stop)` at message-receipt time -- a separate, pre-existing
  design detail unrelated to this fix's scope, noted here only because
  clearing `self.messages` per trip means a message's `stop_id` never
  needs to remain valid past the trip it belongs to, which this fix
  makes strictly true (it wasn't fully true before, when stale messages
  lingered across trips).

## 5. Success criteria

- [x] `self.messages` is reset per trip, matching the existing
      `gps_points`/`events`/`delays` pattern
- [x] A message from an earlier trip is never re-persisted under a
      later trip's `trip_id`
- [x] A genuine message received during a later trip is still correctly
      captured and attributed
- [x] Real executable Python test (4 checks) against the actual engine,
      fully passed
- [x] `python3 -m py_compile` clean
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver confirms in real use that Trip Detail/Full Report/CSV
      export for each delivery in a multi-delivery shift show only that
      delivery's own customer messages.
- [ ] Driver sign-off.
