# PRD: Trip List / Trip Detail crash on every open (theme/MaterialCardView mismatch)

Status: IMPLEMENTED (2026-09-13), found from a real diagnostic-log export
the driver uploaded (`dasher_monitor_full_history-21.txt`) -- the first
real, on-device evidence available in this entire session's work
(everything before this was verified by code reading only, no device
ever available). Critical severity: this crash made two core screens
completely unusable, confirmed happening on the driver's real device.

## 0. What the log showed

8 separate crash entries, all dated 2026-09-13 (the day this was found),
spread across roughly 5 hours of real use:

```
[2026-09-13 13:17:19] CRASH: ... Unable to start activity ...TripListActivity:
  android.view.InflateException: ... Error inflating class
  com.google.android.material.card.MaterialCardView
...
Caused by: java.lang.IllegalArgumentException: The style on this component
  requires your app theme to be Theme.MaterialComponents (or a descendant).
```

4 crashes in `TripListActivity`, 4 in `TripDetailActivity` -- every single
attempt to open either screen that day crashed, 100% reproducible. Both
are core features (Trip History browsing and each trip's own detail
view) -- this wasn't a rare edge case, it was these two screens being
entirely broken.

## 1. Root cause

`activity_trip_list.xml` and `activity_trip_detail.xml` both wrap their
content in a `<com.google.android.material.card.MaterialCardView
style="@style/Widget.MaterialComponents.CardView" ...>`. Material
Components widgets styled with an explicit `Widget.MaterialComponents.*`
style perform a runtime check (`ThemeEnforcement.checkMaterialTheme`)
that the hosting Activity's theme itself descends from
`Theme.MaterialComponents` -- and throws `IllegalArgumentException`
immediately if it doesn't.

Both activities are themed `Theme.DasherMonitor.TripHistory`
(`AndroidManifest.xml`). Every theme in `themes.xml`, including this
one (via the implicit dot-name-inherited parent, `Theme.DasherMonitor`),
only ever descended from `Theme.AppCompat.DayNight` -- never
`Theme.MaterialComponents`. `com.google.android.material:material:1.12.0`
IS a real build dependency (confirmed in `app/build.gradle`), so this
compiled fine; the mismatch only surfaces at runtime, exactly matching
why this shipped without being caught by any build-time check.

## 2. Fix

Gave `Theme.DasherMonitor.TripHistory` an explicit
`parent="Theme.MaterialComponents.DayNight"`. This intentionally
REPLACES (not adds to) the usual implicit dot-name inheritance from
`Theme.DasherMonitor` -- confirmed this is safe specifically because
`Theme.DasherMonitor` only ever defines `colorPrimary`/
`colorPrimaryDark`/`colorAccent`, and `.TripHistory` already redeclares
its own (teal) values for exactly those three items, so nothing is
actually lost by no longer implicitly inheriting from it.
`Theme.MaterialComponents.DayNight` is a documented, Google-supported
drop-in superset of `Theme.AppCompat.DayNight` -- every AppCompat
attribute this app already relies on elsewhere still resolves, plus
what `MaterialCardView`'s style actually needs.

Scoped to ONLY this one theme, not the shared base `Theme.DasherMonitor`
-- confirmed via grep that `MaterialCardView` (or any other
`com.google.android.material` widget) is used in exactly these two
layout files and nowhere else in the app, so the other 5 themes
(`Permissions`, `DataManagement`, `Diagnostics`, `DeveloperTesting`,
`TrustedContacts`) were never actually at risk of this same crash and
didn't need touching.

### 2.1 Fixed a self-inflicted XML syntax error while writing this

First attempt at the explanatory comment used " -- " (em-dash style,
this session's own prose convention) inside the XML comment body itself
-- XML comments cannot contain `--` anywhere in the body, the exact
same class of mistake this repo's own `accessibility_service_config.xml`
history already flagged once before. Caught by re-parsing the file with
a real XML parser before committing (not just assumed correct), same
discipline as every other XML edit in this repo. Fixed by switching to
single-hyphen punctuation, matching the convention already used
elsewhere in this file's sibling XML comments (e.g. `AndroidManifest.xml`).

## 3. Honest, disclosed, unverified risk

`Theme.DasherMonitor.TripHistory` is also applied to 4 OTHER activities
that were never crashing: `TripHistoryActivity`,
`LocationProfitabilityMapActivity`, `ParkingZoneMapActivity`,
`CustomerZoneMapActivity` (see `AndroidManifest.xml`). None use a
Material Components layout widget themselves (confirmed via the same
grep), so none were ever at risk of THIS specific crash -- but switching
the shared theme's parent can still change the DEFAULT look of plain
platform widgets (`Button`, `EditText`, etc.) those screens do use,
since `Theme.MaterialComponents` themes commonly apply their own default
styles for those. No Android device/emulator available in this
environment to confirm whether any of those 4 screens' visual appearance
actually shifts as a result -- flagged here plainly rather than silently
assumed away, since the crash fix itself doesn't depend on the answer
either way (the crash is fixed regardless).

## 4. Verification

- Traced the exact crash stack trace from the driver's real diagnostic
  log to the exact `MaterialCardView`/`Widget.MaterialComponents.CardView`
  usage in both layout files, and from there to the exact theme
  hierarchy in `themes.xml` -- root cause confirmed by following real
  evidence end to end, not guessed.
- Confirmed via grep that `MaterialCardView`/Material Components layout
  widgets appear in exactly `activity_trip_list.xml` and
  `activity_trip_detail.xml`, nowhere else in the app's layout files --
  the basis for scoping this fix to one theme instead of the shared base.
- Confirmed via grep which 6 activities share `Theme.DasherMonitor.TripHistory`
  in `AndroidManifest.xml`, and reasoned through the disclosed risk in
  ss3 for the 4 that weren't crashing.
- Re-parsed the edited `themes.xml` with a real XML parser (caught and
  fixed the `--`-in-comment mistake described in ss2.1 before this was
  ever committed).
- No Android device/emulator available in this environment (same
  disclosed limitation as every other Java/XML-side change in this
  repo) -- cannot confirm on-device that `TripListActivity`/
  `TripDetailActivity` now open successfully, only that the root cause
  (theme not descending from `Theme.MaterialComponents`) is now
  genuinely fixed by direct inspection of the resulting theme hierarchy.

## 5. Success criteria

- [x] Root cause traced from a real crash stack trace to the exact
      theme/widget mismatch, not guessed from a hypothesis
- [x] `Theme.DasherMonitor.TripHistory` now descends from
      `Theme.MaterialComponents.DayNight`, resolving the
      `ThemeEnforcement` check `MaterialCardView` performs
- [x] Confirmed no attribute is lost by replacing (not combining with)
      the implicit dot-name parent -- `.TripHistory` already redeclares
      everything `Theme.DasherMonitor` defines
- [x] Fix scoped to only the one theme actually needing it, not the
      shared base or the 5 unrelated themes
- [x] Disclosed, not silently assumed, the unverified visual-appearance
      risk for the 4 other screens sharing this theme
- [x] Re-parsed the edited XML with a real parser, catching a real
      self-inflicted syntax error before it could have been committed
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see ss4. Real on-device behavior (crash actually
      resolved, no visual regression on the 4 other TripHistory-themed
      screens) is unconfirmed.
- [ ] Driver confirms in real use: Trip List and Trip Detail both open
      without crashing, and the 4 other TripHistory-themed screens
      (Trip History, Location Profitability Map, Parking Zone Map,
      Customer Zone Map) still look correct.
- [ ] Driver sign-off.
