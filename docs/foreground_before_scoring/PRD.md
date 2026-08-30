# PRD: Don't present the notification-based offer score as authoritative

Status: DRAFT -- awaiting sign-off before implementation begins.
Scope: this one feature only. Not a general codebase pass.

## 0. What this is / isn't

This is a **reliability fix** for `AppNotificationListenerService`'s
instant, notification-based offer scoring -- not a rewrite of it, and not
a change to `DasherAccessibilityService`'s separate, accurate,
screen-based scoring (`parse_offer_screen`). Both paths keep existing;
what changes is how much confidence the notification-based path is
allowed to present before the accurate one has had a chance to run.

Original ask (driver-reported): "Force the dasher app to appear in the
foreground before the smart score can calculate the offer." Root cause,
confirmed with the driver (2026-08-30): the notification-based score is
sometimes wrong or incomplete, and gets voiced/shown before Dasher has
even opened -- the fix is to stop presenting that score with confidence,
and make sure Dasher opens regardless of what it guessed.

## 1. Why (root-cause investigation, code-verified)

Read `AppNotificationListenerService.java` in full (`handleDasherNotification`,
`launchDasherApp`, `showOfferOverlayFallback`) and `DasherAccessibilityService.java`'s
`parse_offer_screen` handling (~L793-830). Findings:

1. **Two separate, independently-computed scores exist for the same
   offer**, by design (see this file's own class doc, L36-47): a
   notification-based score (`parse_offer_notification`, "intentionally
   lenient... built without a real offer-notification sample to
   calibrate against" per its own docstring) fires instantly regardless
   of what's on screen, and a screen-based score
   (`DasherAccessibilityService.parse_offer_screen`) fires once Dasher's
   actual Accept/Decline screen is read, built from real screenshots and
   presented as the live "Smart Score" overlay badge.
2. **Real bug -- the lenient score gates whether Dasher opens at all.**
   `handleDasherNotification` only calls `launchDasherApp()` `if
   (!"Poor".equals(label))` (L298). Since this label comes from the
   lenient, unconfirmed parser, a real Excellent/Good offer misclassified
   as "Poor" would never trigger the auto-launch -- silently costing the
   driver the one thing (an early heads-up) this whole notification path
   exists to provide. Confirmed as a live risk, not hypothetical: a real
   diagnostic log already on file for this app shows a launch attempt
   that led to `DasherAccessibilityService` never reading Dasher's screen
   for the rest of that session, meaning "the on-screen Smart Score badge
   never got a chance to compute or show either" (see
   `launchDasherApp`'s own doc comment, L419-430) -- i.e. this exact
   failure mode (accurate score never appears because the notification
   -based path didn't hand off to it correctly) has already happened for
   real.
3. **Real bug -- the lenient score is presented with full confidence in
   THREE places**, not just voice: `handleDasherNotification`'s
   `VoiceAnnouncer.speak(...)` (L284-285, states the exact score/label/
   rate), `HapticFeedback.vibrateForLabel(...)` (L286, a distinct
   vibration pattern per label), and -- one level down, inside
   `launchDasherApp` itself -- both `showOfferOverlayFallback`'s
   `scoreLine` (L566-568: `"Smart Score: %.0f/100"`) and the full-screen
   auto-launch notification's `scoreNote` (L517: `", score " +
   Math.round(finalScore)"`). All four are driven by the same
   `finalScore`/`label` values from the lenient parser. Notably, the
   latter two (`showOfferOverlayFallback`, the notification body) already
   have an honest fallback built in for exactly this situation --
   `finalScore >= 0 ? <show it> : "Open Dasher for details" /
   "(no score available)"` -- but the smart_score-present branch never
   uses it, because it always has *a* finalScore value (>= 0) even when
   that value is the thing in question.
4. **The other two branches (`hasPayout` only, no payout/distance at
   all) already do this correctly** -- they announce that an offer
   exists (with payout if known) and always launch Dasher, without ever
   presenting a computed score/label as fact. The fix below brings the
   `smart_score != null` branch in line with that same honest pattern,
   rather than inventing a new one.

## 2. Definition of "functional" for this task

- [ ] Dasher is launched for every recognized offer notification,
      regardless of what the notification-based parser guessed the
      label was -- the "Poor" gate on `launchDasherApp()` is removed.
- [ ] No voice announcement, haptic pattern, overlay text, or
      auto-launch notification body presents the notification-based
      `smart_score`'s `final_score`/`label`/`$/km`/`$/hr` as a confident,
      final number. The real Smart Score badge (screen-based,
      `DasherAccessibilityService`) remains the only place a numeric
      score is presented as authoritative.
- [ ] The notification-based path still tells the driver *something*
      useful immediately (offer exists, payout if known) -- this is a
      confidence downgrade, not a removal of the instant-detection
      feature the class doc describes as the whole point of this file.
- [ ] `DeveloperTestingActivity`'s offer-outcome simulation (if it
      exercises this code path) still passes / is updated to match.
- [ ] No change to `DasherAccessibilityService.parse_offer_screen` or the
      live Smart Score badge it drives -- out of scope, already correct.

Non-goals (explicitly out of scope for this task):
- Improving `parse_offer_notification`'s parsing accuracy itself (the
  regex/extraction logic) -- out of scope; this PRD only changes how much
  the *result* of that parsing is trusted for display, not how it's
  computed.
- The BAL (Background Activity Launch) reliability mechanism in
  `launchDasherApp` (full-screen intent + overlay fallback) -- already
  correct per its own documented investigation, not touched here.
- `MessageIntelligence` / customer-message handling in the same file --
  unrelated code path, not touched.

## 3. Design

### 3.1 Always launch, never gated by the lenient label

In `handleDasherNotification`, move `launchDasherApp(restaurantName, -1)`
to fire unconditionally as soon as an offer is recognized (`is_offer` is
true), before the `smart_score`/`hasPayout`/else branching -- removing
the `if (!"Poor".equals(label))` check entirely. Always pass `-1` for the
score parameter (see 3.2) so every downstream display already falls back
to its existing honest "no score yet" text.

### 3.2 Never display the notification-parsed score as final

In the `score != null` branch, stop building the score-specific voice
line (`VoiceAnnouncer.speak` with score/label/$-per-km/$-per-hr) and stop
calling `HapticFeedback.vibrateForLabel(label)`. Replace with the same
pattern the `hasPayout`-only branch already uses: announce the payout (if
present) and that Dasher is opening for the full score, e.g. `"New offer
detected: $X.XX. Opening Dasher for the full score."` -- reusing the
already-extracted `payout` variable, not the derived `smart_score`.
`launchDasherApp` is called with `-1` (per 3.1), so
`showOfferOverlayFallback`'s overlay and the full-screen notification
both already show "Open Dasher for details" / "(no score available)"
without any further change needed in `launchDasherApp` itself.

### 3.3 Diagnostic logging keeps the raw lenient score

`logDiagnostic("OFFER", ...)` calls may keep recording the lenient
`final_score`/`label` for later debugging of the parser's accuracy (per
its own honesty note, it may need correcting against real samples) --
this is a log line reviewed later, not something presented to the driver
in the moment, so it's not subject to the same confidence concern as 3.2.

## 4. Testing / verification approach

No JVM/instrumented test source set exists in this repo (same limitation
noted in `docs/road_warrior_icon/PRD.md`). Verification is by code
inspection plus, if `DeveloperTestingActivity` has an offer-notification
simulation, exercising it manually. On-device confirmation that Dasher's
full-screen intent actually fires and the driver no longer hears a
confident (possibly wrong) score is **flagged as unverifiable in this
environment** -- no Android device/emulator available, consistent with
every other PRD in this repo.

## 5. Open questions

None blocking. The core root-cause question (is this a parsing-accuracy
problem or a presentation-confidence problem) was resolved with the
driver before writing this PRD -- confirmed as presentation-confidence,
scoped accordingly.

## 6. Success criteria (implementation-phase checklist)

- [x] `launchDasherApp()` call in `handleDasherNotification` is
      unconditional (no `"Poor".equals(label)` gate) and fires as the
      first action once an offer is recognized
- [x] `launchDasherApp` is always called with score `-1` from this path,
      so its own existing "no score available" fallbacks (overlay,
      full-screen notification) are what the driver actually sees
- [x] Voice announcement for the `smart_score != null` branch no longer
      states a specific score/label/$-per-km/$-per-hr; announces payout
      (if known) + "opening Dasher for the full score" instead
- [x] `HapticFeedback.vibrateForLabel(label)` call removed from the
      notification-based path (label is unreliable; no haptic feedback
      should be driven by it)
- [x] Diagnostic logging still records the raw lenient score/label for
      later parser-accuracy debugging (unchanged from today)
- [x] `DasherAccessibilityService`'s screen-based scoring and live badge
      untouched, confirmed by diff review
- [ ] User sign-off
