# PRD: Fix notification ID collisions (unbounded per-trip ID + a pre-existing fixed-ID clash)

Status: IMPLEMENTED (2026-09-15), scouting-pass finding (round 11,
#2/#3, directed audit of the monitoring process).

## 1. What was wrong

Two distinct notification-ID collisions, both in the same
9200-9500 neighborhood already touched by round 10's fix (which moved
the engine-failure alert from 9300 to 9500 after finding it collided
with `BootAndUpdateReceiver.notifyResumed`):

- **Unbounded per-trip ID** (`TripForegroundService.notifyRateThisDelivery`):
  `manager.notify(9200 + tripId, notification)`. `tripId` is
  `trips.id INTEGER PRIMARY KEY AUTOINCREMENT` -- a value that only
  grows across the entire lifetime of the install, never resets. As it
  passed 10, 100, 200, and 300 completed Dasher-mode trips, this exact
  sum landed on `CONSENT_RECOVERY_NOTIFICATION_ID` (9210), the boot-
  resume notification (9300), `raiseMonitoringNotActiveAlert` (9400),
  and even round 9's own `raiseEngineFailureAlert` (9500) -- whose
  comment explicitly documented moving to 9500 specifically to avoid
  the *previous* known collision, without checking it against this
  additive scheme. Since Android notification IDs are scoped
  per-package (not per-channel), whichever notification posted SECOND
  silently replaced the other in the shade -- a driver doing 30+ trips a
  day would round-trip through this collision zone in under two weeks.
- **Pre-existing, unconditional fixed-ID clash**: `raiseRecordingVerificationFailedAlert`
  posted to a bare hardcoded `9200` -- the exact same ID
  `AppNotificationListenerService.AUTO_LAUNCH_NOTIFICATION_ID` already
  used. `AUTO_LAUNCH` fires on nearly every offer detected (not some
  rare condition), so these two completely unrelated notifications
  could clobber each other in ordinary, routine use.

## 2. Design

Audited every `manager.notify(...)` call site and `*_NOTIFICATION_ID`
constant across the app (`grep` for every literal/constant in the
9000-9999 range) and assigned each a disjoint slot:

| ID / range | Owner |
|---|---|
| 9001, 9002 | `MonitoringWatchdogReceiver` (alert, escalated alert) |
| 9100-9199 | `raisePermissionRevokedAlert` (hash-based, unchanged) |
| 9200 | `AppNotificationListenerService.AUTO_LAUNCH_NOTIFICATION_ID` (unchanged) |
| 9210 | `CONSENT_RECOVERY_NOTIFICATION_ID` (unchanged) |
| **9220** | **`RECORDING_VERIFICATION_FAILED_NOTIFICATION_ID` (NEW -- moved off the colliding 9200)** |
| 9300 | `BootAndUpdateReceiver.notifyResumed` (unchanged) |
| 9400 | `raiseMonitoringNotActiveAlert` (unchanged) |
| 9500 | `raiseEngineFailureAlert` (unchanged) |
| **9600-10599** | **`RATE_DELIVERY_NOTIFICATION_ID_BASE` + `(tripId % RATE_DELIVERY_ID_RANGE)` (NEW -- replaces the unbounded `9200 + tripId`)** |

The per-trip design intent of the rating-prompt notification --
multiple deliveries' rating prompts can coexist independently in the
shade, not overwrite each other -- was deliberately preserved (this is
NOT a behavior regression to "only the latest prompt is ever visible"):
`tripId % RATE_DELIVERY_ID_RANGE` still gives each trip within any
realistic single shift its own distinct ID inside a dedicated,
1000-wide reserved block. The modulo only guards against the ID
eventually wrapping into another reserved range as `tripId` keeps
growing across the install's FULL lifetime (years of use), not against
any realistic within-shift collision.

## 3. Verification

Both notification-posting methods depend on live
`NotificationManager`/`Notification.Builder` and can't be exercised
end-to-end outside a device or emulator in this environment. Verified
instead by:

- `python3`-based brace/paren balance check on the edited file: final
  depth 0.
- A `grep`-based manual audit across every `manager.notify(...)` call
  site and `*_NOTIFICATION_ID` constant in the app, re-run after the
  fix to confirm the final namespace has no textual overlaps left.
- A standalone, compiled-and-run Java program (`javac`/`java`) encoding
  that same final namespace (every fixed ID, both ranges) and the exact
  modulo formula now shipped (confirmed identical to the source via
  `grep` immediately before writing the test):
  - Every fixed alert ID is genuinely unique -- no two hardcoded values
    collide.
  - **The bug scenario**: the exact realistic `trip_id` values named in
    the finding (10, 100, 200, 300), plus a wider sweep (0-5000), no
    longer map to any fixed alert ID.
  - The permission-revoked hash-based range (9100-9199) and the new
    rate-delivery range (9600-10599) don't overlap.
  - Within one realistic shift (50 consecutive `trip_id` values), every
    rate-delivery notification ID is still genuinely distinct from every
    other -- confirms the per-trip uniqueness that actually matters in
    practice (multiple simultaneous rating prompts staying independently
    visible) is preserved, not just "doesn't hit a fixed ID."

  4 checks, all passed on first run.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  "two notifications silently replacing each other in the shade" and
  "does the fixed rating-prompt ID range actually behave as expected on
  a real device across a long install lifetime" behaviors have not been
  observed on a device.
- The 1000-wide `RATE_DELIVERY_ID_RANGE` is a judgment call, not a
  derived constant -- chosen to comfortably exceed any single shift's
  realistic trip count (tens, not hundreds) while leaving room above
  9500 before the next reserved block would need to start. A given
  install running for years could theoretically accumulate enough trips
  that two DIFFERENT trip IDs 1000 apart share a shade slot -- an
  accepted, extremely low-probability tradeoff (a driver would need
  BOTH an ancient, still-unacknowledged rating prompt from roughly a
  year+ ago AND a brand-new one at the same time) rather than something
  eliminated entirely, matching the honest-tradeoff pattern already used
  elsewhere in this codebase for similar bounded-but-not-infinite fixes.
- Does not add a systematic, compile-time-enforced way to prevent a
  FUTURE new alert from picking an already-used ID (e.g. a shared
  registry/enum) -- this fix is a manual audit and correction of the
  current, concrete collisions, not a structural guarantee against a
  new one being introduced later. Noted as a possible follow-up if this
  class of bug recurs a third time.

## 5. Success criteria

- [x] The unbounded `9200 + tripId` scheme no longer collides with any
      fixed alert ID as `trip_id` grows across an install's lifetime
- [x] The per-trip design intent (multiple rating prompts coexisting
      independently) is preserved within any realistic single shift
- [x] The pre-existing 9200 collision between recording-verification-
      failed and the offer-detected auto-launch notification is resolved
- [x] Every notification ID currently used in the app is confirmed
      disjoint via a full repo-wide audit
- [x] Standalone compiled Java test (4 checks) of the exact final
      namespace and formula, verified against the shipped source, fully
      passed
- [x] `python3` brace/paren balance check clean
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver/QA confirms on a real device, across a long-running
      install (100+ completed trips), that no alert notification is ever
      silently replaced by a rating prompt or vice versa.
- [ ] Driver sign-off.
