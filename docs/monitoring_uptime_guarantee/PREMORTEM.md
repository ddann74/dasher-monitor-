# Premortem: Monitoring Uptime Guarantee

Status: LIVING RISK REGISTER (created 2026-09-15, last updated
2026-09-15, Ralph-loop iteration 2: R1 and R2 closed). Companion to
`docs/monitoring_uptime_guarantee/PRD.md`.
Updated by every Ralph-loop iteration that closes or narrows a risk --
see that PRD's own acceptance criteria for when this register is
considered "done."

## The scenario

*It's been six months. A driver's monitoring silently failed for a full
8-hour shift. They lost every delivery's data -- no trip history, no
Smart Score learning, nothing -- and never got a single alert telling
them anything was wrong. Support pulls the diagnostic log and finds...
what?*

Working backward from that outcome, every plausible root-cause category
is listed below, each marked:

- **Mitigated** -- a specific fix closes this, cited with its PRD.
- **Open** -- a known, named gap. Not yet fixed.
- **Unknown** -- not yet audited to a confidence either way.

## Risk register

### R1 — [Mitigated] A trip-end DB hiccup after the trip is already saved duplicates its data on retry

A transient DB failure in `_merge_accel_samples_into_history()`'s own,
separate commit -- which runs AFTER `_persist_trip()` has already
durably saved the trip -- left `TripManager.state` stuck at
`TRIP_ACTIVE` (the exception escaped before `_end_trip`'s final
`self.state = self.STATE_IDLE`). The next GPS tick naturally retried
`_end_trip`, and since none of `_persist_trip`'s inserts (stops, events,
delays, messages) had an idempotency guard, the retry duplicated every
child row for a trip that was already correctly saved -- doubled
harsh-brake counts, doubled delay minutes, doubled customer messages,
visible in Trip History, Trip Detail, and every export.

Found by round 12's scouting pass, verified twice against the real
engine (failing `_persist_trip`'s own commit, and separately failing
only the unrelated accel-history commit after the trip was already
fully saved -- both produced duplicated rows).

This was the trip-END mirror of the trip-START bug round 11 already
fixed (`docs/start_trip_state_lie_on_failure/PRD.md`) -- same
architectural root cause (a state flag not atomic with respect to the
operation it describes), opposite end of the trip lifecycle, and a
different failure SHAPE (duplication instead of loss, because
`_persist_trip`'s own writes are NOT purely re-run from scratch the way
`_start_trip`'s were).

**Fixed:** `docs/trip_end_persistence_idempotency/PRD.md` -- `_persist_trip`
now checks `trips.end_time IS NOT NULL` before any writes and skips the
child-row inserts (and the `offer_distance_accuracy` insert) on a retry,
while still always re-running the naturally-idempotent `trips` UPDATE and
still always computing/returning `delivery_speed_event` (never received
by the caller from the failed first attempt).

### R2 — [Mitigated] `dropoff_arrival_ts` can be silently and permanently lost on a single-stop trip

Same root-cause family as R1: a scattered, ad-hoc mid-trip `UPDATE` +
`commit()` in `_evaluate_arrivals` (not deferred to `_persist_trip`)
could fail after `matched = True` was already set in memory, and since
that code path is gated on `not stop["matched"]`, it never ran again for
that stop -- the column stayed `NULL` forever with no retry. Lower
severity than R1 (one supplementary timing field, not core trip data).
Found by round 12.

**Fixed:** `docs/dropoff_arrival_ts_retry/PRD.md` -- `nearest["matched"]`/
`arrival_time` are now only set AFTER the DB write commits successfully,
so a failure leaves the stop unmatched and the next GPS tick naturally
retries the match+write (the write itself was already idempotent via its
`WHERE dropoff_arrival_ts IS NULL` guard).

### R3 — [Open, LOW] System Location toggle being off entirely is never checked

Not a permission issue -- `ACCESS_FINE_LOCATION` can show granted while
the device's Location services toggle is off system-wide. No
`LocationManager.isProviderEnabled()`/`PROVIDERS_CHANGED` usage exists
anywhere in the app. A driver in this state gets the same generic
"monitoring may have stopped" alert as every other staleness cause,
instead of a specific, actionable one. Found by round 9's scouting
pass, not yet fixed.

### R4 — [Open, LOW] `tripWakeLock` is never re-verified during an active trip

Acquired once on the `TRIP_ACTIVE` state-entry edge, never checked
(`isHeld()`) again until the trip ends or a 90-minute safety timeout
expires. Unlike every other monitored resource (permissions, recording,
notification listener), there's no periodic health check for this one.
An early release (a documented real edge case on some OEM skins) would
silently degrade GPS tracking with no detection. Found by round 9, not
yet fixed.

### R5 — [Open, LOW] Battery-optimization-exemption loss is tracked but never alerted

`hasBatteryExemption` is computed every heartbeat alongside the other 3
critical permissions, but unlike them, its true->false transition never
triggers `raisePermissionRevokedAlert`. Re-granting it can't be
automated (a real Android restriction), but detecting and alerting the
loss follows the same already-established, safe pattern used for
accessibility's deep-link alert. Found by round 9, not yet fixed.

### R6 — [Open, LOW] Tutorial/Developer-Testing simulation threads race the shared engine singleton

`TutorialActivity.showStepDriving()` and
`DeveloperTestingActivity.simulateDriveAndArrival()` spawn raw threads
calling into the same process-wide `DriveMonitorEngine`/`TripManager`
singleton with no lock, and nothing cancels them if the screen is
backgrounded mid-simulation. A simulated `add_pickup` call can silently
overwrite a real in-progress delivery's pickup data if the driver
backgrounds the Tutorial/DevTesting screen mid-simulation and opens the
real Dasher app. Found by round 8's scouting pass, not yet fixed.

### R7 — [Open, LOW] GPS-reacquire fires as a no-op guess when the real cause is an engine/DB failure

When the watchdog finds `isRunning=true` but the heartbeat stale, it
unconditionally sends `ACTION_REACQUIRE_LOCATION` -- even when the real
cause is `writeWatchdogHeartbeatIfEngineHealthy` deliberately
withholding the heartbeat because `consecutiveEngineFailures >= 3` (an
engine/DB problem, not a GPS problem). Harmless but pointless in that
case, since the driver is still separately alerted via
`raiseEngineFailureAlert`. Found by round 11's scouting pass, not yet
fixed. Lowest priority in this register -- the driver is never left
uninformed by this gap, only some wasted churn occurs.

### R8 — [Mitigated] Accessibility "granted" != accessibility "alive"

The Settings-permission-grant flag could stay true after a silent
OS/OEM kill of the live accessibility binding, with no independent
liveness signal. **Fixed:** `docs/accessibility_liveness_heartbeat/PRD.md`.

### R9 — [Mitigated] GPS heartbeat staleness had no auto-recovery, or a circuit breaker on the recovery it did get

Originally alert-only; then a restart was added with no protection
against looping on a doomed restart; then other auto-start paths bypassed
that protection. **Fixed, in stages:** `docs/watchdog_reliability/PRD.md`
(auto-restart added) -> `docs/watchdog_restart_circuit_breaker/PRD.md`
(loop protection) -> `docs/circuit_breaker_other_autostart_paths/PRD.md`
(closed for every other auto-start path too).

### R10 — [Mitigated] Watchdog heartbeat proved GPS liveness, not engine/DB liveness

A locked/corrupted sqlite3 connection could leave the whole
delivery-tracking pipeline dead while GPS ticks kept the heartbeat
looking healthy. **Fixed:** `docs/heartbeat_engine_health_gate/PRD.md`.

### R11 — [Mitigated] A dead-but-`isRunning=true` service had zero recovery path

The watchdog's restart branch required the service to be fully dead;
"alive but stalled" got an alert with no recovery attempt. **Fixed:**
`docs/watchdog_stalled_gps_reacquire/PRD.md`.

### R12 — [Mitigated] Alarms don't survive reboot, and the watchdog wasn't re-armed independent of restart success

**Fixed:** `docs/boot_watchdog_rearm/PRD.md`,
`docs/boot_resume_monitoring/PRD.md`.

### R13 — [Mitigated] A second, unguarded foreground-start call could crash the process right after the first guard "fixed" it

**Fixed:** `docs/starttracking_foreground_start_guard/PRD.md`.

### R14 — [Mitigated] Screen recording's "verified playable" check couldn't catch a blank/`FLAG_SECURE` recording, or a mid-session encoder error

**Fixed:** `docs/screen_recording_blank_content_check/PRD.md`,
`docs/screen_recording_liveness_check/PRD.md`.

### R15 — [Mitigated] The app never checked whether its own alerts could even reach the driver

**Fixed:** `docs/notification_visibility_check/PRD.md`.

### R16 — [Mitigated] Notification ID collisions could silently wipe an unrelated, still-relevant alert from the shade

Found and fixed twice (id 9300 in round 10, then a second,
unbounded-per-trip-ID class of the same bug plus a pre-existing 9200
clash in round 11). **Fixed:** `docs/boot_watchdog_rearm/PRD.md` (the
9300 fix, bundled with that round's main change),
`docs/notification_id_collision_audit/PRD.md`.

### R17 — [Mitigated] Trip-start state could lie "ACTIVE" for a trip that was never created

**Fixed:** `docs/start_trip_state_lie_on_failure/PRD.md`.

### R18 — [Ruled out, round 12 audit] Engine/`PythonBridge` singleton re-creation

Traced `PythonBridge.java` (static, synchronized `getEngine()`) and
`drive_monitor.py`'s module-level `get_engine()`/`_engine_instance`.
Every component goes through the same singleton accessor; nothing ever
constructs `DriveMonitorEngine` directly or resets the static field. No
plausible in-process re-creation path found. Closed without a code
change -- already solid.

### R19 — [Ruled out, round 12 audit] `dasher_app_foreground` mode-flag staleness on accessibility-service death

`onServiceConnected()` calls `checkCurrentForegroundWindow()`
immediately, and `foregroundCheckRunnable` re-runs it every 20s
independent of accessibility change events, correcting mode in BOTH
directions. Already closes the same staleness shape rounds 8-11
hardened for other flags. Closed without a code change -- already solid.

## How this register is used

Each Ralph-loop iteration (see the PRD's §"Acceptance" and the loop
mechanics driving this session's ongoing work):
1. Picks the highest-priority `Open` item (R1 first, by severity).
2. Scouts it narrowly if not already concretely specified (most items
   above already are, from prior rounds' findings).
3. Implements, tests (real repro where Python is involved, verified
   compiled-mock tests where Android APIs block full compilation), and
   documents the fix with its own `docs/<feature>/PRD.md`, following
   this session's established rhythm.
4. Updates this register: moves the item to `Mitigated` with a citation,
   or narrows its scope if only partially closed.
5. Commits, pushes, and reports back.

The register is "done" (per the parent PRD's acceptance criteria) when
every item above reads `Mitigated` or `Ruled out`, and a fresh scouting
pass finds nothing new.
