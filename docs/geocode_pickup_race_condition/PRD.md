# PRD: Stale async geocode results overwriting the wrong pickup

Status: IMPLEMENTED (2026-09-14), scouting-pass finding (round 5).

## 1. What was wrong

`DasherAccessibilityService.geocodePickupAndCheckTraffic(restaurantName)`
kicks off an async Google Places geocode request for EVERY offer shown
on screen (not just accepted ones), with a real network round-trip that
can take up to ~10 seconds. If a second, different offer replaced the
currently-registered pickup (`TripManager.pickup`, via `add_pickup`)
before the first offer's geocode callback fired, that callback had no
way to know it was stale. It would unconditionally apply its result to
whatever pickup happened to be registered at the moment it landed --
silently writing offer A's coordinates/address onto offer B's pickup.

This affected three write paths, all reachable from the same stale
callback:
- `TripManager.update_pickup_coordinates` -- overwrote `pickup["lat"]`/`["lon"]`.
- `TripManager.update_pickup_address` -- overwrote `pickup["address"]`
  and also (via `_update_current_trip_column`) the CURRENT trip's stored
  `pickup_address` column, which could belong to a trip started for a
  later, unrelated pickup.
- `DriveMonitorEngine.record_pickup_location`'s canonicalization
  side-effect -- could rename the current pickup's `restaurant_name` to
  a merged identity that had nothing to do with it.

The result: a driver could see the wrong restaurant's address/coordinates
attached to their actual current pickup, with no error or indication
anything had gone wrong.

## 2. Design

Capture the offer's identity (`restaurantName`) at the moment the async
request is kicked off (already available as a closure variable in
`geocodePickupAndCheckTraffic`), and re-verify that identity still
matches the CURRENTLY registered pickup before applying any part of the
result. A mismatch means the result is stale for a pickup that's already
been superseded -- discard it silently, don't error.

- `TripManager.update_pickup_coordinates(self, lat, lon, restaurant_name=None)`:
  new optional parameter. When provided and it doesn't match
  `self.pickup["restaurant_name"]`, the update is a no-op.
- `TripManager.update_pickup_address(self, address, restaurant_name=None)`:
  same guard, extended to also gate the `_update_current_trip_column`
  write (the DB column write is tied to the same pickup identity as the
  in-memory field, so it needs the same protection -- a case beyond the
  literally-reported bug, but the same failure mode).
- `DriveMonitorEngine.record_pickup_location`: guards the
  canonicalization rename of `self.trip_manager.pickup["restaurant_name"]`
  the same way -- only renames if the pickup being renamed is still the
  one this result is actually about.
- `DriveMonitorEngine.update_pickup_coordinates`/`update_pickup_address`
  thin wrappers: thread the new `restaurant_name=None` parameter through
  unchanged.
- `restaurant_name=None` (the default) preserves the exact old
  unconditional-apply behavior, so any caller that doesn't have the
  identity at hand is unaffected.
- `DasherAccessibilityService.geocodePickupAndCheckTraffic`'s async
  `onResult` callback now passes the captured `restaurantName` through
  to both `update_pickup_coordinates` and `update_pickup_address`.

`AppNotificationListenerService.geocodePickupForNotificationOnlyOffer`
was checked separately -- it only calls `record_pickup_location` (which
already takes `restaurant_name` as a required positional argument), so
the Python-side guard alone covers that call site; no Java change was
needed there.

## 3. Verification

Real, executable Python test against the actual `drive_monitor.py`
engine (`DriveMonitorEngine` instantiated directly per scenario, not the
`get_engine()` singleton, so scenarios don't share state), reproducing
the exact race: offer A registered, offer B replaces it, then A's stale
result lands.

- Stale coordinates for a superseded pickup (A) are discarded; the
  current pickup (B)'s coordinates are untouched.
- A genuine, matching result for the CURRENT pickup still applies
  normally.
- Same two checks for `update_pickup_address`.
- `record_pickup_location`'s canonicalization rename does NOT fire when
  the current pickup is unrelated to the stale result's identity.
- The genuine (non-stale) canonicalization case still renames the
  current pickup correctly -- confirms the guard doesn't break the
  intended merge behavior.
- Omitting `restaurant_name` entirely preserves the exact old
  unconditional-apply behavior (backward compatibility for any other
  caller).

10 checks, all passed on first run. `python3 -m py_compile
drive_monitor.py` clean. `DasherAccessibilityService.java` brace/paren
balance verified via script (`final_depth = 0`). Confirmed via `grep -rn`
across `app/src/main/java/` that `DasherAccessibilityService.java` is the
only call site for these two Python methods that needed updating.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  async Places geocode race (two offers shown back-to-back with a slow
  network response for the first) has not been observed on a device,
  only reproduced by directly calling the engine methods in the order
  the race would produce.
- Only covers the identity check for pickups. If two DIFFERENT offers
  for the exact SAME restaurant name happened to race (rare, but
  possible for a chain), the identity check alone can't distinguish
  them -- this is a narrower, much lower-impact case (same restaurant
  name means the address/coordinates are very likely correct or
  near-correct anyway) and was not addressed here.
- Does not add any explicit cancellation of the in-flight Places request
  when a new offer replaces the old one -- the fix is a defensive check
  on arrival, not a proactive cancel. The stale network request still
  completes and is simply discarded; no functional impact, but not
  optimal for battery/network use.

## 5. Success criteria

- [x] Stale geocode results for a superseded pickup are discarded, not
      applied
- [x] Genuine (non-stale) results still apply normally for all three
      write paths
- [x] `record_pickup_location`'s canonicalization rename is guarded the
      same way
- [x] Backward compatible: omitting `restaurant_name` preserves old
      behavior
- [x] Real executable Python test (10 checks) against the actual engine,
      fully passed
- [x] `python3 -m py_compile` clean; Java brace/paren balance verified
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver confirms in real use that back-to-back offers never cross-
      contaminate pickup addresses/coordinates.
- [ ] Driver sign-off.
