# PRD: Widen the margin between deep-park GPS interval and the DASHER watchdog threshold

Status: IMPLEMENTED (2026-09-15), scouting-pass finding (round 8, #4,
directed audit of Dasher-mode monitoring fail-safety).

## 1. What was wrong

`MonitoringWatchdogReceiver`'s heartbeat (`KEY_LAST_HEARTBEAT_MS`) can
only update as often as a real GPS fix actually arrives --
`maybeLogHeartbeat` runs from `TripForegroundService`'s location
callback, not on any independent timer of its own. During
`TripForegroundService`'s deep-park GPS tier
(`GPS_INTERVAL_DEEP_PARK_MS = 30s`, active once a driver has been
stationary 5+ minutes or the screen is off -- exactly the shape of an
ordinary long restaurant wait), the heartbeat's real-world update cadence
is tied to that 30s nominal interval.

`ALERT_THRESHOLD_DASHER_MS` was `60s` -- only a 2x margin over that
nominal interval. This codebase's own class doc for
`MonitoringWatchdogReceiver` already cites a real, confirmed incident of
Android Doze delaying a *5-minute* nominal alarm interval by 17 minutes;
`requestLocationUpdates`'s interval is a *request*, not a guarantee, and
is well-documented to be delayed similarly under Doze on real devices,
especially on aggressive OEM skins this codebase already treats as a
confirmed risk class elsewhere. A single moderately-delayed GPS fix
during deep-park -- not even a fully dropped one -- could plausibly push
the gap since the last heartbeat past 60s, firing a spurious "monitoring
may have stopped" alert during a perfectly ordinary long wait. Round 8's
own finding named the real product risk this creates: a driver who gets
false alarms during every restaurant wait learns to ignore the
notification, including the time it's real.

## 2. Design

Widened `ALERT_THRESHOLD_DASHER_MS` from 60s to 120s -- a 4x margin over
the 30s nominal deep-park interval, matching the same margin philosophy
just applied to the accessibility liveness heartbeat's own threshold
(`docs/accessibility_liveness_heartbeat/PRD.md`, 90s over a 20s nominal
interval, ~4.5x). `ALERT_THRESHOLD_GENERAL_MS` (3 minutes) and
`WATCHDOG_INTERVAL_DASHER_MS` (45s, the alarm CHECK interval, already
documented as close to Android's realistic floor for exact alarms) were
left untouched -- the margin problem was specifically between the deep-
park GPS interval and the DASHER alert threshold, not the check
frequency or the GENERAL-mode threshold.

120s is still meaningfully faster than `ALERT_THRESHOLD_GENERAL_MS`
(180s), preserving the deliberate "detect faster in DASHER mode, since
losing untracked time there costs a real delivery" design intent this
file's own comments already establish -- just with a safer margin around
the specific interval that actually drives the heartbeat during the
condition most likely to also see real Doze delay.

## 3. Verification

`MonitoringWatchdogReceiver` depends on live `AlarmManager`/
`SharedPreferences`/`Context` and can't be compiled/run outside a device
or emulator in this environment. Verified instead by:

- `python3`-based brace/paren balance check on the edited file: final
  depth 0.
- A standalone, compiled-and-run Java program (`javac`/`java`)
  containing the exact shipped constants (`GPS_INTERVAL_DEEP_PARK_MS`,
  old and new `ALERT_THRESHOLD_DASHER_MS`, `ALERT_THRESHOLD_GENERAL_MS`
  -- all confirmed via `grep` immediately before writing the test) and
  the exact `staleness < alertThreshold` comparison from
  `onReceive()`:
  - **The bug scenario**: a realistic 90s gap (one on-schedule heartbeat
    followed by one Doze-delayed GPS fix, not a genuine failure) --
    confirmed the OLD 60s threshold WOULD have falsely alerted on it.
  - Confirmed the NEW 120s threshold does NOT falsely alert on that same
    realistic gap -- the false-alarm risk is closed.
  - Confirmed a genuine, multi-minute (5min) monitoring failure still
    correctly triggers the alert under the new threshold -- real
    detection is not weakened.
  - Confirmed the margin ratio: old was exactly 2x the nominal deep-park
    interval, new is exactly 4x.
  - Confirmed DASHER's new threshold (120s) is still faster than
    GENERAL's (180s) -- the mode-aware detection-speed intent survives
    this change.

  6 checks, all passed on first run.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  Doze-delayed-GPS-fix-during-deep-park scenario has not been observed
  on a device for this specific interval; the fix is built on this
  codebase's own already-cited real Doze-delay evidence (the 17-minute
  watchdog-alarm incident) applied by analogy to a different, related
  timer, not a fresh reproduction of Doze behavior on GPS specifically.
- 120s (4x) is a judgment call, not a derived constant -- same honesty
  status as this file's other tuned thresholds, and as the accessibility
  heartbeat's threshold this fix deliberately mirrors. It has not been
  validated against real-device Doze/OEM GPS-delivery timing data to
  confirm 4x is precisely the right margin (as opposed to, say, 3x or
  5x) -- chosen as a reasonable, disclosed improvement over the prior
  2x, not a definitively "correct" number.
- Does not change `GPS_INTERVAL_DEEP_PARK_MS` itself, or add any
  awareness of the deep-park state to the watchdog's own logic (e.g. a
  dynamically wider threshold specifically while deep-parked vs. a
  fixed one for all of DASHER mode) -- the simpler, single-constant
  change was judged sufficient and lower-risk than a more elaborate
  state-aware threshold, though the latter remains a theoretically
  tighter fix if 120s later proves imprecise in either direction on real
  devices.
- A genuine failure that happens to occur RIGHT as a driver enters deep-
  park will now take up to 120s (rather than 60s) to alert, in the worst
  case -- an accepted, disclosed tradeoff for reducing false alarms,
  consistent with this app's own stated priority (an ignored alert from
  habituation is worse than a slightly slower real one).

## 5. Success criteria

- [x] A realistic Doze-delayed (but non-failure) gap during deep-park no
      longer falsely triggers the DASHER monitoring alert
- [x] A genuine, multi-minute monitoring failure still correctly
      triggers the alert
- [x] The margin over the nominal deep-park interval widened from 2x to
      4x
- [x] DASHER mode's alert threshold remains faster than GENERAL mode's,
      preserving the existing mode-aware design intent
- [x] Standalone compiled Java test (6 checks) against the exact shipped
      constants and comparison logic, fully passed
- [x] `python3` brace/paren balance check clean
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver confirms in real use that long restaurant waits (deep-park)
      no longer produce spurious "monitoring may have stopped" alerts,
      while a genuine monitoring failure still alerts promptly.
- [ ] Driver sign-off.
