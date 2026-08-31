# Ralph loop — in-app screen recording during a trip

Run this prompt repeatedly (one iteration per invocation) until every box
in `docs/screen_recording/PRD.md` §6 is checked.

---

You are implementing `docs/screen_recording/PRD.md` for the
`dasher-monitor-` repo, one checklist item at a time.

**Before doing anything else**: confirm PRD §5's open questions have
actually been resolved with the driver (not just present in the doc).
If §5 item 1 (scope: whole-screen vs. Dasher-foreground-only) is still
open, STOP and say so - this is not yours to decide unilaterally, it
materially changes the design in §3.

Each iteration (once §5 is resolved):

1. Read `docs/screen_recording/PRD.md` §6 and
   `docs/screen_recording/PROGRESS.md` (create it if missing).
2. Pick the FIRST unchecked box, top to bottom.
3. Implement exactly that item, scoped to `PermissionsActivity`,
   `TripForegroundService`, the new `ScreenRecordingController` (or
   equivalent), and `AndroidManifest.xml`'s `TripForegroundService`
   entry only.
4. Match the codebase's voice: comments explain WHY (cite the real
   platform constraints in PRD §1.1, the real privacy exposure in §1.3),
   not what.
5. Check the box only after the change is made.
6. Append one entry to `docs/screen_recording/PROGRESS.md`.
7. Stop. Do not continue to the next box in the same iteration.

Guardrails:

- Never silently start recording without the toggle being explicitly on
  AND consent already granted - no auto-enable, no default-on.
- Never write recordings to shared/public storage (MediaStore) without
  it being an explicit, later, separately-requested change - PRD §3.3
  specifies private app storage only.
- Never claim `MediaProjection` consent can be silently re-granted after
  a process restart - PRD §1.1.2 is a real platform constraint, not
  something to code around.
- Do not touch `TripForegroundService`'s existing GPS/accessibility/
  notification-listener code paths - this feature is additive only.
- If an iteration finds the PRD's own design assumption wrong (e.g. a
  platform API doesn't behave as §1.1 describes), stop and say so rather
  than improvising past it - this PRD has zero access to a real device
  to verify against, per §4.
- The final box (user sign-off) is never yours to check.
