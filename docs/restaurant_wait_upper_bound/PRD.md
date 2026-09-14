# PRD: Cap implausible waits before they corrupt the learned average

Status: IMPLEMENTED (2026-09-14), scouting-pass finding (round 5).

## 1. What was wrong

`SmartScoreEngine.record_restaurant_wait` folds every measured
pickup wait into a plain running mean (`restaurant_wait_history`) with
no upper bound at all -- only `restaurant_name` blank and
`wait_minutes < 0` were ever rejected. Its sibling,
`record_delivery_speed` (just above it in the same class), already
rejects an implausible value (`speed_kmh > 150`) as "a sanity guard
against bad GPS data" -- the same protection never existed here.

GPS can't tell "waiting for the order" apart from "parked, taking a
break" (the method's own docstring already says so) -- a driver taking
a break while still inside the arrival geofence, or a phone going idle
for hours near a restaurant before GPS resumes and departure is
finally detected, produces one multi-hour `wait_minutes` sample with
no way to exclude it later. Verified directly: a real 5-sample average
of 8.0 minutes jumped to 66.7 minutes after a single 6-hour sample.
That corrupted average feeds directly into every future Smart Score
wait-time estimate for that restaurant and its Address Book display,
diluting only at a 1/N rate.

## 2. Design

New `MAX_PLAUSIBLE_RESTAURANT_WAIT_MINUTES = 120.0` -- a generous,
disclosed judgment call (same honesty status as
`GPS_JUMP_MAX_PLAUSIBLE_SPEED_KMH`, not derived from real wait-time
data), chosen specifically to never reject a genuinely long but real
wait (a big, complex, or backed-up order), only a multi-hour anomaly
implausible as an actual wait. `record_restaurant_wait`'s existing
guard now also rejects `wait_minutes > MAX_PLAUSIBLE_RESTAURANT_WAIT_MINUTES`,
returning `False` the same way the existing blank-name/negative-value
guard already does -- the existing caller-side logging
(`DriveMonitorEngine.on_gps_update`'s "Dropped pickup-wait event --
... invalid wait_minutes: {...}"`) already includes the actual
rejected value, so an implausibly large wait is diagnostically visible
without any Java-side change needed.

## 3. Verification

- Real, executable Python test (the actual `SmartScoreEngine` class,
  not reimplemented logic): built a realistic 5-sample average (8.0
  min), confirmed a 6-hour sample is now rejected (not recorded) and
  the average stays exactly unchanged afterward; confirmed a
  genuinely long but real 90-minute wait is still accepted (the cap
  isn't too tight); confirmed the exact boundary behavior (a wait
  exactly at the cap is accepted, just over it is rejected); confirmed
  the pre-existing blank-name/negative-value guards still work
  unchanged. 13 checks, all passed.
- `python3 -m py_compile` clean.

## 4. Honest limits

- 120 minutes is a judgment call, not derived from any real wait-time
  dataset -- same honesty status as this file's other tuned
  thresholds. A real wait longer than 2 hours (extremely rare, but not
  physically impossible the way the GPS-jump distances were) would
  also be rejected here; the tradeoff was chosen the same way as
  `record_delivery_speed`'s own 150 km/h cap -- generous enough that
  this is very unlikely to ever reject genuine data in practice.
- No Android device/emulator available in this environment -- real
  GPS/geofence "stuck inside the arrival radius" behavior that would
  trigger this in practice is unobserved.
- Any restaurant wait average already corrupted by an implausible
  sample before this fix shipped is NOT retroactively corrected -- this
  prevents new corruption going forward, it does not repair historical
  data.

## 5. Success criteria

- [x] A multi-hour implausible wait is rejected, not folded into the
      learned average
- [x] The average is verifiably unchanged after a rejected sample
- [x] A genuinely long but real wait (well under the cap) is still
      accepted
- [x] Exact boundary behavior confirmed (at-cap accepted, over-cap
      rejected)
- [x] Pre-existing guards (blank name, negative value) unaffected
- [x] Real executable Python test (13 checks) fully passed
- [x] `python3 -m py_compile` clean
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment, and no retroactive repair of pre-fix corruption --
      see §4.
- [ ] Driver confirms in real use that restaurant wait times in the
      Address Book stay realistic over time.
- [ ] Driver sign-off.
