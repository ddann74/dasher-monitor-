# PRD: Stop canned-replies re-seeding after a deliberate deletion

Status: IMPLEMENTED (2026-09-14), scouting-pass finding (round 3).

## 1. What was wrong

The starter-set seeding logic's own comment claimed it "never re-seeds
after the user has... deleted their own replies, so this only ever
runs on a truly fresh database" -- but the actual check was just
`SELECT COUNT(*) FROM canned_replies` `== 0`, which is also true the
instant a driver deletes every canned reply -- a real, legitimate
action, not just a fresh-install state.

`_create_schema()` runs on EVERY engine/process start (crash recovery,
an OEM-kill + watchdog restart, or a driver-triggered database
restore) -- not once ever. Verified directly against the real
`Database` class: seed the 8 defaults, delete every one, reopen the
same on-disk file (exactly what any process restart does) -- all 8
defaults silently reappeared verbatim.

Same underlying pattern as `docs/
trusted_contacts_auto_recovery_overtrigger/PRD.md`, found in the same
scouting pass: "is the current state empty" was being used as a proxy
for "was this never set up," when it's also true after a deliberate
clear-out.

## 2. Design

New `canned_replies_seed_state` table -- a durable marker, separate
from `canned_replies` itself, recording whether the starter set has
EVER been seeded for this specific database file. A single row
(`id = 1`) is inserted the first time seeding logic runs and never
touched again.

The seeding check now gates on this marker instead of `canned_replies`'s
row count:

- Marker absent (a genuinely fresh database, or one that predates this
  fix): seed the starter set ONLY if `canned_replies` is ALSO currently
  empty (a database predating this fix, per the bug above, could only
  ever be non-empty at this point -- either the driver's own content,
  or the OLD buggy logic's own re-seeded defaults, since it re-seeded
  on every single restart). Either way, the marker is inserted
  afterward, seeded or not -- from that point on, `canned_replies`'s
  row count is never consulted again to decide this.
- Marker present: do nothing, regardless of `canned_replies`'s current
  count -- a driver who has since deleted everything stays deleted.

## 3. Verification

- Real, executable Python test (real `Database` class, not
  reimplemented logic): confirmed a genuinely fresh database still
  seeds the 8 starter replies and sets the marker; reproduced the exact
  scouting-pass bug scenario (seed → delete all → reopen, mirroring a
  real process restart) and confirmed the list now correctly STAYS
  empty, across two separate reopens (not just a one-time fluke);
  confirmed a driver's own custom reply added afterward survives a
  restart untouched, with no defaults mixed back in; confirmed an
  EXISTING pre-fix database (real data, but no marker row yet) is
  correctly backfilled with the marker WITHOUT re-seeding on top of its
  existing content. 8 checks, all passed.
- `python3 -m py_compile` clean.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  Canned Replies screen's behavior across a real app restart has not
  been observed on a device.
- The backfill logic for an existing pre-fix database relies on the
  reasoning that such a database can only ever be non-empty at
  migration time (since the old bug re-seeded on every restart, it
  could never have stayed genuinely empty across two restarts). This
  is sound reasoning from reading the old code, not something directly
  observed from a real pre-fix production database.
- Same scope note as the trusted-contacts fix: this addresses the one
  real seeding/deletion path in this codebase. A future bulk-delete or
  reset feature for canned replies specifically (none currently exists
  beyond one-at-a-time `delete_canned_reply`) wouldn't need any special
  handling either, since the marker-based gate no longer cares how the
  table became empty.

## 5. Success criteria

- [x] A genuinely fresh database still seeds the starter set correctly
- [x] Deleting every reply and restarting no longer silently re-seeds
      defaults
- [x] Confirmed stable across multiple restarts, not just once
- [x] A driver's own replies survive a restart untouched
- [x] An existing pre-fix database with real data is backfilled with
      the marker, not re-seeded on top of its content
- [x] Real executable Python test (8 checks, reproducing the actual
      scouting-pass finding) fully passed
- [x] `python3 -m py_compile` clean
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver confirms in real use: deleting all canned replies stays
      deleted across an app restart.
- [ ] Driver sign-off.
