# PRD: Make a recognized-but-unparseable dropoff screen loud, not silent

Status: IMPLEMENTED (2026-09-11), driver's own feature-audit finding --
not a specific driver-reported bug. See §3 for the honest limits before
treating this as more than it is.

## 0. What this is / isn't

This does NOT teach `DropoffScreenParser` any new address formats --
that would mean guessing at what an unfamiliar DoorDash dropoff screen
looks like, which this codebase's own discipline explicitly avoids
(`docs/driver_backlog_2026_09_03/PRD.md` §7 P4, "real evidence before
code"). `DropoffScreenParser` itself already discloses, in its own
class doc, that it was "built from exactly two real examples" and that
"more unusual formats (business addresses, ambiguous unit numbering,
apartment complexes with building names) haven't been seen and may not
parse correctly." That disclosed risk is real and unchanged.

What this closes is a SEPARATE gap: when that known risk actually
materializes -- a real dropoff screen that `is_dropoff_screen()`
correctly recognizes, but `parse_dropoff_screen()` can't extract a
usable address from -- nothing said so. `handleDropoffScreen()` just
returned silently, identically to how it behaves when there's genuinely
no dropoff screen showing at all. A driver whose delivery used an
address format this parser doesn't know had no way to tell "arrival
detection isn't working for this one" from "everything's fine, nothing
to report."

## 1. Why this matters

This is safety/routing-relevant, not cosmetic: a delivery whose address
can't be parsed gets ZERO stop registered (not even the old (0.0, 0.0)
placeholder the class doc describes as the pre-parser default) --
arrival detection, the approach-instruction overlay, and anything else
keyed on a registered dropoff stop simply don't fire for that delivery,
with nothing in the log to explain why.

## 2. Fix

`handleDropoffScreen()`'s early return on `full_address == null` now
distinguishes this case explicitly and logs it once (deduped via
`lastDropoffParseFailureLogged`, reset on the next successful parse --
either a later, different delivery, or this same screen eventually
rendering completely -- so a screen stuck unparseable for its whole
dwell time logs once, not on every content-changed tick). The
`DROPOFF` category already used for successful detections carries this
too, just with different wording, so it's easy to grep either outcome
together or apart.

Deliberately NOT changed: the actual behavior when parsing fails (no
stop registered, no placeholder). Investigating whether that's the
right behavior, versus registering a (0.0, 0.0) placeholder the way
pickups still do on a similar failure, would need understanding trip-
completion logic's dependency on registered stops more deeply than
this pass covers -- flagged as a real open question, not silently
decided either way.

## 3. Honest limits

- No Android device/emulator available in this environment, same
  disclosed limitation as every Java-side change in this repo. Whether
  this failure mode has ever actually happened in real use is
  unconfirmed -- this makes an already-disclosed risk visible if/when
  it occurs, it doesn't prove it has.
- **A separate, real issue noticed while reading this code, NOT fixed
  here**: `lastDropoffAddressKey` (the dedup key for a successfully
  parsed address) is never reset anywhere -- not on trip end, not on
  leaving the dropoff screen. If a driver ever delivers to the exact
  same address again in a LATER trip, `handleDropoffScreen()` would
  silently treat it as an already-handled duplicate and skip
  registering it as a stop for the new delivery. Out of scope for this
  pass (a different bug from the one being fixed here), but worth its
  own follow-up.
- Whether registering NO stop (current, unchanged behavior) or a
  (0.0, 0.0) placeholder stop (this class's own doc says that was the
  old, pre-parser default, and pickups still do this on an equivalent
  failure) is the right response to a parse failure is an open
  question this pass deliberately did not resolve -- see §2.

## 4. Success criteria

- [x] A recognized-but-unparseable dropoff screen now logs distinctly
      from both "not a dropoff screen" and "already-handled duplicate
      address"
- [x] Logging throttled to once per unparseable screen instance, not
      once per content-changed tick
- [x] No change to actual stop-registration behavior on a parse
      failure -- visibility only, not a silent behavior change bundled
      in alongside it
- [x] The separately-noticed `lastDropoffAddressKey` never-reset issue
      disclosed explicitly as found-but-not-fixed, not silently ignored
- [x] Brace/paren balance confirmed on the touched file
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §3. Whether this ever actually fires on a real
      unfamiliar address format has not been observed.
- [ ] Driver confirms in real use (or via a future diagnostic log)
      whether this log line ever appears, and if so, what the real
      unrecognized address format looked like -- the actual evidence
      needed to extend `DropoffScreenParser` itself, which this PRD
      deliberately did not attempt without it.
- [ ] Driver sign-off.
