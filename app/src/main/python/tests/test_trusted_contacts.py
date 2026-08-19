"""
FR-16 (PRD.md): TrustedContacts.is_trusted() does substring/case-insensitive
matching against an allowlist. Also covers a real, easy-to-miss behavior:
an EMPTY allowlist (no contacts added yet at all) defaults to reading
everything, not nothing -- documented directly in is_trusted()'s docstring
as a deliberate "don't silently go quiet with no indication why" choice.
Once at least one contact exists, it reverts to a real allowlist.
"""
from drive_monitor import Database, TrustedContacts


def test_is_trusted_defaults_to_true_when_list_is_empty():
    db = Database(":memory:")
    contacts = TrustedContacts(db)

    assert contacts.list_all() == []
    assert contacts.is_trusted("Literally Anybody") is True


def test_is_trusted_exact_match():
    db = Database(":memory:")
    contacts = TrustedContacts(db)
    contacts.add("Maria Garcia")

    assert contacts.is_trusted("Maria Garcia") is True


def test_is_trusted_substring_match():
    db = Database(":memory:")
    contacts = TrustedContacts(db)
    contacts.add("mom")

    # Same person, different display names/decoration across apps -- the
    # whole point of substring matching per the docstring.
    assert contacts.is_trusted("Mom") is True
    assert contacts.is_trusted("Mom ❤️") is True
    assert contacts.is_trusted("Call me Mom") is True


def test_is_trusted_case_insensitive():
    db = Database(":memory:")
    contacts = TrustedContacts(db)
    contacts.add("Maria")

    assert contacts.is_trusted("MARIA GARCIA") is True
    assert contacts.is_trusted("maria garcia") is True


def test_is_trusted_non_matching_sender_rejected_once_list_is_nonempty():
    db = Database(":memory:")
    contacts = TrustedContacts(db)
    contacts.add("mom")
    contacts.add("dad")

    # A real allowlist once it's non-empty -- an unrelated sender must be
    # rejected, not silently allowed through like the empty-list default.
    assert contacts.is_trusted("Random Delivery Spam") is False
    assert contacts.is_trusted("") is False


def test_add_is_case_and_whitespace_normalized_and_deduplicated():
    db = Database(":memory:")
    contacts = TrustedContacts(db)
    contacts.add("  Mom  ")
    contacts.add("MOM")  # same normalized entry, INSERT OR IGNORE should dedupe

    assert contacts.list_all() == ["mom"]


def test_add_empty_or_whitespace_only_name_is_a_noop():
    db = Database(":memory:")
    contacts = TrustedContacts(db)
    contacts.add("")
    contacts.add("   ")

    assert contacts.list_all() == []
    # Confirms the empty-list "read everything" default still applies --
    # these no-op adds must not have created a phantom empty-string entry
    # that would otherwise match every sender via substring-in-string.
    assert contacts.is_trusted("Anyone") is True


def test_remove_takes_a_contact_off_the_allowlist():
    db = Database(":memory:")
    contacts = TrustedContacts(db)
    contacts.add("mom")
    contacts.add("dad")
    assert contacts.is_trusted("Mom") is True

    contacts.remove("mom")

    assert contacts.list_all() == ["dad"]
    assert contacts.is_trusted("Mom") is False
    assert contacts.is_trusted("Dad") is True
