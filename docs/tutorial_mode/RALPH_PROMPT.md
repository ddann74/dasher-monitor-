# Ralph loop — interactive tutorial mode

DONE (2026-09-03). §4 was answered by the driver (Option B + C
combined) and every box in `PRD.md` §7 is checked except driver
confirmation/sign-off. See `PRD.md` §8 for the full implementation
writeup. Kept here for reference only, not as an active prompt — the
per-iteration structure below is still useful if any future follow-up
work on this feature happens (e.g. a fuller `handleGpsResult`
replication for steps 9/10, flagged as a disclosed scope adjustment in
§8.2, not built).

--- Original prompt, for reference ---

Once the driver has answered §4, run this prompt repeatedly (one iteration per
invocation) until every box in `docs/tutorial_mode/PRD.md` §7 is
checked.

Each iteration:

1. Read `docs/tutorial_mode/PRD.md` in full and
   `docs/tutorial_mode/PROGRESS.md` (create it if missing). If §4 isn't
   answered yet, stop and say so instead of guessing.
2. Pick the next unchecked §7 box, in order — the steps are listed in
   the natural build order (entry point → the 11 staged steps → the
   safety/cleanup items → tests), don't skip ahead to a later item
   while an earlier dependency is still unchecked.
3. **Before writing any staged-simulation code**, re-read
   `DeveloperTestingActivity.java`'s `simulateOfferScreen()` and
   `simulateDriveAndArrival()` in full again, even if you read them in
   a previous iteration — confirm the exact real function signatures
   (`parse_offer_screen`, `add_pickup`, `add_stop_to_buffer`,
   `on_gps_update`) haven't changed since this PRD was written, per
   this repo's own P2-style "don't build on a stale citation" guardrail
   used elsewhere.
4. Implement exactly the one item chosen in step 2 — scoped to
   `TutorialActivity` (new file) and its own layout/strings, plus the
   one new `MainActivity` button wiring. Do not touch
   `DeveloperTestingActivity` (PRD §6 non-goal).
5. Every simulated offer/trip row MUST use `is_test_data=1` — verify
   this explicitly for each step that touches `offer_outcomes` or
   `trips`, don't assume a copy-pasted call already has it right.
6. Every step that chains GPS ticks must re-check the
   `blockedByLiveMonitoring()`-equivalent guard (PRD §5 P1) — not just
   once at `TutorialActivity` entry.
7. Match the codebase's own voice: comments cite the real driver
   conversation this PRD's §0 documents and the real existing code
   being reused (file:line), not generic feature description.
8. Verify the same way this repo's other Java-heavy PRDs do: no Android
   SDK/emulator/device in this environment — brace/paren balance plus
   careful code review, stated explicitly in PROGRESS.md, not glossed
   over. Any pure-Python sequencing/cleanup logic gets a real, run test.
9. Check the box in PRD.md §7 only after the change is made and
   verified. If an item turns out to need something not yet resolved
   (e.g. discovers §4's chosen option has its own sub-question), do NOT
   check the box — edit that item's own bullet to say what's blocking
   it now.
10. Append one entry to `docs/tutorial_mode/PROGRESS.md`.
11. Commit, push, and open (or update) a PR for that one item, per this
    repo's established git/PR workflow. Do not batch multiple §7 items
    into one PR unless genuinely inseparable.
12. Stop. Do not continue to the next item in the same iteration.

Guardrails:

- Never resolve §4 by picking whichever option seems easiest — the
  driver decides. If asked to "just continue" while §4 is unanswered,
  surface the question instead of assuming Option A (even though it's
  the PRD's own stated recommendation) — §5 P2's recommendation is
  about SEQUENCING (ship the walkthrough before variety), not a
  substitute for the driver actually choosing.
- Never let this feature's simulated data touch a real trip/report —
  every write needs `is_test_data=1`, checked per write site, not once
  for the whole file.
- Never skip the P1 (live-monitoring race) or P3 (interrupted-tutorial
  cleanup) items as "probably fine" — both are explicit §7 checklist
  items requiring their own verification, not just code review.
- If an iteration discovers something broken or missing that isn't
  part of this PRD's own scope (e.g. a bug in `simulateDriveAndArrival`
  itself), do not fix it here — note it in PROGRESS.md and open a
  separate PRD folder, per this repo's established convention.
- The final two boxes (driver confirmation, sign-off) are never yours
  to check.
