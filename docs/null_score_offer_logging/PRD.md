# PRD: Log a genuinely-recognized offer that never gets a Smart Score

Status: IMPLEMENTED (2026-09-16). Driver-requested diagnostic addition,
same investigation as `docs/accessibility_lifecycle_logging/PRD.md` and
`docs/overlay_permission_silent_dropped_logging/PRD.md` (a driver's
real, uploaded diagnostic log showed the one offer detected that whole
week came through with "no payout/distance," and no Smart Score badge
possible for it).

## 1. What was wrong

`DasherAccessibilityService.handleOfferResult` correctly detects when
`parse_offer_screen` recognizes a genuine Dasher offer screen
(`is_offer_screen: true`), but `smart_score` is only attached by the
Python side when BOTH `payout` and `distance_km` were successfully
extracted from the on-screen text. When either failed to parse --
Dasher's exact wording/formatting not matching the regex the parser
expects, a batch/promotional screen variant, or simply a screen still
mid-render -- `handleOfferResult` silently returned with `return; //
Not enough data parsed yet to compute a score.` No trace was left
anywhere: the Smart Score badge just never appeared for that specific
offer, and a driver (or anyone reading their diagnostic log afterward)
had no way to tell "the screen wasn't recognized as an offer at all"
apart from "it was recognized, but the score-relevant fields never
parsed" -- two very different problems requiring different fixes.

## 2. Design

Added a log line inside the existing `if (score == null)` branch,
reporting the restaurant name (already parsed even when payout/distance
aren't) and specifically which of `payout`/`distance_km` failed to
parse, using `JSONObject.isNull(...)` (true for both an absent key and a
JSON `null` value, matching how Python's `None` serializes).

Deduplicated via a new `lastNullScoreOfferLogged` field, reset whenever
the offer screen disappears (alongside the existing `lastOfferKey =
null` reset) -- `handleOfferResult` fires on every
`TYPE_WINDOW_CONTENT_CHANGED` accessibility event while an offer screen
is showing, which for a screen stuck without a computable score could
otherwise produce the same log line dozens of times per second. Logging
once per distinct offer occurrence keeps this proportional to real
events, matching the rhythm every other event-driven diagnostic log in
this file already follows.

## 3. Verification

`handleOfferResult` depends on a live `AccessibilityService` and the
real Chaquopy `engine` reference, and can't be exercised outside a
device. Verified instead by:

- `python3`-based brace/paren balance check on the edited file: final
  depth 0.
- Confirmed the exact JSON key names (`payout`, `distance_km`,
  `restaurant_name`) against `OfferScreenParser`'s real output in
  `drive_monitor.py` before writing the log line, so the reported field
  names match reality.
- A standalone, compiled-and-run Java program
  (`NullScoreAndOverlayLoggingTest.java`, `javac`/`java`, using the real
  `org.json` library), replicating the exact dedup and field-reporting
  logic (verbatim-copied and confirmed identical to the real source via
  a fresh re-read immediately before writing the test):
  - **The actual bug scenario**: an offer screen recognized with a
    parsed distance but unparsed payout correctly logs exactly that
    ("payout NOT parsed, distance parsed").
  - **Dedup**: a repeated tick for the same still-unscored offer does
    not log a second time.
  - **Reset**: once the offer screen disappears and a fresh one appears,
    the dedup key resets and a new log line correctly fires.
  - **Regression check**: a genuinely scored offer never enters this
    branch at all -- takes the normal, already-working path.

  4 of this test file's 7 checks cover this fix specifically (the other
  3 cover the companion `overlay_permission_silent_dropped_logging` fix
  made in the same session).

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  `TYPE_WINDOW_CONTENT_CHANGED` firing cadence while a real offer screen
  is stuck mid-render has not been observed on a device; the dedup
  logic is verified against the exact conditional, not against real
  event-timing behavior.
- Does not distinguish WHY payout/distance failed to parse (a UI-text
  mismatch vs. a batch/promotional screen variant vs. a screen still
  mid-render) -- only that they did. `docs/screen_recognition_canary/PRD.md`
  is the existing, separate mechanism for the broader "is Dasher's UI
  drifting from what this parser expects" question; this fix is scoped
  to the narrower, already-recognized-as-an-offer case.
- Does not add any driver-facing alert or fallback message for a
  score-less offer -- purely diagnostic, matching this session's other
  two logging-only fixes from the same investigation.

## 5. Success criteria

- [x] A genuinely recognized offer screen that never gets a computable
      score is now directly visible in the diagnostic log, distinct from
      "the screen wasn't recognized as an offer at all"
- [x] The log reports specifically which of payout/distance failed to
      parse
- [x] Logged once per distinct offer occurrence, not spammed on every
      content-changed tick
- [x] A genuinely scored offer is confirmed to never enter this log
      branch (no regression to the normal, working path)
- [x] `python3` brace/paren balance check clean
- [x] Standalone compiled Java test of the exact dedup and
      field-reporting logic, verified against the shipped source
- [ ] HONEST LIMIT: no Android device/emulator available -- see §4.
- [ ] Driver sends a fresh diagnostic log from a real dash; if an offer
      ever fails to score, an `OFFER: Offer screen recognized (...) but
      no Smart Score computable` line confirms it directly, with the
      specific missing field named.
- [ ] Driver sign-off.
