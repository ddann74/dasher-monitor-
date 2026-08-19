"""
FR-1 (PRD.md): TripManager tracks pickup arrival/departure distance via GPS,
snapshotting cumulative distance at each transition. This feeds deadhead
distance (SmartScoreEngine._estimate_deadhead_km) and delivery-leg distance,
which the app's own README says drive real Smart Score numbers -- so this is
exercising a real production code path, not a reimplementation of it.
"""
from drive_monitor import Database, TripManager, haversine_meters, TRIP_START_HOLD_SECONDS

TOLERANCE_KM = 0.001  # 1 meter -- generous for straight-line haversine segments


def _start_active_trip(tm):
    """Drives TripManager from IDLE into an active trip via the real speed/hold
    threshold logic (TRIP_START_SPEED_KMH held for TRIP_START_HOLD_SECONDS),
    exactly as on_gps_update would be driven in production."""
    tm.on_gps_update(lat=0.0, lon=0.0, speed_kmh=20.0, timestamp_ms=0)
    tm.on_gps_update(lat=0.0, lon=0.0, speed_kmh=20.0,
                      timestamp_ms=int((TRIP_START_HOLD_SECONDS + 1) * 1000))
    assert tm.state == TripManager.STATE_ACTIVE, "trip did not start under the expected speed/hold conditions"


def test_pickup_arrival_and_departure_distance_tracking():
    db = Database(":memory:")
    tm = TripManager(db)

    _start_active_trip(tm)

    pickup_lat, pickup_lon = 0.0045, 0.0
    tm.add_pickup("Test Restaurant", lat=pickup_lat, lon=pickup_lon)

    # P1: driving toward the restaurant, still ~500m away -- no arrival yet.
    event = tm.on_gps_update(lat=0.0, lon=0.0, speed_kmh=20.0, timestamp_ms=13000)
    assert event == (None, None)
    assert tm.pickup["arrived_at"] is None

    # P2: exactly at the restaurant -- arrival should be detected (0m <= 50m geofence).
    expected_deadhead_km = haversine_meters(0.0, 0.0, pickup_lat, pickup_lon) / 1000.0
    event = tm.on_gps_update(lat=pickup_lat, lon=pickup_lon, speed_kmh=20.0, timestamp_ms=23000)
    pickup_wait_event, _ = event
    assert pickup_wait_event is None, "arrival itself should not yet emit a wait event"
    assert tm.pickup["arrived_at"] == 23.0
    assert tm._deadhead_distance_km is not None
    assert abs(tm._deadhead_distance_km - expected_deadhead_km) < TOLERANCE_KM, (
        f"deadhead snapshot {tm._deadhead_distance_km} km != expected "
        f"{expected_deadhead_km} km (real straight-line distance from trip start to pickup)"
    )

    # P3: drives ~100m past the restaurant, 72s later -- departure should be
    # detected (now outside the 50m geofence), with a wait_minutes event and a
    # second distance snapshot for the delivery leg that starts here.
    departure_lat = 0.0054
    expected_departure_leg_km = haversine_meters(pickup_lat, pickup_lon, departure_lat, 0.0) / 1000.0
    expected_distance_at_departure_km = expected_deadhead_km + expected_departure_leg_km

    pickup_wait_event, _ = tm.on_gps_update(
        lat=departure_lat, lon=0.0, speed_kmh=20.0, timestamp_ms=95000
    )
    assert pickup_wait_event is not None, "departure should emit a pickup-wait event"
    assert pickup_wait_event["restaurant_name"] == "Test Restaurant"
    assert abs(pickup_wait_event["wait_minutes"] - 1.2) < 0.01, "23s->95s = 72s = 1.2 minutes"
    assert tm.pickup["recorded"] is True
    assert tm._distance_at_departure_km is not None
    assert abs(tm._distance_at_departure_km - expected_distance_at_departure_km) < TOLERANCE_KM, (
        f"departure snapshot {tm._distance_at_departure_km} km != expected "
        f"{expected_distance_at_departure_km} km (real straight-line distance from trip start "
        f"through pickup to the departure point)"
    )

    # Sanity: departure distance must be strictly greater than the deadhead
    # snapshot taken at arrival -- the driver kept moving after arriving.
    assert tm._distance_at_departure_km > tm._deadhead_distance_km
