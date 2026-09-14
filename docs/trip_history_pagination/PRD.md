# PRD: Fix Trip History's silent 20-trip cap

Status: IMPLEMENTED (2026-09-14), follow-up scouting-pass finding.

## 1. What was wrong

`get_trip_history(self, limit=20)` (`drive_monitor.py`) defaults to the
20 most recent completed trips. `TripListActivity`, the one real Java
call site, called it with no arguments at all -- so any driver with
more than 20 total completed trips (trivial after a couple weeks of
normal use) permanently lost browsable access to everything older on
the dedicated "Trip History" screen, with no scroll-to-load-more, no
page indicator, and no signal that older trips even existed. The filter
toggles (All/Dasher/General) only ever re-sliced that same fixed
20-trip window. `docs/trip_history_redesign/PRD.md`, the PRD that built
this screen, explicitly asserted `get_trip_history()` "already returns
everything this needs" -- directly contradicted by the function's own
`limit=20` default, so that claim was never actually verified against
the redesign's real intent, just assumed.

## 2. Design

### 2.1 `get_trip_history(limit=20, before_id=None)` (`drive_monitor.py`)

New `before_id` parameter: when given, pages backward from that trip
id (`WHERE id < ? ORDER BY id DESC`). Cursor pagination, not an
`OFFSET` -- correct even if a new trip completes between page loads
(an `OFFSET`-based scheme would skip or repeat rows under exactly that
condition, since the "offset" itself shifts as new rows are inserted at
the top).

Fetches one extra row past `limit` to compute `has_more` precisely
without a separate `COUNT(*)` query, then trims back down to `limit`
before returning it in the response alongside `has_more`.

### 2.2 `TripListActivity` (Java)

`loadTrips()` (called once at `onCreate`) resets to a fresh, empty
`allTrips` and calls the new `loadMoreTrips()`, which fetches one page,
APPENDS it to `allTrips` (not replaces), updates `hasMoreTrips`, and
advances `nextBeforeId` to the last-loaded trip's id. `loadingMore`
guards against a re-entrant second fetch (e.g. a rapidly double-tapped
Load More row) stacking duplicate trips into `allTrips` mid-flight.

`renderRows()` (the existing client-side filter/render pass, unchanged
in its own filtering logic) now appends a tappable "Load More Trips"
footer row whenever `hasMoreTrips` is true, and distinguishes two
different empty states: genuinely no trips at all, vs. none of the
trips loaded SO FAR matching the current filter while more exist
further back (shown with its own message and a way to keep loading,
instead of a flat, indistinguishable-from-"nothing exists" dead end).

## 3. Verification

- **Real, executable Python test** (real sqlite3, not `:memory:`; a
  real `DriveMonitorEngine` instance, not reimplemented SQL): inserted
  45 completed trips into a fresh database and paged through all three
  resulting pages (20 + 20 + 5) via `before_id`, confirming newest-first
  ordering, the correct `has_more` flag at every boundary (true, true,
  then false on the exhausted last page), no gaps or overlap across all
  45 trips combined, and that the OLD no-argument call site (still used
  as the very first page's own call shape) still returns the newest 20
  unchanged. 14 checks, all passed.
- **Real, compiled, executed Java test** (`TripListPaginationTest.java`,
  `org.json` from Maven Central): exercised the exact accumulation/
  cursor logic from `loadMoreTrips()` against realistic multi-page
  `get_trip_history` responses -- confirmed a first page populates
  `allTrips` and the cursor correctly, a second page APPENDS (not
  replaces) with the cursor advancing and `hasMoreTrips` flipping false
  once exhausted, and a defensive empty-page call (e.g. an extra tap
  after `has_more` already went false) doesn't corrupt the accumulated
  list or the cursor. 3 checks, all passed.
- `python3 -m py_compile` clean; brace/paren balance confirmed on
  `TripListActivity.java`.

## 4. Honest limits

- No Android device/emulator available in this environment -- the real
  on-screen "Load More Trips" row and its tap behavior have not been
  observed on a real screen.
- `hasMoreTrips`/pagination state is not preserved across an Activity
  recreation (e.g. a screen rotation) -- `onCreate` always starts over
  from `loadTrips()`'s fresh first page. Not a new gap introduced here;
  the screen already had no saved-instance-state handling before this
  fix, and re-fetching the first page on rotation is a reasonable,
  low-cost default, not a regression from prior behavior.
- The page size (`TRIP_HISTORY_PAGE_SIZE = 20`) is an unchanged
  judgment call inherited from the original `limit=20` default, not a
  newly-derived constant.

## 5. Success criteria

- [x] `get_trip_history` supports real cursor-based pagination via
      `before_id`, with a precise `has_more` flag
- [x] Cursor pagination chosen specifically over `OFFSET` to stay
      correct as new trips complete between page loads
- [x] `TripListActivity` accumulates pages (not replaces), with a
      re-entrancy guard against a double-tapped Load More
- [x] A tappable "Load More Trips" footer appears whenever more trips
      exist past what's currently loaded
- [x] The "no trips match this filter" empty state is distinguished
      from "more trips exist, just not loaded yet"
- [x] Real executable Python test (14 checks) and real compiled/executed
      Java test (3 checks) both fully passed
- [x] `python3 -m py_compile` clean; brace/paren balance confirmed
- [ ] HONEST LIMIT: no Android device/emulator available in this
      environment -- see ss4.
- [ ] Driver confirms in real use: trips beyond the first 20 are now
      reachable, the Load More row appears/disappears correctly, and
      filtering + loading more together behave sensibly.
- [ ] Driver sign-off.
