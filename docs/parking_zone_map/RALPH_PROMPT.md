# Ralph loop -- real parking-difficulty zone map (satellite basemap)

No open questions block this -- run this prompt repeatedly (one
iteration per invocation) until every box in
`docs/parking_zone_map/PRD.md` §6 is checked.

---

You are implementing `docs/parking_zone_map/PRD.md` for the
`dasher-monitor-` repo, one checklist item at a time.

Each iteration:

1. Read `docs/parking_zone_map/PRD.md` in full (especially §1's
   investigation and §3's design, both written against
   `get_location_profitability()`/`LocationProfitabilityMapActivity`
   as the direct precedent to mirror) and `docs/parking_zone_map/
   PROGRESS.md` if it exists yet.
2. Pick the FIRST unchecked box in §6, top to bottom -- do not skip
   ahead, do not batch multiple boxes in one iteration.
3. Implement exactly that item:
   - The `_score_parking_difficulty` extraction (box 1) must be a real
     refactor, not a rewrite -- `get_parking_difficulty_rating`'s
     existing behavior (thresholds, gating, label wording) must not
     change. Re-read its current body before touching it.
   - `get_parking_difficulty_zones()` (box 2) mirrors
     `get_location_profitability()`'s exact join/loop shape (`SELECT
     ... FROM pickup_location_history GROUP BY restaurant_name`, then
     per-restaurant lookup) -- do not invent a different query
     structure. Write and run a REAL Python test
     (`python3 <test>.py` against `Database(":memory:")`) before
     checking this box, per this repo's own established verification
     convention for every Python change.
   - `ParkingZoneMapActivity.java`/`activity_parking_zone_map.xml`
     (box 3): read `LocationProfitabilityMapActivity.java` and
     `activity_location_profitability_map.xml` FIRST and copy their
     structure -- osmdroid `Configuration`/user-agent setup,
     center-on-average logic, tap-to-detail `AlertDialog`. The only
     deliberate differences are the `XYTileSource` (ESRI World Imagery)
     and the shaded-circle-not-pin marker style, both specified in PRD
     §3.2 -- don't diverge from the proven structure elsewhere.
   - The `TripHistoryActivity` button (box 4) matches
     `locationProfitabilityMapButton`'s existing
     `startActivity(new Intent(...))` one-liner exactly.
4. Match the existing codebase's own voice: comments explain WHY, not
   what -- name the real thing being reused/mirrored
   ("`get_location_profitability()`'s own join shape, reused here for
   ...").
5. Check the box in PRD.md §6, ONLY after the change is made (or, for
   the manual-verification items, only after they were actually
   exercised -- don't check them from code inspection alone).
6. Append one entry to `docs/parking_zone_map/PROGRESS.md` (create it
   on the first iteration): what was done, what file(s) changed, and
   (for verification items) what was actually observed/tested.
7. Stop. Do not continue to the next box in the same iteration.

Guardrails:
- Scoped to `drive_monitor.py` (the two new/refactored methods only --
  do not touch unrelated functions while in the file),
  `ParkingZoneMapActivity.java` + `activity_parking_zone_map.xml`
  (new), `TripHistoryActivity.java` (one new button wiring only),
  `AndroidManifest.xml` (one new Activity declaration). Do not touch
  `LocationProfitabilityMapActivity` itself -- read it as a reference,
  never edit it as part of this PRD.
- No new Gradle dependency -- `osmdroid` is already present.
- No Android SDK/emulator/device in this environment. Verification for
  Java/XML is code review, brace/paren balance, and XML
  well-formedness; for the Python query, a REAL runnable test is
  required (not optional, not "brace balance is enough" -- this repo's
  own established split between Python and Java verification applies
  here). Never claim the satellite basemap or zone shading was
  confirmed rendering on-device unless it actually was.
- If an iteration finds PRD §1's disclosed substitution (ESRI instead
  of literal Google tiles) has become a real problem, or that the
  ESRI endpoint doesn't behave as described, stop and say so rather
  than silently picking a different tile source.
- If an iteration finds the PRD itself needs a change, stop and say so
  instead of improvising past it.
- The final box (driver sign-off) is never yours to check.
