# PRD — the post-delivery rating prompt has never been seen

Status: Step 1 (§5, the safe diagnostic-logging fix) IMPLEMENTED.
Step 2 (the real root-cause fix, candidate A vs. B) remains explicitly
BLOCKED on a real diagnostic log from an actual trip where the prompt
didn't appear — see PROGRESS.md. Do not guess at A vs. B from this PRD.

## 1. What was reported

"I haven't seen the review screen during any of the trips." Investigated
the real trigger path in `TripForegroundService.java` and
`drive_monitor.py`. Unlike the dropoff-instruction bug
(`docs/dropoff_delivery_instruction_wiring/`), this one does NOT have a
single, clean root cause found by reading the code alone — there are
two independent points where it can silently fail, and telling them
apart needs real device data this investigation doesn't have. This PRD
is scoped accordingly: a small, safe diagnosability fix now, and the
real fix deferred until a log confirms which candidate is actually
happening.

## 2. The trigger path, as it actually exists

`notifyRateThisDelivery()` (`TripForegroundService.java:615`) is called
on the genuine "all deliveries complete" transition
(`handleGpsResult`, line 999: `TRIP_ACTIVE -> IDLE`) and on a manual
stop while a trip was active (`stopTracking`, line 868). Both call
sites are real and already wired — this isn't a missing hook.

Inside it, two silent early returns:

```java
JSONObject summary = new JSONObject(engine.callAttr("get_last_trip_summary").toString());
if (!"DASHER".equals(summary.optString("mode", ""))) {
    return; // only real Dasher trips get a feedback prompt, matching the existing gating
}
int tripId = summary.optInt("trip_id", -1);
if (tripId < 0) {
    return;
}
```

Neither branch logs anything. If the prompt never shows, there is
currently no log line anywhere that says WHY — a genuine diagnosability
gap, the same shape as several others already fixed this session
(the `AppNotificationListenerService` personal-message path logs both
"Read aloud" and "Ignored," but this path logs neither of its two
skip reasons).

## 3. Two real candidates for the actual cause — can't be told apart without a log

**Candidate A — the trip's `mode` never became `"DASHER"`.**
`TripManager.get_mode()` (drive_monitor.py:2015) only returns
`"DASHER"` if the Dasher app was detected in the foreground
(`dasher_app_foreground`, set by `DasherAccessibilityService` reading
window-state accessibility events) OR a pickup was registered
(`add_pickup`, fired by real offer-screen parsing) OR a stop was
registered (`add_stop_to_buffer`, fired by real dropoff-screen
parsing — the same call site already investigated in
`docs/dropoff_delivery_instruction_wiring/`). If none of these ever
fire during a real delivery, the trip stays `"GENERAL"` for its whole
duration and the prompt is suppressed by explicit design.

There IS a way to check this after the fact without new code: the app
already logs a `"MODE"` line on every transition
(`TripForegroundService.java:1193`, `onModeChanged` — e.g.
`"GENERAL -> DASHER"`). If a real trip's diagnostic log never shows
that transition, candidate A is confirmed.

**Candidate B — mode WAS `"DASHER"`, but the prompt's delivery
mechanism silently failed.** `notifyRateThisDelivery` tries a direct
foreground launch first (a Background Activity Launch exemption riding
an overlay window), with its own comment admitting: *"a blocked BAL
launch fails SILENTLY -- no exception -- so this can't actually confirm
the direct switch worked."* It falls back to a full-screen-intent
notification, which depends on `POST_NOTIFICATIONS` being granted
(Android 13+) and the `"rate_delivery_prompt"` channel not being
disabled by the driver or an OEM battery-optimization feature. Both of
these already ARE logged today (`"Attempted direct feedback-page
launch..."`, `"Requested feedback-page foreground via full-screen-
intent notification..."`), so — unlike candidate A — this one is
already partially diagnosable from the existing log.

## 4. Non-goals

- Not guessing which candidate is real and building a fix for it
  blind. A's fix (accessibility-detection reliability) and B's fix
  (notification-permission handling) are unrelated code areas — fixing
  the wrong one wastes effort and leaves the driver still not seeing
  the prompt.
- Not touching the DASHER-only gating decision itself (whether GENERAL
  trips should also get a feedback prompt) — that's a product scope
  question, not this bug.

## 5. Proposed design (for review, not yet approved)

**Step 1 (safe, no behavior change, do this regardless of A vs. B):**
add the missing diagnostic logging to `notifyRateThisDelivery`'s two
silent early returns — log the actual `mode` value and `trip_id` when
skipping, mirroring the personal-message path's existing "Ignored,
here's why" shape. This alone doesn't fix anything, but it closes the
gap that's currently blocking a real diagnosis.

**Step 2 (needs a real diagnostic log from an actual trip before it can
be scoped further):** once Step 1 ships and the driver has one real
trip's log where the prompt didn't appear:
- If no `"MODE" ... -> DASHER"` transition appears at all →
  candidate A is confirmed. Next investigation: why
  `dasher_app_foreground`/`add_pickup`/`add_stop_to_buffer` never
  fired for that trip (accessibility-service reliability, a
  materially different and larger investigation than this PRD).
- If the MODE transition DOES appear, and the "Attempted direct
  feedback-page launch" / "Requested feedback-page foreground" lines
  ARE present → candidate B is confirmed. Next investigation: why the
  BAL launch and the full-screen-intent notification both failed to
  actually reach the driver (permission state, OEM notification
  restrictions, Do Not Disturb).

This PRD deliberately stops at "diagnose, then re-scope" rather than
proposing a blind fix for either candidate — same discipline as every
other PRD in this repo (investigate the real, current behavior before
writing code).

## 6. Success criteria

- [x] Step 1: `notifyRateThisDelivery`'s two silent early returns log
      their skip reason (mode value, trip_id).
- [ ] Real diagnostic log from one actual trip where the prompt didn't
      appear, captured and reviewed against §3's two candidates.
- [ ] Root cause identified (A, B, or something not anticipated here).
- [ ] A follow-up PRD (or an addition to this one) scoped to the
      confirmed cause — not written yet, deliberately, since it
      depends on real data this investigation doesn't have.
- [ ] Driver sign-off.
