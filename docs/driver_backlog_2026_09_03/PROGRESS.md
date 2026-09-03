# Progress log — driver backlog triage (2026-09-03)

## Triage (2026-09-03)

Driver sent 36 items in one raw message - bug reports, feature
requests, and plain questions, mixed together, no prioritization, no
numbering. Rather than guess at what was already built versus
genuinely new, spawned a general-purpose agent to investigate each item
against the real code first: check `docs/<topic>/PRD.md` Status headers
for existing coverage, spot-check the actual Java/Python behind any
"IMPLEMENTED" claim rather than trust the doc (this repo has had stale
PRD status headers before), and classify each item.

Result: 15 items already answered/implemented (no new work - listed in
PRD §1 with file:line citations), 3 duplicates (PRD §2), 3 items with a
genuine open question blocking further work (PRD §3 - one incomplete
sentence in the driver's own message, one item with two plausible but
different meanings, one unnamed "the report"), 6 real bugs with no
existing tracking or blocked tracking (PRD §4), and 12 genuinely new
feature requests with no existing design (PRD §5).

Wrote this as a full PRD (`PRD.md`) rather than just relaying the
triage in chat, per the driver's own request, including:
- A recommended priority order (§6) - my own judgment call, disclosed
  as such, not driver-specified.
- A premortem (§7) written for the shape of THIS backlog specifically
  (many independent items across many subsystems, worked one at a time
  over multiple sessions) rather than reusing a single-feature PRD's
  premortem shape - flags schema-drift risk between related items
  (P1), unverified citations the "small" size estimates lean on (P2),
  scope-creep risk on the two large items (P3), the specific danger of
  silently resolving §3's open questions by guessing instead of asking
  (P4), and the accumulating unverified-on-device surface area from
  building this many UI-touching items without a real device in this
  environment (P5).
- A `RALPH_PROMPT.md` adapted from this repo's existing per-feature
  ralph-loop convention, but for a multi-item checklist: follows §6's
  priority order rather than top-to-bottom, requires re-reading sibling
  items before starting anything in P1's schema-drift group, requires
  spot-checking a cited "already exists" function before building on
  top of it (per P2), and explicitly forbids resolving §3's open
  questions by assumption (per P4) - stronger language than this
  repo's usual "use the PRD's own stated recommendation" default,
  because guessing wrong on #4/#17/#26 means building the wrong thing
  entirely, not picking a defensible option among equivalent ones.

No code touched. This PRD's own §9 checklist is the tracking mechanism
for the ralph loop's eventual work through §4/§5 - nothing to check yet.

## #27 implemented (2026-09-03): voice-announce a dash pause

First item per §6's recommended order. `DasherAccessibilityService.java`'s
existing `is_dash_paused_screen` detection block (around line 629)
already stopped GPS tracking and logged `AUTO_PAUSE` on detecting the
Dash Paused screen, but never spoke anything - added one
`VoiceAnnouncer.speak("Dash paused. Monitoring stopped.")` call right
where the pause is detected and acted on, matching this class's
existing voice-announcement style used elsewhere (e.g. the smart-score
readout at line ~935).

**Scope note, per RALPH_PROMPT.md's guardrail against expanding beyond
the chosen item**: while implementing this, noticed the RESUME path
(`attemptAutoStartMonitoring`, called when the Dash Paused screen
clears) is ALSO silent - no voice announcement there either. The
driver's own #27 wording was specifically about missing the "paused"
announcement, and the PRD's own #27 entry scoped the fix to "one
`VoiceAnnouncer.speak()` call at the existing detection point" -
singular, referring to pause. Left resume untouched rather than
silently expanding scope; noted here and in PRD.md's #27 entry as a
candidate for its own, separately-scoped item if the driver wants
symmetric coverage.

**Verification**: same disclosed limitation as every Java-side PRD in
this repo - no Android SDK/emulator/device, so code review plus static
checks. `DasherAccessibilityService.java` brace/paren balance: 151/151
braces, 541/541 parens. Confirmed `VoiceAnnouncer` is in the same
package (`com.drivingefficiency.app`) as this file, no import needed.
Confirmed the new call only fires when `TripForegroundService.isRunning`
is true (same guard as the existing pause-handling code), meaning
`VoiceAnnouncer.init()` (called from `TripForegroundService.onCreate()`)
has always already run by the time this fires - no init-order risk.

PRD §4 box checked. Remaining §6-ordered items: #8, then #6/#9, per the
recommended order.
