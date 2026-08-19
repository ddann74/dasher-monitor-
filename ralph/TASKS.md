# TASKS — Dasher Monitor

Source of truth: `../PRD.md`. One task = one thing a single Ralph iteration can plausibly
finish (implement + verify) in one pass. Do not batch multiple tasks into one iteration. Check
a box `[x]` ONLY after its stated verification step actually passed — not because the code
"looks right." See `ralph/README.md` for the four verification levels this repo uses (real
build > runnable unit test > manual-review checklist > human-only manual-verification script).

Priority order top to bottom within each section; sections are roughly sequential. Section 1
(environment) gates everything else — do it first.

## 1. Environment check (do this before anything else)
- [x] Determine what's actually available in this environment: try `./gradlew assembleDebug`
      (or `./gradlew tasks` as a cheaper first probe) and separately try
      `python3 -m pytest --version` / `python3 -c "import sys; print(sys.version)"`. Record the
      real outcome of both in `ralph/PROGRESS.log` (exact command + exact output/error) so every
      later task in this file knows which verification levels are actually reachable here,
      instead of re-discovering it each time. This task is done once both are tried and logged,
      regardless of whether either succeeds.
      (Verified 2026-08-19, iteration 1 — see ralph/PROGRESS.log. Result: no Android SDK/Gradle
      wrapper jar in this environment, so `./gradlew` builds are not reachable (Level 1
      unavailable, matches a pre-existing repo-documented gap, not a new problem). Python 3.11.15
      present; pytest installed successfully via pip, network egress to PyPI works — Level 2
      (real `python3 -m pytest` runs against drive_monitor.py) IS reachable for this loop.)

## 2. Verify what's claimed done (README + PRD §4, FR-1 through FR-25)
- [x] Set up a way to unit-test `app/src/main/python/drive_monitor.py` in isolation (no
      Chaquopy/Android needed — it's plain Python). Create `app/src/main/python/tests/` (or
      repo-root `tests/`, pick one and note why in PROGRESS.log) with a minimal
      `test_smoke.py` that just imports the module and asserts the key classes exist
      (`TripManager`, `SmartScoreEngine`, `OfferScreenParser`, `MessageIntelligence`,
      `StopsBuffer`, `Database`, `TrustedContacts`). Verify: `python3 -m pytest` (or
      `python3 -m unittest`) actually runs and passes against this file — paste real output.
      (Verified 2026-08-19, iteration 2 — see ralph/PROGRESS.log. Created
      app/src/main/python/tests/{conftest.py,test_smoke.py}. `python3 -m pytest
      app/src/main/python/tests/test_smoke.py -v`: 2 passed.)
- [x] FR-1: write a unit test for `TripManager` pickup arrival/departure distance tracking —
      feed synthetic GPS points, assert deadhead distance and delivery-leg distance match the
      known synthetic path within a defined tolerance. Verify: test file + real passing run.
      (Verified 2026-08-19, iteration 3 — see ralph/PROGRESS.log. Created
      app/src/main/python/tests/test_trip_manager_pickup_distance.py. `python3 -m pytest
      app/src/main/python/tests/test_trip_manager_pickup_distance.py -v`: 1 passed. Full
      suite: 3 passed.)
- [x] FR-3: write a unit test for `TripManager._evaluate_trip_end` covering both the
      GENERAL-mode parking-ends-trip rule and the DASHER-mode pending-stop rule, including the
      documented case where an active not-yet-departed pickup itself counts as a DASHER signal.
      Verify: test file + real passing run.
      (Verified 2026-08-19, iteration 4 — see ralph/PROGRESS.log. Created
      app/src/main/python/tests/test_trip_manager_trip_end.py, 4 cases. `python3 -m pytest
      app/src/main/python/tests/test_trip_manager_trip_end.py -v`: 4 passed. Full suite:
      7 passed.)
- [x] FR-5: write a unit test asserting `SmartScoreEngine`'s six factor weights sum to 1.00 and
      that a known synthetic input produces the expected composite score. Verify: real passing
      run.
      (Verified 2026-08-19, iteration 5 — see ralph/PROGRESS.log. Created
      app/src/main/python/tests/test_smart_score_engine.py, 3 tests including a fully
      hand-computed synthetic-input score (95.1, "Excellent"). `python3 -m pytest
      app/src/main/python/tests/test_smart_score_engine.py -v`: 3 passed. Full suite:
      10 passed.)
- [x] FR-6: write a unit test confirming `delivery_speed_is_learned` is `false` before any
      completed delivery and `true` (using the running average, not the 25 km/h fallback) after
      one synthetic completed delivery. Verify: real passing run.
      (Verified 2026-08-19, iteration 6 — see ralph/PROGRESS.log. Created
      app/src/main/python/tests/test_smart_score_delivery_speed.py, 5 tests including the
      running average and the >150km/h sanity-guard rejection. `python3 -m pytest
      app/src/main/python/tests/test_smart_score_delivery_speed.py -v`: 5 passed. Full
      suite: 15 passed.)
- [x] FR-7 and FR-8: write unit tests covering the 3-tier fallback (restaurant-specific →
      cross-restaurant average → hardcoded default) for both deadhead distance estimation and
      restaurant wait time, seeding history with 0/1-different-restaurant/1-same-restaurant
      prior records for each tier. Verify: real passing run.
      (Verified 2026-08-19, iteration 7 — see ralph/PROGRESS.log. Created
      app/src/main/python/tests/test_smart_score_deadhead_and_wait.py, 6 tests (3 tiers x 2
      factors), including the key case of a restaurant-specific average NOT blending with a
      different restaurant's data. `python3 -m pytest
      app/src/main/python/tests/test_smart_score_deadhead_and_wait.py -v`: 6 passed. Full
      suite: 21 passed.)
- [x] FR-9: write a unit test forcing each of the three traffic-risk preconditions (live result
      <5 min old / 5+ trips personal average / neither) and asserting `traffic_risk_source`
      reports the correct tier each time. Verify: real passing run.
      (Verified 2026-08-19, iteration 8 — see ralph/PROGRESS.log. IMPORTANT: found the real
      code has FOUR tiers (live/zone/personal/generic), not three as README.md and this task's
      own wording say — flagged for human review, not fixed in this iteration per the
      guardrail against doc-editing as a side effect. Created
      app/src/main/python/tests/test_smart_score_traffic_risk.py, 5 tests covering all 4 real
      tiers including priority-ordering proof (zone beats personal, live beats zone). `python3
      -m pytest app/src/main/python/tests/test_smart_score_traffic_risk.py -v`: 5 passed. Full
      suite: 26 passed.)
- [x] FR-10: write a unit test with a mocked/stubbed Open-Meteo response asserting the weather
      factor score moves in the expected direction for heavy rain vs. clear conditions, and that
      it falls back to neutral 100 when no reading is <15 min old. Verify: real passing run.
      (Verified 2026-08-19, iteration 9 — see ralph/PROGRESS.log. "Mocking Open-Meteo" here
      means calling record_live_weather() directly with known values, the same boundary
      WeatherHelper.java's real async callback uses. Created
      app/src/main/python/tests/test_smart_score_weather.py, 9 tests including both deduction
      caps and the 15-minute staleness fallback. `python3 -m pytest
      app/src/main/python/tests/test_smart_score_weather.py -v`: 9 passed. Full suite:
      35 passed.)
- [x] FR-13: write a unit test for `MessageIntelligence` with representative sample strings for
      each recognized message category (delivery note, address correction, ETA update). Verify:
      real passing run.
      (Verified 2026-08-19, iteration 10 — see ralph/PROGRESS.log. Created
      app/src/main/python/tests/test_message_intelligence.py, 13 tests including a
      priority-order regression (instruction keywords beat address-correction keywords when
      both match). `python3 -m pytest app/src/main/python/tests/test_message_intelligence.py
      -v`: 13 passed. Full suite: 48 passed.)
- [x] FR-16: write a unit test for `TrustedContacts.is_trusted()` covering exact match,
      substring match, case-insensitivity, and a non-matching sender being correctly rejected.
      Verify: real passing run.
      (Verified 2026-08-19, iteration 11 — see ralph/PROGRESS.log. Also tested a real
      documented behavior beyond the task wording: an empty allowlist defaults to trusting
      everyone, not no one. Created app/src/main/python/tests/test_trusted_contacts.py, 8
      tests. `python3 -m pytest app/src/main/python/tests/test_trusted_contacts.py -v`:
      8 passed. Full suite: 56 passed.)
- [ ] FR-17: write a unit test for `TripManager.get_mode()` covering all three independent
      DASHER-triggering conditions (Dasher foregrounded / unmatched stop pending / active
      not-yet-departed pickup) plus the GENERAL default. Verify: real passing run.
- [ ] FR-4: manual-review checklist (Java, not Python-testable without Android) — read
      `TripForegroundService`'s phase-timing code path end to end and confirm all five phases
      (deadhead, pickup wait, delivery leg, parking-to-walking, completing dropoff) are each
      captured by a distinct, correctly-ordered timestamp pair with no gap or double-count.
      Write the checklist and findings into `ralph/PROGRESS.log`. This is a manual-review-level
      task, not a build/test — check the box once the checklist is written and each item
      confirmed against the actual code, not before.
- [ ] FR-18: manual-review checklist — trace every call site of `getRootInActiveWindow()` and
      the text-node walk in `DasherAccessibilityService.java`, confirm each one is unreachable
      unless `isDasher` (or equivalent) is true, including after the `isSelf` early-return added
      for the mode-flapping fix. Write the call-site list and confirmation into
      `ralph/PROGRESS.log`.
- [ ] Attempt `./gradlew assembleDebug` (or the closest available build/lint task) at whatever
      verification level Section 1 found reachable. If a real Android SDK is available, this is
      the first-ever recorded successful build of this repo — a significant milestone, log it
      clearly. If not, log the exact failure and note this as an open gap requiring a human with
      an Android SDK to close, rather than checking the box.

## 3. Confirm the three fixes already committed (fix-watchdog-recovery-and-notification-noise)
- [ ] FR-20/FR-21: manual-review checklist — re-read `MonitoringWatchdogReceiver.onReceive()`
      and confirm the kick-vs-restart branch (`ACTION_KICK_LOCATION_UPDATES` when
      `TripForegroundService.isRunning` is true, `ACTION_START_TRACKING` when false) fires on
      *every* stale-heartbeat tick, not just the first — this was the exact bug in the Aug 7
      outage. If a build/test environment is available, additionally write and run a unit test
      simulating repeated stale-heartbeat firings. Log which verification level was actually
      reached.
- [ ] Messenger false-positive fix: manual-review checklist — confirm
      `MESSENGER_SYSTEM_NOTIFICATION_TITLES` in `AppNotificationListenerService.java` correctly
      short-circuits before the `is_trusted_sender` lookup for "Chat heads active", "Messenger
      Audio call", and "Messenger Video call", and does NOT accidentally also swallow real
      Messenger text messages (which have a different, sender-named title). Log the exact code
      path traced.
- [ ] Mode-flapping fix: manual-review checklist — confirm the `isSelf` early return in
      `DasherAccessibilityService.onAccessibilityEvent()` sits before the debounce block, fires
      only for this app's own package, and doesn't suppress any legitimate cross-app mode
      transition. If a build/test environment is available, additionally simulate a burst of
      self-package `TYPE_WINDOW_STATE_CHANGED` events and confirm no mode-change side effects
      fire. Log which verification level was actually reached.

## 4. Close the stubs (PRD §5, "real vs. stub vs. unconfirmed")
- [ ] Reconcile the stale README TODO section (PRD's top-of-document contradiction note):
      rewrite the three incorrect TODO lines (post-accept address reading, geocoding, battery
      optimization exemption) in `README.md`'s "Notes / TODOs" section to reflect that they ARE
      implemented but UNCONFIRMED on a real device — do not claim them fully done, since no
      device test exists yet. Verify: diff review confirms the new wording matches PRD §5
      exactly (implemented-but-unconfirmed, not done, not still-a-stub).
- [ ] Correct the traffic-risk tier count in both `README.md`'s "Real Google Maps
      integration" section and `PRD.md` FR-9: both currently say the traffic-risk factor has
      THREE tiers (live/personal/generic), but `SmartScoreEngine._get_traffic_risk` actually
      has FOUR (live -> zone -> personal -> generic) — found and verified with a real passing
      test in ralph/PROGRESS.log iteration 8 (`test_smart_score_traffic_risk.py`, which proves
      the zone tier's priority over personal with a real fixture). Update both documents to
      describe the zone tier (what it is, `ZONE_MIN_SAMPLES`, its priority position) rather
      than continuing to omit it. Verify: diff review confirms both docs now match the code's
      real 4-tier behavior.
- [ ] Write the exact human-only manual-verification script for post-accept address
      reading + geocoding (PRD §5 row 1-2): step-by-step instructions for a person with a real
      Dasher account to accept a real offer and confirm the "Deliver to X" address is captured
      and geocoded correctly. Save it as `ralph/manual_verification/post_accept_address.md`.
      This task's verification is the script's existence and completeness, not running it — a
      human runs it later.
- [ ] Write the exact human-only manual-verification script for RoadWarrior's `geo:` deep link
      (PRD §5 row 4). Save as `ralph/manual_verification/roadwarrior_deeplink.md`.
- [ ] Write the exact human-only manual-verification script for capturing one real Dasher
      offer-notification sample and confirming `parse_offer_notification()` extracts the correct
      amount/distance from it (PRD §5 row 5, FR-12). Save as
      `ralph/manual_verification/offer_notification_sample.md`.
- [ ] Write the exact human-only manual-verification script for at least one OEM autostart
      deep-link (PRD §5 row 9, FR-22) — pick Samsung first since it's explicitly named in the
      README. Save as `ralph/manual_verification/oem_autostart_samsung.md`.
- [ ] Investigate whether "Accessibility revoked" alerts (PRD §7 risk #4) correlate with a
      specific trigger visible in the diagnostic log format already established by
      `log_diagnostic()` (time-of-day, battery level if logged, foreground app at time of
      revocation, etc.) — this needs a real diagnostic log to analyze, not code reading alone.
      If no fresh log is available in this iteration, write what log fields would be needed to
      do this analysis and leave the task unchecked with that noted in `ralph/PROGRESS.log`
      rather than guessing.

## 5. Out of reach without new data sources (do not attempt without human input)
- [ ] Peak-hour traffic windows / harsh-brake thresholds personalization (PRD §5): needs the
      same "learn from N trips" pattern already used for deadhead/wait-time/delivery-speed.
      Scope this as a design task first — write the learning approach (what data, what
      threshold, what fallback) into `ralph/PROGRESS.log` under "Flagged for human review" for
      approval before implementing, since it changes user-facing safety-alert behavior.
- [ ] Speed-limit-based speeding detection and fuel cost estimates (PRD §5): both need a new
      external data source not currently used anywhere in this app (map speed-limit API,
      vehicle fuel-efficiency input). Flag for human review with the specific data source options
      rather than implementing without a decision on which one to use.
