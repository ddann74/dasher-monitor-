# Progress log -- store wait timer (Arrived at Store -> Confirm Pickup)

## Schema + migration (2026-09-08)

Added `store_wait_over_grace_seconds` to the existing `trips_columns`
migration loop in `Database.__init__` (`drive_monitor.py`, right next
to `pickup_arrival_ts`/`pickup_departure_ts`/`pickup_address`) rather
than a new, separate migration block -- follows the exact established
pattern (`PRAGMA table_info` + `ALTER TABLE ADD COLUMN` per missing
column) already used for every other trip-level field. `REAL`, nullable
-- falls into the existing `else` branch of that loop's `col_type`
logic (only `deadline_text`/`pickup_address` are `TEXT`), so no new
branching needed.

Confirmed via the existing `CREATE TABLE IF NOT EXISTS trips` statement
that `pickup_address` is likewise NOT declared there and relies purely
on this migration loop -- same shape works identically for a fresh
install and an existing database, no `CREATE TABLE` change needed.

Verified: `python3 -m py_compile drive_monitor.py` -- compiles cleanly.

PRD §7 box 1 checked. Next: `record_store_wait_timer` on
`DriveMonitorEngine`.

## record_store_wait_timer (2026-09-08)

Added `TripManager.record_store_wait_timer(over_grace_seconds)` right
after `update_pickup_address` -- a one-line call into the existing
`_update_current_trip_column` helper (see rename note below), matching
`update_pickup_address`'s own exact shape.

**Rename, not a new near-duplicate helper**: `_update_current_trip_text_column`
was previously named for its one and only caller
(`pickup_address`, a `TEXT` column) -- its actual implementation never
inspected the value's type at all (a plain parameterized `UPDATE`,
SQLite doesn't enforce column typing), so adding a second, differently
-typed caller (a `REAL` duration) for a near-identical write would have
meant either a misleadingly-named reused method or a pointless
copy-pasted twin. Renamed to `_update_current_trip_column` (dropping
"_text"), updated its one existing call site
(`update_pickup_address`), and its docstring -- no behavior change,
confirmed by reading the full method body before renaming: the only
type-specific thing about it was ever its name.

`DriveMonitorEngine.record_store_wait_timer(over_grace_seconds)` added
as a thin wrapper, same shape as `update_pickup_address`'s own wrapper
immediately above it -- delegates to `self.trip_manager.
record_store_wait_timer(...)`, returns a JSON `{"recorded": True,
"store_wait_over_grace_seconds": ...}` dict (matching
`record_pickup_unassigned_for_long_wait`'s own returned-JSON pattern,
since `DasherAccessibilityService`'s planned call site logs whatever
JSON comes back).

Verified: `python3 -m py_compile drive_monitor.py` -- compiles cleanly.
Confirmed via `grep` that no other call site referenced the old
`_update_current_trip_text_column` name before renaming it.

PRD §7 box 2 checked. Next: `OverlayHelper.showStoreWaitTimer` /
`clearStoreWaitTimer`.

## OverlayHelper.showStoreWaitTimer / clearStoreWaitTimer (2026-09-08)

New `storeWaitTimerView` static `TextView` field + the two methods,
placed right after `removeExisting()`. `showStoreWaitTimer(Context,
String text)`: if the view already exists, just calls `setText()` in
place (no remove/re-add) -- meant to be called once a second while the
timer is visible, and rebuilding a `WindowManager` view every tick would
be wasteful and could flicker. On first call, builds a small amber
`TextView` (`#CCEF6C00`, the same color already used for the "Good"
Smart Score label) and adds it via `windowManager.addView`, wrapped in
the same `try/catch (RuntimeException)` + `FallbackLogger.log` hardening
`showStatusDot` already has (driver backlog #16's own established
reasoning: `addView()` is a real, OEM/OS-quirk-prone source of runtime
exceptions). `clearStoreWaitTimer(Context)` removes the view and clears
the field, matching `removeStatusDot`'s own shape.

**Positioning**: `Gravity.TOP | Gravity.END`, away from the status dot
(`TOP | START`) and the message/instruction overlays (centered) -- no
device to confirm this visually (PRD §5 P3, disclosed, not resolved
here).

Verified: brace/paren balance 98/98 braces, 429/429 parens (whole file,
after this addition).

PRD §7 box 3 checked. Next: the detection + grace-period/tick state
machine in `DasherAccessibilityService.java`.

## Detection + grace-period/tick state machine (2026-09-08)

Added to the EXISTING `TYPE_VIEW_CLICKED` text-matching block (same one
already handling Accept/Decline/unassign) -- two new `else if` branches,
`equalsIgnoreCase("Arrived at Store")` -> `startStoreWaitGracePeriod()`
and `equalsIgnoreCase("Confirm Pickup")` -> `stopStoreWaitTimer()`. No
new event-handling machinery, per PRD ss4.1 -- this is exactly the same
mechanism already screenshot-proven for "Yes, I want to unassign".

New fields mirror this class's own existing `timeoutHandler`/
`pendingTimeoutRunnable` shape: `arrivedAtStoreTapMs` (`Long`),
`storeWaitTimerVisible` (`boolean`), a dedicated `storeWaitTimerHandler`,
a one-shot `storeWaitTimerStartRunnable` (the delayed grace-period-
elapsed trigger), and a repeating `storeWaitTimerTickRunnable` (calls
`OverlayHelper.showStoreWaitTimer` once a second, re-posts itself).

- `startStoreWaitGracePeriod()`: cancels any stale prior state first
  (defensive), records the tap time, schedules the delayed-start
  runnable at `STORE_WAIT_GRACE_PERIOD_MS` (60s).
- `stopStoreWaitTimer()`: ALWAYS cancels the pending delayed-start
  runnable first, so a pickup confirmed inside the grace period shows
  and persists nothing (PRD ss2's own definition of functional). Only
  if the timer had actually become visible does it stop the tick loop,
  clear the overlay, compute the over-grace duration
  (`now - arrivedAtStoreTapMs - grace period`, floored at 0 as a
  defensive guard against any clock-skew edge case), and call
  `engine.callAttr("record_store_wait_timer", ...)`.
- `cancelStoreWaitTimer()`: shared cleanup (pending runnable + tick loop
  + overlay), used both defensively at the start of a fresh grace period
  and from the "Yes, I want to unassign" branch (PRD ss5 P4) -- an
  unassign means no "Confirm Pickup" is ever coming for this pickup, so
  the timer needs to be torn down there too, not left running/leaked.

`STORE_WAIT`-tagged diagnostic logging at every real decision point
(grace period started, grace period elapsed/timer visible, over-grace
duration recorded, confirmed within grace period) -- PRD ss5 P1's own
mitigation for the disclosed button-text risk: the next real log can
directly confirm whether these two taps were ever detected at all.

Verified: brace/paren balance 169/169 braces, 614/614 parens (whole
file, after this addition). Confirmed `cancelStoreWaitTimer()` is
defined and reachable from both its call sites regardless of method
declaration order (Java doesn't require forward declaration). No
Android SDK/emulator/device in this environment -- same disclosed
limitation as every other Java-only change in this repo; whether these
two button-text guesses actually match Dasher's real UI is unconfirmed
(PRD ss3).

PRD §7 boxes 4 and 5 checked (built together, both touching the same
click-handler region). Next: `_build_trip_summary_dict`/`get_trip_summary`.

## _build_trip_summary_dict returns the new field (2026-09-08)

Confirmed the actual method is `_build_trip_summary_dict` (called by
both `get_trip_summary_by_id` and `get_last_trip_summary`, via a `SELECT
* FROM trips ...` so `row["store_wait_over_grace_seconds"]` is already
available with no query change needed) -- added
`"store_wait_over_grace_seconds": row["store_wait_over_grace_seconds"]`
to its returned dict, right next to `pickup_address`, ahead of
`phase_breakdown` (which is where the OTHER, GPS-based wait duration
lives) so the two related-but-different fields sit near each other for
a future reader.

Verified: `python3 -m py_compile drive_monitor.py` -- compiles cleanly.

PRD §7 box 6 checked. Next: `TripHistoryActivity` display, then the
real Python test.

## TripHistoryActivity display (2026-09-08)

New line, "Store wait beyond 1 min (measured): Xm Ys", inserted right
after the existing "Waiting at restaurant" line in the "Where The Time
Went" section -- adjacent placement so the two related-but-different
wait numbers sit together for the driver. Reads `summary.
isNull("store_wait_over_grace_seconds")` (a top-level summary field,
unlike the other lines in this block which read from `phaseBreakdown`)
-- confirmed `JSONObject.isNull()` already has established precedent in
this exact file for treating a JSON `null` the same as a missing key
(the `traffic_ratio` field uses the identical pattern). Omitted entirely
when null, matching this screen's own convention -- never shown as "0m
0s".

Verified: brace/paren balance 165/165 braces, 1091/1091 parens (whole
file, after this addition). Confirmed `summary` (not `phaseBreakdown`)
is the correct variable already in scope at this point in the method
(`summary.optInt("job_count", ...)`/`summary.optString("feedback_
merchant_wait", ...)` are both already read a few lines above in the
same block).

PRD §7 box 7 checked. Next: real Python test for
`record_store_wait_timer` + the trip-summary field.

## Real Python test + full-suite regression check (2026-09-08)

`test_store_wait_timer.py` (scratchpad, 5 assertions, all passed):
1. `record_store_wait_timer` persists onto the currently-active trip
   (`end_time IS NULL`) and returns the expected
   `{"recorded": True, "store_wait_over_grace_seconds": ...}` JSON.
2. `pickup_address` -- the ORIGINAL caller of the now-renamed
   `_update_current_trip_column` (formerly `_update_current_trip_text_
   column`) -- still persists correctly, confirming the rename didn't
   break its first real caller.
3. `_build_trip_summary_dict` (via `get_trip_summary_by_id`) surfaces
   the recorded value once the trip is completed (`end_time` set
   directly, same technique `test_feedback_dialog_phase_timings.py`
   already uses to skip the full GPS-driven trip lifecycle).
4. A trip that never had `record_store_wait_timer` called against it
   returns `None` for the field, not `0` or a missing key.
5. Calling `record_store_wait_timer` with no active trip (`end_time IS
   NULL` matches zero rows) is a safe no-op -- zero rows touched,
   matching `_update_current_trip_column`'s own established behavior
   for this case.

Re-ran the full existing scratchpad suite (32 test files) after these
changes: 30 pass, 2 fail -- both PRE-EXISTING, unrelated failures
(`test_dropoff_instruction_wiring.py`, a stale pre-#4-fix function
signature already broken before this PRD; `test_parking_v4alpha.py`,
needs a real `GOOGLE_MAPS_API_KEY` env var, not a code failure) -- no
regressions introduced by this feature or the `_update_current_trip_column`
rename.

PRD §7 box 8 checked.

## Final consolidated verification (2026-09-08)

Re-checked brace/paren balance on all three touched Java files together
at the end of the pass (each was also checked individually right after
its own edit): `OverlayHelper.java` 98/98 braces, 429/429 parens;
`DasherAccessibilityService.java` 169/169 braces, 614/614 parens;
`TripHistoryActivity.java` 165/165 braces, 1091/1091 parens. Confirmed
via `grep` that no code still references the old
`_update_current_trip_text_column` name (only the explanatory rename
comment does, as intended).

PRD §7 box 9 checked. All implementation boxes in §7 are now checked.
Remaining: driver confirms in real use (tapping "Arrived at Store" then
waiting past a minute shows the overlay; tapping "Confirm Pickup" stops
it and the duration shows up in Trip History afterward) and driver
sign-off -- both explicitly not mine to check, per this repo's own
ralph-loop convention. Per PRD §3/§5 P1: the single biggest open risk
is whether "Arrived at Store"/"Confirm Pickup" are DoorDash's real
button text -- the next real diagnostic log's `STORE_WAIT`-tagged lines
(or their absence) will confirm or correct this.
