# PRD: Make the watchdog's redundant re-arm actually independent of GPS

Status: IMPLEMENTED (all §6 boxes checked except sign-off) -- this
PRD's own status line was stale, found and corrected during a
2026-09-02 ralph-loop continuation pass. Already went through its own
premortem pass (§3a, P1/P2 found and documented).
Scope: this one structural gap only. Not a general codebase pass.

## 0. What this is / isn't

`docs/watchdog_reliability/PRD.md` (implemented, 2026-08-30) added a
"redundant re-arm" specifically to close a self-perpetuating-alarm-chain
risk: if the OS ever dropped a single scheduled watchdog alarm, nothing
would ever re-arm it. That PRD's own §3 design describes the fix as
running "from the heartbeat path already proven to keep running reliably
for hours in that same real log... but only for as long as this service
itself is still alive."

**That description is not quite what got built.** Re-reading the actual
implementation (`TripForegroundService.maybeLogHeartbeat`, called only
from `onLocationResult`) found the redundant re-arm is not gated on "the
service is alive" - it's gated on "a GPS location update just arrived."
Those are different conditions, and the gap between them is this PRD's
whole subject. This is not a criticism of the first PRD's intent, which
was correct - it's a gap between that intent and what the code actually
does, found by tracing the call chain rather than trusting the comment.

## 1. Why (investigation, 2026-08-31)

1. **Confirmed via code**: `MonitoringWatchdogReceiver.scheduleWatchdog`'s
   redundant re-arm call (`TripForegroundService.java` L747) lives inside
   `maybeLogHeartbeat`, which has exactly one call site: L140, inside
   `LocationCallback.onLocationResult`. There is no other path that
   invokes it.
2. **This means the redundant re-arm shares a failure dependency with the
   exact thing it exists to backstop.** The watchdog's own self-reschedule
   chain (`MonitoringWatchdogReceiver.onReceive` calling
   `scheduleWatchdog` on itself) can fail if a single OS-delivered alarm
   is dropped - the scenario `docs/watchdog_reliability/PRD.md` already
   evidenced as real. The redundant re-arm was meant to catch exactly
   that case. But if GPS location updates ALSO stop arriving at the same
   time - plausible under the same class of aggressive OEM
   background-process throttling that can drop an alarm, and also
   possible from an unrelated cause (a location-settings change, a
   temporarily degraded GPS fix under Doze, a location permission
   silently revoked mid-session, all already-logged-elsewhere concerns in
   this same file) - the redundant re-arm never fires either. Both
   layers require the same underlying condition (GPS still flowing) to
   stay alive, which defeats the point of having two independent layers.
3. **The codebase has already solved this exact class of problem once,
   elsewhere, deliberately.** `TripForegroundService`'s
   `accessibilityHeartbeatHandler`/`accessibilityHeartbeatRunnable`
   (added for a different, but structurally identical, reason) has its
   own comment explaining why: "the regular heartbeat is tied to GPS
   ticks, which slow down significantly when parked... creating gaps...
   This runs on a fixed schedule regardless of GPS tier." That fix
   already exists in this file for accessibility-drop detection; it was
   never applied to the watchdog re-arm specifically, which has the
   identical GPS-tied-gap shape.
4. **Not a hypothetical**: GPS updates already have documented gaps
   independent of any failure - `updateGpsIntervalForSpeed` (referenced
   in the accessibility-heartbeat comment) intentionally slows the GPS
   polling interval "up to 30+ seconds at the deep-park tier" while
   parked. A driver legitimately parked and waiting (exactly DASHER mode,
   exactly when the watchdog's faster 45s interval matters most) can
   already go 30+ seconds between location callbacks as designed
   behavior, not a bug - during which the redundant re-arm is
   structurally unable to help even though nothing is actually wrong.

## 2. Definition of "functional" for this task

- [ ] The redundant watchdog re-arm fires on a fixed schedule as long as
      the service process itself is alive - not gated on GPS callbacks
      arriving at all.
- [ ] Mirrors the existing, already-proven `accessibilityHeartbeatHandler`/
      `accessibilityHeartbeatRunnable` self-repeating `Handler.postDelayed`
      pattern in this same file, rather than inventing a new mechanism.
- [ ] Started alongside the accessibility heartbeat in `startTracking()`,
      stopped alongside it in `stopTracking()`/`onDestroy()` - same
      lifecycle, no new leak surface.
- [ ] `maybeLogHeartbeat`'s own re-arm call is removed (superseded, not
      duplicated - two independent timers both re-arming the same alarm
      is harmless but pointless).
- [ ] No change to `MonitoringWatchdogReceiver` itself, the watchdog's own
      self-reschedule, or the alert/restart logic - this PRD is scoped to
      where the redundant re-arm is triggered FROM, not what it does.

Non-goals:
- Re-verifying `docs/watchdog_reliability/PRD.md`'s own still-open
  on-device confirmation item - separate, already-tracked.
- Changing `WATCHDOG_REARM_INTERVAL_MS` (5 min) - no evidence it's the
  wrong number, this PRD is about the trigger condition, not the cadence.

## 3. Design

### 3.1 Independent re-arm timer

Add `watchdogRearmHandler`/`watchdogRearmRunnable`, identical shape to
`accessibilityHeartbeatHandler`/`accessibilityHeartbeatRunnable`:

```java
private final android.os.Handler watchdogRearmHandler = new android.os.Handler(android.os.Looper.getMainLooper());
private final Runnable watchdogRearmRunnable = new Runnable() {
    @Override
    public void run() {
        MonitoringWatchdogReceiver.scheduleWatchdog(TripForegroundService.this);
        if (monitoringActive) {
            watchdogRearmHandler.postDelayed(this, WATCHDOG_REARM_INTERVAL_MS);
        }
    }
};
```

Started in `startTracking()` next to the accessibility heartbeat's own
`postDelayed` call; stopped in `stopTracking()`/`onDestroy()` next to its
`removeCallbacks` calls.

### 3.2 Remove the GPS-gated version

Delete `lastWatchdogRearmMs` and the re-arm block inside
`maybeLogHeartbeat` (L743-748) - fully superseded by 3.1, which fires on
its own schedule regardless of GPS activity.

## 3a. Premortem (2026-08-31): assume this fix doesn't actually help

Re-traced the real implementation (not just the design) looking for what
this fix does NOT close, the same way the two prior premortems on
`docs/screen_recording/PRD.md` did.

- **P1 - a 5-minute "cold start" window at the beginning of every trip
  is still exactly as exposed as before this fix.** Confirmed in code:
  `startTracking()` calls `MonitoringWatchdogReceiver.scheduleWatchdog(this)`
  directly (L287), THEN separately schedules `watchdogRearmRunnable` with
  `postDelayed(..., WATCHDOG_REARM_INTERVAL_MS)` - so the new,
  GPS-independent backstop's FIRST firing doesn't happen until 5 minutes
  into the trip. If the very first watchdog alarm (the one
  `startTracking()` itself just scheduled) is the one an aggressive OEM
  drops - the exact failure mode `docs/watchdog_reliability/PRD.md`
  evidenced as real - and no watchdog alarm fires again before the
  5-minute mark, this fix provides no protection during that window,
  because it hasn't started running yet. This isn't a flaw unique to
  this PRD's design - `WATCHDOG_REARM_INTERVAL_MS` (5 min) was inherited
  unchanged, per this PRD's own §2 non-goals - but it does mean "the
  redundant re-arm is now GPS-independent" is not the same claim as "the
  redundant re-arm covers the whole trip," and the PRD's own §2 checklist
  doesn't currently distinguish the two. Not fixed here - shrinking this
  window would mean lowering `WATCHDOG_REARM_INTERVAL_MS`, an explicit
  non-goal of this PRD, or firing the new timer once immediately at
  `startTracking()` in addition to its normal schedule, which is a real,
  cheap option worth a follow-up decision rather than doing unilaterally
  here.
- **P2 - "GPS-independent" does not mean "immune to every kind of
  stall," and the fix's own comments could be read as implying more than
  that.** `watchdogRearmRunnable` runs on the main `Looper`, same as
  every other Handler-based timer in this file (including the
  accessibility heartbeat it mirrors). If the main thread itself is ever
  blocked for an extended period - a slow synchronous call into the
  Python engine via Chaquopy, heavy UI work, anything else queued ahead
  of it - this timer is delayed exactly like the GPS-tied one it
  replaced, just for a different underlying reason. This is an inherited
  limitation of the `Handler.postDelayed` pattern itself, not something
  this PRD introduced or could reasonably be expected to solve (a
  dedicated background thread would add real complexity for a scenario
  with no evidence behind it yet), but worth stating explicitly rather
  than letting "GPS-independent" read as "unconditionally reliable."
- **Reviewed and ruled out, not a real risk**: a `removeCallbacks`/
  in-flight-execution race between `stopTracking()` and
  `watchdogRearmRunnable.run()`. Both run on the same main `Looper`
  thread, so they cannot execute concurrently - either the runnable
  finishes before `stopTracking()` runs, or `stopTracking()`'s
  `removeCallbacks` cleanly cancels a still-queued firing. No fix needed;
  noted so this specific concern isn't re-raised and re-investigated
  later without cause.

## 4. Testing / verification approach

Same disclosed limitation as `docs/watchdog_reliability/PRD.md` §4: no
Android SDK/emulator in this environment. Verified by code review only:
confirm the new Handler/Runnable pair mirrors the existing
accessibility-heartbeat pattern exactly (same Looper, same start/stop
lifecycle points), and confirm `maybeLogHeartbeat`'s removed block had no
other caller relying on it.

## 5. Open questions

None blocking - this is a structural fix to an already-agreed-on
mitigation, not a new design decision.

## 6. Success criteria (implementation-phase checklist)

- [x] `watchdogRearmHandler`/`watchdogRearmRunnable` added, mirroring the
      accessibility-heartbeat pattern
- [x] Started in `startTracking()`, stopped in `stopTracking()`/`onDestroy()`
- [x] GPS-gated re-arm removed from `maybeLogHeartbeat`
- [x] Diff-reviewed to confirm no change to `MonitoringWatchdogReceiver`
- [ ] User sign-off
