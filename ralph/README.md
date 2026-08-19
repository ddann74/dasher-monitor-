# Ralph loop — Dasher Monitor

## What this is

A "Ralph loop" (named after the technique popularized by Geoffrey Huntley) is a simple
pattern for autonomous, iterative build-out with a coding agent: run the agent in a loop
against a fixed prompt, a spec, and a shared task list. Each iteration has no memory of the
last except what's committed to git — so the spec and task list *are* the memory. This
forces small, verifiable, checkpointed progress instead of one long unsupervised run.

## Files in this folder

| File | Role |
|---|---|
| `../PRD.md` | The spec. Defines "done" with acceptance criteria, not adjectives. |
| `TASKS.md` | The backlog, ordered, one checkbox per iteration-sized unit of work. |
| `PROMPT.md` | The fixed prompt sent to the agent every iteration. |
| `ralph.sh` | The bash loop that re-invokes the agent, checks the stop condition, logs each run. |
| `PROGRESS.log` | Append-only log the agent writes to each iteration (created on first run). |
| `run_logs/` | Full transcript of every iteration (created on first run). |

## Why this repo needs it

Unlike NPL_Intelligence_Engine (built from a single self-reported "final production report"),
this repo's README is a changelog accumulated across many prior sessions — features, bug fixes,
and honesty caveats added incrementally. `PRD.md` found the README internally contradicts
itself on at least three items (its own TODO list claims things aren't built that the code
shows are), so the first job of this loop is reconciling claim vs. code, not just building new
features. See `PRD.md`'s status note and §7 (Open Risks) for the full picture.

## What "actually done" means for THIS repo

This is an Android app using Gradle + Chaquopy (embedded Python). Most agent sandboxes do
**not** have the Android SDK, an emulator, or a device attached. Be honest about which
verification level a given task can actually reach:

1. **Best**: `./gradlew assembleDebug` (or `./gradlew test` for the Python/JVM-testable parts)
   actually runs and succeeds, in an environment that has the Android SDK installed. If this
   environment doesn't have one, say so explicitly in `PROGRESS.log` rather than skipping the
   attempt — try it, capture the real failure (e.g. "SDK not found"), and fall back to level 2.
2. **Middle**: unit tests for the pure-Python logic (`app/src/main/python/drive_monitor.py`)
   run directly with `python -m pytest` or `python -m unittest`, bypassing Gradle/Chaquopy
   entirely — this is real, run-and-check verification even without an Android toolchain, and
   should be preferred wherever the task is testable this way.
3. **Weakest, use only when 1 and 2 are both impossible**: a careful, explicit manual-review
   checklist — read the exact code path involved, confirm it does what's claimed line by line,
   and write down specifically what was checked and why it's sufficient. This is NOT the same
   as "looks right" — it must be a checklist a human could re-run and get the same answer from.
4. **Requires a real device/account and cannot be done in an agent sandbox at all**: anything
   needing a real Dasher offer on screen, a real customer message, a real OEM device to test an
   autostart deep-link, or RoadWarrior installed. Tasks like these get marked with an explicit
   manual-verification script (exact steps a human runs later) instead of being closed by the
   loop itself — do not check these boxes from inside the loop.

Never claim a task passed level 1 or 2 verification without pasting the real command output
into `PROGRESS.log` in the same iteration.

## Running it

```bash
# from the repo root
chmod +x ralph/ralph.sh
MAX_ITERATIONS=50 ./ralph/ralph.sh
```

Recommended for early runs:
- Start with `MAX_ITERATIONS=5` and read every transcript in `ralph/run_logs/` before letting
  it run longer unattended.
- Don't add an auto-approve/"skip permissions" flag until you trust the loop's behavior on
  this repo — the script deliberately omits one.
- Watch for "WARNING: iteration N made no committed changes" — a few of these in a row
  usually means the agent is stuck on an ambiguous or device-dependent task; go read
  `PROGRESS.log` and either clarify `TASKS.md`/`PRD.md` by hand, or convert the task into an
  explicit manual-verification script instead.

## When it's actually done

Not when `TASKS.md` has no more `[ ]` boxes. Done means every checked box has a linked
artifact — real test output, a real build log, or an explicit, re-runnable manual-verification
checklist for anything that genuinely needs a device — that a human can open and independently
confirm. Skim `PROGRESS.log` for the phrase "Flagged for human review" — that's where the agent
surfaces anything from the README it couldn't verify, found to be stale (like the TODO-list
contradiction `PRD.md` already found), or found to be untrue once actually checked.
