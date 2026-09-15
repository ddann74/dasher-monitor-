# PRD: Monitoring Uptime Guarantee

Status: LIVING DOCUMENT (created 2026-09-15). This is not a new feature
-- it's the consolidated statement of the one goal underneath the ~20
individual fail-safety fixes made across rounds 8-12 of this session's
scouting passes, and the risk register that drives ongoing work toward
it. See `docs/monitoring_uptime_guarantee/PREMORTEM.md` for the
working-backward risk register this PRD's invariants are tested against.

## 1. The invariant

**At every moment monitoring is intended to be active, either it
genuinely is, or the driver has a way to find out within a bounded,
known time.**

Nothing in this app can guarantee monitoring never fails -- GPS pipelines
stall, database connections lock, Android kills processes, drivers
disable permissions. What CAN be guaranteed is that no failure mode is
**silent**: every way monitoring can stop working must either (a) be
detected and auto-corrected, or (b) be detected and surfaced to the
driver through a channel that doesn't itself depend on the thing that
broke.

This PRD exists because, before round 8, the app had real detection and
alerting machinery in several places, but each piece trusted a signal
that could itself be wrong without anyone noticing (a Settings
permission-grant flag standing in for live-binding health, GPS-tick
arrival standing in for engine/DB health, a bare object-reference
null-check standing in for a MediaRecorder actually still writing
frames, an unbounded per-trip integer standing in for a collision-free
notification ID, and -- found last, round 11 -- an engine state flag set
before the operation it was supposed to describe had actually
succeeded). Rounds 8-12 found and closed these one at a time. This
document is where that pattern gets named explicitly, so new code is
checked against it going forward instead of each gap being rediscovered
independently.

## 2. Subsystems and their current mechanisms

Each subsystem below participates in the invariant. For each, the
mechanism already in place is cited by its own PRD rather than
re-described here.

### Accessibility / Dasher detection
- Settings-permission-grant check (baseline, pre-existing).
- Real liveness heartbeat, independent of the Settings flag --
  `docs/accessibility_liveness_heartbeat/PRD.md`.
- The accessibility-down alert deep-links straight to the one fix that
  works (Android provides no self-heal API here) --
  `docs/accessibility_alert_deep_link/PRD.md`.
- Mode-flag (`dasher_app_foreground`) self-correction via a 20s
  independent recheck, in both directions -- confirmed already solid by
  round 12's audit, `DasherAccessibilityService.foregroundCheckRunnable`.
- Consolidated on-screen status: `docs/dasher_detection_status/PRD.md`.
- Package-identity verification: `docs/dasher_package_verification/PRD.md`.

### GPS / location tracking
- Three-tier polling with plausibility checks on GPS jumps (pre-existing,
  earlier rounds).
- Watchdog staleness detection + auto-restart, with a circuit breaker so
  a doomed restart (Android 14 FGS-location `SecurityException`) can't
  loop forever -- `docs/watchdog_reliability/PRD.md`,
  `docs/watchdog_restart_circuit_breaker/PRD.md`.
- The DASHER-mode alert margin over the deep-park GPS interval, widened
  to reduce false alarms -- `docs/watchdog_deep_park_margin/PRD.md`.
- A safe recovery action (re-register location updates) for the case the
  service is alive but GPS has silently stalled --
  `docs/watchdog_stalled_gps_reacquire/PRD.md`.
- Every background auto-start path (driving-detection, three
  accessibility-service triggers, the watchdog itself) now shares one
  circuit breaker instead of three independently retrying the same
  doomed restart -- `docs/circuit_breaker_other_autostart_paths/PRD.md`.
- The watchdog itself gets re-armed after a reboot even if the
  immediately-following restart attempt fails --
  `docs/boot_watchdog_rearm/PRD.md`, `docs/boot_resume_monitoring/PRD.md`.
- The foreground-service-start call is guarded against the same
  `SecurityException` at BOTH call sites (`onCreate()` and
  `startTracking()`), closing a crash that could happen right after the
  first guard was supposed to have already handled it --
  `docs/starttracking_foreground_start_guard/PRD.md`.
- Session continuity across a silent OEM restart --
  `docs/session_start_ms_oem_restart/PRD.md`.
- Crash-recovery reconciliation at next launch --
  `docs/crash_recovery_reconciliation/PRD.md`.

### Engine / database
- The watchdog heartbeat is now gated on the engine/DB pipeline actually
  succeeding, not just GPS ticks arriving -- a locked/corrupted sqlite3
  connection can no longer masquerade as "healthy" --
  `docs/heartbeat_engine_health_gate/PRD.md`.
- Trip-start state is now atomic with respect to failure: `state`
  becomes `TRIP_ACTIVE` only after `trip_id` is genuinely committed, so
  a mid-setup DB failure can't leave the engine lying about an
  in-progress trip that doesn't exist -- `docs/start_trip_state_lie_on_failure/PRD.md`.
- The mirror-image bug at trip END (a DB hiccup in an unrelated commit
  AFTER the trip is already saved causing the natural retry to duplicate
  every child row) is now closed too -- `docs/trip_end_persistence_idempotency/PRD.md`,
  Premortem risk R1.

### Screen recording
- Playability verification extended to catch a uniformly blank
  recording (the `FLAG_SECURE` failure mode), not just "does the file
  open" -- `docs/screen_recording_blank_content_check/PRD.md`.
- Real liveness via a `MediaRecorder.OnErrorListener`, not a bare
  null-check -- `docs/screen_recording_liveness_check/PRD.md`.
- Original consent/type-promotion/segment-rotation hardening --
  `docs/screen_recording/PRD.md`.

### Alerting layer
- The alerting layer's own blind spot -- does the driver's phone even
  show notifications -- is now checked, surfaced through channels that
  don't depend on notifications working -- `docs/notification_visibility_check/PRD.md`.
- Notification ID collisions (both an unbounded per-trip scheme and a
  pre-existing fixed clash) fixed and fully audited --
  `docs/notification_id_collision_audit/PRD.md`,
  `docs/notification_channel_helper/PRD.md`.
- The live-binding gap for the notification listener (the same shape
  round 8 later generalized to accessibility) -- `docs/notification_listener_liveness/PRD.md`.
- Reading reliability -- `docs/notification_reading_reliability/PRD.md`.

## 3. Invariants as testable properties

These are the properties any future change (or scouting pass) should be
checked against:

1. **No silent staleness.** No failure mode leaves monitoring dead for
   longer than the watchdog's own alert threshold without an alert
   firing through at least one channel that doesn't itself depend on
   the failure.
2. **Observable recovery.** Every auto-recovery attempt (a restart, a
   GPS reacquire, an engine retry) is logged, so a diagnostic-log review
   can always tell "recovery was attempted and worked," "attempted and
   failed," or "never attempted" apart.
3. **Bounded retries.** No automatic recovery path retries a provably
   doomed action indefinitely; every retry loop either succeeds, gives
   up with an escalated, distinct signal to the driver, or is
   inherently safe to retry forever (cheap, idempotent, harmless if
   pointless).
4. **Atomic state transitions.** No engine state flag is ever set to
   reflect an operation before that operation has verifiably succeeded
   (round 11's trip-start fix; risk R1 in the premortem is the same
   property, not yet fully closed).
5. **Idempotent persistence.** Retrying a persistence operation after a
   transient failure must never produce duplicate data for the part
   that already succeeded (currently violated -- see Premortem R1).
6. **No ID collisions.** Every notification ID, channel ID, and
   `PendingIntent` request code the app uses is confirmed disjoint from
   every other one currently in use.
7. **A stale signal is never treated as a healthy one.** A lifecycle
   flag (`isRunning`, `mediaRecorder != null`, a Settings grant) is
   never the sole basis for "is this component actually working" --
   there must be an independent liveness/health signal backing it.

## 4. Acceptance

This invariant is "held," not just "improved," when:
- Every item in the Premortem's risk register is `Mitigated`.
- A fresh scouting pass focused on the monitoring process, run after
  all current Open items are closed, finds no new violation of any of
  the seven properties in §3 within its scope.
- No unresolved notification ID / channel ID / PendingIntent request
  code collision exists anywhere in the app (re-audited each time a new
  alert is added).

This is inherently a moving target -- new code can introduce a new
violation of these properties. The acceptance bar is that the
PROPERTIES are enforced by habit/audit going forward, not that the app
reaches a fixed, final state.

## 5. Honest limits

- This document does not cover monitoring correctness in the sense of
  "is the SCORE/data the engine computes accurate" -- it covers only
  "is monitoring running, and does the driver know if it isn't." Data
  accuracy bugs (e.g. distance/fuel/score precision issues from earlier
  rounds) are a related but separate concern already handled elsewhere.
- No Android device/emulator has been available throughout rounds 8-12
  -- every fix cited here was verified via real reproduction scripts
  against the actual Python engine (where Python was involved) or
  standalone compiled Java tests against verbatim-copied logic (where
  Java/Android APIs blocked full compilation). Each individual PRD
  discloses this as its own honest limit; this document doesn't repeat
  it per-subsystem.
- "Bounded time" in the invariant (§1) is deliberately not pinned to one
  number -- different subsystems have different realistic detection
  windows (DASHER-mode GPS staleness: ~120s; accessibility heartbeat:
  ~90s; general-mode staleness: ~180s). The invariant is that a bound
  EXISTS and is known, not that all bounds are equal.
