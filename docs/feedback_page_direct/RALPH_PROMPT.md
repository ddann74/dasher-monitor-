# Ralph loop -- feedback page shown directly, not via notification tap

Run this prompt repeatedly (one iteration per invocation) until every box
in `docs/feedback_page_direct/PRD.md` §6 is checked.

---

You are implementing `docs/feedback_page_direct/PRD.md` for the
`dasher-monitor-` repo, one checklist item at a time.

Each iteration:

1. Read `docs/feedback_page_direct/PRD.md` §6 (Success criteria) and
   `docs/feedback_page_direct/PROGRESS.md` (create it if it doesn't exist
   yet).
2. Pick the FIRST unchecked box in §6, top to bottom -- do not skip ahead,
   do not batch multiple boxes in one iteration.
3. Implement exactly that item, scoped to
   `TripForegroundService.java`'s `notifyRateThisDelivery()` method only:
   - Overlay-first + direct `startActivity()` attempt, mirroring
     `AppNotificationListenerService.launchDasherApp()`
   - `setFullScreenIntent(...)` upgrade on the fallback notification,
     posted unconditionally
   - Stale `TripHistoryActivity` comment correction
   - Final user sign-off (do NOT check this box yourself -- stop and ask)
4. Match the existing codebase's own voice: comments explain WHY, not
   what -- reference `launchDasherApp` as the proven pattern being
   mirrored, the way this PRD's own investigation does.
5. Check the box in PRD.md §6, ONLY after the change is made.
6. Append one entry to `docs/feedback_page_direct/PROGRESS.md`: what was
   done, what file(s) changed.
7. Stop. Do not continue to the next box in the same iteration.

Guardrails:
- This task is scoped to `TripForegroundService.java`'s
  `notifyRateThisDelivery()` only. Do not touch
  `MainActivity.showFeedbackDialog` or its `auto_show_feedback_trip_id`
  handling -- already correct, explicitly out of scope per PRD §2.
- Do not touch `AppNotificationListenerService.launchDasherApp()` -- it's
  the reference pattern being copied, not modified.
- Do not attempt to fix the notification-ID collision noted in PRD §1.4
  -- explicitly out of scope, a separate pre-existing issue.
- No physical device is available. Never claim the full-screen intent was
  confirmed to actually bring the feedback page to the foreground on a
  real device -- only claim what was actually verified (code inspection).
- If an iteration finds the PRD itself needs a change, stop and say so
  instead of improvising past it.
- The final box (user sign-off) is never yours to check.
