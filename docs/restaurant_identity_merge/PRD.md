# Restaurant identity merge (address-based, going forward only)

STATUS: IMPLEMENTED (2026-09-06)

## 0. Origin

Driver asked for "identify restaurant names based on their address,"
starting with the Address Book. Investigation found a real, concrete
reason this matters: the driver's own diagnostic log had "McDonald's"
and "McDonalds AU" as separate offers, and "KFC" / "KFC Fairy Meadow" --
DoorDash's own offer-screen text isn't consistent for the same physical
restaurant. `get_address_book()` groups entirely by that raw text, so
one restaurant's wait-time/deadhead/parking history was silently
fragmenting across multiple, each-less-confident entries.

Three designs were presented: a Places API lookup per restaurant (most
accurate, ongoing cost, new mapping table), coordinate clustering only
(free, fixes fragmentation, no canonical display name), or both
combined. Driver's follow-up ("merge it going forward, don't touch old
data") settled the backfill question but not the Places-API-cost
question -- implemented here is the free, purely-internal coordinate-
clustering piece; a Places API lookup for a cleaner canonical display
name (vs. just reusing whichever name is already best-established) is
a disclosed, not-yet-built follow-up, since it introduces a real,
ongoing API cost that wasn't explicitly confirmed.

## 1. Design

New `DriveMonitorEngine._canonicalize_restaurant_name(name, lat, lon)`:
if a DIFFERENT restaurant name already has recorded pickups (in
`pickup_location_history`) within `RESTAURANT_IDENTITY_MERGE_RADIUS_
METERS` (25m -- deliberately tighter than `ARRIVAL_GEOFENCE_METERS`'s
50m, since "is this the same building" needs to be a stricter question
than "did I arrive," or a food court/strip mall's genuinely different
restaurants would wrongly merge), returns that existing name instead.
Ties (more than one existing name within range) resolve to whichever
has the most samples -- the more established identity.

Wired into `record_pickup_location` (called once real geocoded
coordinates are known for the current pickup): canonicalizes before
inserting into `pickup_location_history`, and -- when the name
actually changes -- also updates the CURRENT trip's own `self.trip_
manager.pickup["restaurant_name"]`. Since `record_restaurant_wait` and
the trip-end `offer_distance_accuracy` write both read that same
per-trip pickup state LATER (at pickup departure / trip end, both
comfortably after geocoding resolves), they inherit the corrected name
automatically -- no separate wiring needed at either of those sites.

## 2. What this does NOT cover (disclosed, not silently missed)

- **`offer_outcomes`** (and therefore Address Book's avg $/km, avg
  $/hr, and avg Smart Score columns) is written at accept/decline/
  timeout time, via a restaurant_name Java passes directly from the
  raw offer-screen parse -- BEFORE geocoding resolves, so before this
  canonicalization can run at all. Those three stats will still
  fragment across name variants even after this fix. Extending this to
  `offer_outcomes` would need Java to learn the canonical name and use
  it at accept-time, a separate, more invasive follow-up not built
  here.
- **Existing rows are never rewritten**, per explicit request --
  "McDonalds AU" rows already in the database before this shipped stay
  exactly as they are, permanently, under that name. Only pickups
  recorded from now on canonicalize.
- **No canonical display name from an external source** -- the merged
  identity is just whichever name was already most-established in this
  driver's own history, not necessarily the "official" business name.
  A Places API lookup could provide that, at a real ongoing cost, not
  built here without separate confirmation.

## 3. Verification

Real, executable tests (`test_restaurant_identity_merge.py`, 5 cases,
all passing):
1. A new name variant at an already-known location merges into the
   established name (the exact McDonald's/McDonalds AU scenario).
2. A genuinely distant, unrelated restaurant is never merged.
3. Two different real restaurants close together (a plaza/food court)
   stay separate -- confirms the tight 25m radius does its job.
4. A pre-existing row under a fragmented name is never rewritten or
   migrated.
5. Multiple existing name variants near one spot merge into whichever
   has the most samples, not an arbitrary one.

`python3 -m py_compile drive_monitor.py` clean. Full existing scratchpad
suite re-run: no regressions (only the known, pre-existing, unrelated
`test_dropoff_instruction_wiring.py` failure and the API-key-gated
`test_parking_v4alpha.py` script).
