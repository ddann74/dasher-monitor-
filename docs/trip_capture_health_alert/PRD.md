# Continuous trip-capture health check + alert

STATUS: IMPLEMENTED (2026-09-06)

## 0. Origin

Direct driver request, after being told (accurately, per the real
diagnostic log already investigated in `docs/duplicate_feedback_launch_
fix/PRD.md`) that screen recording was very likely failing silently
much of the time on their OPPO device:

> "just make sure the diagnostic log monitors it, give me a vibration
> alarm if it doesn't work, but dont use the term recording"

Three distinct asks, all implemented here:
1. The diagnostic log should continuously monitor whether it's actually
   working, not just check at specific trigger points.
2. A vibration alarm should fire (and actually persist) if it stops
   working.
3. Nothing user-visible about this should use the word "recording."

## 1. What existed already

Two reactive checks (`TripForegroundService`, near
`startForegroundWithRecording`) already existed, both firing only when
monitoring/mode restarts: "no consent held" and "recorder failed to
start." Both already raised a notification + a one-shot alarm-style
vibration via the existing shared `raisePermissionRevokedAlert`/
`startPermissionAlertVibration` mechanism (the same one used for the 4
critical-permission alerts). Two real gaps:

- Nothing re-checked this on a standing cadence -- if capture died
  silently MID-trip (no restart happened), nothing would notice until
  the next restart, which per the same log could be minutes or hours
  away, or never, for the rest of that trip.
- `anyCriticalPermissionMissing()` (the check that decides whether to
  keep the alarm vibrating past the initial buzz) never looked at
  screen-recording health at all -- so even the EXISTING alerts had
  their vibration silently cancelled by the very next 15-second
  heartbeat tick, regardless of whether the driver had actually done
  anything about it.
- Both existing alerts, and the diagnostic tag, said "Screen Recording"
  in the driver-visible notification.

## 2. What changed

- New `checkTripCaptureHealth()`, called from the existing 15-second
  `accessibilityHeartbeatRunnable` (same cadence already used for the
  accessibility-permission check) -- while monitoring is active, this
  now continuously logs (`SCREEN_RECORDING` tag, unchanged, for
  continuity with past log exports) whether capture is actually
  running, gated on the feature being enabled AND a trip genuinely
  being active (nothing SHOULD be running otherwise, so nothing to
  alert about).
- Edge-triggered like every other check in this file: only logs on a
  real change, and only raises the alert on a genuine
  running-\>not-running transition mid-trip -- not on the ordinary
  "never started at all" case (already covered by the existing
  reactive checks), which would otherwise double-alert the same root
  failure.
- `anyCriticalPermissionMissing()` now also considers trip-capture
  health, so the alarm-style vibration is no longer silently cancelled
  early while capture is still genuinely broken -- it now behaves
  exactly like the other 4 permission alerts (persists until resolved
  or the existing 90-second safety cap, whichever comes first).
- Both existing alert call sites, and the new one, now say
  **"Trip Capture"** instead of "Screen Recording" in the notification
  title/text (and therefore in the ALERT-tagged diagnostic log line
  those alerts write) -- the underlying `SCREEN_RECORDING` diagnostic
  tag on the routine status lines is unchanged, since that's for this
  app's own future log analysis, not something a bystander would see
  in the notification shade.

## 3. Honest limits, disclosed rather than papered over

- The vibration still respects the existing `PERMISSION_ALERT_
  VIBRATION_MAX_MS` (90s) safety cap, same as every other alert here --
  a sustained failure gets one 90-second alarm burst, not an
  indefinite one. This matches this file's own pre-existing, deliberate
  design ("a genuinely unbounded vibration the driver never notices
  would just drain the battery with no benefit"), not a new limitation
  introduced here.
- Renaming the notification's `permissionName` also changes its derived
  Android notification-channel ID (`permission_revoked_alert_trip_
  capture` vs. the old `permission_revoked_alert_screen_recording`).
  Android does not auto-delete the old channel -- it becomes an unused,
  harmless leftover in the device's own notification settings, not
  something this app can clean up remotely.
- This checks whether the recorder is actively producing output right
  now, not whether the resulting file is genuinely non-empty/valid --
  that's a distinct, already-existing concern (`lastStopWasLikelyEmpty`
  handles that separately, at trip end).

## 4. Verification

No Android SDK/emulator in this environment (disclosed limitation,
consistent with every other Java-only change in this repo):
- Full manual trace of the edge-triggered state machine against every
  transition (healthy start, silent mid-trip failure, sustained
  failure across multiple ticks, recovery, feature disabled, trip
  ends) confirmed the alert fires exactly once per real failure, never
  duplicates the reactive checks' own alert for a same-cause failure,
  and the vibration persistence logic behaves correctly in each case.
- Brace/paren balance confirmed on `TripForegroundService.java`
  (205/205 braces, 929/929 parens) after the edit.
- `git diff` reviewed line by line for both renamed call sites and the
  new method.
