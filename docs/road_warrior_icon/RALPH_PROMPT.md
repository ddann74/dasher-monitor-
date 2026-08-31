# Ralph loop -- RoadWarrior icon: copy address to clipboard

Run this prompt repeatedly (one iteration per invocation) until every box
in `docs/road_warrior_icon/PRD.md` §6 is checked.

Note: this PRD was rewritten on 2026-08-30 to change the icon's tap
behavior from auto-launching navigation to copying the address to the
clipboard. If you have prior context from before that date, discard it --
re-read the current PRD.md in full before starting; the old checklist
items (RoadWarrior-open/fallback-open toasts, placeholder-coordinate
guard, package-name override) are superseded, not still pending.

---

You are implementing `docs/road_warrior_icon/PRD.md` for the
`dasher-monitor-` repo, one checklist item at a time.

Each iteration:

1. Read `docs/road_warrior_icon/PRD.md` §6 (Success criteria) and
   `docs/road_warrior_icon/PROGRESS.md`.
2. Pick the FIRST unchecked box in §6, top to bottom -- do not skip ahead,
   do not batch multiple boxes in one iteration.
3. Implement exactly that item:
   - Replace `NavigationHelper.openAddress()`'s intent-launch body with a
     clipboard-copy action (`ClipboardManager.setPrimaryClip`), updating
     call sites in `OverlayHelper`, `TripForegroundService`, and
     `MainActivity`'s manual button
   - Empty/unresolved-address guard (text-based, not coordinate-based)
   - Single confirmation toast on successful copy
   - Removal of the now-unused RoadWarrior intent-building and package
     -override code (`ROADWARRIOR_PACKAGE`, `PermissionsActivity`'s
     override field, `activity_permissions.xml`'s matching UI block)
   - `DeveloperTestingActivity` hook updates: successful-copy path,
     blocked-copy-when-unresolved path
   - README TODO/description correction (copy-to-clipboard behavior)
   - Core-requirement confirmation: tap on a real, resolved stop copies
     exactly that address text to the clipboard
   - Batch-order pickup re-verification against the new copy call site
     (§4a P6)
   - Final user sign-off (do NOT check this box yourself -- stop and ask)
4. Match the existing codebase's own voice: comments explain WHY, not
   what: name the specific real requirement change being implemented, the
   way existing comments in this file do ("Confirmed real bug, fixed
   here: ...", "previously auto-launched navigation, now copies to
   clipboard because ...").
5. Check the box in PRD.md §6, ONLY after the change is made (or, for the
   manual-verification items, only after they were actually exercised --
   don't check them from code inspection alone).
6. Append one entry to `docs/road_warrior_icon/PROGRESS.md`: what was
   done, what file(s) changed, and (for verification items) what was
   actually observed.
7. Stop. Do not continue to the next box in the same iteration.

Guardrails:
- This task is scoped to `NavigationHelper.java`, `OverlayHelper.java`
  (only if the guard/copy action needs to live there instead),
  `TripForegroundService.java` and `MainActivity.java` (call-site updates
  only), `PermissionsActivity.java` + `activity_permissions.xml` (removal
  of the package-override UI only), `DeveloperTestingActivity.java`, and
  `README.md`. Do not touch geocoding, accessibility parsing, or the Waze
  return-to-sweet-spot path -- all explicitly out of scope per PRD §2.
- No physical device or RoadWarrior install is available. Never claim a
  clipboard paste was confirmed on-device unless it actually was --
  reading the code is not the same as tapping the icon and pasting
  somewhere. Only claim what was actually observed.
- Removing the RoadWarrior package-override code means deleting it, not
  hiding it -- don't leave a now-pointless settings field for the driver
  to find and wonder about.
- If an iteration finds the PRD itself needs a change (missed case, wrong
  assumption), stop and say so instead of improvising past it.
- The final box (user sign-off) is never yours to check.
