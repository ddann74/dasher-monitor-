# Ralph loop -- notification-based offer score confidence downgrade

Run this prompt repeatedly (one iteration per invocation) until every box
in `docs/foreground_before_scoring/PRD.md` §6 is checked.

---

You are implementing `docs/foreground_before_scoring/PRD.md` for the
`dasher-monitor-` repo, one checklist item at a time.

Each iteration:

1. Read `docs/foreground_before_scoring/PRD.md` §6 (Success criteria) and
   `docs/foreground_before_scoring/PROGRESS.md` (create it if it doesn't
   exist yet).
2. Pick the FIRST unchecked box in §6, top to bottom -- do not skip ahead,
   do not batch multiple boxes in one iteration.
3. Implement exactly that item, scoped to
   `AppNotificationListenerService.java`'s `handleDasherNotification` and
   `launchDasherApp` methods only:
   - Unconditional `launchDasherApp()` call, moved to fire first
   - Always pass `-1` as the score from this call site
   - Voice announcement downgrade for the `smart_score != null` branch
   - Removal of `HapticFeedback.vibrateForLabel(label)` from this path
   - Confirm diagnostic logging is unchanged
   - Confirm `DasherAccessibilityService` untouched
   - Final user sign-off (do NOT check this box yourself -- stop and ask)
4. Match the existing codebase's own voice: comments explain WHY, not
   what -- name the specific real bug being fixed (a misclassified
   "Poor" label silently skipping the auto-launch; the lenient score
   being presented as fact in three places), the way existing comments in
   this file already do ("REAL BUG FIX, confirmed via a real diagnostic
   log: ...").
5. Check the box in PRD.md §6, ONLY after the change is made.
6. Append one entry to `docs/foreground_before_scoring/PROGRESS.md`: what
   was done, what file(s) changed.
7. Stop. Do not continue to the next box in the same iteration.

Guardrails:
- This task is scoped to `AppNotificationListenerService.java` only. Do
  not touch `DasherAccessibilityService.java`'s `parse_offer_screen`
  handling or the live Smart Score badge it drives -- both are already
  correct and explicitly out of scope per PRD §2.
- Do not touch `parse_offer_notification`'s parsing/regex logic itself
  (Python side) -- this PRD only changes how much its result is trusted
  for display, not how it's computed.
- No physical device is available. Never claim the full-screen intent was
  confirmed to actually bring Dasher to the foreground on a real device --
  only claim what was actually verified (code inspection, or a simulated
  test hook if one exists).
- If an iteration finds the PRD itself needs a change, stop and say so
  instead of improvising past it.
- The final box (user sign-off) is never yours to check.
