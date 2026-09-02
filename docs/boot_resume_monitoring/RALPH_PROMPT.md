# Ralph loop — resume monitoring after a reboot

Run this prompt repeatedly (one iteration per invocation) until every
box in `docs/boot_resume_monitoring/PRD.md` §5 is checked.

Each iteration:

1. Read `docs/boot_resume_monitoring/PRD.md` §3/§5 and
   `docs/boot_resume_monitoring/PROGRESS.md` (create it if missing,
   and record the driver's actual answers to §4 there before starting
   any box that depends on them).
2. Pick the FIRST unchecked box, top to bottom.
3. Implement exactly that item:
   - `wasIntendedActive` is scoped to `MonitoringWatchdogReceiver.java`
     only — a getter mirroring the existing `markIntendedActive`
     setter's exact SharedPreferences key/file, nothing else in that
     class touched.
   - The resume call is scoped to `BootAndUpdateReceiver.java` only —
     reuse `TripForegroundService.ACTION_START_TRACKING` and
     `context.startForegroundService(...)`, the exact same pattern
     `DrivingDetectionReceiver` already uses successfully in this
     codebase. Do not invent a different start mechanism.
4. If a box depends on §4's open questions (whether
   `MY_PACKAGE_REPLACED` also resumes; Toast vs. notification) and
   PROGRESS.md doesn't yet record the driver's answer, use the PRD's
   own stated recommendation (treat both boot and update the same way;
   use a real notification, not a Toast) and note in PROGRESS.md that
   the recommendation was used absent an explicit override — do not
   silently invent a different answer.
5. Match the codebase's own voice: comments explain WHY (cite that
   every OTHER interruption type already has a recovery path via the
   watchdog, and a reboot was the one silent gap, found during a full-
   app premortem), not what.
6. Check the box only after the change is made (or, for the
   executable-test/on-device item, only after it was actually verified
   the way the box specifies).
7. Append one entry to `docs/boot_resume_monitoring/PROGRESS.md`.
8. Stop. Do not continue to the next box in the same iteration.

Guardrails:

- Do not touch `DrivingDetectionReceiver` or the watchdog's own alarm-
  rearm logic — PRD §2 non-goals.
- Do not add a new persistence mechanism for "was monitoring active" —
  `KEY_INTENDED_ACTIVE` already exists and already means the right
  thing; only read it, don't duplicate it.
- The on-device confirmation box is explicitly flagged in the PRD as
  the one item code review cannot substitute for (real Android-version-
  specific `BOOT_COMPLETED` foreground-service-start edge cases) — do
  not check it from code review, and do not claim it's been verified
  when it hasn't.
- If an iteration finds the PRD itself needs a change, stop and say so
  instead of improvising past it.
- The final box (driver sign-off) is never yours to check.
