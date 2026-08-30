# PRD: Stop per-second speeding samples from crushing the Safety score to zero

Status: DRAFT -- awaiting sign-off before implementation begins.
Scope: this one scoring bug only. Not a general codebase pass.

## 0. What this is / isn't

This is a **bug-fix** PRD for `TripManager._detect_harsh_events` and
`_safety_score` in `drive_monitor.py`. It is **not** a redesign of the
safety-scoring formula's weights, and **not** a change to what counts
as "speeding" (the 60 km/h threshold itself) -- both are flagged as
separate, out-of-scope questions in §5, not silently changed here.

Source: a real "Full Report" export
(`dasher_monitor_full_report2.txt`, covering 26 trips, 2026-08-23 to
2026-08-30) uploaded by the driver, analyzed in this session before
writing this PRD.

## 1. Why (root-cause investigation, code-verified and math-checked
   against the real report)

Read `TripManager._process_point_during_trip`, `_detect_harsh_events`,
`_log_event`, and `_safety_score` in full (`drive_monitor.py`
L2095-2137, L2732-2736).

1. **Confirmed real: `speeding` is logged on every GPS tick while over
   the limit, not once per violation.** `_detect_harsh_events` runs on
   every tick (called from `_process_point_during_trip`, itself called
   per GPS point) and does:
   ```python
   if speed_kmh > DEFAULT_SPEED_LIMIT_KMH:
       self._log_event("speeding", lat, lon, ts, speed_kmh)
   ```
   with no debounce, no "already logged this period" check -- every
   single tick above 60 km/h (`DEFAULT_SPEED_LIMIT_KMH`, L109) appends a
   new event. `harsh_accel`/`harsh_brake` (logged just above, same
   method) don't have this problem in practice, not because they're
   coded differently, but because acceleration crossing a threshold is
   inherently momentary -- you can't sustain extreme acceleration for
   minutes the way you can sustain highway speed.
2. **Confirmed real in the uploaded report: this produces thousands of
   "events" for ordinary highway driving.** Trip 15 (65.77 km, a normal
   commute) logged 2,359 `speeding` rows -- roughly one per second of a
   ~39-minute highway drive, not 2,359 separate incidents. Trips 13 and
   14 show the same pattern (2,356 and 2,072 rows respectively).
   `harsh_accel`/`harsh_brake` stay in the single digits per trip, as
   expected for genuinely discrete events.
3. **Confirmed real, math-checked: this crushes Safety% to the 0.0
   floor on any trip with sustained highway driving.**
   `_safety_score`: `max(0.0, 100.0 - (len(events)/distance_km) * 15.0)`.
   For trip 15: `2359/65.77 * 15 ≈ 538` -- clamped to `0.0`. This isn't a
   rare edge case in the uploaded report: every GENERAL-mode highway
   trip (13, 14, 15, 17, 19) shows Safety% = 0.
4. **Confirmed real, math-checked: this costs up to 30 points off the
   headline Overall% shown to the driver**, not just the Safety% column.
   `composite_score = 0.4*TimeEff + 0.3*Safety + 0.3*Stops`. Trip 15's
   actual reported figures (TimeEff=95, Safety=0, Stops=100, Overall=68)
   match this formula exactly -- `0.4*95 + 0.3*0 + 0.3*100 = 68`. If
   Safety had scored even moderately (e.g. 85, for one or two brief
   genuine overages), Overall would read 93.5, not 68 -- a 25-point
   swing from this bug alone, on a trip with almost no actual harsh
   -driving events.
5. **Related, NOT fixed here**: `DEFAULT_SPEED_LIMIT_KMH` is a single
   flat 60 km/h constant (L109), used only in this one check (confirmed
   via repo-wide search -- no live voice alert or overlay depends on it,
   so this is a scoring-input concern only, not a misleading
   driver-facing alert). On an Australian highway (commonly 100-110
   km/h), this constant is definitionally "wrong" for that road type.
   The fix in this PRD (debouncing) neutralizes this bug's *practical*
   scoring impact -- one long "speeding period" event on a highway trip
   costs almost nothing under the corrected formula (see §1 math in
   §3) -- but the constant itself staying uncalibrated per road type is
   a separate, legitimate question, flagged in §5, not addressed here.
6. **Pure Python, no Android/Chaquopy dependency.** `drive_monitor.py`
   imports only `sqlite3`, `time`, `math`, `json`, `os`, `re`,
   `datetime` -- confirmed importable and runnable standalone
   (`python3 -c "import drive_monitor"` succeeds outside any Android
   context). Unlike every other PRD in this repo, this fix can be
   **genuinely executed and verified in this environment**, not just
   reviewed by eye -- see §4.

## 2. Definition of "functional" for this task

- [ ] A sustained speeding period (many consecutive ticks over the
      limit) is logged as ONE event, not one event per tick.
- [ ] Speed dropping back to/under the limit, then exceeding it again
      later in the same trip, produces a SECOND distinct event -- this
      is still catching real, separate violations, not collapsing an
      entire trip into at most one event regardless of how many times
      the driver actually sped.
- [ ] The logged event's magnitude reflects the PEAK speed reached during
      the violation period, not just the speed at the first tick that
      crossed the threshold -- a period that starts at 61 km/h and peaks
      at 95 km/h should record 95, not 61.
- [ ] `harsh_accel`/`harsh_brake` logging is completely unchanged --
      this task only touches the `speeding` branch.
- [ ] `_safety_score`'s formula (the `15.0` multiplier, the `events_per_km`
      shape) is unchanged -- see §5, this is a separate calibration
      question, not addressed here.
- [ ] Verified by an actual executable test (see §4), not just code
      review -- the first PRD in this repo able to do so.

Non-goals (explicitly out of scope for this task):
- Changing `DEFAULT_SPEED_LIMIT_KMH` or making it road-type-aware --
  separate, legitimate question (§5), not this PRD's fix.
- Re-tuning `_safety_score`'s weights/multiplier now that event counts
  will be much lower -- separate calibration question (§5).
- Recomputing/backfilling `safety_score`/`composite_score` for the 26
  trips already in the uploaded report -- those keep their
  bug-affected historical values unless a separate migration is
  explicitly requested (§5).
- Adding a `duration_seconds` column to the `events` table to record how
  long each speeding period lasted -- would need a schema migration;
  peak magnitude (in scope) captures the "how bad," not "how long,"
  without one.

## 3. Design

### 3.1 Edge-triggered speeding detection

Add a per-trip state flag (e.g. `self._currently_speeding = False`,
reset alongside the trip's other per-trip state at `_start_trip`) and a
reference to the in-progress event dict (`self._open_speeding_event =
None`). In `_detect_harsh_events`:

```python
if speed_kmh > DEFAULT_SPEED_LIMIT_KMH:
    if self._open_speeding_event is None:
        self._open_speeding_event = {
            "event_type": "speeding", "lat": lat, "lon": lon,
            "timestamp": ts, "magnitude": speed_kmh,
        }
        self.events.append(self._open_speeding_event)
    elif speed_kmh > self._open_speeding_event["magnitude"]:
        self._open_speeding_event["magnitude"] = speed_kmh
else:
    self._open_speeding_event = None
```

This mutates the SAME dict already appended to `self.events` while the
period is ongoing (events are only persisted to the DB in bulk at
`_persist_trip`, at trip end -- see L2770-2774 -- so there's no
partial-write concern), and simply stops updating it once the period
ends. A later tick that crosses the threshold again starts a genuinely
new event, since `self._open_speeding_event` was reset to `None` on the
dip below the limit.

### 3.2 No formula change

`_safety_score` itself is untouched. Its existing `15.0`-per-event-per-km
penalty, applied to a corrected (much smaller) event count, already
produces sane results without retuning: a trip with three genuine,
separate highway speeding periods over 10 km would score
`100 - (3/10)*15 = 95.5` -- a mild, defensible penalty for real repeated
violations, instead of today's guaranteed 0.0 for even one continuous
period.

## 4. Testing / verification approach

**Unlike every other PRD in this repo, this fix is genuinely testable in
this sandbox** -- `drive_monitor.py` has zero Android/Chaquopy
dependencies (confirmed, §1.6). Verification plan:

1. Instantiate `Database(":memory:")` and `TripManager(db)` directly in
   a plain `python3` process (no Android, no emulator needed).
2. Start a trip, feed a synthetic sequence of GPS ticks simulating a
   sustained highway speeding period (e.g. 40 ticks, 1 second apart, at
   a rising-then-falling speed profile over 60 km/h -- modeled on the
   real trip 6 sample data in the uploaded report, which rises from
   62 to 95+ km/h and back down).
3. Assert exactly ONE `speeding` event was logged, with `magnitude`
   equal to the peak speed in the sequence -- BEFORE the fix, this
   assertion fails (40 events); AFTER, it passes.
4. Feed a second, separate speeding period later in the same synthetic
   trip (after some ticks back under the limit) and assert a SECOND
   distinct event is logged -- confirms the fix doesn't collapse an
   entire trip into at most one event.
5. Run this against the actual repo file, not a copy, and report the
   real pass/fail output -- not a hypothetical.

This is real, executable verification -- a first for this repo's PRDs,
made possible only because this specific fix lives entirely in
Android-independent Python.

## 4a. Premortem

- **P1 -- a trip with genuinely continuous variable speeding (repeatedly
  dipping just under and over 60 km/h) could still over-count.** A
  driver oscillating right around the threshold (e.g. 58, 62, 59, 63...)
  would open and close many short "events" in quick succession -- more
  than the intent of "one event per real violation period," though
  still a massive improvement over today's per-second logging. Not
  fully solved here (would need a grace/hysteresis window, e.g. "still
  counts as the same period if back under the limit for less than N
  seconds"), and not clearly worth the added complexity for a case the
  uploaded report doesn't show evidence of (its speeding periods are
  long, sustained highway stretches, not threshold-oscillation). Flagged
  as a known limitation, not silently ignored.
- **P2 -- historical trips keep their bug-affected scores**, per the
  explicit non-goal above. If the driver expects trip 13/14/15's Safety%
  to retroactively read correctly after this fix ships, it won't --
  only new trips going forward are affected. Disclosed here so it isn't
  a surprise later.

## 5. Open questions

- **Should `DEFAULT_SPEED_LIMIT_KMH` become road-type-aware** (e.g. a
  higher threshold while `on a detected highway/high-speed road`) rather
  than a flat 60 km/h? Not blocking this PRD -- the debounce fix already
  neutralizes the practical scoring damage -- but the constant is still
  conceptually mislabeling lawful highway speed as "speeding." Only the
  user can decide if this is worth a follow-up.
- **Should the 26 already-recorded trips in the uploaded report be
  recomputed/backfilled** with corrected scores? Not done by default
  (§2 non-goals) -- would need a migration script re-deriving safety
  scores from each trip's existing raw event rows (collapsing them into
  synthetic periods after the fact), a materially larger and riskier
  change than the forward-looking fix itself. Only the user can decide
  if this is worth doing.
- **Should the `15.0` penalty multiplier be retuned** now that
  `speeding` events will be rare instead of dominant, to keep the score
  meaningfully sensitive to repeated real violations? Default in this
  PRD is "leave it exactly as-is" (§3.2) -- flagged in case the user
  wants speeding weighted more heavily than a single harsh brake once
  it's a comparably rare event.

None of the above block starting on the core fix in §3.1 -- they're
independent follow-up decisions.

## 6. Success criteria (implementation-phase checklist)

- [ ] `_detect_harsh_events`'s `speeding` branch rewritten to be
      edge-triggered (one event per continuous violation period)
- [ ] Per-trip state (`_currently_speeding`/`_open_speeding_event`)
      reset correctly at trip start
- [ ] Logged event's magnitude reflects the peak speed during the
      period, not just the entry speed
- [ ] `harsh_accel`/`harsh_brake` logging confirmed unchanged by diff
      review
- [ ] `_safety_score` formula confirmed unchanged by diff review
- [ ] Executable test written and RUN in this sandbox (not just
      reviewed) -- confirms one event per period, confirms two separate
      periods produce two separate events, confirms peak-magnitude
      capture
- [ ] User sign-off
