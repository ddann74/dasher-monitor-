"""
FR-7 and FR-8 (PRD.md): deadhead distance and restaurant wait time both use
the same 3-tier fallback pattern -- restaurant-specific average if this
restaurant has history, else the cross-restaurant average, else a hardcoded
default (0.0 km / 6.0 min) if there's no history at all yet. README.md calls
the deadhead=0.0 case a real, previously-hardcoded bug (it used to silently
max the deadhead_score at 100 for every offer); this test protects the fix.
"""
from drive_monitor import Database, SmartScoreEngine


def _insert_deadhead_sample(db, restaurant_name, actual_deadhead_km):
    db.conn.execute(
        "INSERT INTO offer_distance_accuracy (restaurant_name, actual_deadhead_km) "
        "VALUES (?, ?)",
        (restaurant_name, actual_deadhead_km),
    )
    db.conn.commit()


# -- Deadhead distance: SmartScoreEngine._estimate_deadhead_km -------------

def test_deadhead_tier3_default_when_no_history_anywhere():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)

    km, samples, is_restaurant_specific = engine._estimate_deadhead_km("Brand New Restaurant")

    assert km == 0.0
    assert samples == 0
    assert is_restaurant_specific is False


def test_deadhead_tier2_cross_restaurant_average_when_this_restaurant_has_none():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)
    _insert_deadhead_sample(db, "Restaurant A", 2.0)
    _insert_deadhead_sample(db, "Restaurant A", 4.0)

    km, samples, is_restaurant_specific = engine._estimate_deadhead_km("Restaurant B (no history)")

    assert abs(km - 3.0) < 1e-9, "should fall back to the overall average (2.0, 4.0) -> 3.0"
    assert samples == 2
    assert is_restaurant_specific is False


def test_deadhead_tier1_restaurant_specific_average_when_available():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)
    _insert_deadhead_sample(db, "Restaurant A", 2.0)
    _insert_deadhead_sample(db, "Restaurant A", 4.0)
    _insert_deadhead_sample(db, "Restaurant B", 100.0)  # a very different restaurant

    km, samples, is_restaurant_specific = engine._estimate_deadhead_km("Restaurant A")

    assert abs(km - 3.0) < 1e-9, "must use Restaurant A's own average (2.0, 4.0) -> 3.0, not blended with B's 100.0"
    assert samples == 2
    assert is_restaurant_specific is True


# -- Restaurant wait time: SmartScoreEngine._restaurant_wait_info / record_restaurant_wait --

def test_wait_tier3_default_when_no_history_anywhere():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)

    minutes, samples, is_restaurant_specific = engine._restaurant_wait_info("Brand New Restaurant")

    assert minutes == 6.0
    assert samples == 0
    assert is_restaurant_specific is False


def test_wait_tier2_cross_restaurant_average_when_this_restaurant_has_none():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)
    engine.record_restaurant_wait("Restaurant A", 4.0)
    engine.record_restaurant_wait("Restaurant A", 8.0)  # running avg -> 6.0, 2 samples

    minutes, samples, is_restaurant_specific = engine._restaurant_wait_info("Restaurant B (no history)")

    assert abs(minutes - 6.0) < 1e-9
    assert samples == 2
    assert is_restaurant_specific is False


def test_wait_tier1_restaurant_specific_average_when_available():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)
    engine.record_restaurant_wait("Restaurant A", 4.0)
    engine.record_restaurant_wait("Restaurant A", 8.0)  # running avg -> 6.0
    engine.record_restaurant_wait("Restaurant B", 20.0)  # a much slower restaurant

    minutes, samples, is_restaurant_specific = engine._restaurant_wait_info("Restaurant A")

    assert abs(minutes - 6.0) < 1e-9, "must use Restaurant A's own average, not blended with B's 20.0"
    assert samples == 2
    assert is_restaurant_specific is True
