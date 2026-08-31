# PRD — the dropoff screen's own delivery instruction is parsed, then discarded

Status: IMPLEMENTED and tested (Python half). §5's UX open question
(distinct "from the screen" vs. "new message" source labels) was NOT
answered by the driver -- resolved instead by reusing the existing
"delivery_note:" category unchanged (see PROGRESS.md), not by
inventing a new label scheme. Awaiting user sign-off and on-device
confirmation of the Java-side changes.

## 1. The real bug found

Driver report: "I've also not seen any customer instructions appear as
I near their address." Investigated both possible sources of a
customer instruction in this app and found the primary one is parsed
correctly, then thrown away before it ever reaches the driver.

`DropoffScreenParser.parse()` (`app/src/main/python/drive_monitor.py:1532`)
already extracts the real, free-text delivery instruction directly off
the dropoff screen itself — the note every customer can leave on their
order ("Leave it at the door", "gate code 1234", etc.), confirmed
against two real screenshots per the class's own docstring:

```
Deliver to {customer_name}
by {time}
Call
Message
{street_address}
{suburb}, {state} {postcode}
Unit/Suite
{unit_value}
{delivery_instruction}         e.g. "Leave it at the door"
```

`parse()` returns this as `result["delivery_instruction"]`. But its
only caller, `DasherAccessibilityService.handleDropoffScreen()`
(`app/src/main/java/com/drivingefficiency/app/DasherAccessibilityService.java:634`),
only ever reads `parsed.optString("full_address", ...)` from that same
result — `delivery_instruction` is never read, never passed to
`add_stop_to_buffer`, never logged, never spoken, never shown. It's
computed correctly and then goes nowhere.

Confirmed there's nowhere for it to go even if it were read:
`TripManager.add_stop()` (drive_monitor.py:2036) takes only
`(address, lat, lon)` and builds a stop dict with no instruction
field:

```python
def add_stop(self, address, lat, lon):
    self.stops.append({
        "address": address, "lat": lat, "lon": lon,
        "matched": False, "arrival_time": None,
    })
```

So this isn't a display bug or a triggering-condition bug — the data
the driver is asking for is captured, correctly, on every single
delivery that has a dropoff-screen instruction, and is discarded
before it's stored anywhere.

## 2. The second, separate path — real but secondary

This app also has a wholly separate mechanism for a LATER instruction
that arrives as a customer chat message after the offer was already
accepted (`MessageIntelligence`, `_check_approach_instruction`,
drive_monitor.py:2404) — e.g. "actually leave it at the side door,"
sent mid-delivery. That pipeline is real and already wired end-to-end
(message -> `on_message` -> `_check_approach_instruction` -> overlay),
but two things limit it:

- It only fires for a message that arrives as a genuine notification,
  gated on `is_messaging_style` (`MessageIntelligence.classify`,
  line ~1159) — requires `EXTRA_MESSAGES`/`EXTRA_SELF_DISPLAY_NAME` on
  the notification. Whether the real DoorDash driver app's own
  customer-chat notifications actually carry those extras has not
  been confirmed against a real device the way the offer/dropoff
  screen parsers explicitly were ("built from real screenshots") —
  this is an unverified assumption, not a confirmed one.
- When a message IS seen but fails classification or instruction
  extraction, nothing is logged. Compare `AppNotificationListenerService`'s
  personal-message path (`onNotificationPosted`, ~line 213), which
  logs both `"Read aloud"` and `"Ignored (not on trusted list)"` —
  the work/customer-instruction path has no equivalent "ignored, here's
  why" log line at all. So even if this path IS the reason nothing's
  showing, there's currently no way to confirm that from the
  diagnostic log.

§1 is almost certainly the dominant cause the driver is describing —
it's the one instruction source present on every delivery (not just
ones where the customer happens to send a follow-up message), and it
is unconditionally dropped, not just gated behind an unverified
condition.

## 3. Non-goals

- Not redesigning `MessageIntelligence`/the chat-message pipeline —
  §2's gaps are flagged for awareness, not solved here. If §1's fix
  alone resolves the driver's report, §2 may not need action at all.
- Not touching `OfferScreenParser` or pickup-side instruction handling
  — this is dropoff-instruction specific.

## 4. Proposed design (for review, not yet approved)

1. `handleDropoffScreen` reads `parsed.optString("delivery_instruction", null)`
   alongside `full_address` (both already come from the same
   `parse_dropoff_screen` call — no new Python entry point needed for
   this part).
2. Thread it through to storage: `add_stop_to_buffer` gains an
   `instruction` parameter -> `TripManager.add_stop` gains the same
   -> the stop dict gets a `"delivery_instruction"` field alongside
   `address`/`lat`/`lon`.
3. Surface it through the EXISTING approach-instruction display
   mechanism (`_check_approach_instruction` / `pending_approach_instruction`
   / the overlay already wired to it in Java) rather than building a
   new UI path — that plumbing already works for the chat-message
   case; a dropoff-screen instruction just needs to feed into the same
   `instructions` list for its stop, checked at approach time the same
   way.
4. §2's diagnostic-logging gap (no "ignored, here's why" line for the
   work-message path) is a small, independent, low-risk fix — worth
   doing in the same pass since it's what would let the driver (or a
   future debugging session) actually confirm whether §2 is also
   contributing, without needing another investigation like this one.

## 5. Open questions

- Should a dropoff-screen instruction and a later chat-message
  instruction for the SAME stop both surface (two separate prompts),
  or should a chat message that arrives after the screen-parsed one
  supersede/append to it? Recommend: both surface, clearly labeled by
  source ("From the delivery instructions:" vs. "New message:") —
  simplest, and doesn't risk silently dropping one in favor of the
  other. But this is a UX call, not purely a coding one.
- `DropoffScreenParser.parse`'s own docstring already admits the
  free-text `delivery_instruction` extraction is "best-effort... not a
  fixed set of phrases" and could pick up an unrelated short line on
  an unusual screen layout (only two real screenshots seen). Worth
  the driver knowing this isn't guaranteed-accurate before it starts
  actually surfacing, in case an early false-positive shows up.

## 6. Success criteria

- [x] `delivery_instruction` read in `handleDropoffScreen` and threaded
      through to the stop's stored data.
- [x] Surfaced via the existing approach-instruction overlay mechanism
      when nearing that stop.
- [x] Real executable test (Python side): a stop added with a
      delivery_instruction produces that instruction in
      `_check_approach_instruction`'s output when approached, without
      needing any chat message.
- [x] §2's diagnostic-logging gap closed: the work-message path logs
      why a Dasher/SMS notification was NOT turned into an instruction
      (failed classify, failed extraction), mirroring the personal-
      message path's existing "Ignored (not on trusted list)" logging.
- [ ] On-device confirmation -- **blocked**: no Android emulator/device
      available in this environment.
- [ ] Driver sign-off (ideally after seeing it work on a real delivery,
      given §5's accuracy caveat).
