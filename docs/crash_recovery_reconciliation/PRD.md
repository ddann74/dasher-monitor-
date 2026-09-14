# PRD: Close two real reconciliation gaps after a crash/restart

Status: IMPLEMENTED (2026-09-14), driver question ("what if the monitor
app crashes mid delivery, how will it continue and detect the dasher
status") triggered a full research pass into the existing recovery
architecture, which surfaced these two real, previously-undisclosed
gaps.

## 1. What was already true (not re-built here)

A prior research pass confirmed this app already has substantial, real
crash/restart recovery:

- `MonitoringWatchdogReceiver` detects a stale heartbeat and
  automatically calls `startForegroundService` itself -- no driver tap
  required for the base recovery path.
- `BootAndUpdateReceiver` auto-restarts monitoring after a full reboot
  or app update, if it was intended-active beforehand.
- `_recover_interrupted_trips` marks an orphaned in-progress trip
  complete (`was_interrupted = 1`) rather than silently discarding or
  falsely resuming it, preserving whatever aggregate stats/phase
  timestamps were already saved.
- `_recover_abandoned_offers` records a crash-interrupted offer as
  `outcome_unknown` rather than guessing/mislabeling it `timed_out`.
- `DasherAccessibilityService.checkCurrentForegroundWindow()` already
  does an ACTIVE `getWindows()` reconciliation at `onServiceConnected`,
  specifically because Android's accessibility API only reports
  *changes*, not current state -- this is the existing pattern the two
  fixes below extend to two places that didn't have it yet.

## 2. What was actually missing

Two real gaps, found by tracing the actual restart sequence end to end,
not previously disclosed as open items anywhere in `docs/`:

### 2.1 A real window where mode incorrectly reads GENERAL right after a mid-delivery crash

Every fresh Python engine process starts `TripManager.dasher_app_foreground
= False` (`drive_monitor.py`), and `get_mode()` returns GENERAL until
something corrects it. The only thing that corrects it is
`DasherAccessibilityService`'s own independent reconnect + 20s periodic
loop -- `TripForegroundService.startTracking()` never itself asked what
the accessibility service already knew. So immediately after ANY
restart (watchdog-triggered, boot-triggered, or the driver manually
reopening the app), if the crash happened mid-DASHER-mode-delivery, the
app would report GENERAL mode -- and `MonitoringWatchdogReceiver` would
select its SLOWER GENERAL check interval (2min/3min vs. 45s/60s) --
right when untracked time actually costs a real delivery.

**Fix**: `startTracking()` now checks
`DasherAccessibilityService.isDasherForeground` (a static field that
may already be correct, if the accessibility service happened to
reconnect before this runs -- no guaranteed order between the two
services binding) and calls `engine.callAttr("set_dasher_foreground",
true)` synchronously if so. Only seeds TRUE: the engine's own fresh-
process default is already false, and correcting AWAY from Dasher
remains `DasherAccessibilityService`'s own periodic check's job, same
division of responsibility as before.

### 2.2 Notifications posted while the process was dead were silently never seen

`AppNotificationListenerService.onNotificationPosted` only ever fires
for notifications posted AFTER this listener connects -- there was no
equivalent of `checkCurrentForegroundWindow`'s active reconciliation
for notifications. An offer-via-notification or an urgent customer
message posted while the process was dead, still sitting in the shade
when the listener reconnects, was silently never processed.

**Fix**: `onListenerConnected()` now calls Android's own
`getActiveNotifications()` and replays each through the existing
`onNotificationPosted` handler -- the same detection/parsing/voice-
announcement path a live-posted notification already goes through,
reused rather than duplicated.

Gated to the FIRST connect of this process only (`lastListenerConnectedMs
== 0`, checked before it's overwritten): a later `requestRebind()`-
triggered reconnect within the same still-alive process would otherwise
re-run every already-processed, still-active notification through
`onNotificationPosted` again, risking duplicate voice announcements or
duplicate offer-detection for content this exact process instance
already handled once. A genuine process restart always gets a fresh
`isFirstConnectThisProcess = true` (the field is a `static` reset to 0
at process start), so the real case this fix targets is never skipped.

## 3. Verification

- Real, compiled, executed Java test (`CrashRecoveryReconciliationTest.java`,
  no external dependencies -- pure logic, mirroring both real code
  blocks faithfully with fakes standing in for `DasherAccessibilityService.
  isDasherForeground`, `engine.callAttr`, `getActiveNotifications()`, and
  `onNotificationPosted`): 5 cases -- no seed call when Dasher isn't
  known foreground, a synchronous seed when it is (the actual mode-reset
  bug), already-active notifications replayed on first connect (the
  actual missed-notification bug), NO re-replay on a later same-process
  reconnect (confirms the duplicate-announcement guard), and a clean
  no-op on an empty shade. All 5 passed.
- Brace/paren balance confirmed on both modified files
  (`TripForegroundService.java`, `AppNotificationListenerService.java`).

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  end-to-end sequence (an actual crash, `getWindows()`/
  `getActiveNotifications()` actually returning the real current state
  at that exact moment, the binding-order race between the two services)
  has not been observed on a real device.
- `reconcileActiveNotifications` replays whatever `getActiveNotifications()`
  returns with no staleness filtering of its own -- a notification that's
  been sitting in the shade for hours (not necessarily posted while the
  process was specifically dead) would also be replayed. Not treated as
  a new problem to solve here: the existing downstream handling (offer
  recovery's own `expires_ts` check, `on_notification`'s own dedup) is
  what already has to deal with a stale/already-resolved offer or
  message, the same as it would for any other detection path -- this
  fix's only job is making sure detection runs at all, not re-deriving
  staleness handling that already exists elsewhere.
- The offer-decision-loss gap found in the same research pass (an offer
  that resolved entirely, accept/decline/timeout, while the process was
  dead, ending up `outcome_unknown` with no way to recover the real
  outcome) is deliberately NOT addressed here -- cross-referencing later
  trip/pickup history to guess the real outcome risks corrupting
  calibration with a wrong guess, worse than an honest "unknown."
  Disclosed as an accepted limitation, not solved.
- The raw GPS point track (`gps_points_json`) of a crash-recovered trip
  remains permanently empty (only ever written at normal trip finalize,
  never during periodic partial saves) -- also not addressed here, a
  separate gap from the two this PRD fixes.

## 5. Success criteria

- [x] `startTracking()` synchronously seeds DASHER mode from
      `DasherAccessibilityService.isDasherForeground` when true, closing
      the post-restart GENERAL-mode window
- [x] Only seeds TRUE, never overrides the engine's own correct default
      or fights `DasherAccessibilityService`'s own correct-away-from-
      Dasher logic
- [x] `onListenerConnected` reconciles already-active notifications via
      the existing `onNotificationPosted` path, not a duplicated one
- [x] Reconciliation gated to first-connect-this-process only, with a
      real test confirming no re-replay on a later same-process reconnect
- [x] Real compiled/executed Java test (5 checks, covering both fixes
      and the specific bug each one targets) fully passed
- [x] Brace/paren balance confirmed on both modified files
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see ss4.
- [ ] Driver confirms in real use (or via a future diagnostic log
      capturing an actual OEM-kill event) that a mid-delivery crash no
      longer shows GENERAL mode immediately after recovery, and that a
      notification posted during a real crash window is not missed.
- [ ] Driver sign-off.
