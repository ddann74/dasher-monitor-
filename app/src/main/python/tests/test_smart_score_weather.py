"""
FR-10 (PRD.md): weather factor uses Open-Meteo current precipitation/wind at
the driver's location (via record_live_weather, called from WeatherHelper.java
after it queries Open-Meteo -- there is no HTTP client in this Python module,
so "mocking Open-Meteo" means calling record_live_weather with known values,
exactly as WeatherHelper's real async callback would), a 15-minute freshness
window, and a neutral 100 if no reading is fresh.

NOTE (observation, not a bug): precipitation deduction is capped at 60 and
wind deduction at 30, so the theoretical minimum score under this formula is
10.0 -- the `max(0.0, score)` floor in _get_weather_score can never actually
be reached given these caps. Not fixed here (would change scoring behavior,
out of scope for a test-only task); just recorded for anyone touching this
formula later.
"""
import time

from drive_monitor import Database, SmartScoreEngine


def test_weather_neutral_when_no_reading_recorded():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)

    score, is_live, precip, wind = engine._get_weather_score()

    assert score == 100.0
    assert is_live is False
    assert precip is None
    assert wind is None


def test_weather_clear_conditions_full_score():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)
    engine.record_live_weather(precipitation_mm=0.0, wind_speed_kmh=10.0, temperature_c=20.0)

    score, is_live, precip, wind = engine._get_weather_score()

    assert score == 100.0
    assert is_live is True
    assert precip == 0.0
    assert wind == 10.0


def test_weather_moderate_rain_reduces_score_proportionally():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)
    engine.record_live_weather(precipitation_mm=2.0, wind_speed_kmh=10.0, temperature_c=15.0)

    score, is_live, precip, wind = engine._get_weather_score()

    # 100 - min(60, 2.0*15=30) = 70.0
    assert score == 70.0
    assert is_live is True


def test_weather_heavy_rain_deduction_is_capped_at_60():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)
    engine.record_live_weather(precipitation_mm=10.0, wind_speed_kmh=0.0, temperature_c=12.0)

    score, is_live, precip, wind = engine._get_weather_score()

    # 100 - min(60, 10.0*15=150) = 100 - 60 = 40.0, not a negative/huge deduction
    assert score == 40.0


def test_weather_high_wind_reduces_score():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)
    engine.record_live_weather(precipitation_mm=0.0, wind_speed_kmh=40.0, temperature_c=18.0)

    score, is_live, precip, wind = engine._get_weather_score()

    # wind > 30 threshold: 100 - min(30, (40-30)*1.5=15) = 85.0
    assert score == 85.0


def test_weather_extreme_wind_deduction_is_capped_at_30():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)
    engine.record_live_weather(precipitation_mm=0.0, wind_speed_kmh=80.0, temperature_c=18.0)

    score, is_live, precip, wind = engine._get_weather_score()

    # 100 - min(30, (80-30)*1.5=75) = 100 - 30 = 70.0
    assert score == 70.0


def test_weather_combined_rain_and_wind_deductions_stack():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)
    engine.record_live_weather(precipitation_mm=2.0, wind_speed_kmh=40.0, temperature_c=10.0)

    score, is_live, precip, wind = engine._get_weather_score()

    # 100 - 30 (rain) - 15 (wind) = 55.0
    assert score == 55.0


def test_weather_stale_reading_falls_back_to_neutral():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)
    engine.record_live_weather(precipitation_mm=10.0, wind_speed_kmh=50.0, temperature_c=10.0)
    # Force the reading to look 16 minutes old (freshness window is 15 min).
    engine._live_weather_timestamp = time.time() - (16 * 60)

    score, is_live, precip, wind = engine._get_weather_score()

    assert score == 100.0, "a stale reading must NOT still penalize the score"
    assert is_live is False


def test_calculate_reflects_live_rain_in_weather_component_and_label():
    db = Database(":memory:")
    engine = SmartScoreEngine(db)
    engine.record_live_weather(precipitation_mm=5.0, wind_speed_kmh=10.0, temperature_c=14.0)

    result = engine.calculate(
        payout=20.0, distance_km=10.0, est_minutes=20.0,
        restaurant_name="Test Restaurant", hour_24=10,
    )

    assert result["weather_is_live"] is True
    # 100 - min(60, 5.0*15=75) = 40.0
    assert result["components"]["weather_score"] == 40.0
    assert "5.0mm rain" in result["weather"]
