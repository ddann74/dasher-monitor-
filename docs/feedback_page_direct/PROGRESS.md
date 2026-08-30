# Progress log -- feedback page shown directly, not via notification tap

## Investigation (2026-08-30)

Original driver-reported item: "When I complete an order I want the app
to show me the feedback page not in the notification." Read
`TripForegroundService.notifyRateThisDelivery()`,
`MainActivity`'s existing `auto_show_feedback_trip_id` handling, and
`AppNotificationListenerService.launchDasherApp()` (a proven, already
-shipped solution to the same underlying problem -- reliably bringing an
Activity to the foreground from a background Service).

Unlike `docs/foreground_before_scoring/PRD.md`, this request had one
clear, unambiguous reading -- no root-cause ambiguity requiring a
clarifying question -- so no `AskUserQuestion` round was needed before
writing the PRD.

Found two things worth recording:
1. `notifyRateThisDelivery()`'s own comment already correctly identifies
   *why* it only posts a notification today ("a background service can't
   show a dialog directly") but stops short of the actual fix -- it
   doesn't need to show the dialog itself, only bring `MainActivity` to
   the foreground, which already auto-shows the dialog via the existing
   `auto_show_feedback_trip_id` extra.
2. That same comment was stale: it said tapping the notification "opens
   TripHistoryActivity," but the code has always actually targeted
   `MainActivity` (confirmed via `MainActivity`'s own comment, which
   documents this as an already-fixed real build error from when the
   extra-handling code was originally added to the wrong Activity).
3. Noted, not fixed: `notifyRateThisDelivery` and
   `AppNotificationListenerService`'s `AUTO_LAUNCH_NOTIFICATION_ID` could
   collide on notification id `9200` if `tripId` were ever `0` --
   pre-existing, unrelated to this task, `tripId` is an
   `AUTOINCREMENT` primary key so not actually `0` in practice. Recorded
   in PRD §1.4, explicitly left out of scope.

Wrote `docs/feedback_page_direct/PRD.md`, scoped to
`TripForegroundService.notifyRateThisDelivery()` only.

## Implementation (2026-08-30)

Made the code changes for PRD §6 items 1-4 in one pass:

- Added `FEEDBACK_OVERLAY_AUTO_DISMISS_MS` (20s, matching
  `AppNotificationListenerService`'s `OFFER_OVERLAY_AUTO_DISMISS_MS`
  convention).
- `notifyRateThisDelivery()`: now shows an `OverlayHelper.showMessage`
  overlay ("Delivery complete -- tap to rate it.") first, then attempts a
  direct `startActivity()` launch of `MainActivity` with the existing
  `auto_show_feedback_trip_id` extra -- the same overlay-exemption BAL
  workaround `launchDasherApp()` already uses. The existing fallback
  notification is posted unconditionally afterward (not gated on the
  direct attempt throwing, since a blocked BAL launch fails silently) and
  upgraded from a plain notification to `setFullScreenIntent(pendingIntent,
  true)`, reusing the already-granted `USE_FULL_SCREEN_INTENT` permission
  -- no manifest change needed.
- Corrected the stale `TripHistoryActivity` comment at the
  `TRIP_ACTIVE` -> `IDLE` trigger site to reference `MainActivity`
  correctly, and to point at this PRD.
- `MainActivity.java`: confirmed untouched by diff review -- its
  `auto_show_feedback_trip_id` handling didn't need any change, it
  already does the right thing once `MainActivity` is actually brought to
  the foreground.

Verified by direct review of the changed file (not a build -- no Android
SDK available in this sandbox) -- checked brace balance and confirmed no
naming collision with the new `FEEDBACK_OVERLAY_AUTO_DISMISS_MS`
constant.

**Not done, and can't be from here**: on-device confirmation that the
feedback page actually appears automatically after a real delivery
completes -- no Android emulator/device available in this environment,
same limitation as every other PRD in this repo. Final user sign-off is
the only remaining PRD §6 box.
