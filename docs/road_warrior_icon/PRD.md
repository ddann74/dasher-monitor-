# PRD: Fix the RoadWarrior navigation icon and make it functional

Status: DRAFT -- awaiting sign-off before implementation begins.
Scope: this one feature only. Not a general codebase pass.

## 0. What this is / isn't

This is a **bug-fix + hardening** PRD for the existing RoadWarrior
quick-navigation icon (`OverlayHelper.showNavigationIcon`,
`NavigationHelper.openAddress`, wired from `TripForegroundService` for
both dropoff and pickup, plus the manual "Open in RoadWarrior" button on
`MainActivity`). It is **not** a rewrite and **not** a new feature -- the
icon, the tap wiring, and real geocoding for both stop types already
exist and are already connected end-to-end. This PRD closes the specific
gaps that make it unreliable/silently wrong in the field.

Anything requiring a real device with RoadWarrior actually installed, or
a real live Dasher delivery, is called out explicitly as **unverifiable
in this environment** rather than silently assumed to work.

## 1. Why (root-cause investigation, code-verified)

Read `NavigationHelper.java`, `OverlayHelper.java` (nav-icon section),
`TripForegroundService.java` (icon wiring, ~L797-840), `MainActivity.java`
(`openMostRecentStopInRoadWarrior`), `DasherAccessibilityService.java`
(dropoff/pickup geocoding, ~L560-650 and ~L920-960), and
`GoogleApiHelper.java`. Findings:

1. **Real bug -- no placeholder-coordinate guard.** Every other place in
   this codebase that touches stop coordinates explicitly treats
   `(0.0, 0.0)` as "not geocoded yet" (see
   `DasherAccessibilityService.java` L575, L616, L931, L1021 -- all named
   "placeholder"). `NavigationHelper.openAddress()` has **no such guard**.
   If the icon is tapped before the async geocode callback resolves (no
   API key configured -- `GoogleApiHelper.hasApiKey()` is a real,
   silent no-op path -- or a slow/failed geocode), it opens navigation to
   `geo:0.0,0.0`, the Gulf of Guinea. From the driver's seat this is
   indistinguishable from "the icon is broken."
2. **Real bug -- RoadWarrior vs. fallback is invisible.** `openAddress()`
   tries the RoadWarrior-targeted intent, then silently falls through to
   a generic chooser intent with zero user-visible difference between the
   two outcomes. The entire point of this feature (per its own doc
   comment) is opening RoadWarrior specifically; today a driver has no
   way to tell whether that actually happened.
3. **Unverified, NOT fixable by code alone.** Whether RoadWarrior's app
   actually accepts and pre-fills a single-stop `geo:` intent has never
   been confirmed on a real device with RoadWarrior installed (flagged
   already in `NavigationHelper.java`'s own comments and the README TODO
   list). The package name itself, `com.roadwarrior.android`, **was
   reconfirmed correct** via the current Google Play Store listing during
   this investigation (2026-08-22) -- so a wrong package name is ruled
   out as the cause. Whether RoadWarrior's app declares an intent-filter
   for `geo:` at all is still unverified; RoadWarrior's own site
   describes their real API integration as a business-tier bulk-manifest
   API, not a consumer deep-link scheme, which is a plausible reason a
   single-stop `geo:` intent might never be handled by RoadWarrior
   specifically, no matter how correct the code is.
4. **Stale documentation.** README's "Notes/TODOs" section still says
   "nothing converts an address string into real lat/lon anywhere in the
   app" -- false as of the dropoff/pickup geocoding work already merged.
   Left uncorrected, it will keep sending the next investigation down the
   wrong path (as it nearly did here).

## 2. Definition of "functional" for this task

User-confirmed core requirement (2026-08-22): the icon must **appear
once approaching the stop's address**, and tapping it must **open
navigation already loaded with that target address**, so the driver can
visually pinpoint the exact location before arriving. This is not new
scope -- it is already the intended design
(`TripManager.check_approaching_pickup` / `_check_approaching_stop` show
the icon within `APPROACHING_RADIUS_METERS` = 500m;
`NavigationHelper.openAddress()` already builds `geo:lat,lon?q=address`,
a pin at the coordinates plus the address text as a label). It is called
out explicitly here because it's the acceptance test everything else in
this PRD serves: the placeholder-coordinate bug in item 1 below is
exactly the failure mode that would break this ("pinpoint the location"
silently becomes "pinpoint the ocean").

Tapping the icon must never silently do the wrong thing. Concretely:

- [ ] Manually confirmed: the icon appears within the ~500m approach
      radius of a stop with a real (non-placeholder) address, and tapping
      it opens navigation centered on that address's real coordinates
      with the address itself visible/labeled -- not just "some pin."
- [ ] If coordinates are still the `(0.0, 0.0)` placeholder (geocoding
      hasn't resolved, or no API key configured), the tap **refuses to
      navigate** and shows a clear, distinct on-screen message instead of
      opening a pin in the ocean.
- [ ] A successful RoadWarrior-specific open and a fallback-to-generic-maps
      open are **visibly distinguishable** to the driver (different toast
      text, at minimum).
- [ ] The existing `DeveloperTestingActivity.addTestStopNearby()` manual
      test flow (or a small addition to it) can exercise all three
      outcomes -- RoadWarrior opens, fallback opens, placeholder-blocked
      -- without a live delivery.
- [ ] README's stale geocoding TODO note is corrected to match actual
      code behavior.
- [ ] No change to `ROADWARRIOR_PACKAGE` is needed (reconfirmed correct);
      this is documented in-code so it isn't re-litigated next time.

Non-goals (explicitly out of scope for this task):
- Confirming RoadWarrior's real `geo:` handling on a physical device --
  no such device/app is available in this environment. If the driver can
  test this in the field, that's the one remaining unverifiable step.
- Any change to pickup/dropoff address extraction or geocoding itself --
  both already work; this task only touches what happens *after* a
  coordinate (real or placeholder) reaches `NavigationHelper`.
- The Waze-specific `openAddressWithWaze()` path (return-to-sweet-spot
  icon) -- separate feature, not reported broken, not touched here.

## 3. Design

### 3.1 Placeholder-coordinate guard

`NavigationHelper.openAddress(context, address, lat, lon)` gains an
early check: if `lat == 0.0 && lon == 0.0`, skip both intents entirely
and show a `Toast` explaining the address hasn't resolved yet (distinct
wording from the existing "no maps app found" toast, since the cause and
the fix are different -- one is "try again shortly," the other is
"install a maps app").

### 3.2 Visible outcome feedback

Add a `Toast` on the RoadWarrior-success path (currently the `return;`
right after `context.startActivity(roadWarriorIntent)` does nothing
user-visible) and a different one on the fallback path, so all three
branches (RoadWarrior / fallback / no maps app at all) are distinguishable
in real use, not just in `logDiagnostic` output a driver never sees.

### 3.3 Manual verification hook

Extend `DeveloperTestingActivity.addTestStopNearby()` (or add a sibling
method) with an explicit way to register a stop at the `(0.0, 0.0)`
placeholder on purpose, so the guard from 3.1 can be exercised by tapping
the icon without waiting for a real geocode failure.

### 3.4 Documentation fix

Update the stale geocoding bullet in README's "Notes / TODOs" section to
reflect that dropoff and pickup geocoding are both implemented, and add a
note recording that the RoadWarrior package name was reconfirmed
correct (with date), so this isn't re-investigated from scratch next time.

## 4. Testing / verification approach

This repo has no JVM/instrumented unit test source set yet (`app/build.gradle`
has no `test`/`androidTest` config), and this task doesn't warrant adding
one just for a Toast-and-guard change. Verification is manual, via the
existing `DeveloperTestingActivity` pattern (the same one the code
comments already point to for this exact icon: "the RoadWarrior
navigation icon can actually be exercised by physically walking around").
This is disclosed, not hidden: this PRD's checklist items are verified by
code inspection + the manual test hook in 3.3, not by an automated test
suite, because the emulator/sandbox environment this was built in cannot
install RoadWarrior or a real Dasher account to test against.

## 4a. Premortem (2026-08-22): assume this feature has failed in the field

Assume a driver reports "the RoadWarrior icon doesn't work" again, after
the items 1-5 fix already shipped. Working backward from that, grounded
in the actual code and a real diagnostic log from this app (not
hypothetical risks):

- **P1 -- No Google Maps API key configured.**
  `GoogleApiHelper.hasApiKey()` false -> `DasherAccessibilityService`
  calls `add_stop_to_buffer(fullAddress, 0.0, 0.0)` and returns
  immediately; geocoding is never even attempted. Coordinates stay
  `(0.0, 0.0)` for the entire session, every stop, permanently. The
  guard shipped in items 1-5 shows "Address not resolved yet -- try
  again in a moment" for this case -- **actively wrong**: trying again
  will never work until a key is configured. Setting up the API key is a
  manual `local.properties` step per the README, easy to skip.
- **P2 -- Accessibility permission revoked mid-dash.** Confirmed
  happening for real in the uploaded diagnostic log
  (`2026-08-22 16:40:44`, "ALERT: Accessibility revoked while monitoring
  active"). Once revoked, `DasherAccessibilityService` stops reading
  post-accept screens entirely -- no new address is ever extracted for
  the current or next stop, so coordinates are permanently stuck at
  `(0.0, 0.0)` until the driver notices and manually re-grants it. Same
  misleading "try again in a moment" toast as P1.
- **P3 -- Geocode API call itself fails** (network error, quota,
  invalid key -- the `onError` callback path in
  `DasherAccessibilityService.handleDropoffScreen`). Same permanent
  `(0.0, 0.0)`, same misleading toast, indistinguishable from P1/P2/a
  genuine race condition without more context.
- **P1-P3 share one root problem**: `NavigationHelper.openAddress()`
  cannot currently tell a driver *why* an address is unresolved, only
  *that* it is, so it always shows the same "try again shortly" message
  even when trying again can never work. A driver who taps it 3 times in
  a row, gets the same toast, and gives up has no way to know which of
  "wait a few seconds" or "go check a permission" is the fix.
- **P4 -- Overlay-revoked alert undersells the consequence.** Overlay
  permission revocation IS already alerted
  (`TripForegroundService.checkAndLogPermissions`, confirmed in code),
  but the alert text only says "The Smart Score badge and status dot
  won't show" -- it doesn't mention that `OverlayHelper.showNavigationIcon`
  and `showReturnToSweetSpotIcon` gate on the exact same permission check
  and silently stop appearing too. A driver who reads that alert has no
  reason to connect it to "the RoadWarrior icon stopped showing."
- **P5 -- RoadWarrior's real package name could diverge** (old sideloaded
  APK, regional variant, future Play Store rename) from the
  `com.roadwarrior.android` reconfirmed during the original fix. This
  produces the exact same "RoadWarrior not available" fallback toast as
  RoadWarrior genuinely not handling `geo:` intents at all -- the two are
  indistinguishable to the driver, and there's currently no way to
  self-correct without a code change. Still unverifiable without a real
  device (unchanged from the original PRD), but a settings override would
  at least give an affected driver a way out.
- **P6 -- Batch-offer aggregate names leak into the pin label.**
  Confirmed real in the log: a batch offer geocodes successfully to real
  coordinates under the literal name **"Woolworths Fairy Meadow and 1
  other store"** (`GEOCODE: Resolved ... -> -34.391579,150.89386`). If
  that aggregate string is ever the `address` passed to
  `NavigationHelper.openAddress()` instead of a real per-stop street
  address, the pin's label is a multi-store summary, not something a
  driver can visually pinpoint against -- undermining the exact
  "pinpoint the location" requirement this feature exists for, and batch
  orders are confirmed common in this driver's real usage (two in one
  log). **Investigated further and found NOT to be a real bug**: that
  aggregate string comes from `AppNotificationListenerService`'s
  notification-based offer detection, which only ever calls
  `record_pickup_location()` -- a separate, append-only history table
  used for sweet-spot learning, never read back by the icon. `add_pickup`
  -- the only call that populates `self.pickup["restaurant_name"]`, what
  the icon's tap payload actually falls back to -- is called exclusively
  from `DasherAccessibilityService`'s real screen-parsed Accept handler
  (`app/.../DasherAccessibilityService.java:911`), which correctly
  extracted just "Woolworths Fairy Meadow" for this exact log's batch
  offer (`[BATCH OFFER]` tag). The dropoff icon is likewise unaffected --
  it only ever uses `parse_dropoff_screen`'s real per-stop address. No
  code change made; a hypothesis this session raised on its own turned
  out wrong once traced, and that's recorded here rather than "fixed"
  anyway for the sake of closing the box.

### Mitigations adopted

- P1, P2, P3: `NavigationHelper.openAddress()` now checks, in order, no
  API key configured / accessibility not granted / a recent, matching
  geocode failure recorded for this exact address, and shows a distinct,
  actionable toast for each instead of one generic message.
  `NavigationHelper.recordGeocodeFailure(context, target, message)` is
  called from `DasherAccessibilityService`'s two real geocode `onError`
  callbacks (dropoff's `fullAddress`, pickup's `restaurantName` -- the
  exact strings that later reach `openAddress`), persisted via
  SharedPreferences and matched by exact string equality, time-boxed to
  15 minutes so a stale failure from an old, unrelated stop can't wrongly
  blame a new one reusing the same restaurant name later in a shift.
- P4: the "Overlay" permission-revoked alert text now names the
  navigation icon specifically.
- P5: `NavigationHelper`'s RoadWarrior package name is now
  runtime-overridable, same SharedPreferences pattern as the existing
  Google Maps API key field, exposed on the same Permissions & Setup
  screen (`PermissionsActivity`). Still can't verify the *default* value
  actually opens RoadWarrior on a real device (unchanged, unverifiable in
  this environment) -- but an affected driver now has a way to self-correct
  if it turns out to be wrong for their install, without needing a code
  change.
- P6: investigated, found not to be a real bug (see above) -- no code
  change needed.

## 5. Open questions

None blocking -- the scope above is narrow enough that no user judgment
call is needed before starting. If a real-device test later shows
RoadWarrior genuinely never accepts *any* `geo:` intent (not just an
edge case), the next step would be asking the user whether to drop
RoadWarrior-targeting entirely and just use the generic chooser -- but
that's a follow-up decision, not blocking this task.

## 6. Success criteria (implementation-phase checklist)

- [x] `NavigationHelper.openAddress()` refuses `(0.0, 0.0)` with a clear toast
- [x] RoadWarrior-open and fallback-open are each toast-distinguishable
- [x] `DeveloperTestingActivity` has a way to exercise the placeholder-blocked path
- [x] README's stale geocoding TODO is corrected
- [x] Package name correctness is documented in-code with the verification date
- [ ] Core requirement manually confirmed: icon appears within the
      approach radius of a real (non-placeholder) address, and tapping it
      opens navigation pinned to that address's real coordinates with the
      address labeled/visible -- **blocked**: no Android emulator/device
      is available in this environment to physically tap the icon; see
      PROGRESS.md for what was verified by code inspection instead
- [ ] Manual verification of all 3 branches performed and recorded in
      PROGRESS.md -- **blocked**, same reason as above
- [x] Premortem (§4a) conducted and mitigations for P1-P4 implemented
- [x] P5: RoadWarrior package name made driver-overridable (Permissions & Setup screen, mirrors the API key field)
- [x] P6: investigated -- confirmed not a real bug, no code change needed (see §4a)
- [x] P3: a failed geocode API call is now distinguished from a genuinely
      in-progress one, via `NavigationHelper.recordGeocodeFailure` fed by
      `DasherAccessibilityService`'s real geocode `onError` callbacks
- [ ] User sign-off
