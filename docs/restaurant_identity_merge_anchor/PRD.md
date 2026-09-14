# PRD: Fix restaurant identity merge chain-drift

Status: IMPLEMENTED (2026-09-14), scouting-pass finding (round 4).

## 1. What was wrong

`_canonicalize_restaurant_name` (`docs/restaurant_identity_merge/PRD.md`)
merges a new pickup into an existing restaurant name when it's within
`RESTAURANT_IDENTITY_MERGE_RADIUS_METERS` (25m) of that name's
recorded location -- comparing against the RUNNING AVERAGE of every
prior pickup under that name.

The bug: every merge pulls a new (genuinely different-restaurant)
sample into that average, so the average itself drifts with each
merge. Precisely reproduced: Restaurant A at position 0m, B at 20m
from A (a real, correct merge -- within 25m of A's own true location)
merges into "A". "A"'s average then shifts to the midpoint (10m). A
THIRD, genuinely different restaurant C at 34m from A's real location
-- more than the 25m radius, should never be considered the same place
-- is only 24m from that DRIFTED average, so it incorrectly merges too.

This directly contradicted the original PRD's own claim that the 25m
radius keeps a food court's separate restaurants apart -- that claim
was only ever validated against spacing greater than 25m from every
other restaurant's average, not against a chain of closely-spaced real
businesses where the average itself moves. Once merged, the swallowed
restaurant's real wait-time/parking-difficulty/profitability samples
permanently roll into the wrong identity going forward -- corrupting
exactly the learned data (Address Book stats, location profitability,
parking-difficulty zones) this feature exists to make trustworthy.

## 2. Design

Compare against each other name's ANCHOR location instead -- its FIRST
ever recorded pickup (`MIN(id)` per name), not a running average. A
later merge from a genuinely different, nearby restaurant can then
never move where future comparisons are measured from, since the
anchor is fixed at the very first sample recorded under that name and
never updated afterward.

**Deliberate tradeoff, disclosed**: this trades away catching a
legitimate name-variant merge in the rare case where a restaurant's
very first-ever recorded sample happened to be a noisy GPS outlier (a
false NEGATIVE -- two aliases of the same real place stay separate).
That's accepted over the far worse failure mode this bug caused (a
false POSITIVE -- two different real restaurants silently sharing one
identity, corrupting both). A missed merge is recoverable (the driver
would just see the same restaurant under two names, no worse than
before this feature existed); cross-restaurant data corruption is not
recoverable, since existing rows are never rewritten once merged.

## 3. Verification

- Real, executable Python test (real `DriveMonitorEngine`, not
  reimplemented logic; used direct instantiation rather than the
  module's `get_engine()` singleton wrapper, since multiple genuinely
  independent engines were needed in one script and `get_engine`
  caches a single global instance): precisely reproduced the drift
  mechanism using exact latitude-offset distances (verified via the
  real `haversine_meters` function itself before asserting merge
  behavior) -- confirmed a genuine 20m merge (B into A) still happens
  correctly, and confirmed a THIRD restaurant 34m from A's real anchor
  (but only 14m from the now-merged B, which is what fooled the old
  drifted-average check) correctly stays separate. Also confirmed a
  genuine name-variant merge at the same real location still works
  (the feature's actual purpose, unbroken by this fix), and confirmed
  a merged group's anchor stays fixed -- a second merge into it can't
  itself become a new movable anchor for a third, distant restaurant
  to chain off of. 7 checks, all passed.
- `python3 -m py_compile` clean.

## 4. Honest limits

- No Android device/emulator available in this environment -- real GPS
  noise characteristics around actual restaurant entrances/parking
  areas are unobserved; the 25m radius and the anchor-vs-average
  tradeoff are reasoned from this analysis, not from real-world data.
- Any restaurant merges that already happened under the OLD, buggy
  averaging logic before this fix shipped are NOT retroactively
  un-merged -- this fix prevents new incorrect merges going forward,
  it does not detect or repair contamination that may have already
  occurred.
- The anchor-based approach means a restaurant's very first recorded
  sample effectively becomes permanent for merge-comparison purposes,
  for the life of that name. If that first sample is later found to
  have been a genuine GPS outlier, there's no mechanism to correct it
  short of directly editing the database -- an accepted, disclosed
  cost of eliminating the drift bug, not something this fix attempts
  to also solve.

## 5. Success criteria

- [x] A genuine, correct merge (within 25m of a restaurant's real
      anchor) still happens
- [x] A genuinely different, more-distant restaurant no longer
      chain-merges via a drifted average -- the exact bug this fixes
- [x] A merged group's anchor stays fixed, can't itself become a new
      movable anchor for further incorrect chaining
- [x] The feature's real purpose (merging genuine name variants of the
      same real place) is unbroken
- [x] Real executable Python test (7 checks, precisely isolating the
      drift mechanism with exact verified distances) fully passed
- [x] `python3 -m py_compile` clean
- [ ] HONEST LIMIT: no Android device/emulator available, and no
      retroactive repair of any pre-fix contamination -- see §4.
- [ ] Driver confirms in real use that Address Book / profitability
      stats for distinct nearby restaurants (e.g. in a food court) stay
      correctly separate going forward.
- [ ] Driver sign-off.
