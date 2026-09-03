# Progress log — market-relative score thresholds

## Implemented (2026-09-03)

This PRD had sat as a DRAFT (investigation only) since it was first
written, explicitly blocked on the driver saying "yes implement it."
That happened this session, followed directly by answers to all of
§5's open questions (asked via a single set of direct questions, not
guessed at — this PRD's own status line was explicit that guessing
here risked building the wrong mechanism entirely):

- **Design**: §4B (composite-score quartile labeling) — recommended,
  and the one chosen. §4 (per-factor anchors on base_score/hourly_score/
  deadhead_score/wait_score) was NOT built.
- **Percentile/window**: 75th/50th/25th percentile, last 90 days.
- **Sample population**: all scored offers (accepted, declined, timed
  out) — not accepted-only.
- **Circularity floor**: yes — each learned breakpoint can't drop below
  `LABEL_QUARTILE_FLOOR_FRACTION` (0.7) of the corresponding fixed
  breakpoint.

Full design + verification writeup: `PRD.md` §7. Summary: new
`SmartScoreEngine._learned_label_thresholds()` + module-level
`_percentile()` helper; `_label()` converted from a `@staticmethod` to
a real instance method (both existing call sites already called it via
an instance, so no call-site changes needed); `calculate()` computes
the thresholds once and surfaces `label_is_learned`/`label_sample_count`
transparently on its return dict, matching this file's existing "why"
disclosure pattern for every other learned value.

**Verification**: real, runnable Python test
(`test_market_relative_label_thresholds.py`, 7 cases, all passed) —
most importantly, a direct test of the floor mechanism itself (a
uniformly bad market, every offer scoring 20, correctly held at
`85 * 0.7 = 59.5` rather than collapsing "Excellent" down to 20).
`drive_monitor.py` recompiles cleanly. No Java changes needed —
confirmed `colorForLabel`/the badge/haptics all key off the label
string, never a raw score, so this whole change is Python-only. Re-ran
the full existing scratchpad test suite — no regressions.

PRD.md §6 boxes checked except driver sign-off.
