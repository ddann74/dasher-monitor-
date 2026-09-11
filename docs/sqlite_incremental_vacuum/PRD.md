# PRD: Reclaim disk space from deleted rows (SQLite incremental auto-vacuum)

Status: IMPLEMENTED (2026-09-11), driver's own feature-audit finding --
not a specific driver-reported bug. See §3 for the honest limits,
especially §3.1, before treating this as more than it is.

## 0. What this is / isn't

This is a direct follow-up to `docs/history_table_rotation/PRD.md`:
that PRD capped three genuinely-unbounded tables so they stop growing
past a fixed row count, deleting the oldest rows once the cap is hit
-- the same rotate-on-insert pattern `diagnostic_log`/
`zone_activity_log` already used. But a real SQLite fact neither of
those PRDs addressed: in SQLite's default `auto_vacuum=NONE` mode, a
deleted row's page becomes a FREE PAGE inside the database file, not
reclaimed disk space. Rotation stops the row COUNT from growing
forever; it does nothing on its own about the FILE staying at its
high-water mark forever, even as old rows keep getting replaced by new
ones underneath that same ceiling.

This closes that second half of the same problem.

## 1. Design

Two real SQLite mechanisms, used together:

- **`PRAGMA auto_vacuum = INCREMENTAL`** -- tells SQLite to track free
  pages instead of just leaving them in place. Only takes effect
  immediately for a BRAND NEW, still-empty database; an existing
  database with real data needs an actual `VACUUM` (rewrites the whole
  file) to convert. `Database._ensure_incremental_auto_vacuum()`
  handles both cases: checks `PRAGMA auto_vacuum`'s CURRENT value first
  (idempotent -- naturally a no-op on every later launch once already
  converted), sets the PRAGMA, and runs `VACUUM` only when the
  database wasn't already in mode 2. Runs once per `Database`
  construction (both fresh `__init__` and the restore-flow `reopen`),
  logged via a new `DATABASE` diagnostic category so a real conversion
  (or a real failure) is visible, not silent.
- **`PRAGMA incremental_vacuum`** -- once a database IS in incremental
  mode, this is what actually reclaims already-freed pages back into
  usable file space (auto_vacuum=INCREMENTAL doesn't do this
  automatically on every delete the way `auto_vacuum=FULL` would --
  it only makes pages AVAILABLE to reclaim on request). New shared
  `_reclaim_deleted_pages(conn)` helper calls this, called after every
  real bulk delete in this codebase: the three history-table
  rotations, `diagnostic_log`'s rotation AND its manual "clear log"
  action, `zone_activity_log`'s rotation (now itself consolidated onto
  the same shared `_rotate_table_keep_recent` helper the three history
  tables use, removing a near-duplicate copy of the same SQL) and its
  own manual clear, and `reset_all_data`. Calling it on a database
  NOT yet in incremental mode (an existing install whose one-time
  `VACUUM` hasn't run yet, or failed) is a documented, harmless SQLite
  no-op -- safe to call unconditionally after any real delete without
  re-checking the mode at each call site.

## 2. Verification

Real, runnable tests against actual file-backed SQLite databases (not
`:memory:`, since the whole point is real file size behavior) --
temporary files, deleted after the run, not committed as permanent
test files, same ad-hoc convention as the rest of this repo:

- Confirmed a fresh database starts in `auto_vacuum=0` (NONE, SQLite's
  real documented default) and converts to `2` (INCREMENTAL) via
  `PRAGMA auto_vacuum=INCREMENTAL` + `VACUUM`.
- Confirmed the actual `.db` FILE ON DISK measurably shrinks after a
  real bulk delete followed by `_reclaim_deleted_pages` -- not just
  that the pragma calls didn't error, the literal `os.path.getsize()`
  before and after.
- Confirmed calling `incremental_vacuum` on a database NOT in
  incremental mode doesn't raise -- the documented no-op case this
  design relies on to make `_reclaim_deleted_pages` safe to call
  unconditionally.
- Confirmed `Database()` construction against a real fresh file
  reports `{"action": "converted"}`, and that reopening the SAME
  already-converted file on a second `Database()` call reports
  `{"action": "already_incremental"}` -- proving the idempotency this
  design depends on to never redundantly `VACUUM` an already-converted
  database on every single app launch.
- `python3 -m py_compile drive_monitor.py` -- clean.

## 3. Honest limits

### 3.1 The real, disclosed risk: VACUUM on an existing install

Every driver already running this app has a real database file
created under the old default (`auto_vacuum=NONE`). The one-time
conversion for them means a real `VACUUM` runs automatically the next
time the app launches after this update -- not opt-in, not previewed.
`VACUUM` needs roughly the current file size again in free disk space
to build the rewritten copy before swapping it in, and rewrites the
whole file (a genuinely blocking operation for however long it takes).

This is reasoned to be low-risk, not proven low-risk: this app's real
database is small by construction --
`diagnostic_log`/`zone_activity_log` capped at 500 rows,
`pickup_location_history`/`dropoff_location_history`/
`offer_distance_accuracy` now capped at 50,000 rows each of a few
small columns (see `history_table_rotation`) -- so the file is
expected to be single-digit-to-low-double-digit megabytes for even a
heavy daily driver, and `VACUUM` on a database that size is expected
to complete in well under a second on any real Android device. That's
inference from row-count caps, not a measurement against a real
device's actual file. Wrapped in `try/except sqlite3.Error`
specifically so a failure (disk full, killed mid-operation) can never
prevent the app from starting -- the database is left exactly as it
was (SQLite's `VACUUM` is transactional, all-or-nothing), just still
unconverted, and the conversion is simply retried on the next launch,
same as if it had never been attempted.

### 3.2 Other limits

- No Android device/emulator available in this environment, same
  disclosed limitation as every Java-side (and here, Python-side, but
  running under Chaquopy on a real device) change in this repo. The
  tests in §2 ran against a real desktop SQLite file, which shares the
  same real SQLite engine and file format Android's bundled SQLite
  uses -- not a simulation -- but the actual timing/behavior on a real
  phone's storage (especially the one-time VACUUM's real duration) is
  unconfirmed.
- Does not address `restaurant_wait_history` or the four single-row
  upsert tables (`delivery_speed_history`, etc.) -- correctly, since
  none of them delete rows at all (upserts and a naturally-bounded
  keyed table don't produce the free-page problem this PRD exists to
  solve). Consistent with `history_table_rotation/PRD.md`'s own
  correction of the original audit finding.
- `reset_all_data` does not clear `pickup_location_history`/
  `dropoff_location_history` (not part of its existing table list) --
  unrelated, pre-existing scope decision, not something this PRD
  changed or investigated further.

## 4. Success criteria

- [x] `Database._ensure_incremental_auto_vacuum()` correctly converts
      a fresh database immediately and an existing one via a real,
      one-time `VACUUM`, verified against real files, not just read by
      eye
- [x] Idempotent -- verified a second open of an already-converted
      database does not redundantly re-run `VACUUM`
- [x] Never blocks app startup on failure -- wrapped in its own
      `try/except`, failure result logged, not raised
- [x] `_reclaim_deleted_pages` shared helper, called after every real
      bulk delete in this codebase (both PRD.md §1's list and the
      pre-existing `diagnostic_log`/`zone_activity_log`/
      `reset_all_data` paths), not just the newly-added history tables
- [x] `zone_activity_log`'s own rotation consolidated onto the shared
      `_rotate_table_keep_recent` helper instead of keeping a
      near-duplicate copy of the same SQL
- [x] Real file-size shrinkage confirmed with an executable test
      (`os.path.getsize()` before/after), not assumed from the pragma
      calls succeeding
- [x] The VACUUM-on-existing-installs risk stated plainly, not
      minimized or buried
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §3.2. The one-time conversion's real duration
      and behavior on an actual phone's storage is unconfirmed.
- [ ] Driver confirms in real use: the app still starts normally after
      this update (the one-time VACUUM completing without issue), and
      a `DATABASE: Converted to incremental auto-vacuum mode` line
      appears once in the diagnostic log.
- [ ] Driver sign-off.
