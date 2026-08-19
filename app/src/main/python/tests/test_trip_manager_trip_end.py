"""
FR-3 (PRD.md): a GENERAL-mode trip (no stops registered at all) must still
auto-end when parked TRIP_END_PARK_SECONDS+; a DASHER-mode trip must NOT end
while a delivery stop is still pending, and must NOT end while an active
(not-yet-departed) pickup is in progress -- this last case is a real, named
bug fix in README.md ("a long pickup wait... would otherwise satisfy
GENERAL mode's parking-ends-trip rule and end the delivery before the
driver ever left the restaurant"). Exercises the real
TripManager._evaluate_trip_end code path directly, not a reimplementation.
"""
from drive_monitor import Database, TripManager, TRIP_END_PARK_SECONDS, TRIP_START_HOLD_SECONDS

PARK_SPEED_KMH = 0.5  # below TRIP_STOP_SPEED_KMH (1.0)


def _new_active_trip():
    db = Database(":memory:")
    tm = TripManager(db)
    tm.on_gps_update(lat=0.0, lon=0.0, speed_kmh=20.0, timestamp_ms=0)
    tm.on_gps_update(lat=0.0, lon=0.0, speed_kmh=20.0,
                      timestamp_ms=int((TRIP_START_HOLD_SECONDS + 1) * 1000))
    assert tm.state == TripManager.STATE_ACTIVE
    return tm


def _park_for_full_threshold(tm, start_ts):
    """Calls _evaluate_trip_end twice: once to start the park clock, once
    after TRIP_END_PARK_SECONDS has fully elapsed -- mirrors how
    on_gps_update would drive this in production across two GPS ticks."""
    tm._evaluate_trip_end(PARK_SPEED_KMH, start_ts)
    return tm._evaluate_trip_end(PARK_SPEED_KMH, start_ts + TRIP_END_PARK_SECONDS)


def test_general_mode_no_stops_ends_trip_when_parked():
    tm = _new_active_trip()
    tm._trip_mode = "GENERAL"
    assert not tm.stops
    assert tm.pickup is None

    _park_for_full_threshold(tm, start_ts=1000.0)

    assert tm.state == TripManager.STATE_IDLE, (
        "GENERAL-mode trip with no stops must end once parked long enough -- "
        "regression test for the bug where trip-ending previously required at "
        "least one stop to ever exist"
    )


def test_dasher_mode_pending_stop_does_not_end_trip_when_parked():
    tm = _new_active_trip()
    tm._trip_mode = "DASHER"
    tm.add_stop("123 Test St", lat=1.0, lon=1.0)
    assert not tm.stops[0]["matched"]

    _park_for_full_threshold(tm, start_ts=2000.0)

    assert tm.state == TripManager.STATE_ACTIVE, (
        "DASHER-mode trip with an unmatched pending stop must NOT end just "
        "because the driver is parked (e.g. a red light mid-route)"
    )


def test_dasher_mode_active_pickup_does_not_end_trip_when_parked():
    tm = _new_active_trip()
    tm.add_pickup("Test Restaurant", lat=1.0, lon=1.0)
    tm._trip_mode = "DASHER"
    assert tm.pickup is not None and not tm.pickup["recorded"]

    _park_for_full_threshold(tm, start_ts=3000.0)

    assert tm.state == TripManager.STATE_ACTIVE, (
        "DASHER-mode trip with an active (not-yet-departed) pickup must NOT end "
        "during a long restaurant wait -- this is the real bug named in README.md: "
        "GPS-visible 'parked 5+ minutes' looks identical to a genuine end-of-trip park, "
        "so without this guard a long pickup wait would end the delivery before the "
        "driver ever left the restaurant"
    )


def test_dasher_mode_all_stops_matched_no_pickup_ends_trip_when_parked():
    tm = _new_active_trip()
    tm._trip_mode = "DASHER"
    tm.add_stop("123 Test St", lat=1.0, lon=1.0)
    tm.stops[0]["matched"] = True
    assert tm.pickup is None

    _park_for_full_threshold(tm, start_ts=4000.0)

    assert tm.state == TripManager.STATE_IDLE, (
        "DASHER-mode trip where every registered stop is already matched and no "
        "pickup is pending must still end when parked, same as GENERAL mode"
    )
