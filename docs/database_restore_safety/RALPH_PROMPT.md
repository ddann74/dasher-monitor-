# Ralph loop — database restore validation and safety copy

Run this prompt repeatedly (one iteration per invocation) until every
box in `docs/database_restore_safety/PRD.md` §5 is checked.

Each iteration:

1. Read `docs/database_restore_safety/PRD.md` §3/§5 and
   `docs/database_restore_safety/PROGRESS.md` (create it if missing).
2. Pick the FIRST unchecked box, top to bottom.
3. Implement exactly that item:
   - The Python validation method (`validate_backup_file`) is scoped
     to `DriveMonitorEngine` in `app/src/main/python/drive_monitor.py`
     only — do not touch `backup_database_to` or
     `close_database_for_restore`, they already work correctly and are
     reused as-is.
   - The Java-side reorder is scoped to
     `DataManagementActivity.restoreDatabaseFromUri` only.
4. §4's open question (where the safety copy lives, whether old ones
   get cleaned up) has a recommendation already in the PRD
   (app-external storage, keep only the most recent) — reasonable to
   build from directly, it's not a blocking question.
5. Match the codebase's own voice: comments explain WHY (cite that a
   raw file copy over a live DB path had zero validation and no
   rollback, found during a full-app premortem — not a hypothetical),
   not what.
6. Check the box only after the change is made (or, for the
   executable-test item, only after it was actually run — don't check
   it from code inspection alone).
7. Append one entry to `docs/database_restore_safety/PROGRESS.md`.
8. Stop. Do not continue to the next box in the same iteration.

Guardrails:

- Never write to the live database path (`get_database_file_path()`'s
  return value) until AFTER `validate_backup_file` has confirmed the
  candidate file is real, and AFTER a fresh pre-restore safety copy
  has been taken via `backup_database_to`. If an iteration's own code
  would touch `dbPath` before both of those, stop and fix the
  ordering instead of proceeding.
- Do not build a full versioned-backup-history feature — PRD §2 non-
  goal, one most-recent safety copy is the scope.
- Do not add schema-version compatibility checking — PRD §2 non-goal.
- The Python half is genuinely testable in this sandbox with plain
  python3 (`drive_monitor.py` has zero Android/Chaquopy dependency) —
  write and RUN a real test for `validate_backup_file` against a real
  valid DB, a non-SQLite file, and a SQLite file missing `trips`.
- The Java-side file-flow reorder gets brace-balance + cross-reference
  review only (no Android SDK/emulator available) — say so explicitly
  rather than claiming device-level verification.
- If an iteration finds the PRD itself needs a change, stop and say so
  instead of improvising past it.
- The final box (driver sign-off) is never yours to check.
