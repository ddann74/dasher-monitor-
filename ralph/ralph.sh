#!/usr/bin/env bash
# Ralph loop for Dasher Monitor.
#
# Repeatedly invokes an agentic coding CLI with a fixed prompt (ralph/PROMPT.md).
# Each iteration reads PRD.md + ralph/TASKS.md + ralph/PROGRESS.log, does ONE task,
# verifies it for real, commits, and stops. This script just re-runs that loop and
# adds guardrails: an iteration cap, a stop-condition check, and a full log per run.
#
# Written for Claude Code (`claude` CLI). Swap the AGENT_CMD line for another
# agentic CLI if you're using a different tool.

set -euo pipefail

cd "$(dirname "$0")/.."   # repo root (parent of ralph/)

MAX_ITERATIONS="${MAX_ITERATIONS:-50}"
SLEEP_BETWEEN_SECONDS="${SLEEP_BETWEEN_SECONDS:-5}"
LOG_DIR="ralph/run_logs"
mkdir -p "$LOG_DIR"
touch ralph/PROGRESS.log

echo "Ralph loop starting. Max iterations: $MAX_ITERATIONS"

for i in $(seq 1 "$MAX_ITERATIONS"); do
  echo "=== Iteration $i / $MAX_ITERATIONS ==="
  ts=$(date -u +%Y%m%dT%H%M%SZ)
  iter_log="$LOG_DIR/iter_${i}_${ts}.log"

  # Stop condition: every checkbox in TASKS.md already checked.
  if ! grep -q '\- \[ \]' ralph/TASKS.md; then
    echo "No unchecked tasks remain in ralph/TASKS.md. Stopping."
    echo "$(date -u -Iseconds) Loop stopped: all tasks checked." >> ralph/PROGRESS.log
    break
  fi

  # --- Agent invocation ---
  # Requires the Claude Code CLI installed and authenticated.
  # --print runs non-interactively; adjust flags for your installed version.
  # Review your CLI's permission model before adding any auto-approve flag —
  # this script intentionally does NOT pass one, so you can supervise early runs.
  if command -v claude >/dev/null 2>&1; then
    AGENT_CMD=(claude --print "$(cat ralph/PROMPT.md)")
  else
    echo "ERROR: 'claude' CLI not found on PATH. Install Claude Code or edit"
    echo "AGENT_CMD in this script to point at your agentic coding tool."
    exit 1
  fi

  "${AGENT_CMD[@]}" 2>&1 | tee "$iter_log"

  # Surface whether the working tree actually changed this iteration —
  # a no-op iteration (no commit) after several in a row usually means the
  # agent is stuck (often on a task that needs a real device/Android SDK this
  # environment doesn't have); that's a signal to stop and look, not to keep
  # looping.
  if git diff --quiet HEAD -- . ':!ralph/run_logs'; then
    echo "WARNING: iteration $i made no committed changes. Check $iter_log."
  fi

  sleep "$SLEEP_BETWEEN_SECONDS"
done

echo "Ralph loop finished (or hit iteration cap). Review ralph/PROGRESS.log and"
echo "ralph/TASKS.md for current status, and $LOG_DIR for full transcripts."
