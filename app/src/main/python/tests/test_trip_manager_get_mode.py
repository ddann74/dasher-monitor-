"""
FR-17 (PRD.md): TripManager.get_mode() returns DASHER if the Dasher app is
foregrounded, OR an unmatched delivery stop is pending, OR an active
(not-yet-departed) pickup exists -- each condition independently, not just
in combination -- otherwise GENERAL. Exercises the real get_mode() directly.
"""
from drive_monitor import Database, TripManager


def _new_trip_manager():
    return TripManager(Database(":memory:"))


def test_get_mode_general_by_default():
    tm = _new_trip_manager()
    assert tm.get_mode() == "GENERAL"


def test_get_mode_dasher_when_app_foregrounded():
    tm = _new_trip_manager()
    tm.set_dasher_foreground(True)

    assert tm.get_mode() == "DASHER"


def test_get_mode_reverts_to_general_when_dasher_no_longer_foregrounded():
    tm = _new_trip_manager()
    tm.set_dasher_foreground(True)
    assert tm.get_mode() == "DASHER"

    tm.set_dasher_foreground(False)

    assert tm.get_mode() == "GENERAL"


def test_get_mode_dasher_when_unmatched_stop_pending_even_without_foreground():
    tm = _new_trip_manager()
    tm.set_dasher_foreground(False)
    tm.add_stop("123 Test St", lat=1.0, lon=1.0)
    assert tm.stops[0]["matched"] is False

    assert tm.get_mode() == "DASHER"


def test_get_mode_general_once_the_only_stop_is_matched():
    tm = _new_trip_manager()
    tm.add_stop("123 Test St", lat=1.0, lon=1.0)
    tm.stops[0]["matched"] = True

    assert tm.get_mode() == "GENERAL"


def test_get_mode_dasher_when_active_pickup_even_without_foreground_or_stops():
    tm = _new_trip_manager()
    tm.set_dasher_foreground(False)
    tm.add_pickup("Test Restaurant", lat=1.0, lon=1.0)
    assert tm.pickup["recorded"] is False

    assert tm.get_mode() == "DASHER"


def test_get_mode_general_once_pickup_is_recorded():
    tm = _new_trip_manager()
    tm.add_pickup("Test Restaurant", lat=1.0, lon=1.0)
    tm.pickup["recorded"] = True

    assert tm.get_mode() == "GENERAL"


def test_get_mode_dasher_if_any_single_one_of_the_three_conditions_holds():
    # Each of the three signals independently forces DASHER, one at a time,
    # confirming they're evaluated with OR semantics, not requiring all three.
    foreground_only = _new_trip_manager()
    foreground_only.set_dasher_foreground(True)
    assert foreground_only.get_mode() == "DASHER"

    stop_only = _new_trip_manager()
    stop_only.add_stop("Addr", lat=1.0, lon=1.0)
    assert stop_only.get_mode() == "DASHER"

    pickup_only = _new_trip_manager()
    pickup_only.add_pickup("Restaurant", lat=1.0, lon=1.0)
    assert pickup_only.get_mode() == "DASHER"
