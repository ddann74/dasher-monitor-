# PRD: Geocode cache key ignored location bias, reintroducing the wrong-city bug

Status: IMPLEMENTED (2026-09-15), scouting-pass finding (round 7, #3).

## 1. What was wrong

This session's own earlier commit (round 6, `fd2c9fe`) added an
in-memory geocode cache to `GoogleApiHelper.java` to cut redundant
Places API calls. Its cache key (`geocodeCacheKey`) was just
`address.trim().toLowerCase()` -- it never referenced `hasBias`,
`biasLat`, or `biasLon`, even though `geocodeAddressInternal` takes all
three and the cache lookup happened before they were considered at all.

This silently reintroduced the exact real-world failure this class's
own doc comment describes fixing: a bare restaurant name like "Bangkok
Balcony" resolving to the wrong city (Pittsburgh, PA instead of the real
Wollongong, NSW) without a GPS-position bias to disambiguate it. With
the unqualified cache key, whichever call for a given restaurant name
resolved and got cached FIRST -- biased or not, from whatever city --
was served unconditionally to every later call for that same name, even
one made with a different, correct bias, for up to the cache's 24h TTL.
A no-bias call (e.g. made before `TripForegroundService.hasValidLocation`
is true) and a later, correctly-biased call for the same name also
collided, since neither the bias flag nor the bias coordinates were part
of the key at all.

## 2. Design

Folded the bias into `geocodeCacheKey`, now
`geocodeCacheKey(address, hasBias, biasLat, biasLon)`:

- No bias -> `"<normalized address>|nobias"`, its own separate bucket.
- Biased -> `"<normalized address>|<biasLat rounded to 1 decimal
  degree>,<biasLon rounded to 1 decimal degree>"`. 1 decimal degree is
  roughly 11km at the equator -- comfortably finer than
  `PLACES_LOCATION_BIAS_RADIUS_METERS` (50km), so two genuinely
  different operating areas essentially never round into the same
  bucket, while normal GPS jitter/movement within the same metro area
  between offers still lands in the same bucket and benefits from the
  cache exactly as before.

Both the cache read (before the network call) and the cache write
(after a successful response) already shared a single `cacheKey` local
variable computed once per call -- only the key-construction call itself
needed updating; the write site required no separate change.

## 3. Verification

Same approach as the round-6 cache fix, for the same reason (Android
`Context`/network dependencies make the real class impractical to
compile/run outside a device/emulator): a standalone, compiled-and-run
Java program (`javac`/`java`, no Android dependencies) containing a
**verbatim copy** of the updated `geocodeCacheKey`, confirmed identical
to the shipped file via `grep` immediately before running:

- **The bug scenario**: a no-bias lookup and a biased lookup for the
  same restaurant name ("Bangkok Balcony") now produce DIFFERENT cache
  keys (previously identical).
- Biased lookups from two different real cities (Sydney vs. Pittsburgh,
  PA -- the exact incident this class's doc describes) produce
  DIFFERENT cache keys.
- Biased lookups from the SAME metro area (small GPS jitter, well under
  the 1-decimal-degree rounding grid) still produce the SAME cache key
  -- confirms the fix doesn't defeat the cache's purpose for normal use.
- Two no-bias lookups for the same name (different case/whitespace)
  still hit the same cache key -- unaffected by this fix.
- Distinct restaurant names with the same bias still produce distinct
  cache keys (regression check against the round-6 fix).

5 checks, all passed on first run. `python3`-based brace/paren balance
check on the full file: both final depths 0.

## 4. Honest limits

- No Android device/emulator, and no live Google Maps Platform API key,
  available in this environment -- the actual end-to-end behavior (a
  biased call after a no-bias call genuinely triggers a fresh network
  request instead of a stale cache hit) has not been observed on a
  device, only verified by tracing the control flow and unit-testing the
  key algorithm in isolation (see §3).
- The 1-decimal-degree rounding grid is a judgment call, not a derived
  constant -- same honesty status as this file's other tuned thresholds
  (e.g. `PLACES_LOCATION_BIAS_RADIUS_METERS`). A driver operating right
  at a grid-cell boundary between two calls could still see an
  unnecessary cache miss (extra network call, never a wrong result) or,
  in a genuinely pathological case, two bias points ~11km apart that
  round to the same cell despite being in different parts of one very
  large metro area -- judged an acceptable, narrow tradeoff, and
  strictly safer than the pre-fix behavior (no bias awareness at all).
- Does not change `PLACES_LOCATION_BIAS_RADIUS_METERS` or any other
  geocoding parameter -- purely a cache-key correctness fix.

## 5. Success criteria

- [x] A no-bias and a biased geocode call for the same restaurant name
      no longer share a cache entry
- [x] Biased calls from genuinely different regions no longer share a
      cache entry
- [x] Biased calls from the same metro area (normal GPS movement) still
      benefit from the cache
- [x] No-bias calls for the same name still benefit from the cache
      (unaffected)
- [x] Standalone compiled Java test (5 checks) of the exact key
      algorithm, verified against the shipped source, fully passed
- [x] `python3` brace/paren balance check clean
- [ ] HONEST LIMIT: no Android device/emulator or live API key available
      in this environment -- see §4.
- [ ] Driver confirms in real use that a restaurant name encountered in
      two different operating areas resolves correctly in each, not to
      whichever city was geocoded first.
- [ ] Driver sign-off.
