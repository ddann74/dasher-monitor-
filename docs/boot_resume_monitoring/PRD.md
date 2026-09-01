# PRD — monitoring doesn't actually resume after a device reboot

Status: IMPLEMENTED, built from §4's own stated recommendations (no
driver override given): both `ACTION_BOOT_COMPLETED` and
`ACTION_MY_PACKAGE_REPLACED` trigger resume; a real notification is
shown, not a Toast. On-device confirmation is explicitly blocked (see
§5's own note on why that specifically matters here). See PROGRESS.md.

## 1. The real bug found

Found during a premortem pass across every feature in the app.
`BootAndUpdateReceiver` is registered for both `ACTION_BOOT_COMPLETED`
and `ACTION_MY_PACKAGE_REPLACED` (AndroidManifest.xml), and its class
doc frames it as relevant to "OEM battery-killer investigation" — but
reading the actual `onReceive`, it does exactly one thing:

```java
String message = Intent.ACTION_BOOT_COMPLETED.equals(action)
        ? "Device rebooted"
        : Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
            ? "App was updated/reinstalled"
            : "Unknown broadcast: " + action;
engine.callAttr("log_diagnostic", "SYSTEM", message);
```

It logs the event. It never calls `startForegroundService` or
restarts `TripForegroundService` in any way. A device reboot or an
app update is the single most disruptive interruption this app can
experience — every process-level state (the trip in progress, GPS
callbacks, the watchdog's alarm) is gone — and it is the ONE
interruption type with no recovery path at all:

- A killed-but-not-rebooted process: `MonitoringWatchdogReceiver`'s
  `AlarmManager`-scheduled check detects a stale heartbeat and can
  restart the service or kick GPS (docs/watchdog_gps_independent_rearm/).
- A stuck GPS callback: the same watchdog's redundant re-arm covers it
  (docs/watchdog_reliability/).
- A reboot: nothing. The driver has to notice monitoring stopped and
  manually reopen the app.

The data needed to fix this already exists and is already durable
across exactly this kind of event: `MonitoringWatchdogReceiver.
markIntendedActive(context, boolean)` writes a `intended_active` flag
to `SharedPreferences` (`monitoring_watchdog_prefs`) — set `true` in
`TripForegroundService.startTracking()` (line 286) and `false` in
`stopTracking()` (line 910). This is precisely "was monitoring
supposed to be running the moment everything died," survives process
death AND reboot (SharedPreferences persist to disk), and already
exists for the watchdog's own use — just never read by anything
boot-related.

There's also a real, already-used precedent in this exact codebase for
starting the foreground service from a `BroadcastReceiver`:
`DrivingDetectionReceiver.onReceive` already does
`context.startForegroundService(startIntent)` with
`TripForegroundService.ACTION_START_TRACKING` successfully (from an
Activity Recognition transition, not user interaction). `BOOT_COMPLETED`
is one of Android's documented exemptions for starting a foreground
service from the background, so this isn't a new platform risk — it's
the same call this app already makes elsewhere.

## 2. Non-goals

- Not touching `DrivingDetectionReceiver`'s own separate activity-
  recognition-based auto-start logic — unrelated trigger, already works.
- Not changing the watchdog's own alarm-rearm logic once monitoring is
  running — that already handles itself once started.
- Not adding a NEW persisted-intent mechanism — `KEY_INTENDED_ACTIVE`
  already exists and already means exactly the right thing; this PRD
  only adds a reader for it.

## 3. Proposed design (for review, not yet approved)

1. Add a public getter alongside the existing `markIntendedActive`,
   e.g. `MonitoringWatchdogReceiver.wasIntendedActive(Context)`,
   reading the same `KEY_INTENDED_ACTIVE` SharedPreferences value
   (defaulting to `false` if never set).
2. In `BootAndUpdateReceiver.onReceive`, after logging the existing
   `"Device rebooted"`/`"App was updated/reinstalled"` message: if
   `wasIntendedActive(context)` is `true`, call
   `context.startForegroundService(...)` with
   `TripForegroundService.ACTION_START_TRACKING` — the same call
   `DrivingDetectionReceiver` already makes successfully.
3. Log the outcome either way (`"Auto-resumed monitoring after reboot
   (was active before)"` vs. `"Monitoring was off before this event --
   not resuming"`) — matching this app's own established "never let a
   real decision go unlogged" pattern.
4. Surface it visibly to the driver too, not just in the diagnostic
   log — e.g. a one-time Toast or notification on successful auto-
   resume ("Monitoring resumed after restart"), so a driver who didn't
   expect the phone to have rebooted still finds out tracking picked
   back up on its own, rather than discovering it silently days later.

## 4. Open questions

- Should `ACTION_MY_PACKAGE_REPLACED` (an app update) also trigger
  resume, or only a real reboot? An app update also kills the running
  process the same way a reboot does, so the same "was it intended
  active" check applies equally — recommend treating both the same
  way, but this is worth the driver's explicit confirmation since an
  auto-resume immediately after an update is a slightly different
  situation (the driver likely just interacted with the phone to
  install/open something) than waking up after a reboot.
- Should the visible notification (§3 point 4) be a plain Toast (only
  seen if the phone is unlocked/awake at that exact moment) or a
  proper notification (durable, seen whenever next checked)? Recommend
  a notification, matching how every other "something the driver needs
  to know" surface in this app works — but flagging as a real product
  choice, not purely mine to make.

## 5. Success criteria

- [x] `MonitoringWatchdogReceiver.wasIntendedActive(Context)` added.
- [x] `BootAndUpdateReceiver` calls it and starts
      `TripForegroundService` when `true`, for `ACTION_BOOT_COMPLETED`
      and `ACTION_MY_PACKAGE_REPLACED` (§4's own recommendation, used
      absent a driver override).
- [x] The outcome (resumed vs. stayed off) is logged either way.
- [x] The driver is notified visibly on a real auto-resume via a
      notification, not a Toast (§4's own recommendation).
- [ ] On-device confirmation — blocked, no Android emulator/device
      available in this environment. Note: this is the one item in
      this PRD that most needs real-device confirmation, since
      `startForegroundService` from `BOOT_COMPLETED` has known Android-
      version-specific edge cases (the 5-second `startForeground()`
      deadline, OEM-specific boot-broadcast delays) that code review
      alone cannot fully rule out.
- [ ] Driver sign-off.
