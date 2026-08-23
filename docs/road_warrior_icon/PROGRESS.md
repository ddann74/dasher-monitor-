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

## Premortem (2026-08-22)

Before continuing the loop, worked backward from "this feature has
failed again in the field" instead of just resuming the checklist.
Wrote 6 mechanism-specific findings (P1-P6) into PRD §4a, two of them
(P2 accessibility revocation, P6 batch-offer aggregate naming)
confirmed as *actually happening* in the real diagnostic log the user
uploaded, not hypothetical.

Implemented the two highest-value, clearly in-scope mitigations:

- **P1/P2**: `NavigationHelper.openAddress()`'s placeholder-coordinate
  toast was one generic "try again in a moment" message regardless of
  cause -- actively wrong when the real cause (no API key, or
  accessibility revoked) is permanent, not transient. Added
  `unresolvedAddressReason()`, checked in order: missing API key ->
  accessibility off -> genuinely transient. **P3 (a failed geocode API
  call) is honestly NOT distinguished** -- no state exists in
  `NavigationHelper` to detect it; still falls into the generic message.
- **P4**: `TripForegroundService`'s existing overlay-permission-revoked
  alert only mentioned the Smart Score badge/status dot, not that the
  RoadWarrior icon silently stops too (it gates on the same permission
  check). Updated the alert text to name it.

**Deferred, documented, not implemented**: P5 (make the RoadWarrior
package name driver-overridable -- needs a settings-UI decision) and P6
(stop the batch-offer aggregate name from leaking into the pin label --
needs tracing whether a real per-stop address is available at tap time
for a batch pickup). Both recorded as new unchecked PRD §6 items rather
than silently dropped.

Verified by `git diff` review of both changed files -- no syntax issues.

## Continuing the loop -- P5 and P6 (2026-08-22)

Picked up the two deferred premortem items left unchecked in PRD §6.

**P5**: Made the RoadWarrior package name runtime-overridable.
`NavigationHelper` gained `getRoadWarriorPackage`/`setRoadWarriorPackage`,
same SharedPreferences pattern as `GoogleApiHelper`'s API key
(`navigation_prefs` / `roadwarrior_package_override`, falls back to the
reconfirmed `com.roadwarrior.android` default when blank). Exposed on
the same Permissions & Setup screen as the API key field --
`activity_permissions.xml` gained a matching heading/subtext/input/button
block, wired in `PermissionsActivity.java`. `openAddress()` now calls
`getRoadWarriorPackage(context)` instead of a hardcoded constant.

**P6**: Traced the actual code path instead of implementing a fix on
spec. The aggregate "X and 1 other store" string that worried the
premortem only ever reaches `record_pickup_location()` (a separate
history table for sweet-spot learning, via `AppNotificationListenerService`'s
notification-based detection) -- it never reaches `add_pickup()`, the
only call that sets `self.pickup["restaurant_name"]`, which is what the
icon's tap payload actually falls back to. That's called exclusively
from `DasherAccessibilityService`'s real screen-parsed Accept handler,
which correctly extracted the clean single-restaurant name for this
exact log's batch offer. Confirmed not a real bug -- no code change made,
written up in PRD §4a instead of silently checking the box.

PRD §6 is now down to the three items that genuinely require a real
device: on-approach icon confirmation, the 3-branch tap verification,
and final user sign-off. Everything code-fixable from this loop's scope
is done.

Verified by `git diff` review of all four changed files -- no syntax issues.

## Continuing the loop -- P3 (2026-08-22)

Picked up the last honestly-flagged gap: a failed geocode API call
couldn't be told apart from a genuinely in-progress one.

Added `NavigationHelper.recordGeocodeFailure(context, target, message)`,
persisted via the same `navigation_prefs` SharedPreferences as the P5
package override, keyed by the exact address/restaurant-name string
being geocoded and time-boxed to 15 minutes. Wired it into both real
geocode `onError` callbacks in `DasherAccessibilityService`
(`handleDropoffScreen`'s `fullAddress`, `geocodePickupAndCheckTraffic`'s
`restaurantName`) -- the only two call sites that feed
`NavigationHelper`'s tap coordinates. `unresolvedAddressReason()` now
checks this before falling back to the generic "try again" message.

This closes every item in PRD §6 that doesn't require an actual device
tap. Verified by `git diff` review of both changed files -- no syntax
issues.
