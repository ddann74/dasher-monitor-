You are running as one iteration of a Ralph loop building/verifying Dasher Monitor, an Android
co-pilot app for DoorDash Dashers. You have no memory of previous iterations except what is
committed to this repository. Read before you write.

## Read first, every time
1. `PRD.md` — the requirements and acceptance criteria. This is the definition of "done." It
   also documents a real contradiction found in `README.md` (the TODO section is stale on at
   least 3 items) — don't trust README prose over the actual code.
2. `ralph/TASKS.md` — the task backlog. Find the first unchecked `[ ]` box, top to bottom.
3. `ralph/PROGRESS.log` — a running log of what previous iterations did and found. Append to
   it, don't overwrite it.
4. The actual repo state (`git status`, `git log -5`) — trust the code and test/build results
   over any prose claim, including your own from a prior iteration and including `README.md`.

## Do this iteration
1. Pick ONLY the first unchecked task in `ralph/TASKS.md`. Do not skip ahead. Do not attempt
   multiple tasks in one iteration.
2. Implement it fully, including the verification step written next to it.
3. Actually RUN the verification at the highest level available in this environment (see
   `ralph/README.md`'s four verification levels: real build > runnable unit test > explicit
   manual-review checklist > human-only manual-verification script). Do not assert a build or
   test passed without running it. Paste the real command output into `ralph/PROGRESS.log`.
   - If this environment has no Android SDK, say so explicitly the first time you hit it
     ("no Android SDK in this environment, `./gradlew assembleDebug` cannot run") and fall back
     to the next verification level rather than silently skipping verification.
   - If a task genuinely requires a real device, a real Dasher offer, or a real OEM phone, do
     not attempt to fake that verification. Write the exact manual steps a human would run, and
     leave the box unchecked with a note in `PROGRESS.log` that this task needs human
     verification — that is a valid, complete outcome for that task, not a failure.
4. If verification fails: fix it in this same iteration if the fix is small; otherwise leave
   the task unchecked, write exactly what failed and why into `ralph/PROGRESS.log`, and stop
   this iteration. Do not check the box.
5. If verification passes: check the box `[x]` in `ralph/TASKS.md`, commit your work with a
   message describing what was built/verified and what proved it, e.g.:
   `git commit -m "Add TripManager pickup-arrival unit test; python -m pytest: 4 passed"`
6. Never mark a task done, never write "done," "confirmed," or "passed" anywhere, unless there
   is a command output, test result, or file artifact in this same commit that supports it. If
   you're unsure whether something is truly verified, treat it as NOT verified and say so in
   `ralph/PROGRESS.log` — do not round up. This applies doubly to any claim already made in
   `README.md` — the PRD found the README's own TODO list wrong on 3 items; assume other README
   claims need the same scrutiny, not automatic trust.
7. If you discover a README/PRD claim doesn't hold up once actually checked (a feature that
   looks implemented but has a real bug, a "learned" value that never actually updates, etc.),
   that is a valid and expected outcome — record exactly what you found and move on. The goal
   is truth, not confirming what was already written.

## Guardrails
- One task per iteration. Small, verifiable, committed increments beat large unverified leaps.
- If a task in `ralph/TASKS.md` turns out to be ambiguous or too large, split it into smaller
  sub-tasks in place in `TASKS.md` (as new checkboxes) rather than guessing scope.
- Never delete or weaken an existing test to make it pass.
- Never edit `PRD.md`'s acceptance criteria to make a task easier — if you believe a criterion
  is wrong, note it in `ralph/PROGRESS.log` under "Flagged for human review" instead.
- Never edit `README.md`'s feature descriptions to match what you find without saying so — if
  you confirm or refute a README claim, note it in `PROGRESS.log`; only update `README.md`
  itself as its own explicit task (e.g. the "reconcile the stale TODO section" task), not as a
  side effect of an unrelated task.
- If every box in `ralph/TASKS.md` is already checked and verified, do not invent new work —
  write "ALL TASKS COMPLETE" as the last line of `ralph/PROGRESS.log` and stop.

End your turn after committing. The next iteration starts fresh and will re-read everything
above.
