# PRD: Switch geocoding from the Geocoding API to Places "Find Place From Text"

Status: IMPLEMENTED (2026-09-13), found from a real diagnostic-log
export the driver uploaded (`dasher_monitor_full_history-21.txt`) --
direct follow-up to `docs/trip_history_theme_crash/PRD.md`, found in
the same log analysis session.

## 0. What the log showed

Real geocode results from a single day of use, NSW, Australia:

```
[2026-09-13 17:33:08] GEOCODE: Failed: Geocoding failed: ZERO_RESULTS       (Red Rooster)
[2026-09-13 18:01:25] GEOCODE: Failed: Geocoding failed: ZERO_RESULTS       (Dan Murphy's)
[2026-09-13 18:02:25] GEOCODE: Resolved Fat Boy Pide and Grill -> -34.4233494,150.8937443
[2026-09-13 18:29:28] GEOCODE: Failed: Geocoding failed: ZERO_RESULTS       (KFC)
[2026-09-13 18:50:41] GEOCODE: Failed: Geocoding failed: ZERO_RESULTS       (Liquorland)
[2026-09-13 18:56:27] GEOCODE: Resolved Bangkok Balcony -> 40.43792759999999,-79.92115539999999
[2026-09-13 19:22:05] GEOCODE: Resolved Woolworths Unanderra -> -34.4552778,150.8430556
[2026-09-13 19:24:06] GEOCODE: Resolved Rustic Thai Restaurant -> -34.4221228,150.893618
                       (D'Amato's Family Restaurant: no geocode entry at all -- separate
                        issue, the app process was killed by the OS right after this offer
                        was detected; see docs/trip_capture_health_alert's own OEM-kill
                        evidence in the same log, not something this PRD addresses)
```

Two real, distinct problems in that data:
1. **5 of 8 geocode attempts failed outright** (`ZERO_RESULTS`) -- all bare
   multi-location chain names (Red Rooster, Dan Murphy's, KFC, Liquorland).
2. **One resolved to the wrong CONTINENT.** `40.4379276,-79.9211554` is
   Pittsburgh, Pennsylvania, USA. Every other coordinate that day is in
   the Wollongong/Unanderra area of NSW (`-34.4x, 150.8x`). A later
   "Delivery Update" notification in the same log reveals the real
   restaurant's actual name: **"Bangkok balcony-Crown St"** -- DoorDash's
   own notification included a disambiguating street name that the
   app's own restaurant-name extraction (from an earlier, different
   notification) had already dropped by the time it reached geocoding.
   The very next `TRAFFIC: Query failed: Route not found: ZERO_RESULTS`
   line is the direct, cascading consequence -- of course no route
   exists from NSW to Pennsylvania. Worse: `PICKUP_PERSIST: record_
   pickup_location succeeded` shows this wrong coordinate was still
   saved into the driver's real `pickup_location_history` data.

## 1. Root cause

`GoogleApiHelper.geocodeAddressInternal` called the plain Geocoding API
(`maps.googleapis.com/maps/api/geocode/json?address=...`) with no
region or location biasing whatsoever. The Geocoding API is Google's
own documented endpoint for converting a **structured postal address**
into coordinates -- not for resolving a bare venue/business **name**
with no address, no suburb, no region hint at all. Given a name that
exists as multiple different real-world places (a common restaurant/
chain name, or an unrelated business elsewhere with the same or a
similar name), the Geocoding API has no signal to prefer a nearby
match over one on the other side of the planet, and can return either
`ZERO_RESULTS` or a confidently-wrong single result depending on what
else happens to share that name globally.

## 2. Fix

Switched to the Places API's **"Find Place From Text"** endpoint
(`maps.googleapis.com/maps/api/place/findplacefromtext/json`) --
Google's own purpose-built endpoint for resolving a venue/business
NAME (not just a structured address) to a real place, which natively
supports a `locationbias` parameter.

- `fields=geometry,formatted_address` requests only the "Basic Data"
  SKU tier (cheapest) -- exactly the two fields this app actually
  reads, matching what the old Geocoding API call already extracted
  (`results[0].geometry.location` + `results[0].formatted_address`,
  now `candidates[0].geometry.location` + `candidates[0].formatted_address`).
- New `PLACES_LOCATION_BIAS_RADIUS_METERS = 50_000` (50km) --
  `locationbias=circle:50000@<lat>,<lon>` when a bias location is
  provided. This is a soft PREFERENCE toward nearby candidates, not a
  hard restriction -- a genuinely correct distant match can still win,
  it just no longer wins by default against an equally-plausible
  nearby one the way an unbiased query did. UNCONFIRMED as the "right"
  radius in any rigorous sense -- a judgment call sized to a realistic
  driving/delivery area, same honesty status as this app's other tuned
  thresholds (`ZONE_SNAPSHOT_MIN_INTERVAL_SECONDS`, the 50,000-row
  history caps, etc.).
- Two new overloads added to both `geocodeAddress` and
  `geocodeAddressWithFormatted` (`..., double biasLat, double biasLon,
  callback`), alongside the original no-bias signatures (kept, not
  removed) -- existing callers that genuinely have no location context
  keep compiling unchanged.
- All 4 real (non-diagnostic) call sites now pass the driver's current
  GPS position when available, via the SAME established
  `TripForegroundService.hasValidLocation`/`lastKnownLat`/`lastKnownLon`
  static-field pattern already used elsewhere in this codebase (traffic
  checks, `DeveloperTestingActivity`, `TutorialActivity`) -- not new
  plumbing, reusing what already exists:
  - `AppNotificationListenerService.geocodePickupForNotificationOnlyOffer`
    (restaurant name, pickup via notification path)
  - `DasherAccessibilityService.handleDropoffScreen` (dropoff street
    address -- less ambiguous than a bare name, but still benefits from
    biasing when the same street name recurs across different towns)
  - `DasherAccessibilityService.geocodePickupAndCheckTraffic`
    (restaurant name, pickup via screen-reading path -- the exact
    method that produced the Pittsburgh result)
  - `PermissionsActivity`'s home-address save (driver-typed address,
    biased by current GPS at setup time as a reasonable proxy)
  - Falls back to the unbiased overload when `hasValidLocation` is
    false (no GPS fix yet this session) -- same behavior as before,
    not a new failure mode.
- `DiagnosticsActivity`'s "Test Google Maps Connection" call was left
  untouched -- it geocodes a fixed, already self-disambiguating test
  string ("Sydney Opera House, Sydney NSW"), where a location bias adds
  nothing.

### 2.1 Honest, disclosed, operational limit

This needs the **Places API** (not just Geocoding API) enabled on the
same Google Cloud project the driver's API key belongs to. An existing
key that only ever had Geocoding API turned on will need Places API
enabled too before this works -- cannot be verified from this
environment (no live key/project to test against). The existing
error-message plumbing (`error_message` from Google's own response,
already surfaced verbatim) will show the specific reason
(`REQUEST_DENIED -- This API project is not authorized to use this
API` or similar) if that's the case -- documented directly in
`GoogleApiHelper`'s own class comment so a future `REQUEST_DENIED` from
this exact endpoint isn't mistaken for something else.

## 3. Verification

- Traced every one of the 5 real call sites to `GoogleApiHelper`
  before changing anything, confirming for each whether the driver's
  current GPS position is realistically available there (all 4 real
  call sites use the same pre-existing `TripForegroundService` static
  fields already relied on elsewhere in this codebase) and whether the
  5th (Diagnostics' fixed test string) genuinely doesn't need it.
- **Real, compiled, executed Java test** (not just brace/paren balance
  -- a real JVM, a real `org.json` library fetched from Maven Central,
  the exact parsing code copied verbatim from `GoogleApiHelper`) run
  against: Google's own documented realistic Find Place From Text
  response shape (confirmed correct lat/lon/formatted_address
  extraction), a `ZERO_RESULTS` response (confirmed it correctly
  routes to the error path instead of crashing on `candidates[0]`), a
  `REQUEST_DENIED` response with `error_message` (confirmed the
  specific reason surfaces, not just the bare status), a candidate
  missing `formatted_address` entirely (confirmed the existing
  `optString(..., "")` fallback still holds under the new response
  shape), and the exact `locationbias` URL-fragment string construction
  (confirmed well-formed). All 5 passed.
- Brace/paren balance confirmed on all 4 modified files after every
  edit (`GoogleApiHelper.java`, `AppNotificationListenerService.java`,
  `DasherAccessibilityService.java`, `PermissionsActivity.java`).
- Confirmed via grep that every real call site was updated, the one
  intentionally-unchanged Diagnostics call site still compiles against
  the preserved original no-bias overload signature, and no stray
  reference to the old `results` (vs `candidates`) response key
  remains anywhere in the file.

## 4. Honest limits

- No Android device/emulator available in this environment (same
  disclosed limitation as every other Java-side change in this repo)
  -- the real HTTP request/response round-trip against Google's live
  Places API was never exercised end-to-end; only the exact JSON-
  parsing logic was verified, against a real, documented response
  shape, with a real JVM and a real JSON library.
- Does not fix the underlying cause of the missing "-Crown St"-style
  disambiguating context noted in ss0 -- the restaurant NAME extraction
  itself (which notification/parser produces the string handed to
  geocoding) is unchanged; this PRD only fixes what geocoding DOES
  with whatever name it's given. A future improvement could look at
  whether a later, more specific notification (like the "Delivery
  Update" one that revealed the real "-Crown St" name) could feed back
  into a re-geocode -- out of scope here.
- The 50km bias radius (ss2) is a reasoned judgment call, not measured
  against real driver behavior across different metro areas.
- Does not retroactively fix the bad Pittsburgh coordinate already
  saved to this driver's real `pickup_location_history` table from the
  incident in ss0 -- that's existing, already-stored data this PRD
  doesn't touch or clean up.

## 5. Success criteria

- [x] Switched from the Geocoding API to Places "Find Place From Text",
      the endpoint actually designed for resolving a venue/business
      name (not just a structured address)
- [x] All 4 real call sites now bias toward the driver's current GPS
      position when available, reusing the existing
      `TripForegroundService` static-field pattern, not new plumbing
- [x] Preserved the no-bias overloads unchanged for the one call site
      that genuinely doesn't need biasing (Diagnostics' fixed test
      address)
- [x] Real, compiled, executed test against Google's documented
      response shape, `ZERO_RESULTS`, `REQUEST_DENIED` with a specific
      reason, a missing-field candidate, and the bias URL construction
      -- all 5 passed
- [x] Brace/paren balance confirmed on every modified file
- [x] Disclosed, not hidden, the operational requirement that the
      Google Cloud project needs Places API enabled (may not already
      be, for an existing key that only had Geocoding API on)
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see ss4. The real network round-trip against
      Google's live API is unverified.
- [ ] Driver confirms in real use: "Bangkok balcony-Crown St" (or a
      similarly ambiguous name) now resolves to the correct nearby NSW
      location, and previously-`ZERO_RESULTS` chain names (Red Rooster,
      KFC, Liquorland, Dan Murphy's) now resolve successfully.
- [ ] Driver confirms the Google Cloud project's Places API is enabled
      (or enables it, if a `REQUEST_DENIED` error surfaces).
- [ ] Driver sign-off.
