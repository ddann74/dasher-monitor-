# Ralph loop — diagnose why the rating prompt has never shown

Run this prompt repeatedly (one iteration per invocation) until every
box in `docs/feedback_prompt_never_shown/PRD.md` §6 is checked.

Each iteration:

1. Read `docs/feedback_prompt_never_shown/PRD.md` §5/§6 and
   `docs/feedback_prompt_never_shown/PROGRESS.md` (create it if
   missing).
2. Pick the FIRST unchecked box, top to bottom.
3. **Step 1 box** (the logging fix): implement exactly that item,
   scoped to `notifyRateThisDelivery()` in
   `TripForegroundService.java` only — log the `mode` and `trip_id`
   values at both existing silent early returns, matching the log
   category/shape already used elsewhere in this file
   (`logDiagnostic("BUTTON", ...)` for this method's other lines).
   This box is safe to implement without further sign-off — it only
   adds logging, no behavior change.
4. **Every box after Step 1**: these all depend on a REAL diagnostic
   log from an actual trip where the prompt didn't appear. Do not
   implement a fix for candidate A or candidate B from PRD §3 without
   that log in hand — stop and say so instead. Guessing which one is
   real and building for it blind is explicitly what PRD §4's non-
   goals rule out.
5. Match the codebase's own voice: comments explain WHY (cite that
   this path had zero diagnostic logging on either skip reason, unlike
   the personal-message path a few hundred lines away in the same
   file), not what.
6. Check the box only after the change is made (or, for a log-review
   box, only after a real log was actually reviewed — not from
   assumption).
7. Append one entry to `docs/feedback_prompt_never_shown/PROGRESS.md`.
8. Stop. Do not continue to the next box in the same iteration.

Guardrails:

- Do not implement a fix for candidate A (accessibility-detection
  reliability) or candidate B (notification/BAL delivery) speculatively
  — PRD §5 Step 2 is explicit that this needs a real log first.
- Do not change the DASHER-only gating decision itself — PRD §4 non-goal.
- The Java-side logging change gets brace-balance + cross-reference
  review (no Android SDK/emulator available) — say so explicitly
  rather than claiming device-level verification.
- If an iteration finds the PRD itself needs a change — e.g. a real
  log surfaces a THIRD candidate not anticipated in §3 — stop and say
  so instead of improvising past it.
- The final box (driver sign-off) is never yours to check.
