# PRD: Fix the latent 9199 notification-ID collision risk

Status: IMPLEMENTED (2026-09-15). Round-13 scouting finding #3 -- the
verification pass run after `docs/monitoring_uptime_guarantee/PREMORTEM.md`'s
7-item Ralph loop closed. Added to the premortem as R22.

## 1. What was wrong

`raiseDasherPackageNotFoundAlert` (added 2026-09-14,
`docs/dasher_package_verification/PRD.md`) posted to a bare hardcoded
`9199`. `raisePermissionRevokedAlert`'s own notification ID is
hash-based: `9100 + Math.abs(permissionName.hashCode() % 100)`, giving it
any value in `9100-9199` depending on the permission name passed in.

Round 11's `docs/notification_id_collision_audit/PRD.md` -- written
*after* the Dasher-package-not-found alert already existed -- documented
`9100-9199` as belonging entirely to `raisePermissionRevokedAlert`'s
scheme, with no mention of 9199's prior claim. Every fix made since
(this session's own Location-Services and Battery-Optimization-Exemption
alerts included) treated that band as fully available to auto-assign
into via the hash formula, with no manual check against 9199.

Independently recomputing the hash formula for all 8 `permissionName`
strings currently in use confirmed none happens to land on 9199 today --
**no active collision exists**. But the band was never actually fully
reserved as the audit's table claimed: any future permission name (or a
rename of an existing one) that happens to hash to `99 mod 100` would
silently overwrite the Dasher-package-not-found alert in the shade, or
vice versa, with zero warning -- exactly the failure shape
`docs/notification_id_collision_audit/PRD.md` was written to eliminate,
and a genuine gap in invariant property 6 ("every ID confirmed disjoint
from every other one currently in use") in
`docs/monitoring_uptime_guarantee/PRD.md`'s §3 that round 11's "full
repo-wide audit" didn't actually catch.

## 2. Design

Moved `raiseDasherPackageNotFoundAlert`'s notification ID off the bare
`9199` literal to a new named constant,
`DASHER_PACKAGE_NOT_FOUND_NOTIFICATION_ID = 9230` -- genuinely outside
the `9100-9199` hash-reserved band, and confirmed disjoint from every
other fixed ID in the app (`9001`, `9002`, `9200`, `9210`, `9220`,
`9300`, `9400`, `9500`) and the `9600-10599` per-trip rate-delivery
range. `9230` sits in the same small gap between
`RECORDING_VERIFICATION_FAILED_NOTIFICATION_ID` (9220) and
`BootAndUpdateReceiver.notifyResumed` (9300) already established by this
file's own prior fixes, matching the existing style of adjacent, clearly
namespaced fixed IDs.

Updated `docs/notification_id_collision_audit/PRD.md`'s own namespace
table to add the new 9230 row and note the correction to its 9100-9199
row, so a future reader of that audit doesn't repeat the same
unaccounted-for assumption.

## 3. Verification

`manager.notify(...)` depends on a live `NotificationManager` and can't
be exercised end-to-end outside a device. Verified instead by:

- `python3`-based brace/paren balance check on the edited file: final
  depth 0.
- A standalone, compiled-and-run Java program
  (`NotificationId9199Test.java`, `javac`/`java`) re-verifying the app's
  full notification-ID namespace, confirmed against a fresh `grep` of
  every fixed ID and the hash formula immediately before writing the
  test:
  - 9230 is confirmed outside the 9100-9199 hash-reserved band.
  - 9230 doesn't collide with any of the 8 current `permissionName`
    strings' hash-based IDs.
  - No two fixed IDs in the full namespace collide with each other (a
    full pairwise duplicate check, 0 found).
  - 9230 is confirmed outside the 9600-10599 rate-delivery range.
  - 9199 is confirmed no longer claimed by any fixed ID -- it's now
    genuinely available to the hash-based scheme without risk of
    silently colliding with the Dasher-package-not-found alert.

  5 checks, all passed on first run.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  "does 9230 actually avoid clobbering anything in the shade on a real
  device" behavior has not been observed.
- Still no structural, compile-time-enforced guarantee against a FUTURE
  new alert picking an ID that collides with something -- this is a
  manual audit and correction, same honest limit
  `docs/notification_id_collision_audit/PRD.md` already disclosed for
  itself. Flagged there as a possible follow-up "if this class of bug
  recurs a third time" -- this is now the third occurrence (round 10,
  round 11, round 13), so that follow-up (a shared ID registry/enum) is
  worth genuinely considering in a future round rather than continuing
  to rely on manual audits alone.

## 5. Success criteria

- [x] `raiseDasherPackageNotFoundAlert` no longer uses an ID inside the
      hash-reserved 9100-9199 band
- [x] The new ID is confirmed disjoint from every other fixed ID and
      reserved range in the app
- [x] `docs/notification_id_collision_audit/PRD.md`'s own namespace table
      updated to reflect the correction
- [x] `python3` brace/paren balance check clean
- [x] Standalone compiled Java test (5 checks) of the full namespace,
      verified against the shipped source, fully passed
- [ ] HONEST LIMIT: no Android device/emulator available -- see §4.
- [ ] Driver/QA confirms on a real device that the Dasher-package-not-found
      alert and every permission-revoked alert coexist independently in
      the shade.
- [ ] Driver sign-off.
