# PRD: Index the permanent per-trip tables on trip_id

Status: IMPLEMENTED (2026-09-11), driver's own feature-audit finding --
not a specific driver-reported bug. #2 of a fresh scouting pass, right
after `docs/accessibility_service_unbind_cleanup/PRD.md` (#1 of the
same pass -- see that PRD's intro for the full pass context).

## 0. What this is / isn't

`stops`, `events`, `delays`, and `messages` are the permanent trip-
history record -- unlike the three tables `docs/history_table_rotation/
PRD.md` deliberately capped at 50,000 rows each (a disclosed, accepted
data-loss tradeoff for learned-average features), these four are
correctly NEVER rotated: they're a driver's actual delivery history,
not a rolling calibration sample. That's the right design; this PRD
doesn't change it.

What was missing: every one of these four tables is queried by
`WHERE trip_id = ?` (or grouped/ordered by `trip_id`) -- in
`_build_trip_summary_dict` (`drive_monitor.py`, shared by both
`get_last_trip_summary()` and `get_trip_summary_by_id()`, i.e. every
single "open a trip's detail screen" tap) and in `export_full_report`'s
SAFETY EVENTS/DELAYS/MESSAGES sections -- with no supporting index
anywhere in the schema (confirmed via grep for `CREATE INDEX` across
the whole file before this fix -- zero matches, in any table). SQLite
fell back to a full table scan filtered by `trip_id` on every one of
these lookups.

## 1. The real failure this caused

For an active, long-time driver, `events` in particular accumulates a
row per harsh-accel/harsh-brake/speeding detection across every trip,
forever -- with no cap, by design. Opening any single historical
trip's detail screen, or generating a full report export, triggered a
full scan of these tables looking for the handful of rows matching
that one `trip_id`, scanning every row that ever existed. This gets
measurably slower with literally every additional day the app remains
in use, compounding for `export_full_report` (already O(all trips))
across every trip's own child-table lookups -- the exact opposite of
the disclosed, deliberate row-count discipline already applied to the
three rotation-capped tables.

## 2. Fix

Added `CREATE INDEX IF NOT EXISTS idx_<table>_trip_id ON <table>(trip_id)`
for all four tables (`stops`, `events`, `delays`, `messages`), placed
in `_create_schema()` right after the existing `offer_distance_accuracy`
column migration and right before `_ensure_incremental_auto_vacuum()`
runs -- so it executes on every `Database` construction (both a fresh
`__init__` and the restore-flow `reopen`), same as every other
schema-maintenance step in that method.

`CREATE INDEX IF NOT EXISTS` needed no `PRAGMA table_info` existence
check the way the `ALTER TABLE ADD COLUMN` migrations above it do --
it's natively idempotent and safe to run unconditionally on both a
brand-new database and one that's had these indexes since the last
launch.

## 3. Verification

Real, runnable Python test against an actual file-backed SQLite
database (not just read by eye, and not `:memory:` -- consistent with
this repo's own established verification convention for Python-side
changes):

- Confirmed all 4 indexes (`idx_stops_trip_id`, `idx_events_trip_id`,
  `idx_delays_trip_id`, `idx_messages_trip_id`) are created on the
  correct tables via `sqlite_master`.
- Confirmed via `EXPLAIN QUERY PLAN` that SQLite's real query planner
  actually switches to `SEARCH ... USING INDEX idx_events_trip_id
  (trip_id=?)` for a `WHERE trip_id = ?` lookup, and to
  `idx_messages_trip_id` for the exact query shape
  `get_last_trip_summary`/`get_trip_summary_by_id` use (`WHERE trip_id
  = ? AND extracted_instruction IS NOT NULL ORDER BY timestamp`) --
  not assumed from the `CREATE INDEX` succeeding, the literal query
  plan SQLite chooses.
- Confirmed `_create_schema()` is idempotent -- called 3 times in a
  row against the same open connection with no error, matching how
  `reopen()` re-runs it.
- Confirmed a real query against a table already holding data (a
  populated `events` row) still returns the correct result with the
  index present, not just an empty-table sanity check.
- `python3 -m py_compile drive_monitor.py` -- clean.

## 4. Honest limits

- No Android device/emulator available in this environment, same
  disclosed limitation as every other Python-side change in this repo
  (though it runs under Chaquopy's real CPython + SQLite on-device,
  same engine/format as the desktop test here) -- the real-world
  latency improvement on an actual phone's storage, for an actual
  driver's actual months/years-old `events` table, is reasoned from
  SQLite's documented index behavior and confirmed via `EXPLAIN QUERY
  PLAN` on a desktop file, not measured on a real device with a real
  large dataset.
- Does not add an index on any other column/table -- scoped
  specifically to the 4 tables the scouting pass identified as both
  genuinely unbounded AND missing a supporting index for their actual
  query pattern; did not audit every other query in this large file
  for a similar gap (out of scope for this specific finding).
- A new index has a real, small write-side cost (every `INSERT INTO
  events`/`stops`/`delays`/`messages` now also updates that table's
  index) -- an accepted, standard tradeoff for a table that's read via
  `trip_id` far more often than it's written to (a handful of inserts
  per trip vs. a full-table read on every historical trip view), not
  separately measured here.

## 5. Success criteria

- [x] All 4 permanently-growing per-trip tables (`stops`, `events`,
      `delays`, `messages`) indexed on `trip_id`
- [x] Confirmed via `EXPLAIN QUERY PLAN` (not just successful table
      creation) that the query planner actually uses the new indexes
      for the real query shapes this app runs
- [x] `CREATE INDEX IF NOT EXISTS` -- safe for both a fresh database
      and an existing install, no separate migration-check needed
- [x] Confirmed idempotent across repeated `_create_schema()` calls
      (matches the real `reopen()` code path)
- [x] Confirmed against a populated table, not just an empty one
- [x] `python3 -m py_compile` -- clean
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see ss4. Real on-device latency improvement for
      a large, real `events` table is unconfirmed.
- [ ] Driver confirms in real use: opening an older trip's detail
      screen and generating a full report export both feel at least as
      fast as before (this is a pure read-side optimization -- it
      should never make anything slower or behave differently, only
      faster for anyone with enough historical data for the difference
      to be noticeable).
- [ ] Driver sign-off.
