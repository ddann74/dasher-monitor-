# PRD: A real liveness signal for DasherAccessibilityService

Status: IMPLEMENTED (2026-09-15), scouting-pass finding (round 8, #1,
directed audit of Dasher-mode monitoring fail-safety).

## 1. What was wrong

Every place in this app that answers "is accessibility working right
now" -- `MainActivity.buildDasherDetectionStatusLine()` (the in-app
"✓ Actively watching for Dasher activity" status), `MainActivity`'s
missing-permissions warning, `TripForegroundService`'s
`accessibilityHeartbeatRunnable` and `checkAndLogPermissions` (which
feed `raisePermissionRevokedAlert`), and `appendDetectionWarning` (the
persistent notification text) -- derived the answer purely from
`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, i.e. whether the
driver has GRANTED the permission in Android Settings. None of them
checked whether the service's live binding was actually still alive and
receiving events.

Unlike `AppNotificationListenerService` (which has real, OS-guaranteed
`onListenerConnected`/`onListenerDisconnected` callbacks, already used
via `isListenerConnected`/`lastListenerConnectedMs` --
`docs/notification_listener_liveness/PRD.md`), `AccessibilityService`
has no equivalent Android API that reliably fires when the OS or an
aggressive OEM battery manager silently kills the service's binding.
`onUnbind`/`onDestroy` in this class only reliably cover the teardown
paths already handled (see their own comments) -- not every real-world
kill. Critically, `ENABLED_ACCESSIBILITY_SERVICES` can stay populated in
Settings even after such a kill (this codebase already treats this
class of OEM background-kill as a confirmed real risk elsewhere, e.g.
`docs/watchdog_reliability/PRD.md`'s cited 7-17+ minute blackouts).

**Concrete scenario:** a driver starts a dash; both the GPS foreground
service and the accessibility service are alive. An OEM battery manager
kills only the accessibility service's binding (not the process, not
the GPS foreground service) -- a plausible, previously-undefended
failure mode. Offer detection, Accept/Decline tracking, and dropoff
address parsing silently stop for the rest of the shift. GPS keeps
ticking normally, so the GPS-based watchdog never fires. The Settings
entry is untouched, so every check above kept reporting "accessibility
enabled" / "✓ Actively watching for Dasher activity" -- the exact false
positive this app's whole purpose depends on not producing.

## 2. Design

Added a genuine, self-reported liveness signal to
`DasherAccessibilityService`, following the same indirect-staleness
pattern `MonitoringWatchdogReceiver` already uses for GPS (since Android
provides no direct "still alive" query for `AccessibilityService` the
way `NotificationListenerService` gets real connect/disconnect
callbacks):

- `public static volatile long lastHeartbeatMs` -- updated from THREE
  places, all of which only ever run while the service is genuinely
  alive and bound: `onServiceConnected()` (first signal), the top of
  `onAccessibilityEvent()` (fires for real accessibility events from
  ANY foreground app, not just Dasher -- so ordinary phone use keeps it
  fresh), and `foregroundCheckRunnable`'s own 20s self-repost (a floor
  that keeps it fresh even during a stretch with zero real events, e.g.
  screen off -- and which itself only continues reposting while the
  service is alive, per the existing `onUnbind`/`onDestroy`/`onInterrupt`
  cancellation).
- `public static boolean isHeartbeatStale()` -- `true` only once the
  service has reported in at least once (`lastHeartbeatMs > 0`, the same
  "ever connected" cold-start guard already used for the notification
  listener) AND more than `HEARTBEAT_STALE_THRESHOLD_MS` (90s -- 4.5x
  `foregroundCheckRunnable`'s own 20s interval, a generous margin chosen
  deliberately to avoid false alarms, per this same round's own caution
  about over-alerting habituating drivers to ignore real ones) has
  passed since. One shared threshold/method instead of four independent
  copies of the same arithmetic.
- All four consuming call sites now compute `hasAccessibility`
  (or the equivalent "problem" entry) as `grantedInSettings &&
  !isHeartbeatStale()` (or the branching equivalent) -- so a stale
  heartbeat now produces the SAME "accessibility not working" signal a
  genuine Settings revoke always did, reusing every existing downstream
  mechanism (the status line, the persistent notification, and
  `raisePermissionRevokedAlert`) rather than inventing new ones.

## 3. Verification

`DasherAccessibilityService`/`MainActivity`/`TripForegroundService`
depend on live Android `AccessibilityService`/`Context` APIs and can't
be compiled/run outside a device or emulator in this environment.
Verified instead by:

- `python3`-based brace/paren balance check on all three edited files:
  every final depth 0.
- A standalone, compiled-and-run Java program (`javac`/`java`, no
  Android dependencies) containing a **verbatim copy** of
  `isHeartbeatStale()` and its threshold constant (confirmed identical
  to the shipped source via `grep` immediately before writing the test),
  plus the exact `grantedInSettings && !isHeartbeatStale()` composition
  used at all three `TripForegroundService` call sites and the
  `if (!granted) ... else if (isHeartbeatStale()) ...` branching used in
  `MainActivity`/`appendDetectionWarning`:
  - **The bug scenario**: granted in Settings, heartbeat stale ->
    `hasAccessibility` is `false` and the problem-reporting branch
    correctly says "not responding" (the exact false-positive this fix
    closes).
  - Fresh heartbeat, granted -> reports fully healthy, unaffected.
  - Cold start (`lastHeartbeatMs == 0`, never reported in yet) -> NOT
    treated as stale, preserving original behavior for the first
    moments after launch.
  - Not granted in Settings at all, even with a fresh/present heartbeat
    -> still reports the original "not enabled" problem, never masked
    by the heartbeat check.
  - Boundary check: exactly at the threshold is not yet stale (strict
    `>`, not `>=`).

  11 checks, all passed on first run.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  "OS/OEM silently kills the accessibility binding while the process and
  GPS service survive" scenario has not been observed on a device. The
  fix is built on well-documented, widely-reported Android platform
  behavior (accessibility bindings dying silently on aggressive OEM
  skins while `ENABLED_ACCESSIBILITY_SERVICES` stays populated) rather
  than something reproduced here.
- `isHeartbeatStale()` is an INDIRECT signal (silence implies death), not
  a direct one -- Android genuinely provides no better API for this.
  A pathological case where the service is alive but somehow stuck
  (e.g. deadlocked without crashing) for over 90s with zero
  `onAccessibilityEvent` calls AND the `foregroundCheckRunnable` Handler
  itself also stuck would still read as "stale," which is actually the
  desired outcome (a hung service is exactly as useless as a dead one)
  -- noted for completeness, not a gap.
- The 90s threshold is a judgment call, not a derived constant -- same
  honesty status as this codebase's other tuned thresholds (e.g.
  `MonitoringWatchdogReceiver`'s own `ALERT_THRESHOLD_DASHER_MS`). Round
  8's own findings flagged a DIFFERENT threshold (`GPS_INTERVAL_DEEP_PARK_MS`
  vs. the watchdog's GPS threshold) as uncomfortably tight; this fix
  deliberately chose a wider margin (4.5x vs. that finding's ~2x) to
  avoid repeating the same mistake, but this has not been validated
  against a real device's actual Doze/OEM throttling behavior for
  accessibility events specifically.
- Does not change `raisePermissionRevokedAlert`'s notification text to
  distinguish "Settings revoked" from "silently went stale" -- both
  produce the same "Accessibility ... Offer detection and Accept/Decline
  tracking won't work" alert, which is honest and actionable either way
  (the fix is the same either way: re-enable/restart), but a driver
  can't tell which happened from the alert alone.

## 5. Success criteria

- [x] A silently-killed accessibility binding (Settings entry untouched)
      is now detected as non-working, not silently reported as fine
- [x] The in-app status line, persistent notification, and permission-
      revoked alert all use the same liveness signal
- [x] Cold start (service hasn't reported in yet) is never treated as a
      false "stale" state
- [x] A genuine Settings revoke still reports correctly and is never
      masked by the heartbeat check
- [x] Standalone compiled Java test (11 checks) of the exact staleness
      and composition logic, verified against the shipped source, fully
      passed
- [x] `python3` brace/paren balance check clean on all three edited
      files
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver confirms in real use that an accessibility-service kill
      (simulated via Settings toggle or force-stop, if a genuine silent
      OEM kill can't be reproduced on demand) now surfaces as "not
      responding" rather than staying silently green.
- [ ] Driver sign-off.
