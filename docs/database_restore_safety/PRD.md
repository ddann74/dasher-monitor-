# PRD — database restore has no validation and no safety copy

Status: IMPLEMENTED and tested (Python half). On-device confirmation of
the Java-side file flow is blocked (no Android emulator/device
available). §6 (added 2026-09-02): driver reported "I can't load the
backup database file" -- root cause found and fixed (the restore
picker's overly narrow MIME-type filter was hiding the file from the
system picker) -- awaiting driver confirmation this was the actual
symptom. See PROGRESS.md.

## 1. The real bug found

Found during a premortem pass across every feature in the app.
`DataManagementActivity.restoreDatabaseFromUri` (Java):

```java
private void restoreDatabaseFromUri(android.net.Uri uri) {
    String dbPath = engine.callAttr("get_database_file_path").toString();
    engine.callAttr("close_database_for_restore");
    try (InputStream in = getContentResolver().openInputStream(uri);
         OutputStream out = new FileOutputStream(dbPath)) {
        ...copies uri's bytes straight over dbPath...
    }
    ...shows "Restore Complete", asks for a full app restart...
}
```

Two real gaps:

1. **No validation of the chosen file.** The system file picker
   (`ACTION_OPEN_DOCUMENT`, MIME type `application/octet-stream`) does
   not restrict selection to `.db`/SQLite files — any file is
   selectable. Whatever the driver picks is streamed directly over the
   live database file, with nothing checking it's even a valid SQLite
   database, let alone this app's schema, before that happens.
2. **No safety copy of the current database before it's overwritten.**
   The live DB is already destroyed by the time anything could go
   wrong. If the chosen file turns out to be invalid (or the driver
   simply picked the wrong file), the failure only surfaces on the
   NEXT app restart when `sqlite3.connect()` tries to open a corrupt
   file — and by then, months of real trip history, learned
   restaurant/deadhead/wait data, and personal calibration are already
   unrecoverably gone. The confirmation dialog warns "this cannot be
   undone" about REPLACING data with a real backup — it does not warn
   about, or protect against, restoring a broken/wrong file.

This app already has the exact tool needed to avoid both gaps:
`backup_database_to(dest_path)` (drive_monitor.py) uses SQLite's own
online-backup API specifically because "a raw copy could catch a
transaction mid-write and produce a corrupted snapshot" — the same
correct pattern this fix needs, just not currently applied to the
restore path at all.

## 2. Non-goals

- Not building a full versioned/multiple-restore-point history — one
  most-recent pre-restore safety copy is enough to close this gap.
- Not adding schema-version compatibility checking (e.g. rejecting a
  backup from an old app version with a different schema) — out of
  scope; basic "is this even a real SQLite database with the tables
  this app expects" is the bar for this PRD.
- Not changing `backup_database_to` or the backup (save) side of this
  feature — it's already correct (uses the online-backup API), this
  PRD is restore-only.

## 3. Proposed design (for review, not yet approved)

Reorders the existing restore flow so nothing touches the live
database until the chosen file is proven safe:

1. Copy the chosen `uri`'s bytes to a NEW temp file (e.g.
   `restore_candidate.db` in the app's cache dir) — NOT directly to
   `dbPath`, unlike today.
2. New Python method, e.g. `validate_backup_file(path)`: opens the
   candidate file read-only and confirms it's a real SQLite database
   with this app's expected core tables present (at minimum `trips`;
   `PRAGMA integrity_check` is also cheap and worth including). Returns
   a clear ok/reason result, the same shape `lastFailureReason()`-style
   methods already use elsewhere in this codebase.
3. If invalid: show the driver exactly why (e.g. "Not a valid Dasher
   Monitor backup file"), delete the temp candidate, and stop — the
   live database is never touched.
4. If valid: call the EXISTING `backup_database_to(safety_path)` on
   the still-open live connection to take a real, consistent pre-
   restore safety copy (reusing the method that already exists for
   this exact purpose, not a new one) — stored somewhere durable (not
   the cache dir, which Android can clear) and clearly named/dated.
5. Only now: `close_database_for_restore()`, replace `dbPath` with the
   validated candidate file, delete the temp candidate.
6. The "Restore Complete" dialog mentions where the pre-restore safety
   copy was saved, in case the validated-but-still-wrong-content case
   happens (a real backup from a different install, say) and the
   driver needs to recover manually.

## 4. Open questions

- Where should the pre-restore safety copy live, and should old ones
  be cleaned up automatically (keep only the most recent) or left for
  the driver to manage manually? Recommend: app-external storage
  (survives longer than cache, same location `ScreenRecordings/`
  already uses), keep only the single most recent one — a UX/storage
  call, not purely a coding one, but low-stakes enough that this
  recommendation is reasonable to build from directly.

## 5. Success criteria

- [x] Chosen backup file is copied to a temp location first, never
      streamed directly over the live database path.
- [x] `validate_backup_file` implemented and rejects a non-SQLite file
      and a SQLite file missing this app's core tables.
- [x] A real pre-restore safety copy is taken via the existing
      `backup_database_to` before the live database is touched.
- [x] Restoring a genuinely valid backup still works end-to-end (no
      regression on the working case).
- [x] Real executable test (Python side): `validate_backup_file`
      against a real valid DB, a non-SQLite file, and a SQLite file
      missing the `trips` table.
- [ ] On-device confirmation of the Java-side file flow — blocked, no
      Android emulator/device available in this environment.
- [ ] Driver sign-off.

## 6. Driver-reported (2026-09-02): "I can't load the backup database file"

### 6.1 Root cause found

`restoreDatabaseFromUri`'s file picker (`DataManagementActivity`,
`REQUEST_RESTORE_DATABASE_FILE`) launched
`ACTION_OPEN_DOCUMENT` filtered to `setType("application/octet-stream")`
only — the same type the Backup button requests when it CREATES a
file. That's fine for creation (the app controls the type at creation
time), but wrong for opening an existing one: whatever storage
provider the driver browses through afterward (Google Drive, a file
manager, Downloads, a saved email attachment) is free to index a
`.db` file under a completely different MIME type
(`application/x-sqlite3` and similar are common). Android's Storage
Access Framework then hides or greys out that exact file in the
picker, since it no longer matches the app's requested type — this
directly matches "I can't load the backup file": not a crash, not a
rejected file, the file was never selectable in the first place.

### 6.2 Fix

Broadened the RESTORE picker's type to `"*/*"` (kept
`CATEGORY_OPENABLE`). Confirmed safe to do — MIME type was never this
feature's actual safety gate: `validate_backup_file` (§3/§5, already
implemented) does real content validation (SQLite integrity check +
required-table check) on whatever gets picked, regardless of its
reported type, so a stray non-database file picked under the broader
filter is still correctly rejected before touching the live database.
The BACKUP button's `ACTION_CREATE_DOCUMENT` type was deliberately left
unchanged — that's a different flow (the app names and types the file
it creates), not the one the driver reported an issue with.

### 6.3 Verification

Brace/paren balance confirmed (50/50, 272/272) after the edit.
Code-reviewed against the real `validate_backup_file` implementation
to confirm it doesn't rely on MIME type anywhere (it opens the file
read-only via a plain URI connection and inspects real SQLite content
only) — broadening the picker filter doesn't weaken that check at all.
Also checked whether this codebase enables SQLite WAL mode (which
would leave `-wal`/`-shm` sidecar files that could make a restore
silently not take effect even after a successful file copy) - it does
not; no `journal_mode`/WAL pragma anywhere in `drive_monitor.py`, so
the existing close-then-overwrite sequence isn't at risk of that
separate failure mode. On-device confirmation that the file now
actually appears in the picker remains blocked — no Android
emulator/device available in this environment; this is a diagnosis
from code reading, not confirmed against the driver's exact screen.

- [x] §6.1's root cause found and fixed (picker MIME-type filter
      broadened to `*/*`, confirmed not to weaken `validate_backup_file`'s
      real content-based safety check)
- [ ] Driver confirms this was the actual symptom seen (vs. a
      different failure mode not yet diagnosed) and that the backup
      file now appears/selects correctly.
