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
