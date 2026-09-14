# PRD: Strip newlines from extracted_instruction in the full report

Status: IMPLEMENTED (2026-09-14), scouting-pass finding (round 5).

## 1. What was wrong

`export_full_report`'s MESSAGES section scrubs internal newlines from
`body` (`body_clean = (r["body"] or "").replace("\n", " ")`) but not
from the adjacent `extracted_instruction` column, even though
`extracted_instruction` is derived from that same `body` text
(`MessageIntelligence.extract_instruction`'s
`f"delivery_note: {body.strip()}"` only strips LEADING/TRAILING
whitespace, not internal newlines) and can carry the identical
embedded newlines a real multi-line customer message has (e.g. "Leave
at door\nGate code 1234\nThanks!").

Since `_format_table` joins each logical row into one fixed-width
physical line, an embedded newline in one cell splits that row across
multiple physical lines in the plain-text/PDF report -- misaligning
that one row's own columns (localized to that row, not the whole
report). The `.csv` export (`export_trips_csv`) was checked separately
and already correctly escapes its one free-text field for both commas
and newlines -- this gap was specific to the full-report table
formatter.

## 2. Design

One-line fix: `instruction_clean = (r["extracted_instruction"] or "").replace("\n", " ")`,
matching exactly what `body_clean` already does on the line above it.

## 3. Verification

- Real, executable Python test, through the actual, complete
  `export_full_report()` call (not just the isolated string-replace
  logic): inserted a real trip and a real message with a genuine
  multi-line body, confirmed `extract_instruction` itself still
  preserves the embedded newlines (so the test's input genuinely
  exercises the bug path), then parsed the real generated report's
  MESSAGES section and confirmed it now renders as exactly ONE data
  row (not split across multiple physical lines), with the full
  instruction text -- including what used to be on separate
  lines -- intact on that single row. 3 checks, all passed.
- `python3 -m py_compile` clean.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  PDF rendering of a corrected row has not been observed on a device
  (though the underlying text produced by `export_full_report` is
  identical between the plain-text and PDF export paths -- the PDF
  export just draws the same string line-by-line, per
  `DataManagementActivity.exportFullReportAsPdf`).
- Purely a report-formatting fix -- does not touch the stored
  `extracted_instruction` value itself (still contains the real
  newlines in the database, which is correct: only the flattened
  report OUTPUT needed fixing, not the underlying data other features
  read, e.g. `TripDetailActivity.populateInstructions`, which displays
  multi-line instructions correctly on their own lines in the UI and
  should keep doing so).

## 5. Success criteria

- [x] `extracted_instruction` newlines are stripped the same way
      `body` already is
- [x] Verified through the real, complete `export_full_report()` call,
      not just the isolated fix in unit
- [x] Confirmed the underlying test input genuinely still contains
      embedded newlines before asserting the output doesn't
- [x] Real executable Python test (3 checks) fully passed
- [x] `python3 -m py_compile` clean
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver confirms in real use that a multi-line customer
      instruction renders as one clean row in the full report.
- [ ] Driver sign-off.
