"""
FR-13 (PRD.md): MessageIntelligence recognizes delivery notes, address
corrections, and ETA updates in customer SMS/Messenger text via simple
keyword rules, checked in a fixed priority order (instruction >
address_correction > eta_adjustment), and only for Dasher-app or SMS
messages (not arbitrary apps).
"""
from drive_monitor import MessageIntelligence


# -- classify(): which notifications are even eligible to be parsed --------

def test_classify_true_for_dasher_app_messaging_style():
    assert MessageIntelligence.classify("com.doordash.driverapp", True) is True


def test_classify_true_for_sms_app_messaging_style():
    assert MessageIntelligence.classify("com.google.android.apps.messaging", True) is True


def test_classify_false_for_unrelated_app_even_if_messaging_style():
    assert MessageIntelligence.classify("com.whatsapp", True) is False


def test_classify_false_for_allowed_package_but_not_messaging_style():
    assert MessageIntelligence.classify("com.doordash.driverapp", False) is False


# -- extract_instruction(): keyword category recognition -------------------

DELIVERY_NOTE_SAMPLES = [
    "Please leave at the front door",
    "Use the back door, thanks",
    "Gate code is 4521",
    "Door code 1234#",
    "Please buzz apartment 5B",
    "I'm in apartment 12",
    "It's unit 4 on the left",
    "Just leave it on the porch",
]

ADDRESS_CORRECTION_SAMPLES = [
    "Not the house with the blue door, the one next to it",
    "Actually I'm at my friend's place now, different address",
    "It's the house on the corner, not the one you're heading to",
    "Its actually the building behind this one",
]

ETA_ADJUSTMENT_SAMPLES = [
    "Running late, be there in 5",
    "I'll be there in a few more minutes",
    "On my way down now",
    "Sorry, running late to answer the door",
]

NEGATIVE_SAMPLES = [
    "Thank you so much!",
    "Ok",
    "Sounds good",
    "Have a great day",
]


def test_extract_instruction_delivery_note_samples():
    for text in DELIVERY_NOTE_SAMPLES:
        result = MessageIntelligence.extract_instruction(text)
        assert result is not None and result.startswith("delivery_note:"), (
            f"expected delivery_note for {text!r}, got {result!r}"
        )


def test_extract_instruction_address_correction_samples():
    for text in ADDRESS_CORRECTION_SAMPLES:
        result = MessageIntelligence.extract_instruction(text)
        assert result is not None and result.startswith("address_correction:"), (
            f"expected address_correction for {text!r}, got {result!r}"
        )


def test_extract_instruction_eta_adjustment_samples():
    for text in ETA_ADJUSTMENT_SAMPLES:
        result = MessageIntelligence.extract_instruction(text)
        assert result is not None and result.startswith("eta_adjustment:"), (
            f"expected eta_adjustment for {text!r}, got {result!r}"
        )


def test_extract_instruction_negative_samples_return_none():
    for text in NEGATIVE_SAMPLES:
        result = MessageIntelligence.extract_instruction(text)
        assert result is None, f"expected no match for {text!r}, got {result!r}"


def test_extract_instruction_priority_order_instruction_beats_address_correction():
    # Contains both an INSTRUCTION_KEYWORDS hit ("leave at") and an
    # ADDRESS_CORRECTION_KEYWORDS hit ("it's") -- the real check order in
    # the code is instruction first, so this must classify as delivery_note,
    # not address_correction.
    text = "It's fine, just leave at the front door"
    result = MessageIntelligence.extract_instruction(text)
    assert result.startswith("delivery_note:"), (
        f"instruction keywords must win over address-correction keywords per real check "
        f"order, got {result!r}"
    )


# -- is_urgent(): triage priority ------------------------------------------

def test_is_urgent_true_for_delivery_note():
    assert MessageIntelligence.is_urgent("delivery_note: leave at door") is True


def test_is_urgent_true_for_address_correction():
    assert MessageIntelligence.is_urgent("address_correction: not this house") is True


def test_is_urgent_false_for_eta_adjustment():
    assert MessageIntelligence.is_urgent("eta_adjustment: running late") is False


def test_is_urgent_false_for_none():
    assert MessageIntelligence.is_urgent(None) is False
