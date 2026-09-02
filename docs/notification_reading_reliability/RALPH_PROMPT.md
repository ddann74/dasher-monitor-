# Ralph loop — fix blank-sender-name personal message drops

DO NOT RUN THIS YET. `docs/notification_reading_reliability/PRD.md`
§5 has one open question (should a name-less message fall back to
"read it anyway" when the trusted list is non-empty) that needs a real
driver decision before implementation — not a coding judgment call.

Once answered (recorded in
`docs/notification_reading_reliability/PROGRESS.md`), run this prompt
repeatedly (one iteration per invocation) until every box in
`docs/notification_reading_reliability/PRD.md` §6 is checked.

Each iteration:

1. Read `docs/notification_reading_reliability/PRD.md` §4/§6 and
   `docs/notification_reading_reliability/PROGRESS.md` (create it if
   missing). If §5 isn't answered yet and the box depends on it, stop
   and say so instead of guessing.
2. Pick the FIRST unchecked box, top to bottom.
3. Implement exactly that item, scoped to
   `AppNotificationListenerService.onNotificationPosted`'s personal-
   message branch only (`isPersonalMessagingApp`) — do not touch the
   work-message (`isDasher`) branch, a separate, already-recently-
   fixed path.
4. Match the codebase's own voice: comments explain WHY (cite the real
   driver-provided log evidence — blank and phone-number-only sender
   names, found by reading the actual field log, not hypothesized),
   not what.
5. Check the box only after the change is made (or, for the on-device
   item, only after it was actually verified).
6. Append one entry to `docs/notification_reading_reliability/PROGRESS.md`.
7. Stop. Do not continue to the next box in the same iteration.

Guardrails:

- Do not change `is_trusted_sender`'s substring-match semantics — PRD
  §3 non-goal, a separate already-disclosed tradeoff.
- Do not touch the work-message/`MessageIntelligence` pipeline — PRD
  §3 non-goal.
- Do not invent an answer to §5's open question — use whatever the
  driver actually decided, recorded in PROGRESS.md.
- On-device confirmation cannot be claimed from code review — whether
  `EXTRA_MESSAGES` carries what this design assumes for a real
  messaging app's real notification needs an actual device to confirm.
- If an iteration finds the PRD itself needs a change, stop and say so
  instead of improvising past it.
- The final box (driver sign-off) is never yours to check.
