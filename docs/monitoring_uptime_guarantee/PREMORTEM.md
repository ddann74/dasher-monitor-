# Premortem: Monitoring Uptime Guarantee

Status: LIVING RISK REGISTER (created 2026-09-15, last updated
2026-09-16). R1-R7 closed the loop's originally-known Open items. Round
13's required verification pass (per the parent PRD's §4 acceptance
criteria) found 3 new items (R20-R22). Round 14 found one more (R23),
auditing round 13's own R21 fix. Round 15, hunting for the same pattern
R21/R23 revealed (a same-process restart not re-establishing state a
fresh process gets "for free"), found it again in a different subsystem
-- reopening and re-fixing R4 (a wake-lock guard too narrow to self-heal
after a routine auto-pause). All currently Mitigated or Ruled out.
Companion to `docs/monitoring_uptime_guarantee/PRD.md`. Per that PRD,
this is inherently a moving target, not a one-time finish line -- every
round so far has found something new, and round 15 additionally showed
a fixed item can be worth re-opening under a related pattern, not just
auditing brand-new code.
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

### R3 — [Mitigated] System Location toggle being off entirely is never checked

Not a permission issue -- `ACCESS_FINE_LOCATION` can show granted while
the device's Location services toggle is off system-wide. No
`LocationManager.isProviderEnabled()`/`PROVIDERS_CHANGED` usage existed
anywhere in the app. A driver in this state got the same generic
"monitoring may have stopped" alert as every other staleness cause,
instead of a specific, actionable one. Found by round 9's scouting pass.

**Fixed:** `docs/location_services_toggle_check/PRD.md` --
`checkAndLogPermissions` now checks `LocationManagerCompat.isLocationEnabled()`
on the same heartbeat cadence as every other critical permission, and
raises a specific, deep-linked alert (distinct from the `ACCESS_FINE_LOCATION`
grant alert) on a genuine drop or an already-off-at-start.

### R4 — [Mitigated, re-fixed round 15] `tripWakeLock` is never re-verified during an active trip

Was acquired once on the `TRIP_ACTIVE` state-entry edge, never checked
(`isHeld()`) again until the trip ended or a 90-minute safety timeout
expired. Unlike every other monitored resource (permissions, recording,
notification listener), there was no periodic health check for this one.
An early release (a documented real edge case on some OEM skins) would
silently degrade GPS tracking with no detection. Found by round 9.

**Fixed (round 9):** `docs/trip_wakelock_reverify/PRD.md` --
`verifyTripWakeLock()` now runs on the same heartbeat cadence as every
other periodic check in `TripForegroundService`, and transparently
re-acquires the wake lock (a safe, idempotent self-heal) if it's found
unheld during an active trip, with a diagnostic log entry so field logs
show exactly when/how often this fires.

**Reopened and re-fixed (round 15):** that fix's own guard required
`tripWakeLock != null`, narrower than what `acquireTripWakeLock()`
actually supports (it's explicitly null-safe). A routine Dash-Paused
auto-pause stop calls `releaseTripWakeLock()` unconditionally (setting
the field to `null`) even when the trip deliberately stays `TRIP_ACTIVE`
through the pause (a DASHER trip with a pending dropoff, per
`force_end_trip`'s own `allow_mid_delivery_end=false` guard) --
`lastKnownTripState` never transitions in that case, so the normal
acquire-on-transition path never re-fires either, permanently defeating
the self-heal through this specific, routine restart path. Same
architectural shape as R21/R23 (a same-process restart not
re-establishing state a fresh process gets "for free"), in a different
subsystem. **Fixed:** `docs/trip_wakelock_reverify_null_guard/PRD.md` --
widened the guard to `tripWakeLock == null || !tripWakeLock.isHeld()`.

### R5 — [Mitigated] Battery-optimization-exemption loss is tracked but never alerted

`hasBatteryExemption` was computed every heartbeat alongside the other 3
critical permissions, but unlike them, its true->false transition never
triggered `raisePermissionRevokedAlert`. Re-granting it can't be
automated (a real Android restriction), but detecting and alerting the
loss follows the same already-established, safe pattern used for
accessibility's deep-link alert. Found by round 9.

**Fixed:** `docs/battery_exemption_revoked_alert/PRD.md` -- a mid-session
transition block identical in shape to the other 3 critical permissions
now fires `raisePermissionRevokedAlert`, deep-linking to
`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (mirroring `PermissionsActivity`'s
own re-grant button exactly). Deliberately scoped to the mid-session
transition only, not an already-off-at-start alert -- see that PRD's
Honest Limits.

### R6 — [Mitigated] Tutorial/Developer-Testing simulation threads race the shared engine singleton

`TutorialActivity.showStepDriving()` and
`DeveloperTestingActivity.simulateDriveAndArrival()` spawn raw threads
calling into the same process-wide `DriveMonitorEngine`/`TripManager`
singleton, with their `TripForegroundService.isRunning` guard checked
only once before the thread spawned -- nothing cancelled them if the
screen was backgrounded mid-simulation and real monitoring started.
Found by round 8's scouting pass.

**Fixed:** `docs/simulation_thread_live_monitoring_race/PRD.md` -- both
threads now re-check `isRunning` on every loop iteration and stop making
any further engine calls the moment it flips true. Also fixed a worse
bug this uncovered: `DeveloperTestingActivity`'s cleanup `finally` block
unconditionally called `force_end_trip()`, which would have silently
ended a genuinely real trip in exactly this scenario -- now gated on the
same interruption flag.

### R7 — [Mitigated] GPS-reacquire fires as a no-op guess when the real cause is an engine/DB failure

When the watchdog found `isRunning=true` but the heartbeat stale, it
unconditionally sent `ACTION_REACQUIRE_LOCATION` -- even when the real
cause was `writeWatchdogHeartbeatIfEngineHealthy` deliberately
withholding the heartbeat because `consecutiveEngineFailures >= 3` (an
engine/DB problem, not a GPS problem). Harmless but pointless in that
case, since the driver was still separately alerted via
`raiseEngineFailureAlert`. Found by round 11's scouting pass. Lowest
priority in this register -- the driver was never left uninformed by
this gap, only some wasted churn occurred.

**Fixed:** `docs/watchdog_engine_failure_aware_reacquire/PRD.md` -- a new
`SharedPreferences` flag (`KEY_ENGINE_FAILURE_ACTIVE`), written in the
same file/lifecycle as the existing heartbeat timestamp, lets
`MonitoringWatchdogReceiver` tell the two causes apart and skip the
pointless reacquire when the engine/DB is the real cause, while a
genuine GPS stall still correctly triggers the reacquire as before.

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

### R20 — [Mitigated] `BootAndUpdateReceiver`'s auto-resume bypassed the restart circuit breaker, and could post a false "resumed" notification

Found by round 13's verification scouting pass (run after R1-R7 closed,
per this PRD's own acceptance criteria). Every other background
auto-start path already checked the restart circuit breaker before
attempting a restart; `BootAndUpdateReceiver`'s own reboot/update
auto-resume was the one left out, and would keep re-attempting an
identical doomed restart on every reboot. Its `notifyResumed()` call was
also unconditional right after `startForegroundService()`'s async
dispatch returned without throwing -- which does not mean the restart
actually succeeded -- so a genuine downstream failure could produce both
the correct failure alert AND a false "resumed" notification.

**Fixed:** `docs/boot_resume_circuit_breaker_and_false_notification/PRD.md`
-- added the same circuit-breaker check every other path already has,
and replaced the unconditional notification with a `goAsync()` + short
delayed re-check (mirrors `DasherAccessibilityService`'s own established
pattern) that only posts "resumed" once genuinely confirmed.

### R21 — [Mitigated] `KEY_ENGINE_FAILURE_ACTIVE` (this session's own R7 flag) could stay stuck `true` across a session boundary

Found by round 13's verification pass, auditing R7's own fix for
regressions. The flag was only reset by a genuine heartbeat write, which
requires a GPS callback plus a full heartbeat interval to have already
elapsed. A session ending while the engine was genuinely failing left
the flag `true` in `SharedPreferences`; the next session inherited that
stale value, and if IT then hit a genuine GPS stall before its own first
successful heartbeat, the watchdog would wrongly attribute the new stall
to the old, already-resolved failure and skip the real
`ACTION_REACQUIRE_LOCATION` self-heal. Bounded impact -- the primary
staleness alert still fired either way, so the driver was never left
with zero signal; only the auto-recovery action R7 exists to gate
correctly would be skipped for the wrong reason.

**Fixed:** `docs/watchdog_engine_failure_flag_session_reset/PRD.md` --
the flag is now explicitly reset to `false` at session start (inside
`startTracking()`'s success path, right after `recordRestartSuccess()`),
the same point `consecutiveEngineFailures` itself (the in-memory
trigger) already implicitly resets to 0 via a fresh
`TripForegroundService` instance.

### R22 — [Mitigated] Notification ID 9199 sat unreserved inside the hash-auto-assigned 9100-9199 band

Found by round 13's verification pass. `raiseDasherPackageNotFoundAlert`
used a hardcoded `9199` since `docs/dasher_package_verification/PRD.md`
(2026-09-14) -- predating round 11's notification-ID collision audit,
which documented 9100-9199 as belonging entirely to
`raisePermissionRevokedAlert`'s hash-based scheme without accounting for
this prior claim. No permission name currently hashes to 9199 (verified
by direct computation against all 8 in-use `permissionName` strings), so
there was no ACTIVE collision, but nothing prevented a future or renamed
permission from silently colliding with it.

**Fixed:** `docs/notification_id_9199_reservation/PRD.md` -- moved
`raiseDasherPackageNotFoundAlert` to a new dedicated constant,
`DASHER_PACKAGE_NOT_FOUND_NOTIFICATION_ID = 9230`, genuinely disjoint
from the hash-reserved band and every other fixed ID/range in the app,
and updated `docs/notification_id_collision_audit/PRD.md`'s own
namespace table with the correction. This is the third time this exact
class of bug has occurred (rounds 10, 11, 13) -- that PRD's own
follow-up suggestion (a shared, compile-time-enforced ID registry) is
now worth genuinely considering rather than continuing to rely on manual
audits alone.

### R23 — [Mitigated] `consecutiveEngineFailures`/`engineFailureAlertRaised` never reset on a same-process restart, silently downgrading the specific engine-failure alert

Found by round 14's scouting pass, auditing R21's own fix. R21 reset the
durable `KEY_ENGINE_FAILURE_ACTIVE` flag at `startTracking()`'s success
point on the assumption that `consecutiveEngineFailures` itself
"already implicitly resets to 0 for every fresh `TripForegroundService`
instance" -- true only for a process-death restart, but
`TripForegroundService` deliberately keeps the same instance alive
across a stop→restart cycle, and that same-instance path
(`DasherAccessibilityService`'s routine Dash-Paused/Dash-Resumed
auto-pause, and the manual Stop/Start toggle) is the *more common*
restart path in real usage. If the engine/DB problem that originally
tripped the 3-failure threshold was still happening after such a
restart, `engineFailureAlertRaised` stayed latched `true` forever,
silently downgrading the specific "Delivery tracking error" alert
(designed to fire once per failure streak) into a
fires-once-per-process-lifetime signal for the rest of the shift. Never
a silent-staleness violation (the generic watchdog alert still covered
the driver either way), but a real degradation of the specific
diagnostic signal.

**Fixed:** `docs/engine_failure_counters_restart_reset/PRD.md` -- both
in-memory fields are now reset alongside `KEY_ENGINE_FAILURE_ACTIVE` at
the same `startTracking()` success point, regardless of which restart
path triggered it.

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
