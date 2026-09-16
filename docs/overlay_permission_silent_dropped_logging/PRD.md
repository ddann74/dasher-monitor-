# PRD: Log OverlayHelper's silent drop when the overlay permission is missing

Status: IMPLEMENTED (2026-09-16). Driver-requested diagnostic addition,
following on directly from `docs/accessibility_lifecycle_logging/PRD.md`
-- same investigation (a driver's skepticism that the Smart Score badge
was ever appearing, on a real OPPO CPH2591/ColorOS device), closing a
second identified blind spot.

## 1. What was wrong

`OverlayHelper.showMessage` -- the single method behind the Smart Score
badge, arrival-instruction overlays, and the status dot -- returned
silently, with zero log line, whenever `SYSTEM_ALERT_WINDOW` ("draw over
other apps") wasn't granted. The class's own doc even already disclosed
this in prose ("If that permission was never granted, this dot silently
never appears at all -- see the troubleshooting note in the README"),
but nothing about it was ever written to the actual diagnostic log a
driver could export and hand over for real diagnosis. If this were ever
the true cause of a missing badge, a diagnostic log would show nothing
distinguishing it from "the badge was never even attempted."

## 2. Design

The single early-return `if (!hasPermission(context) || message == null
|| message.isEmpty())` was split into two: the null/empty-message case
(a normal, uninteresting no-op -- most call sites pass a guaranteed
non-empty string, so this almost never fires for a real reason) stays a
silent return, while the missing-permission case now calls a new
`logDroppedForMissingPermission(context, message)` before returning.

`OverlayHelper` is a static, `Context`-free utility class with no engine
reference of its own (unlike every other class in this codebase that
logs diagnostics through an instance field), so the new helper mirrors
the same `PythonBridge.getEngine(context)` + `FallbackLogger`-safety-net
pattern every other class's `logDiagnostic` already uses, rather than
introducing a new logging mechanism. The message is truncated to 40
characters in the log line -- confirming an overlay call happened and
was dropped is the point, not reproducing the full text.

## 3. Verification

`WindowManager`/overlay permission checks depend on a live Android
runtime and can't be exercised outside a device. Verified instead by:

- `python3`-based brace/paren balance check on the edited file: final
  depth 0.
- A standalone, compiled-and-run Java program
  (`NullScoreAndOverlayLoggingTest.java`, `javac`/`java`, using the real
  `org.json` library already on this environment's classpath),
  replicating `showMessage`'s exact early-return ordering (confirmed
  identical to the real source via a fresh re-read immediately before
  writing the test): an empty message never triggers the drop log (not
  a real overlay attempt), a real message with permission missing IS
  logged as dropped, and a real message with permission granted shows
  normally with no spurious log. 3 of the test's 7 checks cover this
  file specifically (the other 4 cover the companion
  `null_score_offer_logging` fix in the same session).

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  `PythonBridge.getEngine` call succeeding from this static-utility call
  site (as opposed to an instance method) has not been observed on a
  device, though the pattern is identical to ones already proven
  elsewhere in this codebase (e.g. `MonitoringWatchdogReceiver.logToEngine`,
  a similarly Context-based static helper).
- Purely diagnostic -- does not add any new driver-facing alert for a
  missing overlay permission (that's a separate, larger design decision
  -- e.g. whether to surface a persistent warning the way missing
  Location/Accessibility already do -- deliberately out of scope for
  this targeted logging fix).
- Every call site of `showMessage` shares this one log path, so a driver
  missing this permission for an entire shift would see one log line per
  overlay attempt (badge per offer, each arrival instruction, etc.), not
  a single summary -- an accepted tradeoff for simplicity over the
  self-imposed 5-bullet-point rhythm this session has kept for every fix
  so far.

## 5. Success criteria

- [x] A missing overlay permission is now directly visible in the real,
      exportable diagnostic log, not only inferable
- [x] A normal null/empty-message no-op (not a real overlay attempt)
      never produces a spurious log line
- [x] `python3` brace/paren balance check clean
- [x] Standalone compiled Java test confirms the exact early-return
      ordering, verified against the shipped source
- [ ] HONEST LIMIT: no Android device/emulator available -- see §4.
- [ ] Driver sends a fresh diagnostic log; if the overlay permission was
      ever the real cause, an `OVERLAY: showMessage() dropped` line
      confirms it directly.
- [ ] Driver sign-off.
