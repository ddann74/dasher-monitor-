"""
FR-5 (PRD.md): SmartScoreEngine computes a 6-factor weighted composite score,
weights summing to 1.00, matching a known synthetic input's hand-computed
expected value.
"""
from drive_monitor import (
    Database,
    SmartScoreEngine,
    WEIGHT_BASE_RATE,
    WEIGHT_HOURLY_RATE,
    WEIGHT_DEADHEAD,
    WEIGHT_RESTAURANT_WAIT,
    WEIGHT_TIME_OF_DAY,
    WEIGHT_WEATHER,
)


def test_base_weights_sum_to_one():
    total = (
        WEIGHT_BASE_RATE + WEIGHT_HOURLY_RATE + WEIGHT_DEADHEAD
        + WEIGHT_RESTAURANT_WAIT + WEIGHT_TIME_OF_DAY + WEIGHT_WEATHER
    )
    assert abs(total - 1.00) < 1e-9, f"weights sum to {total}, not 1.00"


def test_calibrated_weights_match_base_weights_with_no_calibration_history():
    # A fresh engine with no personal_calibration rows must use the exact
    # base weights, unadjusted -- this is the precondition the hand-computed
    # expected score below depends on.
    db = Database(":memory:")
    engine = SmartScoreEngine(db)
    weights = engine._get_calibrated_weights()
    assert weights == {
        "base_rate": WEIGHT_BASE_RATE,
        "hourly_rate": WEIGHT_HOURLY_RATE,
        "deadhead": WEIGHT_DEADHEAD,
        "restaurant_wait": WEIGHT_RESTAURANT_WAIT,
        "time_of_day": WEIGHT_TIME_OF_DAY,
        "weather": WEIGHT_WEATHER,
    }
    assert abs(sum(weights.values()) - 1.00) < 1e-9


def test_calculate_known_synthetic_input_matches_hand_computed_score():
    # Fresh DB: no trip history, no restaurant history, no offer_distance_accuracy
    # rows, no live traffic/weather set on the engine -- every factor falls back
    # to its documented cold-start default, making the output fully deterministic
    # and hand-computable:
    #   base_rate = 20/10 = $2.00/km -> base_score = min(100, (2.0/2.0)*100) = 100.0
    #   hourly_rate = (20/20)*60 = $60/hr -> hourly_score = min(100, (60/60)*100) = 100.0
    #   deadhead: no history -> 0.0 km -> deadhead_score = 100 - 0*10 = 100.0
    #   wait: no history -> 6.0 min default -> wait_score = 100 - (6-3)*8 = 76.0
    #   traffic: hour=10 is outside both peak windows (11-14, 17-20), <5 trips
    #            recorded -> generic low-risk -> time_score = 70.0
    #   weather: no live reading -> neutral -> weather_score = 100.0
    #   final = 100*.36 + 100*.225 + 100*.135 + 76*.09 + 70*.09 + 100*.10 = 95.14
    db = Database(":memory:")
    engine = SmartScoreEngine(db)

    result = engine.calculate(
        payout=20.0,
        distance_km=10.0,
        est_minutes=20.0,
        restaurant_name="Brand New Test Restaurant",
        hour_24=10,
    )

    expected_components = {
        "base_score": 100.0,
        "hourly_score": 100.0,
        "deadhead_score": 100.0,
        "wait_score": 76.0,
        "time_score": 70.0,
        "weather_score": 100.0,
    }
    assert result["components"] == expected_components, (
        f"components {result['components']} != expected {expected_components}"
    )

    expected_final = (
        100.0 * WEIGHT_BASE_RATE + 100.0 * WEIGHT_HOURLY_RATE + 100.0 * WEIGHT_DEADHEAD
        + 76.0 * WEIGHT_RESTAURANT_WAIT + 70.0 * WEIGHT_TIME_OF_DAY + 100.0 * WEIGHT_WEATHER
    )
    assert abs(result["final_score"] - round(expected_final, 1)) < 1e-9, (
        f"final_score {result['final_score']} != hand-computed {round(expected_final, 1)}"
    )
    assert result["final_score"] == 95.1
    assert result["label"] == "Excellent"
    assert result["traffic_risk_source"] == "generic"
    assert result["deadhead_samples"] == 0
    assert result["restaurant_wait_is_learned"] is False
    assert result["delivery_speed_is_learned"] is False
