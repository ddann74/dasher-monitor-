# PRD: Stop trusted-contacts auto-recovery from undoing a deliberate removal

Status: IMPLEMENTED (2026-09-14), scouting-pass finding (round 3).

## 1. What was wrong

`MainActivity.attemptTrustedContactsAutoRecovery()`'s own comment
claims it "ONLY triggers... after a reinstall or a data reset," but its
actual check is just `existing.length() == 0` -- indistinguishable from
a driver deliberately removing their last trusted contact via
`TrustedContactsActivity.showTrustedContacts()`'s remove-on-tap dialog.

`last_trusted_contacts_file_uri` (the SharedPreferences pointer to the
last saved/loaded contacts file, used to auto-restore) is written on
every save/load and was never cleared on removal. Since
`attemptTrustedContactsAutoRecovery()` runs in `MainActivity.onCreate()`
-- which re-fires on any fresh app process, including the routine
OEM-kill restarts this app already deals with extensively elsewhere --
a driver who intentionally trimmed their trusted list to zero got it
**silently repopulated** from the old saved file the next time the app
happened to restart, with only an easy-to-miss Toast as any indication.
This list "gates which senders' messages get read aloud while
driving" (the code's own words) -- silently reversing a driver's
explicit trust decision is safety/privacy-relevant, not cosmetic.

## 2. Design

`TrustedContactsActivity.clearRecoveryPointerIfListNowEmpty()`, called
after every removal in `showTrustedContacts()`'s dialog click handler:
re-checks the CURRENT list; if it's now empty, clears
`last_trusted_contacts_file_uri` from SharedPreferences. This leaves
auto-recovery nothing to recover FROM the next time it runs, so a
deliberately-emptied list stays empty.

The genuine intended case (a real reinstall or data reset) is
unaffected: that pointer was never touched by an in-app removal in the
first place in that scenario -- the engine's trusted-senders list is
empty because it's a fresh database, not because a removal ran, so
`attemptTrustedContactsAutoRecovery()` still finds the pointer set (it
survived the reinstall, since it's saved to an external file the
system's Storage Access Framework keeps a persistable permission
grant for) and correctly recovers.

## 3. Verification

- Real, compiled, executed Java test (`TrustedContactsRecoveryTest.java`,
  pure logic mirroring both `clearRecoveryPointerIfListNowEmpty` and
  `attemptTrustedContactsAutoRecovery`'s actual gating checks, with
  fakes standing in for the engine's trusted-senders list and the
  SharedPreferences pointer): confirmed removing a driver's LAST
  trusted contact clears the pointer and auto-recovery would correctly
  NOT trigger afterward (the actual bug this fixes); confirmed the
  genuine reinstall/data-reset case still correctly triggers
  auto-recovery (not broken by this fix); confirmed removing one of
  SEVERAL contacts (list stays non-empty) does NOT touch the pointer;
  confirmed adding a new contact after the list was emptied still
  works normally, no lingering bad state. 4 checks, all passed.
- Brace/paren balance confirmed on `TrustedContactsActivity.java`.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  SharedPreferences write/read and the real app-restart timing have not
  been observed on a device.
- This fix is scoped to removal via `TrustedContactsActivity`'s own
  remove-on-tap dialog -- the one real removal path in this codebase
  (confirmed via `remove_trusted_sender` call-site search). If a future
  removal path is ever added elsewhere, it would need the same
  `clearRecoveryPointerIfListNowEmpty()` call to get the same
  protection.
- Doesn't address a related, narrower edge case: a driver could still
  re-empty the list, then IMMEDIATELY re-add a DIFFERENT single
  contact, then restart the app before ever tapping Save -- in that
  specific sequence the pointer stays cleared (correct, since the list
  isn't empty) and nothing is silently restored, so this isn't
  actually a gap, just noted for completeness of the reasoning.

## 5. Success criteria

- [x] Deliberately emptying the trusted-contacts list clears the
      auto-recovery pointer
- [x] The genuine reinstall/data-reset case still correctly
      auto-recovers, unaffected by this fix
- [x] Removing one of several contacts (list stays non-empty) does not
      touch the pointer
- [x] No lingering bad state after re-adding a contact post-empty
- [x] Real compiled/executed Java test (4 checks) fully passed
- [x] Brace/paren balance confirmed
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver confirms in real use: deliberately removing all trusted
      contacts stays removed across an app restart, and a genuine
      reinstall still correctly offers recovery.
- [ ] Driver sign-off.
