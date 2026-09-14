# PRD: Cache Places geocode and Distance Matrix results to cut redundant billable calls

Status: IMPLEMENTED (2026-09-14), scouting-pass finding (round 6, #3).

## 1. What was wrong

`GoogleApiHelper.java` had no caching or rate limiting anywhere. Two
real, unbounded cost multipliers:

- Every offer the screen-reader detects fires a fresh Places "Find
  Place From Text" geocode call, plus a Distance Matrix traffic call,
  with zero caching of previously-resolved restaurant coordinates
  (`DasherAccessibilityService.geocodePickupAndCheckTraffic`, called
  unconditionally for every newly-detected offer -- its own comment
  confirms "geocoding kicks off for EVERY offer shown, not just accepted
  ones"). A driver getting repeat offers from the same handful of
  restaurants throughout a shift (routine DoorDash behavior) re-geocoded
  the identical restaurant name every single time.
- `AppNotificationListenerService.geocodePickupForNotificationOnlyOffer`
  is an independent, explicitly-documented "focused duplicate" of that
  same pipeline, triggered from the notification path instead of
  screen-reading, with no de-dup against it -- so the exact same real
  offer could trigger two full geocode+traffic call pairs (one from the
  notification, one once the driver opens Dasher and the screen is
  parsed).

Both the Places API and Distance Matrix API are explicitly documented in
this same file's class comment as "paid beyond a monthly free credit."
During a busy period with rapid offer churn (offers appearing/expiring
every 10-30s is normal), there was no cap, cache, or minimum-interval
throttle of any kind.

## 2. Design

Add two small, in-memory, process-lifetime caches directly inside
`GoogleApiHelper`, at the single choke point both detection paths
already funnel through (`geocodeAddressInternal` for geocoding,
`getTrafficDelayRatio` for traffic) -- so both the "same restaurant
repeated all shift" and "notification path vs. screen path for the same
offer" cases are fixed by the same mechanism, with no changes needed in
either calling service:

- **Geocode cache**: `Map<String, CachedGeocode>` keyed by the trimmed,
  lowercased query text (the restaurant name both call sites already
  pass) -> `{lat, lon, formattedAddress, cachedAtMs}`. 24h TTL -- a
  physical restaurant's location essentially never moves, so this
  comfortably covers an entire shift's repeat offers and the
  notification-vs-screen double-detection (seconds apart, in practice),
  while still self-healing within a day if Google's data for that place
  changes.
- **Traffic cache**: `Map<String, CachedTraffic>` keyed by
  `"lat,lon->lat,lon"` rounded to 5 decimal places (~1.1m), so two
  `lastKnownLat`/`lastKnownLon` reads a few seconds apart for a driver
  who hasn't actually moved still collapse to the same key. 3-minute
  TTL, deliberately much shorter than the geocode cache -- traffic
  genuinely changes over time; this only exists to collapse the
  notification-path/screen-path double-query for literally the same
  route moments apart, not to treat live traffic as static data.
- On a cache hit within TTL, the result is posted to the callback
  exactly as the network path would (same `MAIN_HANDLER.post`), so
  every downstream call site (`record_pickup_location`,
  `update_pickup_coordinates`, `update_pickup_address`,
  `record_live_traffic_delay`) is unaffected -- indistinguishable from a
  fresh network result to any caller.
- Applies uniformly to all `geocodeAddressInternal` callers (pickup-name
  geocoding from both services, and dropoff-address geocoding), not just
  the two pickup call sites the finding named -- the same restaurant
  name or the same repeat-customer dropoff address benefits identically,
  and there was no reason to special-case it.

## 3. Verification

`GoogleApiHelper.java`'s core logic is difficult to exercise end-to-end
in this environment: it depends on live `android.content.Context`,
`Handler`/`Looper`, and a hardcoded `https://maps.googleapis.com` URL
with no seam to redirect to a local test server. Verified instead by:

- `python3`-based brace/paren balance check on the full file: both
  final depths 0 (balanced).
- A standalone, compiled-and-run Java program
  (`javac`/`java`, no Android dependencies) containing a **verbatim
  copy** of the two new key-building methods (`geocodeCacheKey`,
  `trafficCacheKey`, confirmed identical to the shipped file via `grep`
  immediately before running) plus the same TTL-comparison logic used
  in `geocodeAddressInternal`/`getTrafficDelayRatio`, exercising:
  - Two different-case/whitespace variants of the same restaurant name
    produce the same cache key and hit the cache.
  - A fresh cache entry is correctly treated as within TTL.
  - An entry older than the TTL is correctly treated as expired (not
    reused).
  - Two distinct restaurant names never collide.
  - Two GPS reads a sub-meter apart (jitter from a stationary driver)
    collapse to the same traffic cache key.
  - A materially different route produces a different traffic cache
    key.
  - The traffic TTL is meaningfully shorter than the geocode TTL.

  7 checks, all passed on first run.
- Manually traced both write sites (`GEOCODE_CACHE.put`/
  `TRAFFIC_CACHE.put`, immediately before each existing
  `MAIN_HANDLER.post` success path) and both read sites (immediately
  after the existing `hasApiKey` check, before spawning the network
  `Thread`) to confirm the cache is consulted before any network call
  and populated only on a genuine successful response -- a failed
  geocode/traffic call is never cached, so a transient failure doesn't
  poison future lookups.

## 4. Honest limits

- No Android device/emulator, and no live Google Maps Platform API key,
  available in this environment -- the actual network short-circuit
  (does a real second call for the same restaurant genuinely skip the
  HTTP round-trip) has not been observed end-to-end on a device, only
  verified by tracing the exact control flow and unit-testing the
  key/TTL algorithm in isolation (see §3).
- Same-named restaurant chain location in a DIFFERENT part of a
  multi-metro driver's operating area would incorrectly reuse the
  first-resolved location for up to 24h. Judged an acceptable, narrow
  tradeoff: a same-named chain a driver actually operates near in two
  distinct metro areas is a rare case, and the cost/impact of the
  unbounded-repeat-call problem this fixes is both more certain (real
  money on every single repeat offer) and more common than this edge
  case.
- The cache is purely in-process and unbounded in entry count (though
  bounded in practice by the number of distinct restaurant
  names/routes/addresses a driver's app process ever sees before being
  killed by Android) -- no eviction beyond TTL expiry-on-read. Not
  expected to be a real memory concern at this scale, but not proactively
  bounded either.
- Does not add any cross-process/persistent cache (e.g. surviving an app
  restart) -- purely an in-memory, current-process optimization. A
  restaurant re-geocoded after the app process is killed and restarted
  pays for one fresh call again, which is expected and fine.

## 5. Success criteria

- [x] Repeat geocode requests for the same restaurant name within 24h
      reuse the cached result instead of a fresh network call
- [x] The notification-triggered and screen-triggered paths for the same
      offer no longer both fire independent network calls (whichever
      resolves first populates the cache the other reads)
- [x] Repeat traffic queries for the same route within a few minutes
      reuse the cached result
- [x] A failed geocode/traffic call is never cached
- [x] Cache hits are indistinguishable to callers from a fresh network
      result (same callback/posting behavior)
- [x] `python3` brace/paren balance check clean
- [x] Standalone compiled Java test (7 checks) of the exact key/TTL
      algorithm, verified against the shipped source, fully passed
- [ ] HONEST LIMIT: no Android device/emulator or live API key available
      in this environment -- see §4.
- [ ] Driver confirms in real use (e.g. via Google Cloud Console API
      usage dashboard) that repeat-offer and dual-detection-path API
      call volume visibly drops.
- [ ] Driver sign-off.
