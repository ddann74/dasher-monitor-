import drive_monitor


def test_module_imports():
    assert drive_monitor is not None


def test_expected_classes_exist():
    expected = [
        "TripManager",
        "SmartScoreEngine",
        "OfferScreenParser",
        "MessageIntelligence",
        "StopsBuffer",
        "Database",
        "TrustedContacts",
    ]
    missing = [name for name in expected if not hasattr(drive_monitor, name)]
    assert not missing, f"drive_monitor.py is missing expected classes: {missing}"
