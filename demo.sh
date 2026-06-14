#!/usr/bin/env bash
#
# 30-second terminal demo. Seeds 1k players, starts the simulator, tails the live stats for a
# minute, then prints the leaderboard. Run it against a running stack (docker compose up, or the
# app on :8080). This is the quick "is it alive and doing something" demo for the README.
#
# Usage:  ./demo.sh [base_url] [players] [seconds]

set -euo pipefail

BASE="${1:-http://localhost:8080}"
PLAYERS="${2:-1000}"
SECONDS_TO_TAIL="${3:-60}"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }

# Pull a field out of a JSON object on stdin without needing jq.
jget() { python3 -c "import sys,json; print(json.load(sys.stdin).get('$1',''))"; }

if ! curl -fs "$BASE/actuator/health" >/dev/null 2>&1; then
  echo "App not reachable at $BASE. Start the stack first (docker compose up)." >&2
  exit 1
fi

say "Seeding $PLAYERS players (idempotent; skips handles that already exist)"
curl -s -X POST "$BASE/api/admin/seed?count=$PLAYERS&seed=42" >/dev/null
echo "done."

say "Starting the simulator ($PLAYERS players, requeue 0.9)"
curl -s -X POST "$BASE/api/admin/simulate/start" -H 'Content-Type: application/json' \
  -d "{\"players\":$PLAYERS,\"requeueProbability\":0.9,\"matchDurationMinMs\":20,\"matchDurationMaxMs\":60}" >/dev/null
echo "running."

say "Live stats for ${SECONDS_TO_TAIL}s (queue depth · matches/sec · avg wait · strategy · anomalies)"
printf '%-9s | %-9s | %-11s | %-9s | %-9s | %s\n' "t(s)" "queue" "matches/s" "avg wait" "strategy" "anomalies"
END=$((SECONDS + SECONDS_TO_TAIL))
T0=$SECONDS
while [ "$SECONDS" -lt "$END" ]; do
  S=$(curl -s "$BASE/dashboard/stats")
  QD=$(echo "$S" | jget queueDepth)
  MPS=$(echo "$S" | jget matchesPerSec)
  AW=$(echo "$S" | jget avgWaitMs)
  ST=$(echo "$S" | jget currentStrategy)
  AN=$(echo "$S" | jget anomalyCount)
  printf '%-9s | %-9s | %-11s | %-9s | %-9s | %s\n' \
    "$((SECONDS - T0))" "$QD" "$(printf '%.1f' "${MPS:-0}")" "$(printf '%.0fms' "${AW:-0}")" "$ST" "$AN"
  sleep 2
done

say "Stopping the simulator"
curl -s -X POST "$BASE/api/admin/simulate/stop" >/dev/null
echo "stopped."

say "Leaderboard (top 10)"
curl -s "$BASE/api/leaderboard?limit=10" | python3 -c "
import sys, json
rows = json.load(sys.stdin)
print(f'{\"rank\":<5}{\"player\":<22}{\"rating\":>7}')
for r in rows:
    print(f'{r[\"rank\"]:<5}{r[\"handle\"]:<22}{r[\"rating\"]:>7}')
"
