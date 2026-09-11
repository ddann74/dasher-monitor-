# PRD: Detect the notification listener's live binding dying silently

Status: IMPLEMENTED (2026-09-11), driver's own feature-audit finding --
not a specific driver-reported bug. See §4 for the honest limits before
treating this as more than it is.

## 0. What this is / isn't

This is about `AppNotificationListenerService` specifically -- the
component behind offer-via-notification detection and all message
reading (personal + work). It is NOT a fix for the accessibility
service (already has its own dedicated 15s heartbeat,
`TripForegroundService.accessibilityHeartbeatRunnable`) or GPS (already
has a whole watchdog PRD, `docs/watchdog_reliability/PRD.md`). This
closes the same class of gap for the one remaining always-on component
that never got the equivalent treatment.

## 1. The gap

`TripForegroundService.checkAndLogPermissions()` already checked four
permissions on every heartbeat, including "Notification Access" -- but
that check only ever reads `Settings.Secure`'s
`enabled_notification_listeners`, which answers "has the driver granted
this app notification access," not "is Android's live binding to this
listener currently connected." Those are genuinely different things:
the permission stays granted even if the live binding silently fails
to survive an OEM background kill and never reconnects on its own --
the exact class of instability already confirmed real elsewhere in
this app (`docs/watchdog_reliability/PRD.md`,
`docs/screen_recording/PRD.md` §21, both driven by real diagnostic
logs from a device with `knownAggressiveOem=true`).

Until now, nothing distinguished these two states. If the live binding
died while the permission stayed granted, the practical effect would
be silent and total: no offer-via-notification detection, no urgent
message reading, no trusted-contact message reading -- and the
existing PERMISSIONS log line would keep reporting
`notificationAccess=true` the entire time, since it was never actually
checking the thing that broke.

## 2. Fix

Two real, documented `NotificationListenerService` lifecycle callbacks
(`onListenerConnected()`/`onListenerDisconnected()`) exist specifically
to answer this -- this app simply never implemented them before now.

- `AppNotificationListenerService`: new `onListenerConnected()` /
  `onListenerDisconnected()` overrides. Set public static
  `isListenerConnected` / `lastListenerConnectedMs` /
  `lastListenerDisconnectedMs` (mirrors the existing
  `DasherAccessibilityService.lastDasherForegroundMs` cross-component
  pattern), log a distinct `NOTIFICATION_LISTENER` diagnostic line each
  time, and -- on disconnect specifically -- call Android's own
  `requestRebind()` (API 24+, this app's minSdk is 26, no version gate
  needed) to proactively ask the system to reconnect rather than
  passively waiting.
- `TripForegroundService.checkAndLogPermissions()`: new
  `hasNotificationListenerConnected` check, structurally parallel to
  the existing 4 permission checks (same `lastLoggedX`/edge-triggered
  pattern), but deliberately a SEPARATE alert category ("Notification
  Listener", not "Notification Access") -- the two failure modes need
  different driver action (re-grant a permission vs. wait for/trigger a
  reconnect), so conflating them under one alert would have been
  confusing, not just imprecise.

### 2.1 Why the cold-start false positive is avoided

`isListenerConnected` only becomes meaningful once
`onListenerConnected()` has fired at least once -- at a fresh process
start, `AppNotificationListenerService` and `TripForegroundService` are
separate Android services bound independently, with no guaranteed
order. Checking `isListenerConnected` at `TripForegroundService`'s
`forceLog=true` call site (startTracking()'s very first line) could
easily read `false` simply because Android hasn't gotten around to
binding the listener yet, not because anything is actually wrong.
Guarded by `notificationListenerEverConnected` (`lastListenerConnectedMs
> 0`) everywhere this check runs -- the alert and the "changed"
detection both require having seen at least one genuine connection
first, so "hasn't connected yet" is never mistaken for "was connected,
now isn't."

## 3. Verification

Same disclosed limitation as every Java-side change in this repo -- no
Android SDK/emulator/device available in this environment. Neither
`onListenerConnected`/`onListenerDisconnected` actually firing, nor
`requestRebind()` actually working, has been observed.

- Brace/paren balance: `AppNotificationListenerService.java` 82/82
  braces, 349/349 parens; `TripForegroundService.java` 237/237 braces,
  1124/1124 parens.
- Confirmed the new alert category ("Notification Listener") is
  distinct from the existing "Notification Access" category -- separate
  notification channel ID (derived from the name, same mechanism every
  other `raisePermissionRevokedAlert` category already uses), so
  neither can be confused with or suppress the other.
- Confirmed the new check is NOT added to the `forceLog` block (the
  "already off when monitoring starts" case) -- only to the periodic
  "changed while monitoring" edge-triggered block, per §2.1's reasoning.

## 4. Honest limits

- **No real diagnostic log evidence this specific failure (live binding
  dying while permission stays granted) has ever actually happened.**
  This is proactive hardening built from a well-established pattern
  already confirmed real for two OTHER always-on components on the
  same device class, not from an observed instance of THIS component
  failing. If `NOTIFICATION_LISTENER: Disconnected` never appears in a
  real log, that could mean this component happens to be more
  resilient than the other two, or that it hasn't been tested under
  the right conditions yet -- can't distinguish those without more
  real-world logs.
- Whether `requestRebind()` actually succeeds in practice (as opposed
  to Android silently ignoring or delaying it) is unconfirmed --
  Android's own documentation doesn't guarantee immediate or eventual
  reconnection, only that the request is made. If it doesn't work,
  the alert (and needing the driver to notice and, if it keeps
  happening, reinstall/reboot) remains the real fallback, same honesty
  standard as the rest of this app's OEM-kill mitigations.

## 5. Success criteria

- [x] `onListenerConnected()`/`onListenerDisconnected()` implemented,
      previously entirely absent
- [x] `isListenerConnected`/`lastListenerConnectedMs`/
      `lastListenerDisconnectedMs` exposed as public static fields,
      mirroring the existing cross-component pattern
- [x] `requestRebind()` called on disconnect, using this app's real
      minSdk (26), no version gate needed
- [x] `TripForegroundService` cross-checks this new liveness signal on
      its existing heartbeat cadence, as a genuinely separate alert
      category from the pre-existing permission-grant check
- [x] Cold-start false positive explicitly guarded against
      (`notificationListenerEverConnected`), not just assumed away
- [x] Brace/paren balance confirmed on both touched files
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4. Nothing here has been observed firing (or
      correctly staying silent) on a real device.
- [ ] Driver confirms in real use (or via a future diagnostic log)
      whether `NOTIFICATION_LISTENER: Disconnected` ever appears, and
      if so, whether `requestRebind()` visibly recovers it (a
      subsequent `Connected` line) or the driver has to intervene.
- [ ] Driver sign-off.
