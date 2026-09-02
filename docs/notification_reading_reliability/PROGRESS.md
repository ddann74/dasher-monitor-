# Progress log — personal messages with no extractable sender name

## Implementation (2026-09-02)

Per PRD §4, plus one deeper bug found while implementing it:

- `AppNotificationListenerService.extractPersonalMessageSenderName`
  (new): reads the LAST message's sender `Person` name from
  `EXTRA_MESSAGES` when present (API 28+, guarded -- minSdk is 26),
  falling back to `EXTRA_TITLE` only when `EXTRA_MESSAGES` isn't there
  or carries no usable sender. Never returns null.
- The personal-message branch now logs three DISTINCT outcomes instead
  of two: `"Read aloud (trusted sender: X)"`, `"Ignored -- no usable
  sender name could be extracted..."` (new), and `"Ignored (not on
  trusted list: X)"` -- the first and third existed before, the middle
  one is what closes the diagnostic gap PRD §4 point 2 asked for.
- §5's open question: implemented per its own stated recommendation
  (no driver override was given by the time the ralph-loop
  continuation reached this PRD) -- a name-less message still reads
  aloud when the trusted-contacts list is genuinely empty, stays
  dropped (but now distinctly logged) when a real list exists.

### A second, deeper real bug found while implementing

While tracing exactly how "read anyway when the list is empty" was
supposed to reach a blank sender name, found that
`TrustedContacts.is_trusted` (Python) checked
`if not normalized_sender: return False` BEFORE checking whether the
trusted list was even empty. This meant the documented "no contacts
added yet -> read everything" default (already in this method's own
docstring, already relied on elsewhere) never actually applied to a
blank/unextractable sender name, REGARDLESS of the Java-side fix above
-- a driver with zero trusted contacts configured (i.e. who has
explicitly opted into "read everything") would still have had a
name-less message silently dropped. Fixed by reordering: check
`all_trusted` first, only check the normalized name if the list is
genuinely non-empty. This is a real, independent bug from the Java-
side EXTRA_MESSAGES gap -- fixing only one half would not have solved
the driver's actual "missed one" report.

## Verification (2026-09-02) — ACTUALLY EXECUTED, not just reviewed

Wrote `test_trusted_sender_ordering.py` (scratchpad, throwaway) and
ran it directly against the real, modified `drive_monitor.py` via
plain `python3`. Real output:

```
PASS: blank/None sender name with an empty trusted list reads by default (the bug this fixes)
PASS: blank sender name with a non-empty trusted list still correctly stays rejected
PASS: a real trusted-name match still works exactly as before
PASS: a real untrusted name is still correctly rejected

ALL ASSERTIONS PASSED
```

Also verified: `ast.parse(drive_monitor.py)` clean; brace/paren counts
in `AppNotificationListenerService.java` balanced (0/0) before and
after every edit.

The Java-side `EXTRA_MESSAGES` extraction itself could not be verified
on-device -- no Android emulator/device available in this environment,
and per PRD §6's own note, this specifically needs a real messaging
app's real notification to confirm `EXTRA_MESSAGES`/`Person.getName()`
actually carries what this design assumes. Verified by code review
only: the API-28 guard is correct for this app's minSdk 26, and the
fallback-to-`EXTRA_TITLE` path is unconditionally exercised (and
already covered by the Python-side test above) regardless of whether
the Android-specific extraction works as assumed.

Remaining PRD §6 boxes: on-device confirmation (blocked) and driver
sign-off.
