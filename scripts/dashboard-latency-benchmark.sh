#!/usr/bin/env bash
#
# Dashboard read-endpoint latency benchmark (Phase 8, story P8.1).
#
# The dashboard polls /dashboard/queue, /dashboard/matches, /dashboard/stats, and
# /dashboard/anomalies every one to two seconds from every open tab, so each endpoint has a hard
# budget: under 50ms at 10k players. This verifies that against a 10k-player working set rather
# than assuming it.
#
# Methodology note (this is the important part). The budget is about *data scale*: do these reads
# stay cheap when the queue is deep and the matches table is large. So we build a 10k-player data
# set at rest and measure the reads against it with the system otherwise idle. We deliberately do
# NOT run the in-app simulator during measurement: a 10k-thread closed-loop load generator
# co-located in the same JVM would starve the very reads it is timing (it steals the CPU and the
# connection pool), so the numbers would measure contention, not the endpoint's cost. The matcher
# loop is also left off so the pre-filled queue and match history stay fixed for the whole run. The
# read endpoints are bounded top-N queries over indexed columns, so this is exactly the property
# under test: latency must not grow with how deep the queue or how large the matches table is.
#
# Usage:   scripts/dashboard-latency-benchmark.sh [jar] [players] [matches] [samples]
# Requires: project Postgres (5433) + Redis (6379) up, port 8080 free, JDK 21.

set -euo pipefail

JAR="${1:-target/elo-arena-matchmaker-0.0.1-SNAPSHOT.jar}"
PLAYERS="${2:-10000}"
MATCHES="${3:-50000}"
SAMPLES="${4:-200}"
QUEUE_DEPTH="$PLAYERS"          # every player parked WAITING: the deepest the queue panel ever reads
ANOMALIES="${ANOMALIES:-300}"
BASE="http://localhost:8080"
OUT="dashboard-latency-results.md"
PG="${PG_CONTAINER:-match-forge-postgres-1}"
REDIS="${REDIS_CONTAINER:-match-forge-redis-1}"

ENDPOINTS=(/dashboard/queue /dashboard/matches /dashboard/stats /dashboard/anomalies)

psql_pg() { docker exec -i "$PG" psql -U eloarena -d eloarena "$@"; }

wait_healthy() {
  for _ in $(seq 1 60); do
    if curl -fs "$BASE/actuator/health" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "app did not become healthy" >&2; return 1
}

# Time SAMPLES sequential GETs of a path and print "p50 p99" in milliseconds.
measure() { # measure(path)
  local path="$1" times=()
  for _ in $(seq 1 "$SAMPLES"); do
    times+=("$(curl -s -o /dev/null -w '%{time_total}' "$BASE$path")")
  done
  printf '%s\n' "${times[@]}" | python3 -c "
import sys
xs = sorted(float(l)*1000 for l in sys.stdin if l.strip())
def pct(p): return xs[min(len(xs)-1, int(len(xs)*p))]
print(f'{pct(0.50):.1f} {pct(0.99):.1f}')
"
}

echo ">>> building a ${PLAYERS}-player working set at rest"
# Truncate everything but seasons; ensure exactly one active season for the match FK.
psql_pg -c "TRUNCATE TABLE anomalies, matches, queue_entries, rating_history, \
  season_leaderboard_snapshots, players RESTART IDENTITY CASCADE;" >/dev/null
psql_pg -c "INSERT INTO seasons (name) SELECT 'Benchmark Season' \
  WHERE NOT EXISTS (SELECT 1 FROM seasons WHERE ended_at IS NULL);" >/dev/null

# Players with a spread of ratings so divisions and bands vary across the panel.
psql_pg -c "INSERT INTO players (handle, rating, region) \
  SELECT 'P_' || g, 800 + (g % 1600), 'NA' FROM generate_series(1, ${PLAYERS}) g;" >/dev/null

# Every player parked WAITING, with enqueue times spread over the last 15 minutes so the
# server-computed band differs row to row (fresh joiners near base, long waiters near the cap).
psql_pg -c "INSERT INTO queue_entries (player_id, rating_at_join, enqueued_at, status) \
  SELECT id, rating, now() - (random() * interval '900 seconds'), 'WAITING' FROM players;" >/dev/null

# A large match history. player_a/player_b are distinct consecutive ids (satisfies chk_players_distinct);
# created_at defaults to now() so ORDER BY id DESC still returns newest-first for the feed.
SEASON_ID=$(psql_pg -tA -c "SELECT id FROM seasons WHERE ended_at IS NULL ORDER BY id LIMIT 1;")
psql_pg -c "INSERT INTO matches (season_id, player_a_id, player_b_id, rating_a, rating_b, rating_delta, wait_ms_a, wait_ms_b, status) \
  SELECT ${SEASON_ID}, ((g - 1) % ${PLAYERS}) + 1, (g % ${PLAYERS}) + 1, \
         1200 + (g % 400), 1200 + ((g + 17) % 400), abs(((g % 400)) - (((g + 17) % 400))), \
         (g % 5000), (g % 7000), 'COMPLETED' \
    FROM generate_series(1, ${MATCHES}) g;" >/dev/null

# Some detected double-matches so the anomalies feed and the stats count have real rows to read.
psql_pg -c "INSERT INTO anomalies (player_id, match_id, conflicting_match_id) \
  SELECT g, g, g + 1 FROM generate_series(1, ${ANOMALIES}) g;" >/dev/null

# The stats strip reads queue depth from Redis (the matcher normally sets it); set it here so the
# field is realistic. Matcher loop is off below, so it stays put.
docker exec "$REDIS" redis-cli SET eloarena:stats:queue_depth "$QUEUE_DEPTH" >/dev/null

echo ">>> starting app (matcher loop OFF so the data set stays fixed, no simulator)"
java -jar "$JAR" --eloarena.matcher.loop-enabled=false \
  > /tmp/eloarena-dashboard-bench.log 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null || true' EXIT
wait_healthy

# Warm up the JIT, the connection pool, and Postgres's cache so the measurement reflects steady state.
echo ">>> warming up"
for path in "${ENDPOINTS[@]}"; do
  for _ in $(seq 1 20); do curl -s -o /dev/null "$BASE$path"; done
done

echo ">>> measuring (${SAMPLES} samples per endpoint)"
{
  echo "# Dashboard endpoint latency"
  echo
  echo "Working set: ${PLAYERS} players, ${QUEUE_DEPTH} waiting in queue, ${MATCHES} matches, ${ANOMALIES} anomalies."
  echo "Measured at rest (no simulator, matcher loop off), ${SAMPLES} samples per endpoint."
  echo "Latency is full client-observed time_total. Budget: under 50ms."
  echo
  echo "| Endpoint | p50 (ms) | p99 (ms) | Budget |"
  echo "|----------|----------|----------|--------|"
} > "$OUT"

for path in "${ENDPOINTS[@]}"; do
  read -r P50 P99 <<< "$(measure "$path")"
  VERDICT=$(python3 -c "print('OK' if float('$P99') < 50 else 'OVER')")
  echo "| \`$path\` | $P50 | $P99 | $VERDICT |" >> "$OUT"
  echo "    $path -> p50 ${P50}ms p99 ${P99}ms ($VERDICT)"
done

echo
echo "Results written to $OUT:"
cat "$OUT"
