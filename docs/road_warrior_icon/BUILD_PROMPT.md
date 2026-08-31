Fix the RoadWarrior navigation icon in the `dasher-monitor-` repo per
`docs/road_warrior_icon/PRD.md`. Implement all unchecked boxes in PRD §6
in order, in one pass:

1. In `NavigationHelper.java`, make `openAddress()` refuse to navigate
   when `lat == 0.0 && lon == 0.0` (the placeholder sentinel used
   throughout `DasherAccessibilityService.java` for "not geocoded yet")
   -- show a Toast explaining the address hasn't resolved yet instead of
   opening `geo:0.0,0.0`.
2. In the same method, add a Toast on the RoadWarrior-success branch and
   a different one on the generic-fallback branch, so a driver can tell
   which one actually opened -- today both are silent.
3. In `DeveloperTestingActivity.java`, add a way to register a test stop
   at the `(0.0, 0.0)` placeholder on purpose (alongside the existing
   `addTestStopNearby()`), so the new guard from step 1 can be exercised
   by tapping the icon without waiting for a real geocode failure.
4. In `README.md`'s "Notes / TODOs" section, correct the stale bullet
   claiming "nothing converts an address string into real lat/lon
   anywhere in the app" -- dropoff and pickup geocoding are both already
   implemented (`DasherAccessibilityService.handleDropoffScreen`,
   `parse_offer_screen` pickup path via `GoogleApiHelper`). Also add a
   note that `com.roadwarrior.android` was reconfirmed as the correct
   Play Store package name on 2026-08-22.
5. Manually verify the core requirement: with a real (non-placeholder)
   geocoded address registered as the approaching stop, confirm the icon
   appears once within the ~500m approach radius, and that tapping it
   opens navigation pinned to that address's real coordinates with the
   address labeled/visible -- this is the acceptance test everything else
   here serves (a driver pinpointing the exact location before arriving).
6. Manually verify the other two tap outcomes (fallback-maps open,
   placeholder-blocked) using the `DeveloperTestingActivity` hooks, and
   record exactly what was observed for all three branches in
   `docs/road_warrior_icon/PROGRESS.md`.

Constraints: match the existing codebase's comment style (explain WHY a
change exists, name the real bug it fixes, the way existing comments like
"Confirmed real bug, fixed here: ..." already do). Do not touch
geocoding, accessibility parsing, or the separate Waze
return-to-sweet-spot path -- out of scope per PRD §2. No physical device
or RoadWarrior install is available in this environment -- never claim
RoadWarrior itself was confirmed to open and pre-fill the pin; only
report what was actually observed (which toast fired, whether the guard
blocked navigation). Check off each PRD §6 box as its item is completed,
except the final "user sign-off" box, which is never yours to check.
