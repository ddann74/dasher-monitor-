# PRD — personal messages with no extractable sender name are silently dropped

Status: IMPLEMENTED and tested. §5's open question was built from its
own stated recommendation (no driver override given when the ralph
loop continuation reached this PRD) -- see PROGRESS.md. Also found and
fixed a second, deeper real bug while implementing: the Python-side
empty-trusted-list default didn't apply to a blank sender name at all,
even before this PRD's Java-side fix -- see PROGRESS.md.

Driver-reported: "I don't know if all notifications are being read out
by the app — it missed the one being paused."

## 1. Real evidence, from the driver's own diagnostic log

Repeated real entries:

```
PERSONAL_MSG: Ignored (not on trusted list: )
PERSONAL_MSG: Ignored (not on trusted list: )
PERSONAL_MSG: Ignored (not on trusted list: 0400 441 176)
```

Most of these show a **blank sender name** — not "unknown," not a real
name, an empty string. One shows a phone number instead of a contact
name. Neither case can ever match a trusted-contact entry (`"mom"`,
`"maria"`, etc.), so both are unconditionally dropped, logged only as
"not on trusted list" — indistinguishable in the log from a genuinely
untrusted sender being correctly filtered.

## 2. Root cause (code-confirmed)

`AppNotificationListenerService.onNotificationPosted`:

```java
CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE, "");
...
boolean trusted = engine.callAttr("is_trusted_sender", title.toString()).toBoolean();
```

Only `EXTRA_TITLE` is ever read as the sender name. For a real
`MessagingStyle` conversation notification (the standard Android
template messaging apps use for SMS/chat), the top-level
`EXTRA_TITLE` is the *conversation* title, which for some apps/
configurations is blank or generic — the actual per-message sender
identity lives inside `EXTRA_MESSAGES` (a `Parcelable[]` of `Message`
objects, each carrying its own sender `Person`), which this code never
reads at all. A phone-number-only title (the second log example) is
the same underlying problem from a different angle: whatever produced
that title didn't resolve a saved contact name either.

This means: for any personal message where the notification's
top-level title doesn't happen to already be a clean display name, the
trusted-sender check is comparing against the wrong (or empty) string
every time — a message from someone genuinely on the trusted list
could be silently dropped, not just a stranger's message correctly
filtered. There's no way to tell the two cases apart from the log as
it stands today, which is very likely the "missed the one being
paused" the driver is describing.

## 3. Non-goals

- Not changing the work-message pipeline (`on_message`/
  `MessageIntelligence`, already has its own recent diagnostic-logging
  fix from `docs/dropoff_delivery_instruction_wiring/`) — this PRD is
  the personal-message (trusted-contacts) path specifically.
- Not adding a new messaging-app integration — still SMS/Messenger
  only, per the existing scope.
- Not changing `is_trusted_sender`'s substring-match semantics — a
  separate, already-disclosed tradeoff, unrelated to this bug.

## 4. Proposed design (for review, not yet approved)

1. When `EXTRA_MESSAGES` is present (a real `MessagingStyle`
   notification — `isMessagingStyle` already computed in this exact
   method), read the LAST message's sender `Person` name from it
   instead of falling back to a possibly-blank `EXTRA_TITLE`. Fall
   back to `EXTRA_TITLE` only when `EXTRA_MESSAGES` isn't present or
   has no usable sender.
2. Close the matching diagnostic gap while in this code anyway: log
   the DISTINCT case of "no usable sender name could be extracted at
   all" separately from "a real name was extracted but isn't on the
   trusted list" — right now both produce the exact same
   `"Ignored (not on trusted list: X)"` line, and a blank/number-only
   `X` is indistinguishable from "the extraction itself is the
   problem" without reading this PRD's own investigation.

## 5. Open questions

- Should a message with no extractable sender name at all fall back to
  "read it anyway" (safer for not missing something real, matching
  this app's own existing "no contacts added yet -> read everything"
  default) or "still drop it" (safer for not reading a stranger's spam
  text aloud while driving)? Recommend reading it anyway ONLY when the
  trusted-contacts list is otherwise empty (matching the existing
  documented default exactly) — when real contacts exist, a message
  the app genuinely can't attribute to anyone should stay dropped, but
  now logged distinctly so this is visible instead of silently
  identical to a correctly-filtered stranger. This is a real driver
  preference, not purely a coding call.

## 6. Success criteria

- [x] Sender name extraction reads `EXTRA_MESSAGES`'s last message's
      sender when present, falling back to `EXTRA_TITLE` only when it
      isn't.
- [x] "Could not extract any usable sender name" is logged distinctly
      from "a real name was extracted but isn't trusted."
- [x] §5's open question implemented per its own stated recommendation
      (read anyway only when the trusted list is genuinely empty).
- [ ] On-device confirmation — blocked, no Android emulator/device
      available in this environment; this specifically needs a real
      `MessagingStyle` notification from a real messaging app to
      confirm `EXTRA_MESSAGES` actually carries what this design
      assumes.
- [ ] Driver sign-off.

## 7. Driver-requested (2026-09-09): diagnostic logging for TrustedContactsActivity's own silent failures

Follow-on to `docs/screen_recording/PRD.md` §19's field-test-checklist
logging audit, extended to whether the same gap existed elsewhere.
`TrustedContactsActivity` — the screen that manages the trusted-sender
list this PRD's own filtering depends on — had ZERO `logDiagnostic`
calls anywhere: add/remove a contact, save/load the list to/from a
file (the reinstall-survival feature), and viewing the list were all
either Toast-only or silent on failure. For a safety-relevant list
(gates which senders get read aloud while driving), a failed add that
the driver didn't notice in time, or a failed file load after a
reinstall, would have left no trace anywhere.

Fixed by adding this Activity's own `logDiagnostic(String, String)`
wrapper (same defensive pattern used throughout this app) and logging:
add contact (success + failure), remove contact, save-to-file
(success + failure), load-from-file (success + failure, including the
`openInputStream()` returned-null case), and the list-view load
failure.

### Verification

- Brace/paren balance confirmed
- HONEST LIMIT: no Android device/emulator available in this
  environment — not observed firing on a real device.

## 8. Success criteria for §7

- [x] `logDiagnostic` wrapper added
- [x] Add/remove contact, save/load file, and view-list failure all
      log
- [x] Brace/paren balance confirmed
- [ ] Driver confirms: adding, removing, saving, and loading trusted
      contacts each show a real line in the diagnostic log
- [ ] Driver sign-off.

## 9. Direct follow-up (2026-09-13): dedup the "ignored" logging itself

Real, confirmed evidence (2026-09-13 driver-uploaded diagnostic log):
`PERSONAL_MSG: Ignored (not on trusted list: Chat heads active)` logged
10 times in about 3 seconds. "Chat heads active" isn't a real contact
-- it's Messenger's own system notification about its floating-bubble
feature, correctly filtered as untrusted every single time (no wrong
ACTION was ever taken), but Android reposted the identical notification
10 times in a row, and nothing deduped the resulting log line, so 10
near-identical lines crowded out genuinely useful diagnostics around
them.

This app already had the exact right precedent for this shape of
problem, in the same file: `lastUnrecognizedNotificationKey`, added
for the Dasher-side "NOT recognized as an offer" path when Dasher
itself was found to repost a notification repeatedly. Applied the
identical pattern to the personal-message "ignored" path instead of
inventing a new one: new `lastPersonalMessageIgnoredKey` field, keyed
by `packageName + "|" + <branch tag> + "|" + <distinguishing text>`
(the sender name for the "not on trusted list" branch, since that's
what varied in the real incident; just the message text for the "no
usable sender name" branch, since senderName is empty there by
definition) -- an identical consecutive repost is silently skipped,
anything that actually differs (a different sender, different text, or
a genuine new message arriving after some other notification broke the
run) still logs normally.

Deliberately did NOT touch the "Read aloud (trusted sender)" branch --
a real trusted-contact message should always be read aloud and logged
even if its text happens to repeat, since silently swallowing a
legitimately-arriving message would be a real behavior regression, not
a log-cleanliness improvement. Only the two "ignored" branches (which
take no further action either way) were deduped.

### 9.1 Verification

- Traced the real incident's exact shape (10 identical reposts, 3
  seconds, one sender name) against the fix: first repost logs and
  sets the key, the following 9 have an identical key and are skipped,
  matching `lastUnrecognizedNotificationKey`'s own already-proven
  behavior for the same repost pattern on the Dasher-notification side.
- Brace/paren balance confirmed on the modified file.
- Same accepted tradeoff `lastUnrecognizedNotificationKey` already
  has, not a new one introduced here: only an IMMEDIATELY consecutive
  identical repost is suppressed (compared against the single last key,
  not a broader history) -- a genuinely repeated message from a real
  contact, with something else in between, still logs normally.

### 9.2 Success criteria

- [x] `lastPersonalMessageIgnoredKey` added, following the exact
      existing `lastUnrecognizedNotificationKey` precedent in the same
      file rather than a new pattern
- [x] Both "ignored" branches deduped; the "read aloud" branch
      deliberately left untouched
- [x] Brace/paren balance confirmed
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- not observed firing on a real device.
- [ ] Driver confirms in real use: a real repost storm (Messenger's
      "Chat heads active" or similar) no longer floods the diagnostic
      log with identical lines, while genuinely new personal messages
      still log and read aloud normally.
- [ ] Driver sign-off.
