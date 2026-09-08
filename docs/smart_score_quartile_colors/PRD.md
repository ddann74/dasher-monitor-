# Smart Score quartile colors

STATUS: IMPLEMENTED (2026-09-06)

## 0. Origin

Driver asked what the Smart Score's label colors were, expecting 4
(one per quartile) -- the app had 4 labels (Excellent/Good/Fair/Poor,
from `SmartScoreEngine._label`'s learned-quartile thresholds) but only
3 visually distinct colors (Excellent and Good were both green, one
darker than the other). Iterated with the driver to a final palette:
green / orange / red / purple, with "Poor" additionally getting a
diagonal black-striped pattern instead of a plain color, as a
deliberately louder warning treatment.

Also confirmed directly from `_learned_label_thresholds()`'s own query:
yes, the quartile cutoffs are computed from ALL scored offers in the
last 90 days (`offer_outcomes`, no filter on outcome) -- accepted,
declined, AND timed-out/unresponded offers, not just accepted ones.

## 1. Palette

| Label | Color | Hex (base, before alpha) |
|---|---|---|
| Excellent | Green | `#2E7D32` |
| Good | Orange | `#EF6C00` |
| Fair | Red | `#C62828` |
| Poor | Purple, diagonal black stripes | `#6A1B9A` base + black stripes |

## 2. Centralized, not duplicated

This exact 4-label mapping previously existed as 4 separate, manually
hand-copied switch statements (`DasherAccessibilityService.
colorForLabel`, `DeveloperTestingActivity`'s inline copy explicitly
commented "kept in sync manually", `LocationProfitabilityMapActivity.
colorForLabel`, and `TutorialActivity` which had drifted out of sync
entirely -- hardcoding the old "Excellent" green regardless of the
actual computed label, a real, self-caught bug found while making this
change). Centralized into three new `OverlayHelper` static methods:

- `baseColorForScoreLabel(label)` -- the plain `int` color, the single
  source of truth every other method/caller builds on.
- `isPoorScoreLabel(label)` -- whether this is the "everything else"
  bucket that gets the striped treatment.
- `backgroundForScoreLabel(context, label)` -- a full `Drawable`
  (`ColorDrawable` for the three plain colors, `stripedDrawable` for
  Poor), for callers that show it as a View background.

All 4 previous call sites now delegate to these instead of maintaining
their own copy.

## 3. The striped pattern

`OverlayHelper.stripedDrawable(context, baseColor, stripeColor)`: no
existing UI element in this app used a pattern before, only solid
colors -- built as a small tile (drawn once with `Canvas`/`Paint`,
diagonal lines at a fixed stroke width) wrapped in a `BitmapDrawable`
with `REPEAT` tile mode, so it renders correctly at any actual badge/
marker size rather than being pre-sized for one specific use.

Two different rendering contexts needed this:
- The live offer badge (`OverlayHelper.showMessage`) is a real
  `TextView`, so a new `Drawable`-accepting overload was added
  alongside the existing `int`-color one (which now just wraps its
  color in a `ColorDrawable` and calls the new overload -- every
  existing caller unaffected).
- The profitability map's markers (`LocationProfitabilityMapActivity.
  coloredDotBitmap`) are pre-rendered onto a small `Bitmap` for
  `Marker.setIcon()`, not a live View -- stripes are drawn there by
  clipping the canvas to the marker's own circular path and drawing
  the shared striped `Drawable` on top, so the pattern stays confined
  to the circle rather than spilling into the bitmap's transparent
  corners.

## 4. Honest note on at-a-glance legibility

Flagged directly to the driver before building: a striped pattern is
slower to read at a glance than a solid color, and this badge is meant
to be glanced at while driving. Driver's explicit choice, not silently
assumed -- offered a solid-color-with-black-border alternative first,
driver confirmed the literal striped version.

## 5. Verification

No Android SDK/emulator in this environment (disclosed limitation,
consistent with every other Java-only change in this repo):
- Manual trace of `stripedDrawable`'s tile-drawing loop bounds
  (finite, ~4-6 iterations at realistic density values, no risk of a
  runaway loop).
- Confirmed `Canvas.clipPath` is used on a plain software-rendered
  Bitmap canvas (not a hardware-accelerated View), where it's fully
  supported on every API level this app targets -- not the
  hardware-layer case where `clipPath` has known restrictions.
- `grep` confirmed zero remaining references to the old method/field
  names (`colorForLabel`, `smartScoreBadgeColor`) anywhere in the
  Java tree after the refactor.
- Brace/paren balance confirmed on all 5 touched files (`OverlayHelper.
  java` 87/87 braces, 391/391 parens; `DasherAccessibilityService.java`
  151/151, 553/553; `DeveloperTestingActivity.java` 47/47, 230/230;
  `TutorialActivity.java` 44/44, 213/213; `LocationProfitabilityMap
  Activity.java` 16/16, 119/119).
