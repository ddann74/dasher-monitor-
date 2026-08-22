# Progress log -- RoadWarrior icon fix

## Investigation (2026-08-22)

Read `NavigationHelper.java`, `OverlayHelper.java`, `TripForegroundService.java`,
`MainActivity.java`, `DasherAccessibilityService.java`, `GoogleApiHelper.java`,
and the README. Confirmed via web search that `com.roadwarrior.android` is
the correct, current Play Store package name for RoadWarrior Route Planner
-- ruling that out as the cause.

Found the real, in-scope, code-fixable bugs: `NavigationHelper.openAddress()`
has no guard against the `(0.0, 0.0)` placeholder-coordinate sentinel used
everywhere else in this codebase, so a tap before geocoding resolves (or
with no API key configured) silently opens navigation to the middle of the
ocean; and neither the RoadWarrior-open nor the fallback-open path gives
the driver any visible confirmation of which one happened. Also found the
README's "nothing converts an address string into real lat/lon" TODO note
is stale -- both dropoff and pickup geocoding are already implemented.

Wrote `docs/road_warrior_icon/PRD.md` scoping the fix to those items.
Implementation not yet started -- awaiting sign-off per the PRD.

## Requirement clarification (2026-08-22)

User confirmed the core requirement: the icon should appear on approach
to the stop's address and, when tapped, open navigation already loaded
with that target address for pinpointing. Confirmed this matches the
existing design intent (`APPROACHING_RADIUS_METERS` trigger +
`geo:lat,lon?q=address` tap payload) rather than being new scope -- added
as an explicit acceptance criterion in PRD §2/§6, since it's exactly what
the placeholder-coordinate bug (item 1) would silently break.

## Implementation (2026-08-22)

Made the code changes for PRD §6 items 1-5:

- `NavigationHelper.openAddress()`: added the `(0.0, 0.0)` placeholder
  guard (early return + "Address not resolved yet" toast); added a
  "Opening ... in RoadWarrior" toast on the RoadWarrior-success branch;
  added a "RoadWarrior not available -- opening ... in your default maps
  app instead" toast on the fallback branch. Documented the package-name
  reconfirmation date in the `ROADWARRIOR_PACKAGE` comment.
- `DeveloperTestingActivity.java` + `activity_developer_testing.xml` +
  `strings.xml`: added a new "Add Test Stop With Unresolved (0,0)
  Address" button (`addPlaceholderTestStopButton` /
  `addPlaceholderTestStop()`), a sibling to the existing
  `addTestStopNearby()`, registering a stop at the exact placeholder
  coordinates so the new guard can be tapped on demand.
- `README.md`: corrected the two stale TODO bullets ("Post-accept address
  reading" and "Geocoding") to reflect that both are actually implemented
  (`DasherAccessibilityService.handleDropoffScreen`,
  `GoogleApiHelper.geocodeAddress`/`geocodeAddressWithFormatted`), and
  updated the RoadWarrior bullet with the reconfirmed package name and
  the new toast-visibility fix, while keeping the genuinely still-open
  part (RoadWarrior's real `geo:` handling on a physical device) honestly
  unresolved.

Verified by direct `git diff` review of all four changed files -- no
syntax issues, matches the existing codebase's comment style.

**Not done, and can't be from here**: the two remaining §6 boxes need an
actual tap on a running app (icon appears at the approach radius; the
three tap outcomes fire the right toast). This sandbox has no Android
emulator or device attached, so none of that was physically exercised --
only confirmed by reading the code paths. That verification, and final
sign-off, are the next step whenever this can be run on a real device or
emulator.
