# Ralph loop — alert if a dash isn't being monitored

DO NOT RUN THIS YET. `docs/dash_monitoring_awareness/PRD.md` §5 has two
open questions (where the shared alert utility should live; the exact
"give it a moment" delay) that need a real decision before §6 starts —
neither is a coding judgment call to improvise past.

Once those are answered (recorded in
`docs/dash_monitoring_awareness/PROGRESS.md`), run this prompt
repeatedly (one iteration per invocation) until every box in
`docs/dash_monitoring_awareness/PRD.md` §6 is checked.

Each iteration:

1. Read `docs/dash_monitoring_awareness/PRD.md` §4/§6 and
   `docs/dash_monitoring_awareness/PROGRESS.md` (create it if missing).
2. Pick the FIRST unchecked box, top to bottom.
3. Implement exactly that item. The shared alert utility (§5) is the
   foundation box — do it first regardless of listed order if it isn't
   done yet, since the other boxes depend on it existing. Reuse
   `raisePermissionRevokedAlert`'s existing notification shape (sound +
   vibration, its own channel) rather than inventing a new visual
   style.
4. Match the codebase's own voice: comments explain WHY (cite that
   auto-start already exists and already works when nothing blocks
   it — this closes the one remaining silent-failure gap, not a new
   feature from scratch), not what.
5. Check the box only after the change is made (or, for the on-device
   item, only after it was actually verified the way the box
   specifies).
6. Append one entry to `docs/dash_monitoring_awareness/PROGRESS.md`.
7. Stop. Do not continue to the next box in the same iteration.

Guardrails:

- Do not touch the existing auto-start logic in
  `checkCurrentForegroundWindow` or `DrivingDetectionReceiver` beyond
  adding the post-attempt verification — PRD §3 non-goal.
- Do not duplicate `MonitoringWatchdogReceiver`'s stale-heartbeat
  alerting — this PRD is scoped to "never successfully started," not
  "started then went stale."
- Do not invent the "give it a moment" delay value or the shared-
  utility's exact shape without checking PROGRESS.md for the driver's
  actual answer to §5 first.
- On-device confirmation cannot be claimed from code review — the
  actual trigger (Android rejecting a background service start) is a
  real platform behavior, not something a sandbox can reproduce.
- If an iteration finds the PRD itself needs a change, stop and say so
  instead of improvising past it.
- The final box (driver sign-off) is never yours to check.
