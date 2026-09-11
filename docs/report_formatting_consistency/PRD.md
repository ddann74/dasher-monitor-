# PRD: Report/data-display formatting consistency

Status: IN PROGRESS (2026-09-11), driver's own feature-audit finding --
not a specific driver-reported bug. Driver asked to fix all findings
"top to down" in the ranked order the scouting pass presented them.

## 0. What this is / isn't

A feature-audit scouting pass compared `String.format`/`DecimalFormat`
patterns for currency, rates, percentages, dates/times, and missing-
value placeholders across the app's real report/data-display surfaces
(Trip List, Trip Detail, Trip History dialogs, Address Book, Location
Profitability Map, Permissions screen, the Python-side full report
export, and the MainActivity feedback dialog). It found 8 places where
the same metric is formatted two different ways on two screens a driver
can reach one tap apart, with no evidence any of the differences were
intentional design choices -- each looks like independent code written
at different times rather than a deliberate distinction.

This PRD tracks each of the 8 fixes as its own numbered section, in the
exact ranked order the driver approved ("top to down"). Each section
gets a "Success criteria" checklist immediately after it, same
convention as the rest of this repo.

## 1. Distance precision mismatch (Trip List vs Trip Detail)

`TripListActivity.java:145` showed a trip's `distance_km` as
`"%.1f km · %s"`; tapping into `TripDetailActivity.java:270` for that
exact same trip's same `distance_km` field showed `"%.2f km"` -- e.g.
"4.2 km" on the list, "4.23 km" one tap later for the identical value.
No other screen in the app shows trip distance with 2 decimals; 1
decimal is the app-wide convention for a driver-facing km figure (the
deadhead-km lines in the same two files, and in `TripHistoryActivity`
report bodies, are all already `%.1f km`). Standardized Trip Detail to
`%.1f km` to match everywhere else, rather than the reverse, since 2
decimal places on a km distance reads as false precision to a driver
(GPS-derived distance isn't accurate to 10 meters) and every sibling
figure in the app already made that call.

Did NOT touch `TripHistoryActivity.java:117-118`'s
`"Avg error if delivery-only/total-trip: %.2f km"` lines -- those are a
distance-ACCURACY calibration report (comparing predicted vs. actual),
a genuinely different metric class from a trip's own distance, where
the extra decimal is more plausibly intentional (small calibration
errors are the whole point of that report). Out of scope for this
specific finding, which was about the same field shown two ways.

### 1.1 Success criteria

- [x] `TripDetailActivity.java`'s Distance row uses `%.1f km`, matching
      `TripListActivity.java`
- [x] Confirmed via grep that no other `%.2f km` trip-distance display
      exists elsewhere in the app (the two remaining `%.2f km` hits are
      the distance-ACCURACY report, a different metric, left untouched)
- [x] Brace/paren balance check on the modified file -- clean
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- verified by code reading only, not by tapping
      through the actual screens.
- [ ] Driver confirms in real use that Trip List and Trip Detail now
      show the same distance figure for the same trip.

## 2. Unrounded double in the Rejected Offers Report's per-factor comparison

`TripHistoryActivity.java` (Rejected Offers Report, per-factor
accepted/declined/timed-out comparison, ~line 673-677) printed
`String.valueOf(c.optDouble("avg_accepted"))` -- Java's default double
`toString()` -- right next to the `$%.2f`-formatted $/km lines a few
lines above in the same dialog (~line 659-665). The underlying Python
value (`get_rejected_offers_report`'s `avg()` helper,
`drive_monitor.py:5205-5206`) is already `round(x, 1)`, so the raw
number itself was fine, but `String.valueOf` on a double whose value
happens to be a whole number prints a trailing `.0` (e.g. "82.0")
while every other formatted figure in this app uses an explicit
`String.format`, and the two styles sitting a few lines apart in the
same dialog read as inconsistent/unpolished even though the actual
numbers were already correctly rounded server-side.

Wrapped all three (`avg_accepted`, `avg_declined`, `avg_timed_out`) in
`String.format(Locale.US, "%.1f", ...)`, matching the 1-decimal
precision Python already rounds these factor scores to, and matching
this app's established convention of never relying on a raw
`String.valueOf`/`toString()` for a driver-facing number.

### 2.1 Success criteria

- [x] All three per-factor comparison values use `String.format` with
      explicit `%.1f`, not `String.valueOf`
- [x] Precision (1 decimal) matches what Python's `avg()` helper
      already rounds to -- not inventing a new precision, just making
      the display explicit
- [x] `n/a` missing-value branches (already present) left untouched --
      that's item 6's scope, not this one
- [x] Brace/paren balance check on the modified file -- clean
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- verified by code reading only.
- [ ] Driver confirms in real use that this report's factor comparison
      no longer shows trailing ".0"-style raw doubles next to the
      formatted $/km lines above it.

## 3. Unify Smart Score display convention across screens

The live overlay badge (`DasherAccessibilityService.java:1334`,
`AppNotificationListenerService.java:797`, `TutorialActivity.java:226`)
and Trip Detail's offer-assessment card (`activity_trip_detail.xml`'s
static `/100` sibling `TextView` next to `offerScoreNumberText`) both
anchor the Smart Score with an explicit "/100" -- e.g. "82/100" -- so a
driver has a fixed scale to read the number against. Five other places
that show the exact same 0-100 metric omitted that anchor and showed
a bare number instead, reading like a different, unscaled figure:

- `TripHistoryActivity.java:336` -- Address Book, per-restaurant avg
- `TripHistoryActivity.java:425` -- restaurant visit history dialog
- `TripHistoryActivity.java:487,490,493` -- Accept/Decline Stats
  dialog, avg score by outcome (accepted/declined/timed out)
- `TripHistoryActivity.java:886` -- Weather vs. Pay correlation dialog
- `LocationProfitabilityMapActivity.java:157` -- map marker tap detail

All six added `/100` immediately after the score figure (before any
trailing stdev/label suffix already on that line), matching the live
badge and Trip Detail's convention exactly rather than inventing a new
one.

### 3.1 Success criteria

- [x] All 6 identified bare-number Smart Score displays now show
      `/100`, matching the live badge and Trip Detail
- [x] `/100` placed immediately after the score, before any existing
      stdev/label suffix on the same line, not appended at the end of
      the whole formatted string
- [x] Grepped the full app for every remaining `Smart Score`/
      `avg_smart_score` display site to confirm none were missed
- [x] Brace/paren balance check on both modified files -- clean
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- verified by code reading only.
- [ ] Driver confirms in real use that every Smart Score figure across
      the app now reads against the same "/100" scale.

## 4. Fuel-rate precision flip in the same ternary

`PermissionsActivity.java`'s `applyFuelCostSubtext()` (~line 822-829)
formats the exact same `effectiveRate` variable two different ways
depending on which branch of one ternary runs: `"$%.4f/km"` when the
driver has configured their own rate, `"$%.2f/km"` when showing the
flat default -- so saving a real rate made the displayed precision
get MORE precise instead of staying constant, even though it's
literally the same double variable either way. Every other $/km figure
app-wide (Trip History reports, Weather vs. Pay, Address Book, the
live offer badge, Trip Detail) uses 2 decimals; nothing else in the
app shows 4. Standardized the configured branch to `%.2f` to match.

### 4.1 Success criteria

- [x] Both branches of the ternary format `effectiveRate` as `%.2f`
- [x] Confirmed via grep that 2 decimals is the app-wide convention
      for every other $/km display, not an arbitrary choice
- [x] Brace/paren balance check on the modified file -- clean
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- verified by code reading only.
- [ ] Driver confirms in real use that the fuel-rate subtext no longer
      changes precision after configuring and saving a real rate.
