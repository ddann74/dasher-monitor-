# Ralph loop -- RoadWarrior icon fix

Run this prompt repeatedly (one iteration per invocation) until every box
in `docs/road_warrior_icon/PRD.md` §6 is checked.

---

You are implementing `docs/road_warrior_icon/PRD.md` for the
`dasher-monitor-` repo, one checklist item at a time.

Each iteration:

1. Read `docs/road_warrior_icon/PRD.md` §6 (Success criteria) and
   `docs/road_warrior_icon/PROGRESS.md`.
2. Pick the FIRST unchecked box in §6, top to bottom -- do not skip ahead,
   do not batch multiple boxes in one iteration.
3. Implement exactly that item:
   - `NavigationHelper.openAddress()` placeholder-coordinate guard
   - Toast feedback distinguishing RoadWarrior-open vs. fallback-open
   - `DeveloperTestingActivity` manual hook for the placeholder-blocked path
   - README TODO correction (geocoding note)
   - In-code documentation of the reconfirmed package name + date
   - Core-requirement confirmation: icon appears within the approach
     radius of a real address, tap opens navigation pinned to that
     address's real coordinates with the address labeled
   - Manual verification pass + PROGRESS.md write-up of what was tapped
     and what happened for each of the 3 branches
   - Final user sign-off (do NOT check this box yourself -- stop and ask)
4. Match the existing codebase's own voice: comments explain WHY, not
   what: name the specific real bug/incident being fixed, the way
   existing comments in this file do ("Confirmed real bug, fixed here:
   ...", "previously used placeholder (0.0, 0.0) coordinates").
5. Check the box in PRD.md §6, ONLY after the change is made (or, for
   the manual-verification item, only after it was actually exercised --
   don't check it from code inspection alone).
6. Append one entry to `docs/road_warrior_icon/PROGRESS.md`: what was
   done, what file(s) changed, and (for the verification item) what was
   actually observed tapping the icon in each of the 3 states.
7. Stop. Do not continue to the next box in the same iteration.

Guardrails:
- This task is scoped to `NavigationHelper.java`, `OverlayHelper.java`
  (only if the guard needs to live there instead), `DeveloperTestingActivity.java`,
  and `README.md`. Do not touch geocoding, accessibility parsing, or the
  Waze return-to-sweet-spot path -- all explicitly out of scope per PRD §2.
- No physical device or RoadWarrior install is available. Never claim
  "RoadWarrior opened and pre-filled the pin" as verified -- that's the
  one thing this environment cannot confirm. Only claim what was actually
  observed (a toast fired, a guard blocked navigation, etc).
- If an iteration finds the PRD itself needs a change (missed case, wrong
  assumption), stop and say so instead of improvising past it.
- The final box (user sign-off) is never yours to check.
