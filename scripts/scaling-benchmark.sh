#!/usr/bin/env bash
#
# Matcher-scaling benchmark (Phase 7, story P7.6). Measures pairing throughput as the number of
# matcher instances grows, which is the project's headline claim: FOR UPDATE SKIP LOCKED lets
# concurrent matchers partition the queue and add throughput instead of duplicating work.
#
# Topology per run: one api-only instance (matcher loop OFF) serves the API and runs the in-app
# simulator that keeps the queue full; N matcher-only instances (loop ON, random ports) do the
# actual pairing. All share the one Postgres and Redis. Total matches are read from the shared
# Redis counter (eloarena:stats:matches_created), which every matcher increments, so it sums
# throughput across instances regardless of how many there are.
#
# Usage:  scripts/scaling-benchmark.sh [jar] [window_seconds]
# Requires: project Postgres (5433) + Redis (6379) up, port 8080 free, JAVA_HOME = JDK 21.

set -euo pipefail

# The in-app simulator opens thousands of concurrent sockets to its own API, so the default macOS
# soft file-descriptor limit (256) is blown almost immediately ("Too many open files"). Raise it
# for this shell and every java child it launches.
ulimit -n 20480 2>/dev/null || true

# Ramp the simulator's joins over a few seconds so it does not open every socket at once (see
# SimulatorProperties.rampUpMs). At 50k players a synchronized join burst would need ~100k sockets
# in the single api JVM and exhaust file descriptors immediately.
export ELOARENA_SIMULATOR_RAMP_UP_MS="${ELOARENA_SIMULATOR_RAMP_UP_MS:-5000}"

JAR="${1:-target/elo-arena-matchmaker-0.0.1-SNAPSHOT.jar}"
WINDOW="${2:-30}"
BASE="http://localhost:8080"
OUT="scaling-results.md"
PG="${PG_CONTAINER:-match-forge-postgres-1}"
REDIS="${REDIS_CONTAINER:-match-forge-redis-1}"

# Each run is "players matchers strategy".
SPECS=(
  "10000 1 locking"
  "10000 2 locking"
  "10000 3 locking"
  "10000 3 naive"
  "50000 3 locking"
)

MATCHER_PIDS=()
API_PID=""

reset_state() {
  docker exec "$PG" psql -U eloarena -d eloarena -c \
    "TRUNCATE TABLE anomalies, matches, queue_entries, rating_history, season_leaderboard_snapshots, players RESTART IDENTITY CASCADE;" >/dev/null
  docker exec "$REDIS" redis-cli FLUSHALL >/dev/null
}

wait_healthy() {
  for _ in $(seq 1 90); do
    curl -fs "$BASE/actuator/health" >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "api instance did not become healthy" >&2; return 1
}

matches_counter() {
  local v; v=$(docker exec "$REDIS" redis-cli GET eloarena:stats:matches_created 2>/dev/null || echo 0)
  [ "$v" = "" ] && v=0
  echo "$v"
}

anomaly_count() {
  docker exec "$PG" psql -U eloarena -d eloarena -tA -c "SELECT count(*) FROM anomalies;" 2>/dev/null || echo 0
}

stop_all() {
  curl -s -X POST "$BASE/api/admin/simulate/stop" >/dev/null 2>&1 || true
  for pid in "${MATCHER_PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done
  [ -n "$API_PID" ] && kill "$API_PID" 2>/dev/null || true
  wait 2>/dev/null || true
  MATCHER_PIDS=(); API_PID=""
}
trap stop_all EXIT

{
  echo "# Matcher-scaling benchmark"
  echo
  echo "Window: ${WINDOW}s · api-only instance runs the simulator (requeue 0.95, match 20-60ms);"
  echo "N matcher-only instances do the pairing. Throughput is the shared Redis matches counter."
  echo
  echo "| Players | Matchers | Strategy | Matches in window | Throughput (matches/s) | Anomalies |"
  echo "|---------|----------|----------|-------------------|------------------------|-----------|"
} > "$OUT"

for spec in "${SPECS[@]}"; do
  read -r PLAYERS MATCHERS STRATEGY <<< "$spec"
  echo ">>> players=$PLAYERS matchers=$MATCHERS strategy=$STRATEGY"
  reset_state

  # API instance: serves API + simulator, matcher loop OFF.
  java -jar "$JAR" --spring.profiles.active=api-only \
    > "/tmp/eloarena-scale-api.log" 2>&1 &
  API_PID=$!
  wait_healthy

  curl -s -X PUT "$BASE/api/admin/matcher-strategy" -H 'Content-Type: application/json' \
    -d "{\"strategy\":\"$STRATEGY\"}" >/dev/null
  curl -s -X POST "$BASE/api/admin/seed?count=$PLAYERS&seed=42" >/dev/null

  # N matcher instances: loop ON, random port, large batch so they are not tick-starved.
  for i in $(seq 1 "$MATCHERS"); do
    java -jar "$JAR" --spring.profiles.active=matcher-only --server.port=0 \
      --eloarena.matcher.strategy="$STRATEGY" \
      --eloarena.matcher.batch-size=200 --eloarena.matcher.interval-ms=250 \
      > "/tmp/eloarena-scale-matcher-$i.log" 2>&1 &
    MATCHER_PIDS+=($!)
  done
  sleep 12 # let matcher instances finish starting

  curl -s -X POST "$BASE/api/admin/simulate/start" -H 'Content-Type: application/json' \
    -d "{\"players\":$PLAYERS,\"requeueProbability\":0.95,\"matchDurationMinMs\":20,\"matchDurationMaxMs\":60}" >/dev/null
  sleep 6 # warmup before measuring

  C0=$(matches_counter)
  sleep "$WINDOW"
  C1=$(matches_counter)
  ANOM=$(anomaly_count)

  DELTA=$((C1 - C0))
  THROUGHPUT=$(python3 -c "print(round($DELTA/$WINDOW, 1))")
  echo "| $PLAYERS | $MATCHERS | $STRATEGY | $DELTA | $THROUGHPUT | $ANOM |" >> "$OUT"

  stop_all
  sleep 3
done

echo
echo "Results written to $OUT:"
cat "$OUT"
