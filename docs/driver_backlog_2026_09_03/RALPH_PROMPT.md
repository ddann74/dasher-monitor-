# Ralph loop — driver backlog triage (2026-09-03)

Run this prompt repeatedly (one iteration per invocation) until every
box in `docs/driver_backlog_2026_09_03/PRD.md` §4 and §5 is checked or
explicitly, individually marked blocked (see guardrails below - a
blocked item is not the same as a checked one, and this loop does not
end just because every remaining item happens to be blocked).

---

You are working `docs/driver_backlog_2026_09_03/PRD.md` for the
`dasher-monitor-` repo, one checklist item at a time, per iteration.

Each iteration:

1. Read `docs/driver_backlog_2026_09_03/PRD.md` in full (§1-§9) and
   `docs/driver_backlog_2026_09_03/PROGRESS.md` (create it if missing).
   §6 is the priority order to follow - do not just go top-to-bottom
   through §4 then §5; follow §6's numbered order, skipping any item
   whose own entry says it's blocked (needs a diagnostic log, needs a
   driver answer to an open question) until that blocker is actually
   resolved.
2. Pick the next actionable item per §6's order that is NOT blocked.
3. **Before writing any code**, re-read §7 (premortem) P1 and P2 for
   that specific item: if it's #1, #5, #6, #7, #14, or #25, check
   whether a sibling item from that same P1 group was already
   implemented since this PRD was written, and read what it actually
   built before designing this one, so the two don't drift into
   different schemas/patterns for the same concept. If the item's size
   estimate leans on a specific cited function (per P2), open that
   function and confirm it actually does what §5 claims before building
   on top of it - do not take the citation on faith.
4. If the item is one of §3's genuinely open questions (#4, #17, #26)
   or depends on one being resolved first (#17a/#17b, the size of #26),
   STOP and surface the question to the driver instead of guessing -
   per PRD §7 P4, this is NOT the kind of open question a stated-
   recommendation default is appropriate for. Do not implement either
   reading of an ambiguous item speculatively "to cover both."
5. If the item is one of §4's evidence-blocked bugs (#13, #21, #22,
   #16) and the needed diagnostic log/evidence has not arrived, leave
   it unchecked and move to the next actionable item per §6 - do not
   guess at a fix without the evidence the PRD itself says is required.
6. Implement exactly the one item chosen in step 2, scoped to whatever
   files that item's PRD entry names or clearly implies - do not expand
   into a general cleanup of the surrounding code while there.
7. Match the codebase's established voice in comments: explain WHY
   (cite the real driver ask, the real evidence, the real existing code
   being built on), not what.
8. Verify the same way this repo's other PRDs do: Python changes get a
   real, runnable test (`drive_monitor.py` has zero Android/Chaquopy
   dependency); Java changes get brace/paren-balance verification plus
   careful code review, since no Android SDK/emulator/device exists in
   this environment - state that limitation explicitly in
   PROGRESS.md, don't gloss over it.
9. Check the box in PRD.md §4/§5 only after the change is made and
   verified. If the item turns out to need a driver answer or evidence
   partway through implementation (not apparent until you're in the
   code), do NOT check the box - instead edit that item's own bullet in
   place to say what's now blocking it, matching how §4's items already
   describe their own blockers.
10. Append one entry to `docs/driver_backlog_2026_09_03/PROGRESS.md`
    describing what was done (or why it's now blocked).
11. Commit, push, and open (or update) a PR for that one item, following
    this repo's established git/PR workflow - do not batch multiple
    backlog items into one PR unless they were genuinely inseparable
    (e.g. a schema change and the one query that needs it).
12. Stop. Do not continue to the next item in the same iteration.

Guardrails:

- Never resolve §3's open questions (#4, #17, #26) by picking whichever
  reading seems more likely - ask the driver. This is explicitly called
  out in PRD §7 P4 as different from this repo's usual "use the PRD's
  own stated recommendation" pattern.
- Never implement #13, #21, #22, or #16 without the specific diagnostic
  evidence each one's PRD entry names as required - these are
  real-evidence-first bugs, not design decisions with a defensible
  default.
- Never start #1 or #29 (the two large items) without first raising
  PRD §7 P3's scoping question with the driver - "what's the smallest
  version that actually helps" - even if every other item is done and
  these are all that's left.
- Never batch-implement multiple §4/§5 items in one iteration to move
  faster. One item, one PR, one PROGRESS.md entry, every time - matches
  every other PRD in this repo.
- If an iteration discovers something broken or missing that ISN'T one
  of the 36 original items (per this PRD's own §0 scope note), do not
  fix it here - note it in PROGRESS.md and, if it's substantial, open a
  new `docs/<topic>/PRD.md` folder for it rather than scope-creeping
  this one.
- The final PRD.md §9 boxes (driver confirms priority order, answers
  open questions) are never yours to check.
