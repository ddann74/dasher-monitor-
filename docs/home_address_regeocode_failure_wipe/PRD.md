# PRD: A failed home-address re-geocode silently wiped a working Shift Routing config

Status: IMPLEMENTED (2026-09-15), scouting-pass finding (round 7, #4).

## 1. What was wrong

`PermissionsActivity`'s home-address save flow
(`saveHomeAddressButton`'s click listener) pre-fills the address field
with whatever was previously saved (`ShiftRoutingPrefs.getHomeAddress`),
lets the driver edit it, and on Save geocodes the new text. The
`onError` callback (fired when that geocode fails -- a typo, or a
transient network/geocoding failure) called
`ShiftRoutingPrefs.clearHomeLocation(context)` unconditionally.
`clearHomeLocation` only removes the stored `KEY_HOME_LAT`/`KEY_HOME_LON`
(`ShiftRoutingPrefs.java:39-41`) -- it never touches `KEY_HOME_ADDRESS`,
and `onError` never calls `setHomeAddress` either.

So: a driver with a working, previously-configured home address who
opens Permissions & Setup, edits the field (a typo, or just re-saving
during a network blip), and hits a failed geocode would silently lose
their working configuration -- `ShiftRoutingPrefs.isConfigured()`
flips to `false`, and `TripForegroundService`'s hotspot-or-home routing
suggestion silently stops firing -- while `getHomeAddress()` keeps
returning what looks like a perfectly valid, already-saved address on
the next screen load. Nothing about the stored state signals that
anything was destroyed; only a small subtext hint elsewhere on the
screen would eventually indicate the feature isn't configured, easy to
miss after a routine edit that "should have" just updated the address.

## 2. Design

In `onError`, check whether a previously-valid home location already
exists (`ShiftRoutingPrefs.getHomeLatLon(context) != null`) BEFORE
clearing. Only call `clearHomeLocation` when there wasn't one --
preserving the original, correct intent for a genuinely failed
FIRST-time setup (never keep stale placeholder coordinates paired with
an address that never resolved). A failed EDIT of an already-working
configuration now leaves that working configuration completely alone
instead of destroying it. The Toast and diagnostic log now say
explicitly whether the previous home address was kept, so the failure
isn't silent either way.

Deliberately does not touch the `homeAddressInput` EditText itself --
the driver's in-progress typed text (which they may still be trying to
fix) is left as-is, same as any other form validation failure; only the
underlying stored config and the failure messaging changed.

## 3. Verification

`PermissionsActivity` itself can't be compiled/run outside a real
Android device/emulator (it depends on the Activity lifecycle, views,
and Toast). Verified instead by:

- `python3`-based brace/paren balance check on the edited file: final
  depth 0.
- A standalone, compiled-and-run Java program (`javac`/`java`)
  containing the REAL, unmodified `ShiftRoutingPrefs.java` (copied
  verbatim from the repo, not reimplemented) plus a minimal in-memory
  fake `android.content.Context`/`SharedPreferences` (mirroring real
  Android's per-file-name persistence semantics -- two different
  `Context` instances requesting the same prefs file name share the
  same store), and a test class exercising the EXACT new conditional
  logic from `PermissionsActivity`'s `onError` (copied verbatim from
  the source, confirmed via `grep` immediately before writing the
  test):
  - **The bug scenario**: a working home config (`setHomeAddress` +
    `setThreshold`, `isConfigured()` true) followed by a simulated
    failed re-geocode -- confirmed `getHomeLatLon` is unchanged (exact
    coordinates preserved) and `isConfigured()` stays `true`.
  - **No-overreach check**: a context with NO previously-configured
    home location, hit with the same simulated failure -- confirmed it
    still correctly clears/stays unconfigured (nothing to protect,
    matches the original, correct behavior for first-time setup).
  - **Regression check**: a genuinely successful edit (the `onResult`
    path, unrelated to this fix) still updates the saved location
    normally.

  8 checks, all passed on first run.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  UI flow (typing an address, tapping Save, a geocode call genuinely
  failing) has not been observed on a device. Verified instead against
  the real, unmodified `ShiftRoutingPrefs.java` plus a faithful
  reproduction of the exact new conditional logic -- not a full
  Activity-level integration test.
- The `homeAddressInput` field itself is intentionally left showing the
  driver's failed edit attempt (not reverted to the still-active saved
  address) -- consistent with normal form-validation UX (let the driver
  see and fix what they typed), but means the screen doesn't visually
  confirm "your previous address is still active" beyond the Toast
  message and the subtext refresh; a driver who dismisses the Toast
  quickly could still be briefly unsure whether anything changed.
- Does not add any explicit "this edit failed, reverted to your saved
  address" persistent UI state -- relies on the existing Toast/subtext
  refresh mechanisms already in place for this screen.

## 5. Success criteria

- [x] A failed re-geocode no longer wipes a previously-working home
      location
- [x] A failed first-time geocode still correctly results in no saved
      location (original intent preserved)
- [x] A genuinely successful edit still updates the saved location
      normally
- [x] The failure Toast/log now say explicitly whether the previous
      address was kept
- [x] Standalone compiled Java test (8 checks) against the real,
      unmodified `ShiftRoutingPrefs.java` and the exact new conditional
      logic, fully passed
- [x] `python3` brace/paren balance check clean
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see §4.
- [ ] Driver confirms in real use that editing (and failing to
      re-resolve) their home address no longer silently disables Shift
      Routing.
- [ ] Driver sign-off.
