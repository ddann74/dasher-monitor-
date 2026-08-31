# Ralph loop — make the watchdog re-arm GPS-independent

Run this prompt repeatedly (one iteration per invocation) until every box
in `docs/watchdog_gps_independent_rearm/PRD.md` §6 is checked.

---

You are implementing `docs/watchdog_gps_independent_rearm/PRD.md` for the
`dasher-monitor-` repo, one checklist item at a time.

Each iteration:

1. Read `docs/watchdog_gps_independent_rearm/PRD.md` §6 and
   `docs/watchdog_gps_independent_rearm/PROGRESS.md` (create it if
   missing).
2. Pick the FIRST unchecked box, top to bottom.
3. Implement exactly that item, scoped to `TripForegroundService.java`'s
   `startTracking`/`stopTracking`/`onDestroy`/`maybeLogHeartbeat` and the
   new `watchdogRearmHandler`/`watchdogRearmRunnable` fields only.
4. Match the codebase's voice: comments explain WHY (cite the real gap -
   the redundant re-arm was gated on GPS callbacks, not service
   liveness, defeating its own purpose in exactly the scenario it was
   built for), not what.
5. Check the box only after the change is made.
6. Append one entry to `docs/watchdog_gps_independent_rearm/PROGRESS.md`.
7. Stop. Do not continue to the next box in the same iteration.

Guardrails:

- Do not touch `MonitoringWatchdogReceiver.java` - this PRD is scoped to
  where the re-arm is triggered FROM, not the watchdog's own logic.
- Do not change `WATCHDOG_REARM_INTERVAL_MS` - PRD §2 non-goals.
- Mirror `accessibilityHeartbeatHandler`/`accessibilityHeartbeatRunnable`
  exactly (same Looper, same start/stop points) - don't invent a
  different mechanism.
- The final box (user sign-off) is never yours to check.
