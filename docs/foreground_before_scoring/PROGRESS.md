# Progress log -- notification-based offer score confidence downgrade

## Investigation + root-cause clarification (2026-08-30)

Original driver-reported item: "Force the dasher app to appear in the
foreground before the smart score can calculate the offer." Read
`AppNotificationListenerService.java` in full and
`DasherAccessibilityService.java`'s screen-based scoring section before
writing anything, since the class's own doc comment states its whole
purpose is scoring "the moment the notification itself arrives,
regardless of what's on screen" -- a literal "block until foreground"
reading would directly regress that deliberate design decision.

Asked the driver to confirm the real problem before guessing between
three plausible readings (notification score inaccurate vs. launch
unreliable vs. just wants it sooner). Confirmed: the notification-based
score is sometimes wrong/incomplete, and the fix is to stop presenting it
with confidence rather than to block on foreground.

Found two additional confirmed bugs while investigating, beyond what was
reported:
1. `launchDasherApp()` was gated on `!"Poor".equals(label)` -- since the
   label comes from the same lenient parser, a misclassified real
   Excellent/Good offer would silently never auto-launch at all. A real
   diagnostic log already on file shows this exact failure mode occurring
   (Dasher's screen never read for the rest of a session after an
   auto-launch attempt, so the accurate badge never got a chance to
   compute).
2. The lenient score was presented as fact in THREE more places beyond
   the voice announcement flagged in the original report:
   `HapticFeedback.vibrateForLabel`, `showOfferOverlayFallback`'s
   `scoreLine`, and the full-screen auto-launch notification's
   `scoreNote` -- the latter two already had an honest "no score
   available" fallback built in, just never used by this code path.

Wrote `docs/foreground_before_scoring/PRD.md` scoping the fix to
`AppNotificationListenerService.java` only, explicitly leaving
`DasherAccessibilityService`'s accurate screen-based scoring untouched.

## Implementation (2026-08-30)

Made the code changes for PRD §6 items 1-6 in one pass:

- `handleDasherNotification`: moved `launchDasherApp(restaurantName,
  -1)` to fire unconditionally, immediately after the offer-dedup check,
  before any smart_score/hasPayout branching. Removed the
  `!"Poor".equals(label)` gate entirely.
- `smart_score != null` branch: removed the score/label/$-per-km/$-per-hr
  voice line and the `HapticFeedback.vibrateForLabel(label)` call.
  Replaced with the same payout-only announcement pattern the
  `hasPayout`-only branch already used ("New offer detected: $X.XX.
  Opening Dasher for the full score."). Confirmed via
  `parse_offer_notification` (Python) that `payout` is always non-null
  whenever `smart_score` is computed, so no extra null-check was needed.
  Diagnostic logging kept, updated to note the score is "lenient... not
  announced" for later parser-accuracy review.
- Since `launchDasherApp` already had `finalScore >= 0 ? <show it> :
  "no score available"` fallbacks built into both
  `showOfferOverlayFallback` and the full-screen notification body,
  passing `-1` from this call site was sufficient to fix those two
  displays too, with no changes needed inside `launchDasherApp` itself.
- Checked `DeveloperTestingActivity.simulateOfferOutcomes()`: writes
  directly to the `offer_outcomes` table (the screen-based
  accepted/declined/timed-out tracking), an unrelated code path --
  doesn't exercise `handleDasherNotification` at all, so no test-hook
  update was needed or possible for this specific change (there's no
  existing way to simulate an incoming Dasher notification from
  `DeveloperTestingActivity`).
- `DasherAccessibilityService.java`: not touched, confirmed by review --
  its `parse_offer_screen` handling and live Smart Score badge are
  unchanged.

Verified by direct review of the changed file (not a build -- no Android
SDK available in this sandbox) -- checked brace balance, confirmed
`HapticFeedback` has no remaining call site or stale import in this file,
and re-read the full method end-to-end against the PRD's design.

**Not done, and can't be from here**: on-device confirmation that the
full-screen intent still reliably brings Dasher to the foreground, and
that the driver actually hears the downgraded announcement instead of the
old one -- no Android emulator/device available in this environment, same
limitation as every other PRD in this repo. Final user sign-off is the
only remaining PRD §6 box.
