# Audit — anything that fails silently

Status: DONE. One real gap found and fixed (verified with a real
executable test). Everything else checked came back clean, with
reasoning below for why each pattern found was ruled out rather than
just skipped.

Requested: "conduct a thorough audit of the code for anything that
fails silently." Scope: both `app/src/main/python/drive_monitor.py`
and every Java file under `app/src/main/java/com/drivingefficiency/app/`.
This codebase has already been through many rounds of this exact kind
of audit in earlier work — most of the obvious patterns (bare
`except:`, un-logged catch blocks, un-checked `UPDATE` rowcounts) are
already fixed and carry their own "GAP N" / "Confirmed real bug, fixed
here" comments. This pass specifically looked for what those earlier
passes missed.

## Bug found and fixed: `_update_current_trip_phase_timestamp` had zero diagnostic trace

`TripManager._update_current_trip_phase_timestamp` (drive_monitor.py)
is the write path for `pickup_arrival_ts` and `pickup_departure_ts` —
two of the four phase-timing columns on `trips`, and (per
`docs/deadhead_stacked_order_baseline/PRD.md` §7) the two riskiest
ones, since they update with no `IS NULL` guard (last-wins) unlike
`dropoff_arrival_ts`/`walking_confirmed_ts` (first-wins, guarded).

An earlier "GAP 3 (diagnostic-coverage pass)" already closed this
exact hole for the OTHER three phase-timestamp writers in the same
file:
- `dropoff_arrival_ts`'s `UPDATE` (in `_evaluate_arrivals`) checks
  `cursor.rowcount > 0` and sets `_last_phase_capture_log`.
- `walking_confirmed_ts`'s `UPDATE` does the same.
- `_update_current_trip_text_column` (pickup_address) does the same.

All three feed `_last_phase_capture_log`, which
`DriveMonitorEngine.on_gps_update` reads and clears every tick, and
`TripForegroundService.java:1144` logs under the `"PHASE_TIMING"`
category (confirmed by reading the Java consumer directly, not
assumed).

But `_update_current_trip_phase_timestamp` — used for the other two
phase timestamps — was never touched by that earlier fix. It ran the
`UPDATE`, committed, and returned, with no rowcount check and no log
line at all. If a `pickup_arrival_ts`/`pickup_departure_ts` write ever
silently affected zero rows (e.g. a departure event racing trip-end),
there was no way to know from the log — unlike every sibling write in
the exact same file.

### Fix

```python
cursor = self.db.conn.execute(
    f"UPDATE trips SET {column_name} = ? WHERE end_time IS NULL", (ts,)
)
self.db.conn.commit()
if cursor.rowcount > 0:
    self._last_phase_capture_log = f"Captured {column_name} = {ts}"
```

Matches the exact shape already used at the other three call sites —
no new mechanism, just extending the one that already exists to the
one place it was missed.

### Verification — ACTUALLY EXECUTED, not just reviewed

Wrote and ran `test_phase_capture_log.py` (scratchpad, throwaway)
directly against the real, modified `drive_monitor.py` via plain
`python3`. Real output:

```
PASS: Captured pickup_arrival_ts = 1050.0
PASS: Captured pickup_departure_ts = 1200.0
ALL ASSERTIONS PASSED
```

Also confirmed the Java-side consumer (`TripForegroundService.java:1144`,
`logDiagnostic("PHASE_TIMING", ...)`) already existed and needed no
change — this fix alone makes these two writes visible in the log for
the first time.

## Patterns checked and ruled out (not new bugs)

- **Bare `except:` / catch-all `except Exception:` in Python**: none
  found anywhere in `drive_monitor.py`.
- **Every Java `catch (... ignored)` block** (`OverlayHelper` x5,
  `OemBackgroundHelper` x1, `ScreenRecordingController` x2,
  `TrustedContactsActivity` x1): read each one individually. All are
  genuinely benign — idempotent view-removal calls where the exception
  means the desired end state (view already gone) was already reached,
  a fallback loop trying the next candidate Intent, or a non-critical
  permission-persistence call where the primary operation already
  succeeded. Each has a comment explaining why swallowing is correct,
  not just a bare ignore.
- **`GoogleApiHelper`/`WeatherHelper`'s network `catch (Exception e)`
  blocks**: all route into an `onError`/`postError` callback rather
  than swallowing. Traced the one real caller of each
  (`checkCurrentWeather`, the geocoding/traffic paths in
  `DasherAccessibilityService`) and confirmed every `onError` handler
  actually calls `logDiagnostic` — none of them silently drop the
  error.
- **Schema migrations** (`Database._create_schema`, the
  `ALTER TABLE ... ADD COLUMN` block): deliberately unguarded by any
  try/except — a real failure here raises uncaught and would crash app
  init loudly, which is the correct behavior for a migration failure,
  not a silent-failure gap.
- **Offer-screen non-detection** (`handleOfferResult`'s
  `is_offer_screen == false` branch): correctly unlogged for the
  common case (every non-offer accessibility event would otherwise
  spam the log) — the one real state transition inside it (offer
  timeout detection) is already fully logged.
- **Best-effort parsers returning `None`** (`estimate_minutes_until_deadline`,
  `compute_deadline_timestamp` on unparseable `deadline_text`): a
  graceful-degradation fallback for a display-only comparison, not a
  functional silent failure — already documented as best-effort in
  the surrounding code.
- **Mutable default arguments**: none found in `drive_monitor.py`.

## Known, already-documented gaps NOT re-litigated here

These were found in earlier investigations this session and are
tracked in their own PRDs rather than duplicated here:

- `docs/dropoff_delivery_instruction_wiring/`: the dropoff screen's
  own delivery instruction is parsed but discarded before use.
- `docs/feedback_prompt_never_shown/`: `notifyRateThisDelivery`'s two
  early returns are still silent (PRD already scopes closing this as
  its own Step 1).
- `docs/math_calculation_audit/`: `offer_distance_accuracy` only ever
  records the last pickup of a stacked order (data loss, not a silent
  code-path failure, but related).
