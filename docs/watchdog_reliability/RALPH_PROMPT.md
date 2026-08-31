# Ralph loop -- make the monitoring watchdog survive a dropped alarm

Run this prompt repeatedly (one iteration per invocation) until every box
in `docs/watchdog_reliability/PRD.md` §6 is checked.

---

You are implementing `docs/watchdog_reliability/PRD.md` for the
`dasher-monitor-` repo, one checklist item at a time.

Each iteration:

1. Read `docs/watchdog_reliability/PRD.md` §6 (Success criteria) and
   `docs/watchdog_reliability/PROGRESS.md` (create it if it doesn't
   exist yet).
2. Pick the FIRST unchecked box in §6, top to bottom -- do not skip
   ahead, do not batch multiple boxes in one iteration.
3. Implement exactly that item, scoped to
   `TripForegroundService.java`'s `maybeLogHeartbeat`/`onCreate` and
   `MonitoringWatchdogReceiver.java`'s `scheduleWatchdog` only:
   - Redundant re-arm call from the heartbeat path, throttled
   - Schedule-confirmation logging
   - Manufacturer/model/SDK/isKnownAggressiveOem logging in onCreate
4. Match the existing codebase's own voice: comments explain WHY, not
   what -- name the specific real evidence this PRD is grounded in (the
   zero WATCHDOG entries and zero onTrimMemory entries in the real
   uploaded log), the way this repo's other PRDs already reference real
   diagnostic logs and real incidents rather than hypothetical risks.
5. Check the box in PRD.md §6, ONLY after the change is made.
6. Append one entry to `docs/watchdog_reliability/PROGRESS.md`: what was
   done, what file(s) changed.
7. Stop. Do not continue to the next box in the same iteration.

Guardrails:
- This task is scoped to `TripForegroundService.java` and
  `MonitoringWatchdogReceiver.java` ONLY, and only the specific methods
  named in PRD §3. Do not touch `MonitoringWatchdogReceiver`'s alert
  -notification content, check intervals/thresholds, or restart-attempt
  logic -- all already correct and tuned against a real prior incident,
  explicitly out of scope per PRD §2.
- Do not touch `OemBackgroundHelper.java` or `PermissionsActivity.java`
  -- the autostart-guidance mitigation they implement is already correct
  and unrelated to this PRD's scope.
- This PRD is explicit that it narrows a real gap, not eliminates OS/OEM
  kills entirely (see §4a premortem). Do not write comments, log
  messages, or PROGRESS.md entries that overclaim what was fixed --
  match the PRD's own calibrated language.
- No physical device is available, and this specific failure mode (an
  OEM background kill) can't be simulated from
  `DeveloperTestingActivity` either. Never claim the fix was confirmed
  to prevent a repeat blackout -- only claim what was actually verified
  (code inspection). The real test is the next field diagnostic log.
- If an iteration finds the PRD itself needs a change, stop and say so
  instead of improvising past it.
- The final box (user sign-off) is never yours to check.
