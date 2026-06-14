#!/usr/bin/env bash
#
# Connection-pool benchmark (Phase 7, stories P7.5 and P7.6).
#
# For each pool size it starts a fresh app instance (fresh metrics), seeds players, runs the
# in-app simulator under heavy concurrency for a fixed window, then scrapes the metrics that tell
# the contention story: connection-acquisition wait (the bottleneck probe) and result-processing
# latency (the hot path that spikes when the pool is starved). Restarting per pool size gives
# clean, comparable metric windows.
#
# Usage:
#   scripts/benchmark.sh [jar] [pool_sizes] [players] [duration_seconds]
# Example:
#   scripts/benchmark.sh target/elo-arena-matchmaker-0.0.1-SNAPSHOT.jar "10 30 50" 8000 60
#
# Requires: the project's Postgres (5433) and Redis (6379) running, port 8080 free, and JAVA_HOME
# pointing at a JDK 21. Touches only this project's datastores.

set -euo pipefail

# The in-app simulator opens thousands of concurrent sockets to its own API, so the default macOS
# soft file-descriptor limit (256) is blown almost immediately ("Too many open files" in the
# Tomcat acceptor). Raise it for this shell and every java child it launches.
ulimit -n 20480 2>/dev/null || true

# Ramp the simulator's joins over a few seconds so it does not open every socket at once (see
# SimulatorProperties.rampUpMs). Without this the synchronized startup burst exhausts file
# descriptors before the steady-state load even begins.
export ELOARENA_SIMULATOR_RAMP_UP_MS="${ELOARENA_SIMULATOR_RAMP_UP_MS:-4000}"

JAR="${1:-target/elo-arena-matchmaker-0.0.1-SNAPSHOT.jar}"
POOL_SIZES="${2:-10 30 50}"
PLAYERS="${3:-8000}"
DURATION="${4:-60}"
BASE="http://localhost:8080"
OUT="benchmark-results.md"
PG_CONTAINER="${PG_CONTAINER:-match-forge-postgres-1}"

# Boost the matcher so it is NOT the bottleneck: we want connection-pool acquisition to be the
# variable under test, not the matcher tick rate. A large batch drained frequently keeps the
# queue shallow so the load lands on the result-submission hot path, which is where pool
# contention shows. (These are env overrides; relaxed binding maps them onto eloarena.matcher.*)
export ELOARENA_MATCHER_BATCH_SIZE="${ELOARENA_MATCHER_BATCH_SIZE:-500}"
export ELOARENA_MATCHER_INTERVAL_MS="${ELOARENA_MATCHER_INTERVAL_MS:-250}"

truncate_data() {
  docker exec "$PG_CONTAINER" psql -U eloarena -d eloarena -c \
    "TRUNCATE TABLE anomalies, matches, queue_entries, rating_history, season_leaderboard_snapshots, players RESTART IDENTITY CASCADE;" >/dev/null
}

metric() { # metric(name, statistic) -> value
  curl -s "$BASE/actuator/metrics/$1" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(next((m['value'] for m in d['measurements'] if m['statistic']=='$2'), 0))" 2>/dev/null || echo 0
}

percentile() { # percentile(name, quantile) from prometheus
  curl -s "$BASE/actuator/prometheus" \
    | grep "^$1{" | grep "quantile=\"$2\"" | awk '{print $2}' | head -1 || echo ""
}

wait_healthy() {
  for _ in $(seq 1 60); do
    if curl -fs "$BASE/actuator/health" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "app did not become healthy" >&2; return 1
}

wait_port_free() { # don't start the next instance until the previous one has released 8080,
                   # otherwise the new app binds late and the first request hits the dying one.
  for _ in $(seq 1 30); do
    lsof -ti :8080 >/dev/null 2>&1 || return 0
    sleep 1
  done
  echo "port 8080 still in use" >&2; return 1
}

{
  echo "# Connection-pool benchmark"
  echo
  echo "Players: $PLAYERS · load window: ${DURATION}s · in-app simulator (requeue 0.9, match 20-60ms)"
  echo
  echo "| Pool size | Matches created | Acquire wait mean (ms) | Acquire wait max (ms) | Result p99 (ms) | Queue wait p99 (ms) |"
  echo "|-----------|-----------------|------------------------|-----------------------|-----------------|---------------------|"
} > "$OUT"

for POOL in $POOL_SIZES; do
  echo ">>> pool size $POOL"
  # Clean, identical starting state for every pool size so the comparison is fair.
  truncate_data
  DB_POOL_SIZE="$POOL" java -jar "$JAR" > "/tmp/eloarena-bench-pool-$POOL.log" 2>&1 &
  APP_PID=$!
  trap 'kill $APP_PID 2>/dev/null || true' EXIT
  wait_healthy

  curl -s -X POST "$BASE/api/admin/seed?count=$PLAYERS&seed=42" >/dev/null
  curl -s -X POST "$BASE/api/admin/simulate/start" -H 'Content-Type: application/json' \
    -d "{\"players\":$PLAYERS,\"requeueProbability\":0.9,\"matchDurationMinMs\":20,\"matchDurationMaxMs\":60}" >/dev/null

  sleep "$DURATION"

  curl -s -X POST "$BASE/api/admin/simulate/stop" >/dev/null
  sleep 2

  MATCHES=$(metric eloarena.matches.created COUNT)
  ACQ_COUNT=$(metric hikaricp.connections.acquire COUNT)
  ACQ_TOTAL=$(metric hikaricp.connections.acquire TOTAL_TIME)
  ACQ_MAX=$(metric hikaricp.connections.acquire MAX)
  RES_P99=$(percentile eloarena_result_processing_seconds 0.99)
  WAIT_P99=$(percentile eloarena_queue_wait_seconds 0.99)

  ACQ_MEAN_MS=$(python3 -c "print(round(($ACQ_TOTAL/$ACQ_COUNT*1000) if $ACQ_COUNT>0 else 0, 2))")
  ACQ_MAX_MS=$(python3 -c "print(round($ACQ_MAX*1000, 2))")
  RES_P99_MS=$(python3 -c "print(round(float('${RES_P99:-0}' or 0)*1000, 2))")
  WAIT_P99_MS=$(python3 -c "print(round(float('${WAIT_P99:-0}' or 0)*1000, 2))")

  echo "| $POOL | $MATCHES | $ACQ_MEAN_MS | $ACQ_MAX_MS | $RES_P99_MS | $WAIT_P99_MS |" >> "$OUT"

  kill $APP_PID 2>/dev/null || true
  wait $APP_PID 2>/dev/null || true
  trap - EXIT
  wait_port_free
done

echo
echo "Results written to $OUT:"
cat "$OUT"
