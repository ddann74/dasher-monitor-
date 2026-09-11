# PRD: Shared notification-channel-creation helper

Status: IMPLEMENTED (2026-09-11), driver's own feature-audit finding --
not a specific driver-reported bug. Item #9 from the improvement-
scouting pass, fixed after all 8 report-formatting findings (see
`docs/report_formatting_consistency/PRD.md`).

## 0. What this is / isn't

A feature-audit scouting pass flagged notification-channel creation
boilerplate as duplicated across roughly 9 call sites: the same
"SDK-version-gate, construct `NotificationChannel`, optionally set a
description and enable vibration, register with `NotificationManager`"
block, copy-pasted independently in `TripForegroundService.java` (6
separate alert/prompt channels: permission-revoked, consent-recovery,
recording-verification-failed, monitoring-not-active, rate-delivery-
prompt, and the main trip-tracking channel), `AppNotificationListenerService.java`
(auto-launch-for-offers), `BootAndUpdateReceiver.java` (auto-resumed-
after-reboot), and `MonitoringWatchdogReceiver.java` (monitoring-
failure-alert) -- 9 real sites, confirmed by reading each one
individually before touching anything (not assumed from the finding's
count).

Reading all 9 side by side also surfaced a real, if minor, drift
between the copies: `TripForegroundService.createNotificationChannel()`
(the main trip-tracking channel) called
`manager.createNotificationChannel(channel)` with NO null-check on
`manager` first, unlike every other one of the 9 sites, which all
guarded with `if (manager == null) { return; }` (or equivalent) before
doing anything. `getSystemService(NotificationManager.class)`
returning null is an edge case (undocumented, essentially never
happens on a real device), but it's exactly the kind of small,
independent divergence duplicated boilerplate accumulates over time
without anyone noticing -- concrete evidence the original finding was
pointing at a real problem, not just a style nitpick.

## 1. Design

New `NotificationChannelHelper` (`public final class` with a private
constructor and one `static` method, the same shape as this codebase's
existing `OverlayHelper`/`WeatherHelper`/`NavigationHelper`/
`GoogleApiHelper`):

```java
static void ensureChannel(NotificationManager manager, String channelId, String name,
                           int importance, String description, boolean vibrate)
```

- Takes the already-fetched `NotificationManager` (not a `Context`) --
  every one of the 9 original sites had already called
  `getSystemService(NotificationManager.class)` itself, several with
  the result used again immediately afterward (`manager.notify(...)`)
  or with call-site-specific null-handling (an early `return` vs. a
  log line first) that the helper shouldn't try to own.
- `manager == null` is a silent no-op inside the helper -- this now
  covers the one site (`createNotificationChannel()`) that previously
  had no null-check at all, fixing that real (if minor) latent NPE
  risk as a side effect of consolidating, not as a separate change.
- `description` accepts `null` to skip `setDescription` entirely --
  the one channel (`CHANNEL_ID`, "Trip Tracking") that never set one.
- `vibrate` is an explicit `boolean` rather than always calling
  `enableVibration(true)` -- only 4 of the 9 original sites did
  (`permission_revoked_alert_*`, `recording_verification_failed_alert`,
  `monitoring_not_active_alert`, `MonitoringWatchdogReceiver`'s
  `ALERT_CHANNEL_ID`); preserving that real distinction (alert-style
  channels vibrate, routine ones don't) rather than silently making
  every channel vibrate or none of them.
- The SDK-version gate (`Build.VERSION.SDK_INT >= Build.VERSION_CODES.O`
  -- notification channels are an API 26+ concept) moved inside the
  helper, so no call site needs its own `import android.os.Build;`
  just for this any more (removed that now-unused import from
  `BootAndUpdateReceiver.java`, the one file where it had no other use
  left; `TripForegroundService`/`AppNotificationListenerService`/
  `MonitoringWatchdogReceiver` all still use `Build` for unrelated
  version checks elsewhere, so their import stayed).

All 9 call sites now read as a single line or two calling
`NotificationChannelHelper.ensureChannel(...)`, instead of a 4-6 line
inline block each.

## 2. Verification

- Confirmed via grep that all 9 original `new NotificationChannel(`
  constructions are gone from every caller file, and the only
  remaining one in the whole codebase is inside the helper itself.
- Confirmed via grep that `NotificationChannelHelper.ensureChannel` is
  now called exactly once per original site (6 in
  `TripForegroundService.java`, 1 each in the other 3 files -- 9
  total, matching the count read out of each file individually).
- Removed the now-unused `import android.app.NotificationChannel;`
  from all 4 caller files (no longer directly referenced by name
  anywhere outside the helper), and the now-unused
  `import android.os.Build;` from `BootAndUpdateReceiver.java`
  specifically (confirmed via grep that file had no other `Build`
  usage left; the other 3 files' `Build` imports were confirmed still
  needed for unrelated version checks before removing nothing there).
- Brace/paren balance check on all 5 modified files (4 callers + the
  new helper) -- clean.
- `python3 -m py_compile` doesn't apply here (Java-side change,
  same disclosed limitation as every other Java change in this repo --
  see ss3).

## 3. Honest limits

- No Android device/emulator available in this environment, same
  disclosed limitation as every Java-side change in this repo --
  verified by code reading, grep-based cross-checking, and brace/
  paren balance only, not by actually running the app and observing a
  real notification channel appear in system settings.
- Behavior is intended to be identical to before (same channel ids,
  names, importance levels, descriptions, and vibration settings for
  every one of the 9 channels -- this is a pure consolidation, not a
  redesign of any channel's actual settings), but that equivalence is
  reasoned from reading the diff carefully, not confirmed by an actual
  side-by-side notification-channel inspection on a device.
- The one behavior change that IS real and intentional:
  `createNotificationChannel()`'s main trip-tracking channel now has a
  null-check on `manager` (via the helper) where it previously had
  none -- see ss0. This narrows a latent crash risk; it cannot
  introduce a new one, since the whole method's job is only to
  register a channel that's a no-op if it already exists.

## 4. Success criteria

- [x] One shared helper (`NotificationChannelHelper.ensureChannel`)
      instead of 9 independent copies of the same block
- [x] All 9 original call sites (confirmed by reading each file, not
      assumed from the original finding's count) now call the helper
- [x] Preserved each channel's real settings (id, name, importance,
      description or lack of one, vibration or lack of it) exactly,
      not homogenized to one default
- [x] Fixed the one real divergence found while consolidating (missing
      null-check on `manager` in the main trip-tracking channel) as a
      natural side effect, not left in place for "faithfulness"
- [x] Unused imports (`NotificationChannel` in all 4 callers, `Build`
      in `BootAndUpdateReceiver.java` specifically) removed, not left
      dangling
- [x] Brace/paren balance check on all 5 modified files -- clean
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see ss3. Real on-device notification-channel
      behavior (channel appears correctly in system settings, alerts
      still vibrate/sound as before) is unconfirmed.
- [ ] Driver confirms in real use that every notification this app
      posts (permission-revoked alerts, auto-launch, boot-resume,
      watchdog alerts, the persistent trip-tracking notification, the
      rate-delivery prompt, consent-recovery, recording-verification-
      failed) still behaves exactly as before -- same channel names in
      Android's notification settings, same vibration behavior.
- [ ] Driver sign-off.
