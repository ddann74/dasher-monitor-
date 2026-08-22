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
- [ ] User sign-off
