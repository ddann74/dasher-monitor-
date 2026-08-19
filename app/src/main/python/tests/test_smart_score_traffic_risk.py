"""
FR-9 (PRD.md): traffic risk is checked in priority order and exposes which
tier actually produced the answer via traffic_risk_source.

NOTE -- a real discrepancy found while writing this test: both README.md
("now has three tiers, checked in order: live -> personal -> generic") and
PRD.md's original FR-9 wording describe THREE tiers, but the actual code
(_get_traffic_risk) checks FOUR, in this order: live -> zone
(_get_traffic_risk_by_zone, a specific rounded lat/lon grid cell + hour
combination, requiring >=ZONE_MIN_SAMPLES same-zone-same-hour trips) ->
personal (_is_peak_hour, hour-of-day only, requiring >=5 total trips) ->
generic (hardcoded lunch/dinner window). This test covers the real 4-tier
behavior, not the 3-tier description. Logged here per ralph/PROMPT.md's
instruction to record discovered discrepancies rather than silently
matching stale documentation.
"""
from datetime import datetime

from drive_monitor import Database, SmartScoreEngine


def _ts_at_hour(hour, day=15):
    # Naive local datetime -> timestamp -> datetime.fromtimestamp(...).hour
    # round-trips correctly regardless of the environment's timezone, since
    # both conversions use the same local interpretation.
    return datetime(2026, 1, day, hour, 30, 0).timestamp()


def _insert_trip(db, hour, distance_km, moving_seconds=3600, lat=None, lon=None, day=15):
    db.conn.execute(
        "INSERT INTO trips (start_time, end_time, distance_km, moving_seconds, start_lat, start_lon) "
        "VALUES (?, ?, ?, ?, ?, ?)",
        (_ts_at_hour(hour, day), _ts_at_hour(hour, day) + moving_seconds, distance_km, moving_seconds, lat, lon),
    )
    db.conn.commit()


def test_traffic_generic_tier_off_peak_hour_is_low_risk():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)

    is_high_risk, source = engine._get_traffic_risk(hour_24=10)  # outside 11-14 and 17-20

    assert source == "generic"
    assert is_high_risk is False


def test_traffic_generic_tier_peak_hour_is_high_risk():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)

    is_high_risk, source = engine._get_traffic_risk(hour_24=12)  # inside 11-14 lunch window

    assert source == "generic"
    assert is_high_risk is True


def test_traffic_personal_tier_overrides_generic_once_five_trips_exist():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)
    # 4 fast trips at hour 8 (60 km/h) + 1 slow trip at hour 17 (30 km/h),
    # no lat/lon -> zone tier can never activate (needs non-null coordinates),
    # isolating the personal (hour-of-day-only) tier.
    for _ in range(4):
        _insert_trip(db, hour=8, distance_km=60.0)
    _insert_trip(db, hour=17, distance_km=30.0)

    is_high_risk, source = engine._get_traffic_risk(hour_24=17)

    assert source == "personal"
    # overall_avg = (60*4 + 30) / 5 = 54; this_hour_avg (17) = 30; 30 < 54*0.85=45.9 -> high risk
    assert is_high_risk is True


def test_traffic_zone_tier_overrides_personal_when_available():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)
    zone_lat, zone_lon = 40.0, -70.0
    # 3 slow same-zone-same-hour(9) trips (30 km/h) + 1 faster same-zone
    # trip at a different hour (60 km/h) so the zone's hour-9 average is
    # notably slower than the zone's own overall average.
    for _ in range(3):
        _insert_trip(db, hour=9, distance_km=30.0, lat=zone_lat, lon=zone_lon)
    _insert_trip(db, hour=14, distance_km=60.0, lat=zone_lat, lon=zone_lon)
    # Also seed >=5 unrelated trips so the personal tier WOULD otherwise
    # apply too -- proving zone is genuinely checked first, not just the
    # only data present.
    for _ in range(5):
        _insert_trip(db, hour=9, distance_km=60.0)  # fast, no lat/lon -> personal-tier data only

    is_high_risk, source = engine._get_traffic_risk(hour_24=9, lat=zone_lat, lon=zone_lon)

    assert source == "zone"
    # zone_overall_avg = (30*3 + 60) / 4 = 37.5; zone_hour_avg(9) = 30; 30 < 37.5*0.85=31.875 -> high risk
    assert is_high_risk is True


def test_traffic_live_tier_overrides_zone_and_personal_when_fresh():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)
    zone_lat, zone_lon = 40.0, -70.0
    for _ in range(3):
        _insert_trip(db, hour=9, distance_km=30.0, lat=zone_lat, lon=zone_lon)
    _insert_trip(db, hour=14, distance_km=60.0, lat=zone_lat, lon=zone_lon)

    engine.record_live_traffic_delay(delay_ratio=1.2)  # >= LIVE_TRAFFIC_HIGH_RISK_RATIO (1.15)

    is_high_risk, source = engine._get_traffic_risk(hour_24=9, lat=zone_lat, lon=zone_lon)

    assert source == "live", "a fresh live-traffic reading must win over zone/personal/generic data"
    assert is_high_risk is True
