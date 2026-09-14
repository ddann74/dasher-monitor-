# PRD: Reject physically implausible GPS jumps before they corrupt trip distance

Status: IMPLEMENTED (2026-09-14), scouting-pass finding (round 3).

## 1. What was wrong

`TripManager._process_point_during_trip` accumulated `_cumulative_distance_km`
from every GPS tick with zero plausibility check -- no accuracy
filtering on the Java side, no implied-speed sanity check, no cap.

Confirmed via a real end-to-end run of the actual engine: a single bad
GPS fix (multipath, a cold-start GPS error, any momentary wild jump --
a real, common GPS failure mode, not a hypothetical) landing thousands
of km from the true location, one second after a normal fix, was added
to the trip's real distance as if it were genuine movement. The NEXT
real fix, back near the true location, added the same huge distance
again (haversine distance is symmetric). In the reproduction: a real
trip's `distance_km` went from 0 to ~16,000km after the bad fix, then
~32,000km after the very next real fix -- permanently written into
that trip's `trips` row, and from there into `fuel_cost_estimate` and
`composite_score` shown in Trip History and the Full Report, with no
correction path.

## 2. Design

`_process_point_during_trip` now computes the implied speed between
the new fix and the LAST GOOD fix (`distance / dt`) before trusting it.
If that implied speed exceeds `GPS_JUMP_MAX_PLAUSIBLE_SPEED_KMH`
(250 km/h -- a generous, physically-grounded ceiling well above any
real highway speed, chosen specifically to never reject genuine fast
driving, only a fix that's physically impossible to be real), the
point is rejected ENTIRELY: not added to distance, not appended to
`gps_points`, and not run through harsh-event/major-delay detection
either (a fix bad enough to distrust for distance is bad enough to
distrust for everything derived from it). Rejecting it outright, rather
than just skipping the distance addition, also means the comparison
for the NEXT tick correctly stays anchored to the last known-good
point -- exactly what fixes the "next real fix adds the huge distance
again" half of the bug.

The check is skipped when `dt <= 0` (a duplicate or out-of-order
timestamp) -- that case can't produce a meaningful implied speed
either way, and was never guarded before this fix; not the scenario
this targets, and not worth inventing new handling for a case that
wasn't part of the actual bug.

A rejected fix logs a diagnostic line (`GPS_JUMP` category, mirroring
the existing `gap_sample_log`/`phase_capture_log` consumption pattern
from `TripManager` through `DriveMonitorEngine.on_gps_update` to Java)
rather than raising an alert -- this is the fix self-correcting exactly
as designed, not something the driver needs to act on.

## 3. Verification

- Real, executable Python test (real `DriveMonitorEngine`/`TripManager`,
  not reimplemented logic): reproduced the exact bug scenario the
  scouting pass found (a real trip start, a bad fix 1 second later
  thousands of km away, then a real fix back near the true location) --
  confirmed the bad fix's distance is now rejected (stays near 0, not
  ~16,000km), confirmed the diagnostic log is surfaced, and confirmed
  the next real fix only adds a tiny, real distance (not ~32,000km).
  Also confirmed genuine fast highway driving (~90 km/h) is NOT
  rejected (no false positives), and confirmed a duplicate/non-positive
  `dt` doesn't crash. 6 checks, all passed.
- `python3 -m py_compile` clean; brace/paren balance confirmed on
  `TripForegroundService.java`.

## 4. Honest limits

- 250 km/h is a judgment call, not derived from any real GPS-error
  dataset -- same honesty status as this file's other tuned
  thresholds. It's deliberately generous (a real highway speed
  violation could plausibly approach but not reach it), so this
  specifically catches only fixes that are physically impossible, not
  merely unusually fast.
- This only protects distance/harsh-event/delay detection derived from
  `gps_points`. It does NOT filter the raw `lat`/`lon` used directly by
  other parts of `on_gps_update` in the same tick (stop/pickup arrival
  matching, the approaching-stop check) -- a bad fix could still
  theoretically affect those in the same tick it's rejected for
  distance purposes. Not addressed here; scoped to the specific,
  confirmed distance-corruption bug, not a general "distrust this fix
  everywhere" rewrite.
- No Android device/emulator available in this environment -- real GPS
  hardware's actual error characteristics (how often a fix this bad
  really happens, whether 250 km/h is the right ceiling in practice)
  are unobserved.

## 5. Success criteria

- [x] A physically implausible GPS jump is rejected before being added
      to a trip's real, permanent distance
- [x] The rejection anchors the NEXT tick's comparison to the last
      known-good point, not the rejected one -- fixes both halves of
      the original bug (the bad fix itself, and the "next real fix
      double-counts it" follow-on)
- [x] Genuine fast highway driving is NOT rejected (no false positives)
- [x] A duplicate/non-positive `dt` doesn't crash
- [x] Rejected fixes are logged (self-correcting), not alerted
      (nothing for the driver to act on)
- [x] Real executable Python test (6 checks, reproducing the actual
      scouting-pass finding) fully passed
- [x] `python3 -m py_compile` clean; brace/paren balance confirmed
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver confirms in real use (or via a future diagnostic log) that
      a real GPS glitch no longer corrupts trip distance, and that
      normal fast driving is never incorrectly flagged.
- [ ] Driver sign-off.
