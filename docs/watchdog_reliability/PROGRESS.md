# Progress log -- make the monitoring watchdog survive a dropped alarm

## Investigation (2026-08-30)

Investigated after analyzing a real ~8-hour field diagnostic log the
driver uploaded, which showed two full monitoring blackouts (~15min,
~7min), each only recovered by manually reopening the app. Read
`MonitoringWatchdogReceiver.java` in full and the relevant sections of
`TripForegroundService.java` before writing anything.

Found `MonitoringWatchdogReceiver` already exists and is well-designed
(heartbeat staleness detection via durable SharedPreferences, high
-priority alert, automatic restart attempt, mode-aware timing already
tuned against a prior real incident) -- but the uploaded log had **zero
`WATCHDOG:` entries** during either blackout, despite both lasting far
past its 60-second DASHER-mode alert threshold. Also zero `MEMORY:`
(`onTrimMemory`) entries, and `lowMemory=false` throughout -- pointing at
an OEM-specific kill rather than a standard low-memory one, consistent
with `OemBackgroundHelper`'s existing autostart-guidance logic (also
unmodified here).

Traced the real root cause in code: `scheduleWatchdog()` is only ever
called from `startTracking()` once, and thereafter exclusively from
inside the watchdog's own `onReceive()` -- a fully self-perpetuating
chain with no independent backstop. One OS-dropped alarm (documented
real risk on aggressive OEMs) silently and permanently disables the
whole mechanism for the rest of the session, matching the observed
symptom exactly: no trace at all, not "fired late."

Wrote `docs/watchdog_reliability/PRD.md`, explicitly scoped to
hardening/observability, not a claim to eliminate OS/OEM kills --
premortem documents the residual risk this doesn't resolve.

## Implementation (2026-08-30)

Made the code changes for PRD §6 items 1-4 in one pass:

- `TripForegroundService.java` `onCreate()`: added a `DEVICE:` log line
  (`Build.MANUFACTURER`/`Build.MODEL`/`Build.VERSION.SDK_INT`/
  `OemBackgroundHelper.isKnownAggressiveOem()`), once per session --
  closes the gap where the uploaded log couldn't answer "which phone was
  this."
- `TripForegroundService.java`: added `lastWatchdogRearmMs` +
  `WATCHDOG_REARM_INTERVAL_MS` (5 min), and a throttled call to
  `MonitoringWatchdogReceiver.scheduleWatchdog(this)` inside
  `maybeLogHeartbeat()` -- the same method already proven (by the
  uploaded log's own `HEARTBEAT`/`ACCESSIBILITY_HEARTBEAT` entries) to
  keep running reliably for hours while the service is alive.
  `setExactAndAllowWhileIdle` with `FLAG_UPDATE_CURRENT` safely replaces
  any still-pending alarm, so this is a safe no-op when the watchdog's
  own chain is healthy, and a real second chance when it isn't.
- `MonitoringWatchdogReceiver.java`: `scheduleWatchdog()` now logs a
  `WATCHDOG: Scheduled next check in Ns (MODE mode interval)` line after
  successfully arming the alarm -- makes "was it ever armed" directly
  checkable in a future log, separately from "did it fire." Made
  `logToEngine` `static` (it never used instance state) so
  `scheduleWatchdog` -- itself static -- could reuse the existing
  engine-log/`FallbackLogger`-fallback pattern instead of duplicating it.
  Captured `isDasherModeActive(context)`'s result once (`dasherMode`
  local) and reused it for both the interval calculation and the new log
  message, rather than querying the Python engine a second time just for
  the log text.
- Explicitly did NOT touch alert-notification content, check
  intervals/thresholds, or the restart-attempt logic in `onReceive` --
  all already correct and tuned against a real prior incident (the
  17-minute `setInexactRepeating` gap referenced in the existing code
  comments), out of scope per PRD §2.

Verified by direct review (not a build -- no Android SDK available in
this sandbox): brace balance in both files, confirmed every call site of
the newly-`static` `logToEngine` still compiles conceptually (both
existing call sites are already unqualified, so the change is
source-compatible), confirmed no duplicate/wasted `isDasherModeActive`
engine call was introduced.

**Not done, and can't be from here**: on-device confirmation that a
simulated dropped alarm actually gets caught and re-armed by the new
heartbeat-driven path -- no Android emulator/device available in this
environment, and this specific failure mode (an OEM background kill)
isn't something `DeveloperTestingActivity` can simulate either. Per the
PRD's own §4 and §4a: the real test is the next field diagnostic log --
specifically, whether it shows `WATCHDOG: Scheduled...` entries
throughout the session (confirming the chain, or the redundant re-arm,
kept it alive) and whether a third blackout, if one occurs, is shorter
or self-recovers via the watchdog's restart attempt rather than
requiring a manual reopen. Final user sign-off is the only remaining
PRD §6 box.
