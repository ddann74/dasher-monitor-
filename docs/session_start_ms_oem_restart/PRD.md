# PRD: `sessionStartMs` survives a silent OEM-triggered restart

Status: IMPLEMENTED (2026-09-14), follow-up scouting-pass finding.

## 1. What was wrong

`TripForegroundService.sessionStartMs` (a `public static volatile long`,
set at the top of `startTracking()`) is what
`MainActivity.maybeReviewDeclinedOffersThenShowTripSummary`/
`maybeReviewDeliveryRatingsThenReviewDeclinedOffers` (see docs/
shift_end_decline_reason_review/PRD.md, docs/
zero_interaction_delivery_completion/PRD.md) use to scope "this shift"
for their end-of-shift reviews -- everything since `sessionStartMs` is
this shift's, everything before it isn't.

Being a plain in-memory static field, it does NOT survive a process
death. This app already has a real, previously-documented, and
previously PARTIALLY addressed problem with OEMs (OPPO/ColorOS
specifically observed in a real driver-uploaded diagnostic log)
aggressively killing background processes (see `docs/
boot_resume_monitoring/PRD.md`, `docs/watchdog_reliability/PRD.md`) --
`MonitoringWatchdogReceiver` already exists specifically to detect and
resurrect a silently-killed monitoring session.

The gap: when that resurrection's own `startTracking()` call ran, it
unconditionally reset `sessionStartMs = System.currentTimeMillis()` --
identical code path to a genuine new shift. A driver whose process got
killed and resurrected 40 minutes into a real shift would have every
offer/delivery from before the resurrection silently drop out of "this
shift" for the decline-reason and delivery-rating reviews, with
nothing telling them it happened -- the review would just look emptier
than it should, indistinguishable from an honestly short shift.

## 2. Design

`MonitoringWatchdogReceiver` already has exactly the signal needed to
tell these two cases apart: `wasIntendedActive(context)`, a
SharedPreferences-backed flag set true in `startTracking()` and false
in `stopTracking()` -- durable across process death AND a reboot,
originally built for `docs/boot_resume_monitoring/PRD.md`.

- If `wasIntendedActive()` is **true** when `startTracking()` runs, the
  previous "stop" was never a real Stop Monitoring tap -- this is a
  silent resurrection mid-shift. `sessionStartMs` is RESUMED from a new
  persisted value (`MonitoringWatchdogReceiver.getPersistedSessionStartMs`),
  not reset.
- If it's **false** (a fresh install, or a genuine prior Stop
  Monitoring), this is a real new shift -- `sessionStartMs` resets to
  `System.currentTimeMillis()`, exactly the pre-existing behavior.
- Either way, the resulting value is immediately persisted
  (`persistSessionStartMs`) so the NEXT possible resurrection has
  something real to resume from.
- The read happens at the very top of `startTracking()`, BEFORE
  `MonitoringWatchdogReceiver.markIntendedActive(this, true)` runs
  later in the same method -- reading after that call would always see
  `true` (this call's own write), making the distinction impossible.

No new SharedPreferences file: reuses `MonitoringWatchdogReceiver.
PREFS_NAME`, adding one new key (`session_start_ms`) alongside the
existing `intended_active`/`last_heartbeat_ms`, and two new public
methods (`persistSessionStartMs`/`getPersistedSessionStartMs`) matching
the existing `markIntendedActive`/`wasIntendedActive` encapsulation
style rather than having `TripForegroundService` reach into the prefs
file directly.

## 3. Verification

- Real, compiled, executed Java test (`SessionStartMsResumeTest.java`,
  no external dependencies needed -- pure logic): a fake
  SharedPreferences-backed store mirroring
  `MonitoringWatchdogReceiver`'s exact keys/semantics, exercising a
  faithful copy of the real decision logic added to `startTracking()`.
  4 cases: a fresh install (resets to now, unchanged), a genuine Stop-
  then-Start in the same process (still resets to the new start time,
  unchanged -- confirming this fix does NOT alter the previously-
  documented-correct behavior for a real restart), the actual bug
  scenario (an OEM kill + silent resurrection resumes the real shift
  start instead of resetting it), and a defensive fallback (intended-
  active true but nothing persisted yet -- falls back to now rather
  than crashing or returning 0). All 4 passed.
- Brace/paren balance confirmed on both modified Java files
  (`TripForegroundService.java`, `MonitoringWatchdogReceiver.java`).

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  end-to-end sequence (an actual OEM killing the process, the watchdog
  actually resurrecting it, SharedPreferences actually surviving that)
  has not been observed on a real device. The decision LOGIC is
  verified; the real OS-level trigger conditions are not.
- If the app is fully uninstalled/reinstalled, or the user manually
  clears app data, mid-shift, `intended_active` and the persisted
  `session_start_ms` are both wiped together -- indistinguishable from
  a fresh install, correctly falls back to treating the next Start as a
  new shift. Not a real gap: there's no real prior shift state left to
  resume in that case either way.
- Supersedes the honest-limit bullet in `docs/
  shift_end_decline_reason_review/PRD.md` ss5 that said `sessionStartMs`
  was "verified by reading the code, not observed on a device" for the
  same-process restart case, and adds real handling (not just an
  honest gap) for the cross-process-restart case that PRD didn't cover
  at all.

## 5. Success criteria

- [x] `wasIntendedActive()` read BEFORE `markIntendedActive(true)`
      overwrites it in the same method call
- [x] A genuine Stop-then-Start (same process) still resets
      `sessionStartMs` to the new start time -- not broadened to always
      resume
- [x] A silent OEM-kill resurrection resumes the real shift start
      instead of resetting it -- the actual bug this fixes
- [x] A missing-persisted-value edge case falls back to now safely,
      not to 0 or a crash
- [x] Reuses `MonitoringWatchdogReceiver`'s existing prefs file/
      encapsulation style, no new SharedPreferences file
- [x] Real compiled/executed Java test (4 checks) fully passed
- [x] Brace/paren balance confirmed on both modified files
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see ss4.
- [ ] Driver confirms in real use (or via a future diagnostic log from
      an OPPO/ColorOS-style kill event) that a shift's end-of-shift
      reviews correctly still include offers/deliveries from before a
      mid-shift process restart.
- [ ] Driver sign-off.
