# PRD: Remove duplicate DriveMonitorEngine method definitions

Status: IMPLEMENTED (2026-09-11), driver's own feature-audit finding --
not a specific driver-reported bug. #3 of a fresh scouting pass, after
`docs/accessibility_service_unbind_cleanup/PRD.md` (#1) and
`docs/trip_child_table_indexes/PRD.md` (#2) from the same pass.

## 0. What this is / isn't

A feature-audit scouting pass found, via a real `ast.parse` walk of
`drive_monitor.py` (not just grep/eye-reading), that `DriveMonitorEngine`
had three methods each defined TWICE inside the same class body:
`get_last_parking_gap_for_feedback`, `clear_last_parking_gap_for_feedback`,
and `recalculate_personal_calibration`. In Python, a class body executes
top-to-bottom and simply rebinds the name each time a `def` with that
name appears -- only the LAST definition in the file is ever actually
callable. Each pair's own docstring told the same story independently:
two separate historical bug-fix passes ("this wrapper was completely
missing from DriveMonitorEngine... every real call threw
AttributeError") had patched the exact same missing-wrapper defect
without either one detecting the earlier fix already sitting in the
file -- so the FIRST definition in each pair was, and had been since
whichever fix landed second, 100% dead, unreachable code.

The scouting pass's own assessment was that all three pairs had
"identical bodies... no live behavioral bug today." **That assessment
was wrong for one of the three pairs** -- verifying each pair by
reading the actual surviving (second/live) body, not assuming from the
pair's shared docstring language, found a real, currently-live bug in
`get_last_parking_gap_for_feedback` (see ss1). This PRD documents both
the dead-code cleanup and that separately-discovered live bug.

## 1. The real, live bug: missing JSON serialization

The FIRST (dead) `get_last_parking_gap_for_feedback` copy correctly
returned `json.dumps(result) if result is not None else json.dumps(None)`.
The SECOND (live, actually-callable) copy simply returned
`self.trip_manager.get_last_parking_gap_for_feedback()` directly -- the
raw Python dict, or `None`.

The only real caller, `MainActivity.java:807`
(`engine.callAttr("get_last_parking_gap_for_feedback").toString()`),
calls `.toString()` on the result and treats it as JSON: it checks for
the literal string `"null"`, then otherwise passes it straight to
`new JSONObject(...)`. A raw Python dict's/`None`'s `str()` is NOT
valid JSON -- `None` stringifies to `"None"` (capital N, never equal to
the literal `"null"` the Java code checks for), and a dict stringifies
with single-quoted keys (`{'restaurant_name': 'X', ...}`), which
`JSONObject`'s parser rejects. Both cases throw, both are silently
caught by the surrounding `catch (JSONException | RuntimeException e)`
(there specifically so this exact kind of failure degrades to a plain
"Parking" label rather than blocking the whole feedback dialog) --
meaning the measured park-to-walk-duration context ("Parking (took 45s
to get moving)") has likely never actually shown in the feedback
dialog on a real device, for any driver, since whichever historical
fix pass introduced this exact (now-live) copy of the method.

Fixed by restoring the `json.dumps(...)` call the dead duplicate
already had, in the surviving live method -- confirmed no other logic
needed to change (`get_database_file_path` output, TripManager's own
return shape, and the Java-side parsing code were all already correct
independently).

## 2. Dead-code cleanup (the other two pairs, and this one's first copy)

- `get_last_parking_gap_for_feedback`: removed the dead first copy
  (the one WITHOUT `json.dumps`, see ss1); the surviving copy is the
  one that already had the historical bug-fix docstring, now also
  fixed per ss1.
- `clear_last_parking_gap_for_feedback`: removed the dead first copy.
  Verified this pair genuinely has no live-bug equivalent to ss1's --
  `TripManager.clear_last_parking_gap_for_feedback` has no `return`
  statement (implicitly returns `None` either way), and the only
  caller (`MainActivity.java`, 3 call sites) invokes it via
  `engine.callAttr("clear_last_parking_gap_for_feedback");` without
  ever reading a return value at all -- so the `return` keyword's
  presence/absence between the two copies was never behaviorally
  observable. Confirmed by reading the real call sites, not assumed
  from the method's shape alone.
- `recalculate_personal_calibration`: removed the dead first copy, but
  first merged its more detailed historical docstring (explaining the
  original AttributeError-on-save-feedback bug and its real-world
  effect on the personal-calibration learning loop) into the
  surviving live copy, which previously had only a generic one-line
  "Wrapper -- see SmartScoreEngine..." docstring. Both copies' bodies
  were identical (`return self.smart_score.recalculate_personal_calibration()`)
  -- confirmed genuinely no live bug here, unlike ss1's pair.

`SmartScoreEngine.recalculate_personal_calibration` (line 1473) and
`TripManager.get_last_parking_gap_for_feedback`/
`clear_last_parking_gap_for_feedback` (the REAL implementations these
wrappers delegate to) are in different classes entirely and were never
part of the duplication -- confirmed via each class's own boundaries
before touching anything, not assumed from a same-name grep hit.

## 3. Verification

- Real `ast.parse` walk of the file (same method the scouting pass
  used to find the duplicates in the first place) confirms exactly ONE
  definition of each of the three method names now exists inside
  `DriveMonitorEngine`.
- Real, runnable Python test against an actual `DriveMonitorEngine`
  instance (real file-backed SQLite, not mocked):
  - Confirmed the no-gap case now returns the literal JSON string
    `"null"` (parses to Python `None` via `json.loads`), matching
    exactly what `MainActivity.java`'s `if (!"null".equals(gapJson))`
    check requires.
  - Confirmed the with-gap case returns valid, correctly-keyed JSON
    (`restaurant_name`, `gap_seconds`, `feedback_id`, `stop_type`) that
    parses cleanly -- the exact fields `MainActivity.java` reads via
    `JSONObject.optString`/`optDouble`/`optInt`.
  - Confirmed, as a negative control, that the PRE-FIX behavior
    (`str()` of the raw dict TripManager returns) genuinely does raise
    `json.JSONDecodeError` when parsed -- proving this was a real bug
    with real impact, not a cosmetic difference.
- `python3 -m py_compile drive_monitor.py` -- clean.

## 4. Honest limits

- No Android device/emulator available in this environment -- the fix
  is verified against real Python/SQLite (the same engine Chaquopy
  runs on-device) and by tracing the exact Java call site's parsing
  logic by reading `MainActivity.java` directly, but the actual
  feedback-dialog behavior on a real device (the "Parking (took Ns to
  get moving)" label actually appearing) is unconfirmed.
- Did not re-audit the rest of `drive_monitor.py` for other wrappers
  with a similar "returns raw object instead of JSON" mismatch beyond
  the three duplicate-definition pairs the scouting pass flagged --
  scoped specifically to what this cleanup touched, not a general
  serialization audit.
- This bug's age is unknown -- git blame/history wasn't consulted;
  it's described here as "likely never worked" based on the docstring
  evidence already in the file (both fix passes' own comments say the
  underlying missing-wrapper bug meant the feature "has likely never
  actually run"), not from any external confirmation.

## 5. Success criteria

- [x] All three duplicate method definitions resolved to exactly one
      each, confirmed via a real AST walk (not just visual inspection)
- [x] Found and fixed a real, live bug (missing JSON serialization)
      that the scouting pass's own initial assessment had missed --
      verified by reading the actual surviving body and the real Java
      caller, not assumed from the pair's shared docstring language
- [x] Preserved the more valuable historical docstring in each
      surviving copy rather than losing it when deleting the dead
      duplicate that happened to hold it
- [x] Confirmed via reading the real call sites (not assumed) that the
      `clear_last_parking_gap_for_feedback` pair has no equivalent live
      bug -- its return value is never used by any caller
- [x] Real executable test proves both the fix's correctness (valid,
      correctly-parseable JSON in both the no-gap and with-gap cases)
      and the bug's reality (a negative control showing the pre-fix
      shape genuinely fails to parse)
- [x] `python3 -m py_compile` -- clean
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see ss4. Real on-device feedback-dialog behavior
      is unconfirmed.
- [ ] Driver confirms in real use: after a delivery that included a
      measurable park-to-walk gap, the post-trip feedback dialog's
      parking question now shows "Parking (took Ns to get moving)"
      instead of the plain generic "Parking" label.
- [ ] Driver sign-off.
