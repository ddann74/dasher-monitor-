# Duplicate customer-instruction announcement

STATUS: IMPLEMENTED (2026-09-06)

## 0. Origin

Direct driver complaint (garbled in the original message, clarified by
tracing the actual code): customer delivery instructions were being
read aloud twice per stop -- once when approaching, and again at
arrival, with the arrival announcement also adding an unwanted
"Arrived at [address]" preamble the driver didn't ask for.

## 1. Root cause, confirmed by reading both trigger points

Two independent constants, both exactly 50 meters:
`INSTRUCTION_READ_RADIUS_METERS` (drives `_check_approach_instruction`,
driver backlog #4's own "read aloud... when i approach the address
within 50 meters") and `ARRIVAL_GEOFENCE_METERS` (drives
`_evaluate_arrivals`). Both fire at essentially the same moment, and
both independently read `self.messages` for any customer-chat-derived
instruction within the same `_last_message_cutoff` window --
`_check_approach_instruction` never advanced that cutoff, so the exact
same message was still inside the window when `_evaluate_arrivals` read
it moments later, spoke it again, and only THEN advanced the cutoff.

The dropoff screen's own persistent per-stop instruction
(`delivery_instruction`) was never part of this -- only chat-derived
messages are read at arrival at all, so that field was never actually
duplicated.

## 2. Fix

Each message dict (`TripManager.on_message`) now carries a new
`instruction_announced` flag, `False` until either trigger point
actually speaks it. Both `_check_approach_instruction` and
`_evaluate_arrivals`' own instruction-gathering block now additionally
exclude already-announced messages, and mark whichever ones they DO
include as announced.

Deliberately per-message, not a shared cutoff advance: the existing
`_last_message_cutoff` is a single, trip-wide value already relied on
by both triggers across EVERY stop in a multi-stop/batch order (see
this function's own existing `stop_id` matching, added specifically to
keep a batch's messages attached to the right stop). Advancing that
shared cutoff the moment any one stop's approach fires would risk
silently skipping a DIFFERENT stop's message that arrives in between --
tracking "announced" on the message itself avoids that risk entirely
while still definitely stopping the observed duplicate.

## 3. A real, pre-existing behavior this surfaced (not changed here)

`TripManager.pending_arrival` (and therefore the entire "Arrived at
[address]..." voice announcement + overlay) is ONLY ever populated when
there's a new instruction to report -- confirmed by reading every
assignment site. A delivery with no customer chat message never
triggers an "Arrived at X" announcement at all, with or without this
fix. That means for the exact case the driver described (an
instruction already read at approach, nothing new by arrival), no
arrival announcement fires now -- which is the correct fix for the
duplicate, and also incidentally means the "don't tell me arrived at
the address" part of the request is satisfied for that case, without
touching the announcement's existing wording. Not a general "arrival"
indicator independent of instructions -- that would be a separate,
new feature, not built here.

## 4. Verification

Real, executable tests (`test_instruction_not_duplicated.py`, 3 cases,
all passing):
1. Approach trigger announces a chat-derived instruction and marks it.
2. The SAME instruction does not re-fire at arrival (`take_pending_
   arrival()` correctly returns `None` -- nothing new to report).
3. A genuinely NEW message arriving between approach and arrival still
   announces correctly at arrival, and does not also repeat the
   earlier, already-announced one -- confirms the fix doesn't
   over-suppress.

Re-ran the full existing scratchpad suite: `test_instruction_read_
radius.py` (the closest pre-existing coverage) still passes unchanged.
No regressions elsewhere; only the known, pre-existing, unrelated
`test_dropoff_instruction_wiring.py` failure (stale signature) and the
API-key-gated `test_parking_v4alpha.py` script, neither touched by this
change.

`python3 -m py_compile drive_monitor.py` clean.
