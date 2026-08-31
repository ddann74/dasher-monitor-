# PRD: Make the monitoring watchdog survive what it exists to catch

Status: DRAFT -- awaiting sign-off before implementation begins. This PRD
is about core service reliability, not a UI feature -- read §1 and §4a
carefully before signing off; the fix here narrows a real gap, it does
not (and cannot) promise to eliminate OS/OEM process kills entirely.
Scope: this one reliability gap only. Not a general codebase pass.

## 0. What this is / isn't

This is a **reliability hardening** PRD for `MonitoringWatchdogReceiver`,
which already exists and is already well-designed: it detects a stale
heartbeat via SharedPreferences (durable across process death), fires a
loud alert notification, and attempts an automatic restart of
`TripForegroundService`. This PRD does **not** replace that design or
add a new watchdog -- it closes a real, evidenced gap in the existing
one: a single missed alarm silently and permanently disables it for the
rest of the session, with no way to tell from the diagnostic log that
this happened.

Source: a real ~8-hour field diagnostic log
(`dasher_monitor_full_history12.txt`, 2026-08-30) uploaded by the
driver, analyzed in this session before writing this PRD.

## 1. Why (root-cause investigation, evidence-verified against a real log)

Read `MonitoringWatchdogReceiver.java` in full, `TripForegroundService.java`'s
`onCreate`/`onStartCommand`/`startTracking`/`maybeLogHeartbeat`/
`onTrimMemory`, and `OemBackgroundHelper.java`. Cross-referenced every
finding against the real uploaded log, not just the code in isolation.

1. **Confirmed real: two full monitoring blackouts in one shift**, each
   recovered only by the driver manually reopening the app --
   `OUTCOME: Recovered an offer abandoned by a crash/restart` at 17:47:55
   (Red Rooster, offline since 17:43:23 -- **~15 minutes**) and at
   18:52:54 (Tasty Noodle, offline since ~18:50:47 -- **~7 minutes**).
   Both offers' real outcomes are permanently unknown.
2. **Confirmed real: the watchdog never fired, either time.**
   `MonitoringWatchdogReceiver` logs `"WATCHDOG"` on every check via
   `logToEngine` (L156, L175) -- a repo-wide search of the uploaded log
   (5,068 lines, the full session) found **zero `WATCHDOG:` entries**,
   despite `startTracking()` calling `scheduleWatchdog()` at the start of
   the shift (`TripForegroundService.java` L253) and both blackouts
   lasting far longer than the DASHER-mode alert threshold (60s) that
   should have fired an alert and attempted an automatic restart within
   roughly a minute of either heartbeat going stale.
3. **Confirmed real: `onTrimMemory` also never fired.** This callback is
   already instrumented (`TripForegroundService.java` L714-724) with its
   own comment explaining exactly what its absence would mean: "if this
   fires shortly before an unexpected stop, that confirms system
   memory/battery pressure was the cause. If it's ABSENT right before a
   kill, that points toward a manufacturer-specific killer that bypasses
   this standard callback entirely." It's absent, zero times logged in
   the whole session. Combined with `lowMemory=false` and healthy free
   memory (1200-1400MB used of 3725MB available) on every heartbeat right
   up to both kills, this rules out a standard low-memory kill and points
   at an OEM-specific mechanism -- exactly the class of thing
   `OemBackgroundHelper`'s autostart-settings guidance already exists to
   help with.
4. **Real, code-verifiable design fragility: the watchdog's alarm chain
   has no independent backstop.** `scheduleWatchdog()` is called from
   `startTracking()` once, and thereafter **only** from inside
   `onReceive()` itself (L134: "must reschedule the next check every time
   this fires... or the watchdog would silently stop checking after just
   one firing"). This is a fully self-perpetuating chain: if the OS ever
   fails to deliver a single scheduled alarm -- and some aggressive OEM
   process managers are documented to clear an app's pending alarms
   alongside a force-stop, not just kill the process -- the entire
   watchdog capability dies silently for the rest of the session, with
   nothing else ever re-arming it, and no log line anywhere records that
   this happened. This matches the observed symptom exactly: not "the
   watchdog fired late," but "the watchdog left no trace at all" for both
   incidents.
5. **Real, fixable observability gap: no log line confirms
   `scheduleWatchdog()` actually succeeded**, and no log line records
   `Build.MANUFACTURER`/`Build.MODEL` anywhere in the session, despite
   `OemBackgroundHelper.isKnownAggressiveOem()` already existing and
   depending on exactly that value. This specific uploaded log cannot
   even confirm which phone/OEM this happened on -- a real gap for
   diagnosing the next one.
6. **Already in place, not touched here**: `PermissionsActivity` already
   has a "Fix Background/Autostart Settings" button
   (`OemBackgroundHelper.openAutostartSettings`) guiding the driver to
   their OEM's proprietary autostart/protected-apps screen. Whether this
   specific driver ever completed that step **cannot be determined from
   any log** -- Android provides no cross-vendor query API for that
   OEM-proprietary permission state (a real platform limitation, not an
   app bug), unlike the four other permissions already tracked
   (`location`/`overlay`/`notificationAccess`/`batteryExempt`), which do
   have real query APIs and are already logged every heartbeat.

## 2. Definition of "functional" for this task

- [ ] A single dropped/missed watchdog alarm no longer permanently
      disables the watchdog for the rest of the session -- something
      still alive and running (the service's own heartbeat, proven to
      keep running reliably for hours in the real log) gives it a chance
      to re-arm without requiring a full app reopen.
- [ ] The diagnostic log can confirm, after the fact, whether
      `scheduleWatchdog()` actually succeeded in scheduling the next
      check -- closing the current observability gap.
- [ ] The diagnostic log records `Build.MANUFACTURER`/`Build.MODEL` once
      per session, so a future uploaded log can immediately answer "which
      phone was this."
- [ ] None of this claims to guarantee the underlying OS/OEM kill itself
      is prevented -- see explicit non-goals below and the premortem in
      §4a. This narrows a real gap in the existing safety net; it is not
      a promise the safety net becomes unbreakable.

Non-goals (explicitly out of scope for this task):
- Eliminating OS/OEM background kills entirely -- not achievable from
  app code alone on an adversarial OEM process manager, and this PRD
  does not claim otherwise.
- Querying or confirming whether OEM autostart/protected-apps permission
  was actually granted -- no cross-vendor Android API exists for this;
  `OemBackgroundHelper`'s existing settings-deep-link is the correct,
  already-shipped mitigation for the part of this that IS addressable,
  and is not modified here.
- Any change to `TripForegroundService`'s core GPS/trip-tracking logic,
  or to the alert-notification content/styling in
  `MonitoringWatchdogReceiver.raiseAlert()` -- both already correct,
  untouched.
- Reducing the watchdog's check interval/alert threshold -- already
  tuned (per the code's own comment) against a real prior incident
  where a slower, inexact interval let a 17-minute gap occur; not
  revisited here.

## 3. Design

### 3.1 Redundant re-arm from the service's own heartbeat

In `TripForegroundService.maybeLogHeartbeat()` (already fires reliably
throughout an active session, confirmed by the real log's own
`HEARTBEAT`/`ACCESSIBILITY_HEARTBEAT` entries continuing for hours), add
a periodic call to `MonitoringWatchdogReceiver.scheduleWatchdog(this)` --
throttled to a coarse interval (e.g. every 5-10 minutes, not every
heartbeat), since `AlarmManager.setExactAndAllowWhileIdle` with
`FLAG_UPDATE_CURRENT` safely replaces any still-pending alarm rather than
stacking duplicates. This gives the watchdog chain a second, independent
opportunity to recover if the OS ever silently dropped its own
self-scheduled alarm -- but only for as long as the SERVICE itself is
still alive; it is not a fix for the case where the whole process is
already dead (that case is what `MonitoringWatchdogReceiver`'s
OS-invoked, cross-process design already exists to handle, and remains
the last line of defense for a true full-process kill).

### 3.2 Log confirmation that the alarm was actually scheduled

`MonitoringWatchdogReceiver.scheduleWatchdog()` currently has no
logging at all -- add a diagnostic log line confirming the alarm was
scheduled (with the computed interval, so DASHER vs. GENERAL mode timing
is visible in the log too), so a future uploaded log can directly answer
"was the watchdog even armed" instead of having to infer it from
absence.

### 3.3 Log the device manufacturer/model once per session

In `TripForegroundService.onCreate()`, alongside the existing
install-timing diagnostic line, log `Build.MANUFACTURER` / `Build.MODEL`
/ `Build.VERSION.SDK_INT` and whether
`OemBackgroundHelper.isKnownAggressiveOem()` considers this a
known-aggressive device -- closing the gap where this exact log couldn't
answer "which phone was this" at all.

## 4. Testing / verification approach

No JVM/instrumented test source set in this repo. Verification is by
code inspection and by re-checking the reasoning against the real
uploaded log (already done in §1, not repeatable against a *future*
incident until one is captured with this fix in place). On-device
confirmation that the redundant re-arm actually prevents a repeat of
either blackout is **flagged as unverifiable in this environment** -- no
Android device/emulator available, and reproducing an OEM-specific
background kill isn't something that can be simulated in
`DeveloperTestingActivity` either. The real test is the next field log.

## 4a. Premortem -- assume this ships and the same thing happens again

- **P1 -- the OEM kill also clears scheduled alarms, and the process was
  already dead when §3.1's re-arm interval would have fired.** Real
  possibility: if the process dies and stays dead for less than the
  re-arm interval before the driver notices anyway, §3.1 never gets a
  chance to run at all (it only runs from inside the living service).
  Mitigation: keep the interval short enough to matter (5-10 min, not
  hourly) without being so frequent it meaningfully affects battery --
  this is a genuine trade-off, not a solved problem, and is exactly why
  §2 doesn't claim this eliminates the risk.
- **P2 -- the underlying cause turns out to be something other than an
  OEM kill** (e.g. a real uncaught exception that never reached the
  `ERROR` log because the crash happened in native code, or during
  `onCreate()` before logging was set up). §3.3's manufacturer/model
  logging and §3.2's schedule-confirmation logging both make this
  distinguishable in the *next* log even if this PRD's fix doesn't
  prevent the underlying cause -- observability, not just mitigation, is
  half the point here.
- **P3 -- the driver never actually completed the OEM autostart step**
  (§1 finding 6) and that alone explains both kills, unrelated to
  anything this PRD changes. Not fixable in code (no query API exists);
  the next uploaded log's manufacturer/model field (§3.3) at least lets
  a future investigation check whether this device is
  `isKnownAggressiveOem()` and prompt re-confirming that setting, rather
  than guessing.

## 5. Open questions

None blocking implementation of §3.1-3.3 -- all three are additive,
low-risk logging/re-arming changes that don't alter existing behavior
when everything is already working correctly. The premortem above
documents real residual risk that isn't resolved by this PRD, which is
disclosed rather than implied away, but doesn't block starting on the
concrete fixes.

## 6. Success criteria (implementation-phase checklist)

- [x] `maybeLogHeartbeat()` periodically re-calls
      `MonitoringWatchdogReceiver.scheduleWatchdog()`, throttled to a
      coarse interval, not on every heartbeat
- [x] `scheduleWatchdog()` logs confirmation (interval, mode) that the
      alarm was scheduled
- [x] `onCreate()` logs manufacturer/model/SDK version and
      `isKnownAggressiveOem()` once per session
- [x] No change to `MonitoringWatchdogReceiver`'s alert-notification
      content, check intervals, or restart-attempt logic -- confirmed by
      diff review
- [ ] On-device confirmation that a simulated dropped alarm actually
      gets re-armed by the new heartbeat-driven path -- **blocked**: no
      Android emulator/device available in this environment; the real
      test is the next field diagnostic log
- [ ] User sign-off
