# Progress log — dropoff delivery instruction wiring

## Implementation (2026-08-31)

Per PRD §4:

1. `handleDropoffScreen` (Java) now reads `delivery_instruction` from
   the same `parse_dropoff_screen` result `full_address` already comes
   from, logs it under the existing `"DROPOFF"` category, and passes
   it through both `add_stop_to_buffer` call sites (the no-API-key
   placeholder path and the real geocode-callback path).
2. `add_stop_to_buffer` (Python, `DriveMonitorEngine`) and
   `TripManager.add_stop` both gained an `instruction`/
   `delivery_instruction` parameter (default `None`, so the existing
   Developer-Testing call sites that don't pass one keep working
   unchanged). The stop dict now carries `"delivery_instruction"`.
3. `_check_approach_instruction` now includes the stop's own
   `delivery_instruction` (if present) in the same `instructions` list
   already built from chat messages -- tagged with the exact same
   `"delivery_note: "` category prefix `MessageIntelligence.
   extract_instruction` already uses for this kind of note, so it
   flows through `VoiceAnnouncer.stripCategoryPrefix` unchanged, with
   zero new Java-side display logic needed. Surfaces exactly once per
   stop via the existing `_approach_instruction_shown_for_stop_ids`
   guard -- no new state needed.
4. §2/§4's diagnostic-logging gap: `MessageIntelligence.classify`'s
   local `allowed_packages` tuple became the named
   `ALLOWED_PACKAGES` constant so `on_message` can check package
   membership independently. `on_message` now sets
   `self._last_notification_skip_log` (a new single-slot field, same
   consumed-once pattern as `_last_phase_capture_log`/
   `_last_gap_sample_log`) whenever a Dasher/SMS notification fails
   classification or extraction -- deliberately gated on
   `is_candidate_package` so the overwhelming majority of unrelated-app
   notifications never set it (avoiding the same noise every other
   "deliberately not logged" comment in this codebase already avoids).
   New `DriveMonitorEngine.get_last_notification_skip_log()` getter,
   kept SEPARATE from `on_notification`'s own return value rather than
   bundled into it -- `on_notification`'s return is the live TTS/
   overlay trigger `AppNotificationListenerService` reads directly;
   changing its shape risked that real, working path. Java calls the
   new getter right after `on_notification` returns null, logs under a
   new `"WORK_MSG"` category if non-null.

### A bug caught and fixed before it shipped

First version of the Java-side skip-log check called
`.toString()` directly on the `PyObject` result without a null check.
Caught by re-reading the exact same file's own `workResult != null`
guard a few lines above (which exists precisely because Chaquopy maps
a Python `None` return to Java `null`, not a PyObject whose
`.toString()` is the string `"None"`) -- the original version would
have thrown a `NullPointerException` on every single notification that
had no skip reason to log (i.e. almost all of them). Fixed by checking
`skipLogResult != null` first, mirroring the existing `workResult`
pattern exactly.

## §5's open question -- not answered, not guessed

PRD §5 asked whether a dropoff-screen instruction and a later chat
message should carry distinct "from the screen" vs. "new message"
source labels. That's flagged in the PRD itself as a UX call, and the
driver hasn't weighed in. Per RALPH_PROMPT.md's guardrail, this was
NOT invented -- instead, the implementation reuses the `"delivery_note:
"` category that already exists and already displays correctly with
zero new code, which is unambiguous (it's the exact right semantic
category for this content, not a guess) without requiring a new label
scheme. If the driver wants explicit source labels after seeing it
work, that's a small follow-up, not a redesign.

## Verification (2026-08-31) — ACTUALLY EXECUTED, not just reviewed

Wrote `test_dropoff_instruction_wiring.py` (scratchpad, throwaway) and
ran it directly against the real, modified `drive_monitor.py` via
plain `python3`. Real output:

```
PASS: dropoff-screen delivery_instruction surfaces via the existing approach-instruction mechanism
PASS: does not repeat for the same stop
PASS: no delivery_instruction and no messages -> no approach instruction (unchanged behavior)
PASS: dropoff-screen instruction and a chat-message instruction both surface together
PASS: failed-extraction skip reason now logged: Skipped (com.doordash.driverapp): no known instruction keyword matched
PASS: failed-classify skip reason now logged: Skipped (com.doordash.driverapp): not a MessagingStyle notification
PASS: an unrelated app's notification never sets a skip-log (no noise)

ALL ASSERTIONS PASSED
```

Also verified: `ast.parse(drive_monitor.py)` clean; brace/paren counts
balanced in both modified Java files after every edit.

The Java-side changes (screen reading, click-through-to-Python wiring,
the new skip-log check) could not be verified on-device -- no Android
emulator/device available, per PRD §4's own acknowledgment. Verified
by code review only, and by the NullPointerException catch above,
which came from re-reading an existing pattern in the same file rather
than from running it.

Remaining PRD §6 boxes: on-device confirmation (blocked) and user
sign-off.
