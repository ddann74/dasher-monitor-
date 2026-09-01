# Progress log — database restore validation and safety copy

## Implementation (2026-09-01)

Per PRD §3:

- `DriveMonitorEngine.validate_backup_file(path)` (new,
  `drive_monitor.py`, next to `close_database_for_restore`): confirms
  the candidate file exists, opens read-only via a `file:...?mode=ro`
  URI connection (deliberately NOT plain `sqlite3.connect(path)`,
  which would silently create a fresh empty database if `path` didn't
  exist -- the exact opposite of what validation should do), runs
  `PRAGMA integrity_check`, and confirms the `trips` table is present.
  Returns `{"valid": bool, "reason": str|None}` as JSON.
- `DataManagementActivity.restoreDatabaseFromUri` (Java) rewritten to
  the order PRD §3 specifies: copy the chosen file to a temp
  `restore_candidate.db` in the cache dir (never write to the live DB
  path directly) -> validate it -> if invalid, show the reason and
  stop, live database untouched -> if valid, take a real pre-restore
  safety copy via the existing `backup_database_to` (called on the
  STILL-OPEN live connection, matching that method's own online-backup
  reasoning) into a new `PreRestoreBackups/` app-external folder,
  keeping only the single most recent one (PRD §4's stated
  recommendation, used since no override was given) -> only then
  `close_database_for_restore()` and copy the validated candidate over
  the live path -> delete the temp candidate. The "Restore Complete"
  dialog now names where the safety copy was saved.

No change to `backup_database_to` or `close_database_for_restore`
themselves -- both already correct, reused as-is per PRD §2's non-goal.

## Verification (2026-09-01) — ACTUALLY EXECUTED, not just reviewed

Wrote `test_validate_backup_file.py` (scratchpad, throwaway) and ran it
directly against the real, modified `drive_monitor.py` via plain
`python3` (`DriveMonitorEngine(tmpdir)`, real temp SQLite files -- no
Android/Chaquopy involved). Real output:

```
PASS: a real backup_database_to() output validates as valid
PASS: a nonexistent path is rejected, not silently treated as valid
PASS: validating a missing path creates nothing (no accidental empty-DB creation)
PASS: a non-SQLite file is rejected (Not a valid database file: file is not a database)
PASS: a real SQLite DB missing this app's schema is rejected (Missing expected table(s): trips -- this doesn't look like a Dasher Monitor backup)
PASS: the app's own live database file validates as valid

ALL ASSERTIONS PASSED
```

The "validating a missing path creates nothing" case is the one that
would have been easy to get wrong silently -- confirmed directly by
checking the path still doesn't exist on disk after calling
`validate_backup_file` against it, not just that the function returned
`False`.

Also verified: `ast.parse(drive_monitor.py)` clean after every edit;
brace/paren counts in `DataManagementActivity.java` balanced (0/0)
before and after the rewrite.

The Java-side file flow itself (temp-copy -> validate -> safety-copy ->
swap sequence, the cache-dir/external-storage paths, the dialog text)
could not be verified on-device -- no Android emulator/device
available in this environment. Verified by code review only, including
a deliberate re-check that no code path writes to `dbPath` before both
validation and the safety copy have completed.

Remaining PRD §5 boxes: on-device confirmation (blocked) and driver
sign-off.
