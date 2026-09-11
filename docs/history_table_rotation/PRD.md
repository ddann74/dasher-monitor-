# PRD: Cap the genuinely unbounded "history" tables

Status: IMPLEMENTED (2026-09-11), driver's own feature-audit finding --
not a specific driver-reported bug. See §3 for the honest limits before
treating this as more than it is.

## 0. What this is / isn't

A feature-audit scouting pass flagged "8 history tables with no
rotation cap, unlike `diagnostic_log`/`zone_activity_log` which already
solved this exact problem." Reading each one individually before
touching anything found that finding was only PARTIALLY right:

- `delivery_speed_history`, `walking_speed_history`,
  `accel_dynamics_history`, `park_to_walk_gap_history` all use
  `INSERT ... (id, ...) VALUES (1, ...) ON CONFLICT(id) DO UPDATE` --
  genuinely single-row running-average tables despite the "history"
  name. They don't grow at all. Nothing to fix here.
- `restaurant_wait_history` is keyed by `restaurant_name` with its own
  `ON CONFLICT(restaurant_name) DO UPDATE` -- bounded by how many
  distinct restaurants a driver actually visits (realistically dozens
  to low hundreds), not by trip count. Not truly unbounded in practice.
- `pickup_location_history`, `dropoff_location_history`, and
  `offer_distance_accuracy` are the real ones: each inserts a genuinely
  new row via a plain `INSERT`, roughly once per real pickup/dropoff/
  completed delivery, forever, with no cap. These three are what this
  PRD actually fixes.

Corrected finding, not the original one -- worth stating plainly since
half the flagged list turned out not to be a real problem, and
"fixing" the wrong four tables would have been pure wasted, unrequested
work.

## 1. Design

`_rotate_table_keep_recent(conn, table, max_rows)` -- one shared
module-level helper (not three copies), same simple delete-oldest-past-
cap shape `record_zone_activity_snapshot`'s own
`ZONE_ACTIVITY_LOG_MAX_ROWS` rotation already uses, not
`diagnostic_log`'s rotate-to-archive-file behavior. Called right after
each of the three tables' real `INSERT`, before the surrounding
transaction commits.

Deliberately NOT diagnostic_log's file-archive approach: these three
tables are real learning data (zone maps, profitability, distance-
accuracy calibration), not a pure debug log, so losing the oldest rows
past the cap is a genuine, disclosed tradeoff, not something to hide
behind "nothing is really lost, it's just archived."

### 1.1 Why 50,000, not 500

`diagnostic_log`/`zone_activity_log` cap at 500 because they're
debugging/informational aids where only recent data matters.
`pickup_location_history` etc. feed learned-average features that
specifically need MANY samples accumulated over a long time (a
restaurant needs 3+ before it even shows on a zone map) -- capping
this the same way diagnostic_log does would actively degrade those
features for no real storage benefit (each row is small: a few floats
and a timestamp). 50,000 rows per table is chosen specifically to be
far beyond any realistic near-term row count (even a very active
full-time driver doing ~50 real deliveries a day would take years to
approach it) while still being a genuine ceiling against truly
pathological growth -- a bug causing runaway inserts, or many years of
continuous use. UNCONFIRMED as the "right" number in any rigorous
sense, same honesty status as every other threshold in this app
(`ZONE_SNAPSHOT_MIN_INTERVAL_SECONDS`, etc.) -- a judgment call, not a
derived constant.

## 2. Verification

- Real, runnable Python test (not committed as a permanent test file,
  same ad-hoc verification convention as the rest of this repo):
  confirmed `_rotate_table_keep_recent` keeps exactly the N most
  recent rows (by `id`, highest = newest) when over the cap, deletes
  nothing when under it, and is a correct no-op either way -- verified
  against a real in-memory SQLite table, not just read by eye.
- `python3 -m py_compile drive_monitor.py` -- clean.
- Confirmed all three real call sites (`record_pickup_location`,
  `record_dropoff_location`, `_persist_pickup_job_row`) wired correctly
  via grep, and confirmed the four upsert-pattern tables and
  `restaurant_wait_history` genuinely don't need this by reading their
  own `INSERT` statements directly, not assumed from table naming.

## 3. Honest limits

- The 50,000-row ceiling is a judgment call (see §1.1), not derived
  from any confirmed real-world row-count data -- no diagnostic log or
  device evidence informs it either way.
- `table` is interpolated directly into the SQL string in
  `_rotate_table_keep_recent` -- safe here specifically because every
  real call site passes one of this file's own hardcoded literal table
  names, never anything derived from driver input; flagged explicitly
  in the helper's own docstring so a future reader doesn't mistake this
  for a general-purpose, input-safe utility.
- Did not, at the time this was written, address the SQLite `VACUUM`/
  page-reuse question a broader reading of the same audit also raised
  (deleted rows becoming free pages inside the file, not shrinking it)
  -- **now addressed** in the direct follow-up,
  `docs/sqlite_incremental_vacuum/PRD.md`, which this rotation feeds
  directly (its `_rotate_table_keep_recent` calls are what actually
  produce the deletes that PRD reclaims disk space from).

## 4. Success criteria

- [x] Correctly distinguished genuinely-unbounded tables (3) from
      single-row upserts (4) and a naturally-bounded keyed table (1),
      rather than blindly capping all 8 as originally flagged
- [x] One shared helper, not three near-duplicate copies of the same
      delete-oldest SQL
- [x] Cap sized specifically to avoid degrading the learned-average
      features these tables feed, not just copied from
      diagnostic_log's much smaller debug-log-appropriate limit
- [x] Real executable test verifying keep-most-recent-N behavior and
      the under-cap no-op case, not just read by eye
- [x] `python3 -m py_compile` clean
- [x] SQL-injection-adjacent `table` interpolation explicitly disclosed
      as safe-because-hardcoded, not silently glossed over
- [ ] HONEST LIMIT: no way to observe real row-count growth over actual
      months/years of a real driver's use in this environment -- the
      50,000 figure is reasoned, not measured.
- [ ] Driver confirms in real use (or via a much later diagnostic
      export) that these three tables' row counts stay well under the
      cap during ordinary use, and that zone maps/profitability
      features are unaffected.
- [ ] Driver sign-off.
