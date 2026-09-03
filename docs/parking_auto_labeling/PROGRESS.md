# Progress -- auto-labeled parking-difficulty samples

- 2026-09-03: Implemented per `PRD.md`. `TripManager._record_park_to_
  walk_gap_sample` now writes a `parking_difficulty_feedback` row
  immediately (tagged `source='auto'`) for every stop with a
  restaurant name, using a new `_auto_parking_difficulty_label`
  threshold (fixed 45s default, learned global average once >= 10 real
  gaps exist -- same shape as the walking-speed and recently-parked-
  window learning already in this file). The driver's existing manual
  Easy/Okay/Hard answer at trip-end now upgrades that same row in
  place (via a new `feedback_id` round-tripped through `get_last_
  parking_gap_for_feedback` -> MainActivity -> `record_parking_
  difficulty_feedback`) instead of inserting a duplicate.
- Schema: added `parking_difficulty_feedback.source` via the existing
  `PRAGMA table_info` + `ALTER TABLE ADD COLUMN` migration pattern;
  pre-existing rows backfill as `'manual'`.
- `get_parking_difficulty_rating` now also returns `manual_sample_
  count`/`auto_sample_count` for provenance transparency; the min-
  sample gate itself (`PARKING_DIFFICULTY_MIN_SAMPLES=3`) is unchanged
  and now reachable from auto data alone.
- Verified before shipping that this can't leak fake data into real
  stats: audited every `on_gps_update` call site in the Java tree.
  Both simulated GPS sequences (`DeveloperTestingActivity.
  simulateDriveAndArrival`, `TutorialActivity.showStepDriving`) hold
  speed at exactly `0.5` km/h (`WALKING_SPEED_MIN_KMH`, strict
  less-than in `is_walking_pace`), so neither ever triggers a genuine-
  park detection or reaches the new auto-write path. Documented as a
  real but currently-safe dependency in `PRD.md` ss4, not a structural
  guarantee -- flagged for future simulated GPS flows, not fixed now
  (no existing flow needs it).
- Noticed, not touched (out of scope): `get_parking_difficulty_rating`
  was already fully built but had zero UI call sites in the Java tree
  before this change -- only `get_address_book` (which calls it
  internally) surfaces it, and that screen was already wired up. No UI
  change was needed for the Address Book to start showing real
  Easy/Normal/Difficult labels sooner.
- Tests: `test_parking_auto_labeling.py`, 8 cases, all passing
  (immediate auto-write, multi-stop persistence, rating-from-auto-only,
  manual-upgrade-not-duplicate, feedback_id=-1 fallback, no-restaurant
  skip + stale-id clearing, learned-baseline switch, real on-disk
  schema migration). Full existing suite re-run clean except the
  known, pre-existing, unrelated `test_dropoff_instruction_wiring.py`
  failure.
- `MainActivity.java` brace/paren balance confirmed unchanged-safe
  (151/151, 700/700) after threading `feedback_id` through the
  feedback dialog.
