"""
FR-6 (PRD.md): $/hr is estimated from a learned per-driver delivery_speed_kmh
once >=1 delivery has completed, not the 25 km/h fallback -- this replaced a
real bug (README.md) where an earlier version used time-until-deadline
instead, which produced a wildly misleading number. Exercises the real
SmartScoreEngine.record_delivery_speed / _learned_delivery_speed_kmh code
path directly.
"""
from drive_monitor import Database, SmartScoreEngine


def test_delivery_speed_is_unlearned_before_any_completed_delivery():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)

    speed_kmh, sample_count, is_learned = engine._learned_delivery_speed_kmh()

    assert is_learned is False
    assert sample_count == 0
    assert speed_kmh == SmartScoreEngine.ASSUMED_DELIVERY_SPEED_KMH


def test_delivery_speed_becomes_learned_after_one_completed_delivery():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)

    # 10km delivery leg completed in 0.5 hours -> 20 km/h.
    engine.record_delivery_speed(distance_km=10.0, time_hours=0.5)

    speed_kmh, sample_count, is_learned = engine._learned_delivery_speed_kmh()
    assert is_learned is True
    assert sample_count == 1
    assert abs(speed_kmh - 20.0) < 1e-9


def test_delivery_speed_running_average_across_multiple_deliveries():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)

    engine.record_delivery_speed(distance_km=10.0, time_hours=0.5)   # 20 km/h
    engine.record_delivery_speed(distance_km=10.0, time_hours=0.25)  # 40 km/h

    speed_kmh, sample_count, is_learned = engine._learned_delivery_speed_kmh()
    assert is_learned is True
    assert sample_count == 2
    # running average of 20 and 40 == 30
    assert abs(speed_kmh - 30.0) < 1e-9


def test_delivery_speed_sanity_guard_rejects_implausible_readings():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)

    engine.record_delivery_speed(distance_km=10.0, time_hours=0.5)  # 20 km/h, accepted
    # 10km in 0.01h = 1000 km/h -- a GPS glitch/jump, must be rejected by the
    # >150 km/h sanity guard, leaving the learned average untouched.
    engine.record_delivery_speed(distance_km=10.0, time_hours=0.01)

    speed_kmh, sample_count, is_learned = engine._learned_delivery_speed_kmh()
    assert sample_count == 1, "the implausible reading must not be counted"
    assert abs(speed_kmh - 20.0) < 1e-9


def test_calculate_reflects_learned_delivery_speed_and_flag():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)

    before = engine.calculate(
        payout=20.0, distance_km=10.0, est_minutes=20.0,
        restaurant_name="Test Restaurant", hour_24=10,
    )
    assert before["delivery_speed_is_learned"] is False
    assert before["delivery_speed_samples"] == 0

    engine.record_delivery_speed(distance_km=10.0, time_hours=0.5)  # 20 km/h

    after = engine.calculate(
        payout=20.0, distance_km=10.0, est_minutes=20.0,
        restaurant_name="Test Restaurant", hour_24=10,
    )
    assert after["delivery_speed_is_learned"] is True
    assert after["delivery_speed_samples"] == 1
    assert abs(after["delivery_speed_kmh"] - 20.0) < 1e-9
