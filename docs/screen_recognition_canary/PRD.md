# PRD: A canary for "the offer screen parser stopped recognizing anything"

Status: IMPLEMENTED (2026-09-11), driver's own feature-audit finding --
not a specific driver-reported bug. See §4 for the honest limits before
treating this as more than it is.

## 0. What this is / isn't

This is NOT a general "detect any DoorDash UI change" system -- that
would require guessing at what a changed screen might look like, which
this codebase's own discipline (`docs/driver_backlog_2026_09_03/PRD.md`
§7 P4, "real evidence before code") explicitly avoids.

This is a narrow, evidence-grounded cross-check: this app already has
TWO independent ways to learn that a real Dasher offer exists --
`AppNotificationListenerService.parse_offer_notification` (a
notification-text parser, confirmed real, `docs/foreground_before_
scoring/PRD.md`) and `DasherAccessibilityService`'s screen-based
`is_offer_screen`/`parse_offer_screen` (a full-screen parser, the one
every other offer feature -- Smart Score, Accept/Decline tracking,
calibration -- ultimately depends on). Until now, nothing ever compared
these two signals against each other. This does exactly that, and
only that: when the notification path independently confirms a real
offer AND Dasher is confirmed to have come to the foreground since,
but the screen-based parser never once confirmed an offer screen in a
reasonable window, that's real, structural evidence something is
wrong with screen recognition specifically -- worth a distinct log
line, not silence.

## 1. Why this matters

Every screen parser in this codebase (`is_offer_screen`,
`is_dropoff_screen`, the store-wait-timer button-text match, the
Accept/Decline detection chain) fails the exact same way on a mismatch:
it just returns false/null. Nothing distinguishes "there's genuinely
nothing to recognize right now" from "DoorDash changed something and
we can no longer recognize what we're looking at" -- a full-scale
DoorDash UI update would look, in this app's own logs, identical to a
quiet afternoon with no offers. Several individual parsers already
disclose being "never confirmed against a real screenshot" as their
own risk (`docs/store_wait_timer/PRD.md`,
`docs/dropoff_delivery_instruction_wiring/PRD.md`); this is the first
general detector for the highest-stakes one (offers) rather than
another single-parser patch.

## 2. Design

- `DasherAccessibilityService.lastOfferScreenConfirmedMs` (new public
  static field, mirrors the existing `lastDasherForegroundMs` pattern)
  -- set in `handleOfferResult` the moment `is_offer_screen` is
  confirmed true, regardless of whether a score was computable yet.
- `AppNotificationListenerService.scheduleOfferScreenRecognitionCanary`
  -- called right after `launchDasherApp` for every notification-
  confirmed real offer. Schedules a check `OFFER_SCREEN_CANARY_DELAY_MS`
  (20s) later: if `lastDasherForegroundMs` is AFTER the notification
  arrived (Dasher genuinely came to the foreground) but
  `lastOfferScreenConfirmedMs` is NOT after it (no offer screen ever
  confirmed in that window), logs a new `SCREEN_MISMATCH` diagnostic
  category naming the restaurant and the gap.

### 2.1 Why it does NOT fire on a launch failure

Deliberately gated on Dasher actually reaching the foreground, not
just on the notification arriving. `launchDasherApp` already has its
own disclosed uncertainty -- a blocked Background Activity Launch
fails silently, so it's already possible Dasher never actually came
to the foreground at all after a notification. That's a DIFFERENT,
already-known problem (see that method's own doc). Firing this canary
in that case too would conflate two unrelated failure modes under one
misleading log line. This only fires for the one combination that
specifically isolates a parser problem: foreground confirmed, screen
recognition still didn't happen.

## 3. Verification

Same disclosed limitation as every Java-side change in this repo -- no
Android SDK/emulator/device available in this environment.

- Brace/paren balance: `AppNotificationListenerService.java` 78/78
  braces, 327/327 parens; `DasherAccessibilityService.java` 192/192
  braces, 722/722 parens.
- Confirmed `lastOfferScreenConfirmedMs` is set unconditionally on
  every real `is_offer_screen==true` event, not gated on score being
  ready -- an offer screen that's recognized but still computing its
  score is still a genuine recognition, and gating on score readiness
  would have produced false-positive canary firings for offers that
  were correctly recognized just slightly slower than 20s.
- Confirmed the canary read happens via the existing public static
  field pattern (no new cross-service binding, no new permission).

## 4. Honest limits

- **No real diagnostic log evidence that this specific failure mode
  (screen recognized nothing despite the notification confirming an
  offer) has ever actually happened.** This is proactive, evidence-
  grounded hardening -- built from the REAL, confirmed fact that every
  parser here fails silently and identically, not from an observed
  instance of it happening. If it never fires in real use, that's
  either genuinely good news (recognition is working) or evidence the
  20s window is wrong -- can't distinguish those without a real log.
- The 20-second delay is a judgment call, not derived from any known
  Dasher timing constant. Too short risks a false positive on a slow
  but otherwise-working launch; too long risks the check running after
  the driver already acted (accepted/declined) and the offer screen
  correctly disappeared on its own, which would ALSO show
  `lastOfferScreenConfirmedMs` as stale even though nothing was
  actually wrong. This second case is a known, accepted source of
  possible false positives -- not eliminated, since doing so would
  require detecting "the offer was correctly resolved" independently,
  which is exactly the thing already unreliable (see
  `docs/driver_backlog_2026_09_03/PRD.md` §15/§21's own Accept/Decline
  detection work).
- Only covers the offer-screen parser, the one place a genuinely
  independent second signal (the notification path) exists. Dropoff,
  store-wait, and other screen parsers have no equivalent independent
  corroborating signal in this codebase, so this pattern couldn't be
  extended to them without inventing one.

## 5. Success criteria

- [x] `lastOfferScreenConfirmedMs` added, set on every real
      `is_offer_screen==true` confirmation
- [x] `scheduleOfferScreenRecognitionCanary` added, called for every
      notification-confirmed real offer
- [x] Deliberately gated on Dasher actually reaching the foreground,
      not just the notification arriving -- doesn't conflate a BAL
      launch failure with a parser recognition failure
- [x] New `SCREEN_MISMATCH` diagnostic category, distinct from every
      existing category, easy to grep for on its own
- [x] Known false-positive source (a correctly-resolved offer whose
      screen legitimately disappeared before the 20s check ran)
      disclosed explicitly, not hidden
- [x] Brace/paren balance confirmed on both touched files
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4. Nothing here has been observed firing (or
      correctly NOT firing) on a real device.
- [ ] Driver confirms in real use (or via a future diagnostic log)
      whether `SCREEN_MISMATCH` ever appears, and if so, whether it
      correlates with an actual missed/unrecognized offer or turns out
      to be the disclosed false-positive case instead.
- [ ] Driver sign-off.
