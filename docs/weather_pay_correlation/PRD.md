# Weather vs. pay correlation

STATUS: IMPLEMENTED (2026-09-06)

## 0. Origin

Driver asked: "Have the app take a screenshot of the willy weather app
every 30 mins to compare pay rate with weather."

## 1. Why the literal request wasn't built

Android's screen-capture API (MediaProjection -- the same mechanism
this app's own trip-capture feature already uses) can only capture
whatever is CURRENTLY on screen. There is no way to silently screenshot
a specific other app (WillyWeather) unless it already happens to be the
foreground app at that exact moment, which it essentially never would
be while driving/dashing. The only way to actually get that screenshot
would be to forcibly switch away from whatever the driver is using
(Dasher, a map) to WillyWeather and back, every 30 minutes, while
driving -- disruptive at best, unsafe at worst. Not built.

## 2. What was built instead

This app already has a real, free, live weather integration:
`WeatherHelper.java` calls Open-Meteo's API (BOM ACCESS-G model, ~AU
coverage, no key needed) using the driver's actual current GPS
position, and `SmartScoreEngine.record_live_weather` has been feeding
precipitation/wind/temperature into the Smart Score's weather factor
on every offer, cooldown-limited, for a while now. That data was
previously used once for the current offer's score, then discarded --
same gap already fixed for pickup locations
(`docs/location_profitability_map/`).

- `SmartScoreEngine._get_live_weather_snapshot()`: factored out of
  `_get_weather_score()` (same freshness gate, ≤15 min, unchanged
  behavior there) so a caller can get the raw precip/wind/temp values,
  not just the derived 0-100 score.
- `offer_outcomes` gained three nullable columns (`weather_precip_mm`,
  `weather_wind_kmh`, `weather_temp_c`), migrated via the existing
  `PRAGMA table_info` + `ALTER TABLE` pattern.
- `record_offer_outcome`/`record_offer_timeout` now persist a weather
  snapshot alongside each offer's own pay data -- the actual thing
  "compare pay rate with weather" needs, tied to a specific comparison
  point rather than an arbitrary 30-minute clock. NULL when no fresh
  live weather existed at that exact moment (no guessing).
- New `get_weather_pay_correlation()`: buckets every offer with a
  weather snapshot into "rain" (precip > 0mm, the same threshold
  `_get_weather_score` already treats as meaningful) vs. "no rain",
  reporting avg $/km, avg $/hr, avg Smart Score, avg wind, avg
  temperature per bucket. Gated on `WEATHER_CORRELATION_MIN_SAMPLES`
  (3) per bucket before presenting it as a real comparison. Uses ALL
  scored offers (accepted/declined/timed out), matching Pay Trend/
  Address Book/the profitability map's own population choice.
- New "Weather vs. Pay" button in Trip & History, mirroring the
  existing Pay Trend dialog's style.

Going forward only, same as `docs/restaurant_identity_merge/`: offers
recorded before this shipped have no weather columns and are simply
excluded, not backfilled or guessed.

## 3. Non-goals

- No new API, no new cost -- reuses the already-running, free Open-Meteo
  integration entirely.
- No screenshot, no WillyWeather dependency, no interruption to driving.
- Only a rain/no-rain split is built as the headline comparison
  (matching the driver's literal framing, "compare pay rate with
  weather rain vs not"); average wind/temperature are shown per bucket
  for extra context, but no separate temperature-banded or wind-banded
  breakdown was built -- a reasonable, disclosed follow-up if wanted.

## 4. Verification

Real, executable tests (`test_weather_pay_correlation.py`, 7 cases, all
passing):
1. Fresh live weather at record time is persisted alongside the offer.
2. No live weather ever recorded -> NULL columns, never guessed.
3. Stale live weather (past the 15-min freshness window) -> NULL, not
   a stale value silently reused.
4. The correlation report correctly buckets rain vs. no-rain with real,
   distinct $/km, $/hr, and Smart Score averages per bucket.
5. Below the minimum sample count in a bucket -> `has_enough_data` is
   correctly False, not a misleadingly early "real" comparison.
6. Test data (`is_test_data=1`) excluded from the report.
7. Migration: a real on-disk pre-existing database without the weather
   columns gets them added, existing rows default to NULL.

`python3 -m py_compile drive_monitor.py` clean. Full existing
scratchpad suite re-run: no regressions (only the known, pre-existing,
unrelated `test_dropoff_instruction_wiring.py` failure). Java-side
(`TripHistoryActivity.java`, the new button + layout + string):
brace/paren balance confirmed (164/164, 1081/1081); both touched XML
files re-validated as well-formed. No Android SDK/emulator in this
environment -- same disclosed verification limitation as every other
Java-side change in this repo.
