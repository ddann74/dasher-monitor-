# PRD: Fix substring-matching keyword false positives in message classification

Status: IMPLEMENTED (2026-09-14), scouting-pass finding (round 4).

## 1. What was wrong

`MessageIntelligence.extract_instruction` classified a customer message
by plain substring (`kw in lower`) against three keyword lists
(`INSTRUCTION_KEYWORDS`, `ADDRESS_CORRECTION_KEYWORDS`, `LATE_KEYWORDS`).
Plain substring matching has no concept of word boundaries, so a
keyword can match as part of a completely unrelated word:

- `"not "` (in `ADDRESS_CORRECTION_KEYWORDS`) is a substring of
  "can**not** " -- confirmed: `"I cannot find the entrance"` classified
  as `address_correction`, even though nothing about the address was
  actually being corrected.
- `"unit"` (in `INSTRUCTION_KEYWORDS`) is a substring of "opport**unit**y"
  -- confirmed: `"Thanks for the opportunity!"` classified as
  `delivery_note`.

The trailing space some keywords already carried (`"not "`,
`"actually "`, `"it's "`, `"its "`) was itself an ad-hoc, incomplete
attempt at this exact problem -- it only ever guarded the TRAILING
side, never the leading one, and it actively broke matching a keyword
that's the very last word of a message with nothing following it (no
trailing space to match against at all).

Live TTS/overlay text is unaffected (`VoiceAnnouncer.stripCategoryPrefix`
strips the category before speaking, so an urgent message still gets
read aloud correctly either way), but the permanent Trip History record
(`TripDetailActivity.populateInstructions`, via
`VoiceAnnouncer.friendlyCategoryLabel`) shows the wrong category label
-- a normal customer note reading as if the address itself was wrong.

## 2. Design

New `MessageIntelligence._keyword_matches(keyword, lower_text)`: a
proper `\b<keyword>\b` regex match (via `re.search`, `re.escape`-guarded)
instead of a plain substring check, applied uniformly across all three
keyword lists (not just the one the bug was reported in -- the same
substring-matching mechanism is shared code, so the same class of false
positive was equally possible in `INSTRUCTION_KEYWORDS`, confirmed via
the "opportunity"/"unit" case above). `\b` correctly handles the
trailing-space keywords' own apostrophes (`"it's"`) -- boundary checks
apply only at the very start/end of the matched text, not to internal
characters, so `\bit's\b` matches the whole token correctly.

`SPECIAL_INSTRUCTION_KEYWORDS` (`OfferScreenParser`, a different class
entirely) was deliberately left untouched -- it matches fixed, curated
banner text off Dasher's own offer screen (e.g. "scan barcode", "pin
code"), not free-form customer-written text, a much lower collision-risk
context and out of scope for this specific bug.

## 3. Verification

- Real, executable Python test (the actual `MessageIntelligence` class,
  not reimplemented logic): confirmed the two real substring-only false
  positives from the scouting pass no longer mislabel ("...a knot in
  it..." no longer address_correction; "cannot find the entrance" no
  longer address_correction; "Thanks for the opportunity!" no longer
  delivery_note). Confirmed genuine, real matches for the SAME keywords
  still work correctly across all three categories (an actual address
  correction, an apostrophe-containing keyword, a real "unit" mention,
  a real ETA update) -- the fix doesn't break real detection. Also
  confirmed a keyword appearing as the very LAST word with no trailing
  text now matches correctly, a case the old ad-hoc trailing-space
  keywords would have missed entirely. 9 checks, all passed.
- `python3 -m py_compile` clean.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  Trip History display of a corrected classification has not been
  observed on a device.
- Word-boundary matching still doesn't understand SEMANTICS -- a
  message containing the genuine, standalone word "not" anywhere (e.g.
  "Please do NOT knock") still classifies as `address_correction`,
  which is a pre-existing, disclosed keyword-choice precision
  limitation of this whole feature (see `docs/message_intelligence`-
  adjacent PRDs), not something this fix changes or was meant to fix --
  this fix only addresses SUBSTRING false positives, not whether "not"
  is always the semantically ideal keyword for its category.
- Any message classified before this fix shipped keeps its
  already-recorded (possibly wrong) category label in Trip History --
  this fix prevents new misclassifications going forward, it does not
  retroactively correct historical data.

## 5. Success criteria

- [x] "cannot"/"knot"-style pure substring matches no longer trigger
      `ADDRESS_CORRECTION_KEYWORDS`
- [x] "opportunity"-style pure substring matches no longer trigger
      `INSTRUCTION_KEYWORDS`
- [x] Genuine, real matches for the same keywords across all three
      categories still work correctly
- [x] A keyword as the very last word (no trailing text) now matches,
      closing a real gap the old ad-hoc trailing-space keywords had
- [x] Fix applied uniformly to all three keyword lists, not just the
      one instance originally reported
- [x] Real executable Python test (9 checks) fully passed
- [x] `python3 -m py_compile` clean
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver confirms in real use that Trip History message
      classifications read as expected going forward.
- [ ] Driver sign-off.
