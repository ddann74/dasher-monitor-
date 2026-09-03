# Progress log - navigate to hotspot or home, based on shift rate

## Design + implementation (2026-09-03)

Driver asked mid-session for a feature to suggest driving to a hotspot
or heading home depending on how the shift is going, but flagged
directly: "I don't know how I want to calculate that rate." Asked
directly via a real multi-option question rather than guessing - three
concrete rate-calculation approaches, each with its real tradeoff
(rolling wall-clock $/hr, avg $/hr of recent accepted offers, offer
frequency). Driver picked the recommended option: average $/hr of the
last 3-5 accepted offers.

Investigated before writing anything: the trigger moment
(`TRIP_ACTIVE -> IDLE`), the hotspot destination logic, and the
tap-to-navigate icon pattern all already existed (driver backlog #5's
`get_recent_pickup_hotspot`, `check_show_return_to_sweet_spot`,
`OverlayHelper.showReturnToSweetSpotIcon`). Found one real gap
mid-design: `offer_outcomes` had never persisted the dollar `hourly_rate`
figure, only the 0-100 sub-score - confirmed by reading exactly what
`DasherAccessibilityService` passes into `record_offer_outcome` before
assuming it existed.

**Schema**: new `offer_outcomes.hourly_rate REAL` column + migration.
`record_offer_outcome`/`record_offer_timeout` gained a trailing
`hourly_rate=None` parameter - `DasherAccessibilityService` now
captures the live badge's own `perHr` value (already computed, just
never persisted) into a new `lastSeenHourlyRate` field, snapshotted the
same way `lastSeenComponentsJson` already is for the timeout-runnable
path. `DeveloperTestingActivity`'s 3 simulated-offer calls needed no
changes - the new parameter is trailing with a default, so shorter
existing calls keep working unchanged.

**Rate**: `RECENT_RATE_WINDOW = 5`, `get_recent_shift_rate()` - average
`hourly_rate` of the last 5 accepted, non-test offers with a recorded
rate.

**Settings**: new `ShiftRoutingPrefs` (SharedPreferences, same pattern
as `ScreenRecordingController`'s own prefs) - home address (geocoded
via the existing `GoogleApiHelper.geocodeAddress`, never keeping a
stale lat/lon paired with an address that failed to resolve) and a
driver-typed $/hr threshold. Feature stays fully inactive until BOTH
are set - no fabricated default for either. New "Navigate Home or to
Hotspot" section added to `PermissionsActivity`/`activity_permissions.xml`,
mirroring the existing Fuel Cost section's exact structure.

**Decision**: `check_show_hotspot_or_home_suggestion(lat, lon, home_lat,
home_lon, threshold)` - Java passes the SharedPreferences values in
directly rather than Python knowing about Java-side settings storage
(same separation the screen-recording toggle already uses). Rate at or
above threshold -> hotspot (recent hotspot preferred, falling back to
the all-history sweet spot); below -> home. Suppressed if already
within `UNFAMILIAR_AREA_THRESHOLD_KM` of the destination (reusing the
exact constant `check_show_return_to_sweet_spot` already uses for the
identical reasoning). Every non-suggestion path returns a specific
`reason`, never silently nothing.

**Java wiring**: at the exact same `TRIP_ACTIVE -> IDLE` trigger point,
branches on `ShiftRoutingPrefs.isConfigured()` - if true, calls the new
combined check; if false, calls the ORIGINAL `check_show_return_to_sweet_spot`
completely unchanged. A driver who never sets up home+threshold sees
zero behavior change from this feature existing. New
`OverlayHelper.showHotspotOrHomeIcon()` - a separate overlay slot from
`showReturnToSweetSpotIcon` (own static view field, so the two can
never collide), house emoji for home, the same fire emoji already used
in the Address Book's hotspot text for hotspot - deliberately NOT
reusing the sweet-spot icon's house emoji for a hotspot suggestion,
which would have been actively misleading.

**Self-caught bug during implementation**: first guessed
`GoogleApiHelper.GeocodeCallback`'s interface method names as
`onSuccess`/`onFailure` before actually reading the interface - the
real names are `onResult`/`onError`. Caught by reading the file
directly before finalizing the geocode callback, fixed before this
would have been a real compile error.

**Verification**: same disclosed limitation as every Java-side feature
in this repo - no Android SDK/emulator/device, code review plus static
checks for Java, a real runnable Python test for the decision logic.
`drive_monitor.py` recompiles cleanly. Brace/paren balance verified
across all 5 touched/new Java files (see PRD.md §6 for exact counts).
`test_hotspot_or_home.py` - 8 cases covering both destinations, both
"not configured" and "no data yet" states, and the already-close
suppression - all passed. XML re-validated as well-formed.

PRD §5 boxes checked except driver confirmation and sign-off - both
outstanding until reported back.
