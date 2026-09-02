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

## §6 fix (2026-09-02) — driver reported "I can't load the backup database file"

Read `restoreDatabaseFromUri`'s file-picker Intent setup
(`REQUEST_RESTORE_DATABASE_FILE`) and found a real, well-known Android
Storage Access Framework gotcha: the picker filtered to
`setType("application/octet-stream")`, the same type the Backup
button requests when CREATING a file -- but a `.db` file can get
re-indexed under a different MIME type by whatever provider the
driver later browses through to select it (Google Drive, a file
manager, Downloads, a saved email attachment all commonly report
`.db` under something like `application/x-sqlite3`, not
`application/octet-stream`). Android's SAF then hides or greys out
that exact file, matching the reported symptom precisely -- not a
crash, not a rejected file, never selectable at all.

Fix: broadened the RESTORE picker's `setType` to `"*/*"` (kept
`CATEGORY_OPENABLE`). Confirmed this doesn't weaken the real safety
check -- `validate_backup_file` never looks at MIME type, only real
SQLite content (integrity check + required-table check) via a
read-only URI connection, so a stray non-database file picked under
the broader filter is still correctly rejected before the live
database is ever touched. Left the BACKUP button's
`ACTION_CREATE_DOCUMENT` type unchanged -- a different flow (the app
controls the type when CREATING its own file), not what was reported
broken.

Also checked, since a restore silently not taking effect would look
similar from the driver's side: whether this app enables SQLite WAL
mode, which would leave `-wal`/`-shm` sidecar files that could make a
plain file-copy restore not actually take effect. Confirmed it does
not (`grep` for `journal_mode`/WAL across `drive_monitor.py` -- nothing
found) -- ruled out as a contributing cause.

Verified: brace/paren counts in `DataManagementActivity.java` balanced
(50/50, 272/272) before and after the edit. Not verified on-device --
no Android emulator/device available in this environment; this is a
diagnosis and fix from code reading, since the driver didn't provide
an exact error message or screenshot. Awaiting driver confirmation
this was the actual symptom (the file being unselectable in the
picker), not a different failure mode.
