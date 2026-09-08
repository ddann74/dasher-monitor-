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

## Proactive first-launch OEM nudge (2026-09-08)

Driver asked directly to "fix the OEM background-killing issue." Two
more real diagnostic logs since the above (823 accessibility reconnects
in one 2.5-day session; 17 uncaught-crash occurrences in another --
both root-caused separately, see `docs/dash_monitoring_awareness/
PROGRESS.md`'s latest entry for the crash) confirmed this is real and
ongoing on this driver's own OPPO CPH2591, on top of everything already
built here. Re-read this PRD's own §2 and non-goals first: eliminating
the underlying OS/OEM kill from app code isn't achievable (no such API
exists) and was never this PRD's claim -- so "fix" here means closing
the largest remaining REACHABLE gap, not promising the kill stops.

**Gap found, not previously covered by anything in this PRD or
`docs/watchdog_reliability`**: `PermissionsActivity`'s existing OEM
guidance dialog (`OemBackgroundHelper.showAutostartGuidanceDialog`,
originally a private duplicate inline in that Activity) only ever fires
REACTIVELY -- gated on `!hasAccessibility`, so it only shows when
accessibility happens to already be off AND the driver happens to visit
that specific screen. A driver on a known-aggressive-OEM device who
hasn't hit a revocation yet THIS session gets zero warning before the
first blackout, even though the app already knows
(`OemBackgroundHelper.isKnownAggressiveOem()`) this phone is a
documented offender before anything goes wrong.

**Fix**: added a one-time proactive nudge, `MainActivity.
maybeShowOemAutostartNudge()`, called at the end of `onCreate()` --
the app's one guaranteed entry point, unlike `PermissionsActivity`
which the driver may never open at all if nothing's visibly broken yet.
Gated on `isKnownAggressiveOem()` AND a `SharedPreferences` flag
(`oem_autostart_nudge_shown`, `dasher_monitor_prefs` -- same prefs file
`MainActivity` already uses elsewhere) so it shows exactly once ever,
not on every launch -- a driver who dismisses it isn't nagged again.

**Centralized rather than duplicated**: the dialog itself
(title/message/Open Settings/Not Now) was previously written inline,
once, as a private method in `PermissionsActivity`. Since `MainActivity`
now needs the identical dialog, moved it into `OemBackgroundHelper.
showAutostartGuidanceDialog(Context)` -- a static method both Activities
now call -- rather than hand-copying the same `AlertDialog.Builder`
block a second time. `PermissionsActivity.showOemBackgroundGuidance()`
is now a one-line delegate; its own existing reactive trigger
(`!hasAccessibility && isKnownAggressiveOem()`, checked every time that
screen resumes) and its always-visible manual button are both untouched.

**Honestly scoped, not oversold**: same disclosed limitation as
`OemBackgroundHelper` and this PRD's own §1 finding 6 already state --
there is no cross-vendor Android API to confirm the driver actually
completes the OEM-side toggle after tapping "Open Settings," and this
does not itself prevent the OS from killing the process. It only gets
the existing, already-correct guidance in front of the driver earlier
(before the first real blackout, not only after), and does so exactly
once so it doesn't become noise on every app open.

### Verification

Same disclosed limitation as every Java-only change in this repo -- no
Android SDK/emulator/device.

- Brace/paren balance: `OemBackgroundHelper.java` 33/33 braces, 151/151
  parens; `PermissionsActivity.java` 75/75 braces, 407/407 parens;
  `MainActivity.java` 154/154 braces, 718/718 parens.
- Confirmed `OemBackgroundHelper` is package-private (`final class`, no
  modifier) and both callers (`MainActivity`, `PermissionsActivity`) are
  in the same `com.drivingefficiency.app` package -- no visibility
  change needed.
- Confirmed `PermissionsActivity`'s `AlertDialog`/`Toast` imports are
  still used elsewhere in that file (31 remaining references) after
  removing its own inline copy of this dialog -- no now-unused import
  left behind.
- Confirmed the new nudge only ever fires from `onCreate()` (once per
  process's first-ever launch on a matching device, per the persisted
  flag), not from `onResume()` -- won't re-show on every foreground
  the way `PermissionsActivity`'s reactive check intentionally does.
- Traced `getSharedPreferences("dasher_monitor_prefs", MODE_PRIVATE)`
  against the one other existing usage in `MainActivity` (line ~358) --
  same file/mode, no collision risk on the key name
  (`oem_autostart_nudge_shown` is new, unused elsewhere).

Remaining: on-device confirmation the dialog actually appears once, at
the right moment, without visually colliding with anything else
`onCreate()` does (blocked, no device) -- and, same as this PRD's
original §4a, whether this measurably reduces the next field log's
kill/reconnect count is only answerable from a future real log, not
from here. Driver sign-off.
