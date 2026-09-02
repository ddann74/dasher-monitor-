# PRD: RoadWarrior icon -- copy address to clipboard (supersedes auto-navigate)

Status: IMPLEMENTED (all §6 boxes checked except on-device confirmation
and sign-off) -- this PRD's own status line was stale, found and
corrected during a 2026-09-02 priority-triage pass. The icon copies the
resolved address to the clipboard (not auto-navigate) and is shown/
cleared by `_check_approaching_stop`/arrival detection, the same
appear-on-approach/disappear-on-complete behavior the driver later
separately asked for.
Scope: this one feature only. Not a general codebase pass.

## 0. What this is / isn't

This is a **behavior-change** PRD for the existing RoadWarrior
quick-navigation icon (`OverlayHelper.showNavigationIcon`,
`NavigationHelper.openAddress`, wired from `TripForegroundService` for
both dropoff and pickup, plus the manual "Open in RoadWarrior" button on
`MainActivity`). It is **not** a rewrite of the icon's appearance/trigger
logic and **not** a new feature location -- the icon and the tap wiring
already exist and are already connected end-to-end. What changes is what
happens when the icon is tapped: instead of auto-launching a navigation
app, it copies the stop's address to the clipboard so the driver can
paste it wherever they choose.

## 0a. Requirement change (2026-08-30)

User request: "When the road warrior icon appears change it to copy the
address so I can paste it myself." This **replaces** the tap behavior
designed in the original version of this PRD (auto-launch RoadWarrior, or
fall back to a generic maps chooser, via a `geo:lat,lon?q=address`
intent) with: tapping the icon copies the stop's address text to the
clipboard and shows a confirmation toast; the driver pastes it into
whichever app they choose themselves. Section 1 below (the original
root-cause investigation of the intent-launch approach) is kept as
historical context for the code being replaced. Sections 2, 3, and 6 are
rewritten for the new copy behavior and supersede the auto-navigate
acceptance criteria that were previously checked off in this file --
those items are no longer the target behavior, not because they were
implemented incorrectly.

Verified (2026-08-30, code inspection): as of this rewrite, the icon
still performs the *old* auto-navigate behavior -- `NavigationHelper.java`
has no `ClipboardManager` usage tied to this icon. The copy-to-clipboard
behavior described below has not been implemented yet.

## 1. Why the original design looked the way it did (historical, root-cause investigation, code-verified)

Read `NavigationHelper.java`, `OverlayHelper.java` (nav-icon section),
`TripForegroundService.java` (icon wiring, ~L797-840), `MainActivity.java`
(`openMostRecentStopInRoadWarrior`), `DasherAccessibilityService.java`
(dropoff/pickup geocoding, ~L560-650 and ~L920-960), and
`GoogleApiHelper.java`. Findings from the original (auto-navigate) PRD:

1. **Real bug -- no placeholder-coordinate guard.** Every other place in
   this codebase that touches stop coordinates explicitly treats
   `(0.0, 0.0)` as "not geocoded yet" (see
   `DasherAccessibilityService.java` L575, L616, L931, L1021 -- all named
   "placeholder"). `NavigationHelper.openAddress()` had **no such guard**.
   If the icon was tapped before the async geocode callback resolved (no
   API key configured -- `GoogleApiHelper.hasApiKey()` is a real,
   silent no-op path -- or a slow/failed geocode), it opened navigation to
   `geo:0.0,0.0`, the Gulf of Guinea.
2. **Real bug -- RoadWarrior vs. fallback was invisible.** `openAddress()`
   tried the RoadWarrior-targeted intent, then silently fell through to
   a generic chooser intent with zero user-visible difference between the
   two outcomes.
3. **Unverified, NOT fixable by code alone.** Whether RoadWarrior's app
   actually accepts and pre-fills a single-stop `geo:` intent was never
   confirmed on a real device with RoadWarrior installed. The package
   name itself, `com.roadwarrior.android`, was reconfirmed correct via
   the Google Play Store listing (2026-08-22). Whether RoadWarrior
   declares an intent-filter for `geo:` at all was never confirmed --
   this unresolved uncertainty is part of why the driver asked for a
   copy-to-clipboard alternative instead: it sidesteps the question
   entirely by letting the driver paste into whatever app actually works
   for them.
4. **Stale documentation.** README's "Notes/TODOs" section said "nothing
   converts an address string into real lat/lon anywhere in the app" --
   false as of the dropoff/pickup geocoding work already merged. This was
   corrected during the original PRD's implementation.

The mitigations this investigation drove (placeholder guard, RoadWarrior
package override, outcome-distinguishing toasts -- see the "Previously
implemented, now retired" note in §3.4) are being retired by this PRD, not
because they were wrong, but because the feature they protected (an
auto-launched navigation intent) is being replaced.

## 2. Definition of "functional" for this task

New core requirement (2026-08-30): the icon must still **appear once
approaching the stop's address** (unchanged -- this trigger logic is out
of scope, see Non-goals), and tapping it must **copy that stop's address
text to the clipboard** with a confirmation toast, so the driver can paste
it into RoadWarrior (or any other app) themselves. It must never silently
copy an empty or not-yet-resolved value.

- [ ] Manually confirmed: the icon appears within the ~500m approach
      radius of a stop with a real (non-placeholder) address (unchanged
      trigger, `TripManager.check_approaching_pickup` /
      `_check_approaching_stop`).
- [ ] Tapping the icon when the stop's address text is available copies
      exactly that address text to the clipboard and shows a confirmation
      toast that names (or previews) what was copied.
- [ ] Tapping the icon when the address text is not yet available (empty
      string / not yet extracted or geocoded) refuses to copy and shows a
      clear, distinct "address not available yet" toast instead of
      copying an empty value.
- [ ] The old auto-launch-navigation behavior (RoadWarrior-targeted intent,
      fallback to a generic maps chooser) is removed from this icon's tap
      handler -- it no longer opens any app itself.
- [ ] The RoadWarrior package-override setting on the Permissions & Setup
      screen (added for the old design's P5 mitigation, see §4a) is
      removed, since no intent is launched from this icon anymore.
- [ ] The existing `DeveloperTestingActivity` manual test hooks are
      updated to exercise the two new outcomes -- successful copy,
      blocked-copy-when-unresolved -- replacing the old
      RoadWarrior-opens / fallback-opens / placeholder-blocked hooks.
- [ ] README's description of this icon's tap behavior is updated to
      describe copy-to-clipboard, replacing the auto-navigate description.

Non-goals (explicitly out of scope for this task):
- Any change to the icon's appearance trigger (approach-radius logic) --
  unchanged, works today.
- Any change to pickup/dropoff address extraction or geocoding itself --
  both already work; this task only touches what happens *after* an
  address (real or unresolved) reaches the tap handler. Note: since the
  copy action no longer needs lat/lon, only the address text, geocoding
  failures that only affected coordinates (not the address string itself)
  may no longer need to block the copy -- see §3.2.
- The Waze-specific `openAddressWithWaze()` path (return-to-sweet-spot
  icon) -- separate feature, not reported broken, not touched here.

## 3. Design

### 3.1 Clipboard copy action

Replace the body of `NavigationHelper.openAddress(context, address, lat,
lon)` -- or a renamed equivalent, e.g. `copyAddressToClipboard(context,
address)`, updating call sites in `OverlayHelper`, `TripForegroundService`,
and `MainActivity`'s manual "Open in RoadWarrior" button (also renamed to
reflect the new action, e.g. "Copy Address") -- so tapping the icon calls
`ClipboardManager.setPrimaryClip(ClipData.newPlainText("Delivery address",
address))` instead of building and launching a navigation `Intent`. The
`lat`/`lon` parameters are no longer needed by this action once the intent
-launch code is removed; whether to drop them from the signature or leave
them unused is an implementation-time call, but the intent-building code
itself must be removed, not just dead code left behind.

### 3.2 Empty-address guard

Keep a guard, but change what it checks: since coordinates are no longer
used by this action, guard on the `address` text itself being null/blank
rather than on the `(0.0, 0.0)` coordinate sentinel. If address extraction
genuinely can produce a non-empty address string even when geocoding
(lat/lon) failed, that case should still be copyable -- the driver only
needs the text, not the coordinates, to paste it themselves.

### 3.3 Confirmation feedback

A single "Address copied to clipboard" toast (optionally including a
truncated preview of the address) replaces the old three-outcome toast
set (RoadWarrior-success / fallback-opened / placeholder-blocked). There
are now only two outcomes: copied, or blocked-because-unresolved.

### 3.4 Retire now-unused RoadWarrior intent/package-override code

Previously implemented, now retired: the `ROADWARRIOR_PACKAGE` intent
-building logic, and the runtime package-name override added to the
Permissions & Setup screen (`PermissionsActivity.java`,
`activity_permissions.xml`) as the P5 premortem mitigation in the original
version of this PRD. Both existed solely to make the auto-launched
navigation intent more reliable; with no intent being launched from this
icon, they no longer serve a purpose and should be removed rather than
left as dead, confusing settings.

### 3.5 Manual verification hook

Update `DeveloperTestingActivity`'s existing placeholder-test button
(`addPlaceholderTestStop`, added for the old placeholder-coordinate guard)
to instead register a stop with an empty/unresolved address, exercising
the new blocked-copy path. Keep `addTestStopNearby()` to exercise the
successful-copy path (a real address available, tap, confirm clipboard
contents).

### 3.6 Documentation fix

Update README to describe the icon's tap behavior as copying the address
to the clipboard, replacing any remaining auto-navigate description left
over from the original PRD's documentation fix.

## 4. Testing / verification approach

This repo has no JVM/instrumented unit test source set yet (`app/build.gradle`
has no `test`/`androidTest` config), and this task doesn't warrant adding
one just for a clipboard-copy change. Verification is manual, via the
existing `DeveloperTestingActivity` pattern, same as the original PRD.
Clipboard contents after a tap can be confirmed by pasting into any text
field on the test device -- this is a real, actionable verification step
(unlike the old design's RoadWarrior-specific behavior, which required an
app this environment can't install).

## 4a. Premortem -- status under the new design

The original PRD's premortem (P1-P6) investigated failure modes of the
auto-launched navigation intent. Re-assessed here now that the tap action
no longer launches an intent:

- **P1 (no API key configured), P2 (accessibility revoked), P3 (geocode
  API call fails), P5 (RoadWarrior package divergence)** -- all were
  about *coordinates* being wrong or missing, or about which *app* opens.
  None of that applies once the action is "copy this text string" instead
  of "launch navigation to these coordinates in this app." **Moot under
  the new design.** The mitigations built for them (guard, package
  override, outcome toasts) are retired per §3.4, not reused.
- **P4 (overlay-revoked alert undersells consequence)** -- still
  relevant: if the overlay permission is revoked, this icon (like the
  Smart Score badge) still won't show at all. The existing alert-text fix
  (naming the navigation icon specifically) remains valid and should not
  be reverted.
- **P6 (batch-offer aggregate name leaking into the pin label)** -- still
  directly relevant, arguably more so: if a batch order's aggregate
  string ("Woolworths Fairy Meadow and 1 other store") ever reached the
  tap payload, the driver would copy and paste a wrong/ambiguous address
  instead of just seeing a mislabeled pin. The original investigation
  found this call site only ever receives the real per-stop address
  (`self.pickup["restaurant_name"]`, set exclusively by
  `DasherAccessibilityService`'s screen-parsed Accept handler, never by
  the notification-based aggregate path). This finding is about *which
  address reaches the tap handler*, not about what the handler does with
  it, so it should still hold -- but re-verify it against the new copy
  code path rather than assuming, since the call site is being edited.

## 5. Open questions

None blocking -- the scope above is narrow enough that no user judgment
call is needed before starting.

## 6. Success criteria (implementation-phase checklist)

- [x] `NavigationHelper` (or renamed equivalent)'s tap action copies the
      resolved address text to the clipboard instead of launching a
      navigation intent
- [x] Empty/unresolved address text is guarded: tap shows a distinct
      "address not available yet" toast and does not copy
- [x] Successful copy shows a confirmation toast naming/previewing the
      address copied
- [x] Old RoadWarrior/fallback intent-launch code path is removed
- [x] RoadWarrior package-override setting (Permissions & Setup screen) is
      removed
- [x] `DeveloperTestingActivity` hooks updated: one exercises successful
      copy, one exercises blocked-copy-when-unresolved
- [x] README updated to describe copy-to-clipboard behavior
- [ ] Core requirement manually confirmed on-device: tapping the icon for
      a real, resolved stop copies exactly that address text to the
      clipboard (verified by pasting it somewhere) -- **blocked**: no
      Android emulator/device is available in this environment; see
      PROGRESS.md for what was verified by code inspection instead
- [x] Batch-order pickup case re-verified against the new copy call site
      (per §4a P6)
- [ ] User sign-off
