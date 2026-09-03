# Progress log — interactive tutorial mode

## Implemented (2026-09-03)

Driver answered §4's open question directly: Option B (varied
synthetic environments) + Option C (real address-book restaurants once
history exists), combined — not one or the other.

**Python**: new `DriveMonitorEngine.get_tutorial_environment(base_lat,
base_lon)` — picks a real restaurant from `pickup_location_history` at
random (with real learned wait/deadhead where available) if any exist,
else a random pick from a new 4-profile `TUTORIAL_SYNTHETIC_
ENVIRONMENTS` pool. `base_lat`/`base_lon` double as the simulated
drive's start point, so the destination (real or synthetic) is never
the same point as the start — avoids the geofence-already-satisfied
risk §4's own Option B flagged, by construction rather than by luck.
New `TripManager.discard_pending_pickup_and_stops()` (+ engine
wrapper) — a plain reset, deliberately not reusing `add_pickup`'s own
overwrite-persists-the-outgoing-job path, since a discarded tutorial
pickup was never real.

**Java**: new `TutorialActivity` — an 11-step, driver-paced walkthrough
(step counter, Next, Skip-any-time), entry button on `MainActivity`
(new "Tutorial" nav button, alongside the existing 6-category-screen
buttons but not one of them). Steps 1-6 build the fake offer and
progressively reveal its real `parse_offer_screen`-computed Smart
Score. Step 7 simulates "Accept" (real `add_pickup` call). Step 8
simulates driving (background thread, linear interpolation from start
to destination, then real arrival-detection ticks — mirrors
`DeveloperTestingActivity.simulateDriveAndArrival()`'s own proven
shape, generalized to variable coordinates). Steps 9-10 show the
navigation icon and approach-instruction overlay/voice — see the real
scope adjustment below. Step 11 wraps up and discards the simulated
state.

**Real scope adjustment found during implementation, disclosed not
silent**: originally planned to derive steps 9/10 from a live simulated
GPS stream through the same logic `TripForegroundService.
handleGpsResult` uses. Re-reading that method in full found it's a
large, side-effect-heavy state machine (mode/trip-state tracking, a
scoped WakeLock, the hotspot-or-home/sweet-spot check, and
`notifyRateThisDelivery()` — which brings MainActivity to the
foreground expecting a REAL trip ID). Replicating it faithfully from
`TutorialActivity` would be genuinely risky, and firing
`notifyRateThisDelivery()` against fake data would be a real, wrong
app-wide side effect. Instead, steps 9/10 call `OverlayHelper`/
`VoiceAnnouncer` DIRECTLY with illustrative content — real overlay/
voice code, the exact same methods a real delivery uses, just
triggered by the tutorial's own step sequence instead of derived from
a live GPS response chain. Matches this session's own established
preference (first used in the Profitability Map's marker taps) for
lower-risk, already-proven APIs over a less-certain full replication.

**Self-caught bug**: the new `AndroidManifest.xml` comment initially
used `--` as an em-dash inside an XML comment body — invalid XML, the
same mistake already self-caught once before in
`docs/location_profitability_map/PRD.md`. Caught by re-validating with
`xml.etree.ElementTree.parse()`, not trusting visual inspection.

**Verification**: same disclosed limitation as every Java-side PRD in
this repo — no Android SDK/emulator/device, code review plus
brace/paren balance (`TutorialActivity.java`: 44/44 braces, 213/213
parens; `MainActivity.java`: 151/151 braces, 698/698 parens). Real,
runnable Python tests: `test_tutorial_environment.py` (7 cases —
synthetic pool varies across calls with correct offset math; real
restaurant used once any pickup history exists, with real lat/lon and
learned wait/deadhead where available, sane defaults where not) and
`test_tutorial_discard_pickup.py` (3 cases — discard clears pickup and
stops, confirmed no `offer_distance_accuracy` row is ever persisted
for a discarded fake pickup, engine wrapper delegates correctly). All
touched XML re-validated as well-formed. Re-ran the full existing
scratchpad test suite — no regressions.

PRD.md §7 boxes checked except driver confirmation/sign-off.
