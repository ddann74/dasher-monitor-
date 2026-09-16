# PRD: Log the accessibility service's own connect/disconnect cadence

Status: IMPLEMENTED (2026-09-16). Driver-requested diagnostic addition,
following up directly on a real diagnostic log the driver uploaded
(OPPO CPH2591, ColorOS, `knownAggressiveOem=true`) showing
`lastKnownDasherForeground` and `mode=DASHER` never once appearing
across a full week of real dashing, with zero `MODE:` category log
lines at all despite `accessibility=true` logging continuously.

## 1. What was wrong

`DasherAccessibilityService` never logged its own `onServiceConnected`/
`onUnbind`/`onDestroy`/`onInterrupt` lifecycle events to the diagnostic
log -- only `TripForegroundService.onCreate()` (a full process restart)
was logged, under the `SERVICE` category. This meant a real diagnostic
log had no way to distinguish two very different explanations for "the
accessibility service never detected Dasher's foreground window":

1. The service was genuinely alive and connected the whole time, but
   Dasher's specific window/package just never triggered a detectable
   event (a parsing/event-delivery issue).
2. An aggressive OEM (this codebase already has confirmed, documented
   evidence of ColorOS doing exactly this on this same device model --
   `docs/duplicate_feedback_launch_fix/PRD.md` §5) was silently
   killing and restarting the accessibility service **component**
   itself in rapid bursts, independent of the app's main process (which
   only restarted 3 times in the driver's own week-long log) -- a
   partial failure invisible to the existing `accessibility=true`
   liveness check, since that check only proves the service reconnected
   and posted at least one heartbeat recently, not that it stayed
   connected long enough to ever see Dasher's window.

Without connect/disconnect timestamps, this distinction was
unanswerable from a log alone -- exactly the gap the driver's own
skepticism ("I'm not convinced this is happening") surfaced.

## 2. Design

Added two static fields (mirroring `isDasherForeground`'s own existing
static pattern, so the value survives across a fresh service instance
being constructed for a new connection within the same process):
`lastAccessibilityConnectedMs`, `lastAccessibilityDisconnectedMs`.

- `onServiceConnected()`: logs `"onServiceConnected() -- reconnected Ns
  after the previous disconnect"` if a prior disconnect was recorded, or
  `"...first connection this process"` on the very first connect.
- `onUnbind()`: logs `"onUnbind() -- was connected for Ns"` and records
  the disconnect timestamp.
- `onDestroy()`: logs the same "was connected for Ns" message
  independently (matching this method's own pre-existing "second safety
  net for whichever teardown path actually fires on a given OS/OEM"
  reasoning for its Handler-callback cleanup) -- but only advances
  `lastAccessibilityDisconnectedMs` if `onUnbind` hasn't already recorded
  this same teardown moments earlier, so a single real disconnect isn't
  double-counted and doesn't corrupt the next reconnect-gap measurement.
- `onInterrupt()`: logs its own occurrence too, distinctly from the
  other two (this callback alone isn't a reliable unbind signal, per
  this class's own pre-existing comment on that exact point) -- still
  worth recording, since a cluster of these correlating with the
  reconnect-gap logging would itself be additional evidence of an OEM's
  interrupt-then-restart pattern.

All under a new `ACCESSIBILITY_LIFECYCLE` diagnostic category, so it's
trivially filterable in a future log export.

**What this will show, concretely, the next time the driver sends a
log:** if the connect/disconnect cadence during an active dash looks
like `onServiceConnected -- reconnected 15s after the previous
disconnect` repeated every ~15-20 seconds throughout a dash, that's
direct, first-hand confirmation of an OEM kill-storm -- turning the
prior investigation's "almost certainly OPPO ColorOS" (inferred from a
different signal, a 2.5-day-old log from a separate investigation) into
a confirmed, current, first-hand measurement. If instead connects are
rare and long-lived, the real cause is elsewhere (most likely the
screen-text-parsing fragility already tracked by
`docs/screen_recognition_canary/PRD.md`).

## 3. Verification

`onServiceConnected`/`onUnbind`/`onDestroy`/`onInterrupt` all depend on
a live Android accessibility-service lifecycle and can't be exercised
outside a device. Verified instead by:

- `python3`-based brace/paren balance check on the edited file: final
  depth 0.
- A standalone, compiled-and-run Java program
  (`AccessibilityLifecycleLoggingTest.java`, `javac`/`java`),
  replicating the exact timestamp-bookkeeping logic (verbatim-copied and
  confirmed identical to the real source via a fresh re-read immediately
  before writing the test):
  - First-ever connection logs correctly, not a bogus gap computed
    against the zero-initialized disconnect timestamp.
  - A normal, well-spaced disconnect/reconnect cycle reports the correct
    connected duration and correct gap.
  - **The actual signature this logging exists to catch**: a simulated
    burst of 5 rapid reconnect/disconnect cycles, 2 seconds apart, each
    correctly reports the short gap -- confirming this would surface the
    exact OEM kill-storm pattern as a readable, greppable sequence in a
    real log.
  - **The double-log guard**: `onUnbind` immediately followed by
    `onDestroy` for the same teardown does not advance the disconnect
    timestamp a second time.
  - `onDestroy` firing alone (no preceding `onUnbind`, the exact
    OS/OEM-variance case this method's own pre-existing comment already
    anticipates) still correctly records the disconnect.

  7 checks, all passed on first run.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  cadence on the driver's actual OPPO CPH2591 device has not been
  observed; this fix only ensures the *next* real log they send will
  contain the evidence needed to answer the question definitively.
- Purely diagnostic -- adds no new recovery/self-heal behavior on its
  own. If the next log confirms a genuine OEM kill-storm, the actual fix
  (deep-linking to ColorOS's "Allow restricted settings" /
  autostart-manager screens, or a more aggressive in-app warning when
  this pattern is detected) would be separate, follow-up work informed
  by what this logging actually shows.
- `lastAccessibilityConnectedMs`/`lastAccessibilityDisconnectedMs` are
  static and reset to 0 on a full process restart (same as every other
  static field in this class) -- a process restart immediately following
  a real accessibility disconnect will log that first post-restart
  connect as "first connection this process" rather than computing a
  gap against the pre-restart disconnect. This is an accepted, honest
  simplification: distinguishing "OS killed the accessibility service
  component" from "OS killed the whole process" is exactly the
  distinction `TripForegroundService`'s own existing `SERVICE:
  onCreate()` log line (already timestamped) lets a log reader make by
  cross-referencing the two.

## 5. Success criteria

- [x] Every accessibility-service connect, disconnect (via either
      teardown path), and interrupt is now logged with a timestamp-derived
      duration/gap
- [x] A rapid reconnect/disconnect burst (the OEM kill-storm signature)
      is directly visible and distinguishable from a normal, rare
      disconnect
- [x] `onUnbind` immediately followed by `onDestroy` for the same
      teardown is not double-counted
- [x] `python3` brace/paren balance check clean
- [x] Standalone compiled Java test (7 checks) of the exact bookkeeping
      logic, verified against the shipped source, fully passed
- [ ] HONEST LIMIT: no Android device/emulator available -- see §4.
- [ ] Driver sends a fresh diagnostic log from an active dash on the
      OPPO CPH2591 device, and `ACCESSIBILITY_LIFECYCLE` entries confirm
      or rule out a rapid reconnect pattern.
- [ ] Driver sign-off.
