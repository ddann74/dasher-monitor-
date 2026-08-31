# Ralph loop — wire the dropoff screen's delivery instruction through

Run this prompt repeatedly (one iteration per invocation) until every
box in `docs/dropoff_delivery_instruction_wiring/PRD.md` §6 is checked.

Each iteration:

1. Read `docs/dropoff_delivery_instruction_wiring/PRD.md` §4/§6 and
   `docs/dropoff_delivery_instruction_wiring/PROGRESS.md` (create it
   if missing).
2. Pick the FIRST unchecked box, top to bottom.
3. Implement exactly that item:
   - The read-and-thread-through box touches
     `DasherAccessibilityService.handleDropoffScreen()` (Java),
     `DriveMonitorEngine.add_stop_to_buffer` and `TripManager.add_stop`
     (Python) only.
   - The surface-via-overlay box touches `_check_approach_instruction`
     (and whatever minimal Java-side change is needed to read the
     stop's own instruction alongside message-derived ones) — reuse
     the EXISTING `pending_approach_instruction` plumbing, don't build
     a parallel path.
   - The diagnostic-logging box touches only
     `AppNotificationListenerService.onNotificationPosted` (Java) —
     add the missing "ignored, here's why" log line, mirroring the
     personal-message path's existing logging shape exactly.
4. Match the codebase's own voice: comments explain WHY (cite that
   `delivery_instruction` was already being parsed correctly and
   silently discarded — this is a wiring fix, not a new parser), not
   what.
5. Check the box only after the change is made (or, for the
   executable-test item, only after it was actually run — don't check
   it from code inspection alone).
6. Append one entry to `docs/dropoff_delivery_instruction_wiring/PROGRESS.md`.
7. Stop. Do not continue to the next box in the same iteration.

Guardrails:

- Do not touch `DropoffScreenParser.parse()`'s extraction logic itself
  — it already works and is out of scope; this PRD is about what
  happens to its output afterward.
- Do not touch `MessageIntelligence`/the chat-message pipeline beyond
  the one specific diagnostic-logging box in §6 — PRD §3 non-goals.
- Do not invent an answer to PRD §5's "do both instruction sources
  show, and how are they labeled" open question yourself if it isn't
  already answered in PROGRESS.md — it's a UX call for the driver, not
  a coding judgment call. If it's genuinely unambiguous from the
  existing overlay's design (e.g. it already supports showing multiple
  labeled instructions), say so and proceed; if not, stop and ask.
- The Python half is genuinely testable in this sandbox with plain
  python3 (`drive_monitor.py` has zero Android/Chaquopy dependency) —
  write and RUN a real test for the executable-test box, not just code
  review. The Java-side changes get brace-balance + cross-reference
  review (no Android SDK/emulator available), same as every other Java
  change in this repo's history — say so explicitly rather than
  claiming device-level verification.
- If an iteration finds the PRD itself needs a change, stop and say so
  instead of improvising past it.
- The final box (driver sign-off) is never yours to check.
