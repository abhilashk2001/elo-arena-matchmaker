#!/usr/bin/env bash
#
# Pairing-throughput scaling benchmark (Phase 7, story P7.6).
#
# This isolates the one thing the project's headline claim is about: how fast the locking matcher
# pairs a queue, and whether adding matcher instances adds throughput. The closed-loop simulator
# can't show this cleanly because its poll-and-play cycle caps the offered load well below matcher
# capacity, so the matcher is never the bottleneck. Here we remove the simulator entirely: we
# pre-fill a very deep queue of mutually-compatible waiting players directly in SQL, start N matcher
# instances, and measure pairs created per second while the queue stays deep. With the matcher
# saturated, the throughput delta between 1, 2, and 3 matchers is a clean read on how well
# FOR UPDATE SKIP LOCKED lets concurrent matchers partition the queue.
#
# Only the locking strategy is run here, on purpose. The naive matcher reads the *entire* WAITING
# set every tick (no LIMIT, by design), so a deep queue chokes it before it writes anything; the
# naive double-booking failure is measured under realistic load in scripts/scaling-benchmark.sh
# instead. All queued players share one rating, so every pair is in-band and the pairing algorithm
# never starves: this measures matcher/database throughput, not band-expansion dynamics.
#
# Usage:  scripts/queue-drain-benchmark.sh [jar] [window_seconds] [queue_depth]
# Requires: project Postgres (5433) + Redis (6379) up, JDK 21. No fixed port needed (matchers bind
# random ports; throughput is read straight from the shared Redis counter).

set -euo pipefail

JAR="${1:-target/elo-arena-matchmaker-0.0.1-SNAPSHOT.jar}"
WINDOW="${2:-20}"
QUEUE_DEPTH="${3:-300000}"
OUT="drain-results.md"
PG="${PG_CONTAINER:-match-forge-postgres-1}"
REDIS="${REDIS_CONTAINER:-match-forge-redis-1}"

# Matcher knobs: a big batch ticked fast so each instance pushes hard on the database, which is where
# the interesting contention between concurrent matchers lives.
BATCH_SIZE="${BATCH_SIZE:-200}"
INTERVAL_MS="${INTERVAL_MS:-100}"

# Each run is "matchers strategy". Locking only; see the header for why naive is run elsewhere.
SPECS=(
  "1 locking"
  "2 locking"
  "3 locking"
)

MATCHER_PIDS=()

psql_pg() { docker exec -i "$PG" psql -U eloarena -d eloarena "$@"; }

reset_state() {
  # Truncate everything except seasons (the matcher needs the active season for matches.season_id).
  psql_pg -c "TRUNCATE TABLE anomalies, matches, queue_entries, rating_history, \
    season_leaderboard_snapshots, players RESTART IDENTITY CASCADE;" >/dev/null
  docker exec "$REDIS" redis-cli FLUSHALL >/dev/null
  # Make sure exactly one season is active; seed one if a prior rollover left none.
  psql_pg -c "INSERT INTO seasons (name) SELECT 'Benchmark Season' \
    WHERE NOT EXISTS (SELECT 1 FROM seasons WHERE ended_at IS NULL);" >/dev/null
}

prefill_queue() { # prefill_queue(depth): depth players at rating 1200, each WAITING in the queue.
  local depth="$1"
  psql_pg -c "INSERT INTO players (handle, rating, region) \
    SELECT 'Q_' || g, 1200, 'NA' FROM generate_series(1, ${depth}) g;" >/dev/null
  psql_pg -c "INSERT INTO queue_entries (player_id, rating_at_join, status) \
    SELECT id, 1200, 'WAITING' FROM players;" >/dev/null
}

matches_counter() {
  local v; v=$(docker exec "$REDIS" redis-cli GET eloarena:stats:matches_created 2>/dev/null || echo 0)
  [ -z "$v" ] && v=0
  echo "$v"
}

waiting_count() { psql_pg -tA -c "SELECT count(*) FROM queue_entries WHERE status = 'WAITING';"; }
anomaly_count() { psql_pg -tA -c "SELECT count(*) FROM anomalies;"; }

stop_all() {
  for pid in "${MATCHER_PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done
  wait 2>/dev/null || true
  MATCHER_PIDS=()
}
trap stop_all EXIT

{
  echo "# Pairing-throughput scaling (queue drain)"
  echo
  echo "Queue pre-filled with ${QUEUE_DEPTH} mutually-compatible waiting players (all rating 1200)."
  echo "Matcher: batch-size ${BATCH_SIZE}, interval ${INTERVAL_MS}ms. Window ${WINDOW}s, measured while"
  echo "the queue stays deep so the matcher, not the offered load, is the bottleneck."
  echo
  echo "| Matchers | Strategy | Matches in window | Throughput (matches/s) | Speedup vs 1 | Anomalies | Queue left |"
  echo "|----------|----------|-------------------|------------------------|--------------|-----------|------------|"
} > "$OUT"

BASELINE=""

for spec in "${SPECS[@]}"; do
  read -r MATCHERS STRATEGY <<< "$spec"
  echo ">>> matchers=$MATCHERS strategy=$STRATEGY"
  reset_state
  prefill_queue "$QUEUE_DEPTH"

  for i in $(seq 1 "$MATCHERS"); do
    java -jar "$JAR" --spring.profiles.active=matcher-only --server.port=0 \
      --eloarena.matcher.strategy="$STRATEGY" \
      --eloarena.matcher.batch-size="$BATCH_SIZE" --eloarena.matcher.interval-ms="$INTERVAL_MS" \
      > "/tmp/eloarena-drain-matcher-$i.log" 2>&1 &
    MATCHER_PIDS+=($!)
  done
  sleep 14 # let every matcher instance finish booting and warm up before measuring

  C0=$(matches_counter)
  sleep "$WINDOW"
  C1=$(matches_counter)
  LEFT=$(waiting_count)
  ANOM=$(anomaly_count)

  DELTA=$((C1 - C0))
  THROUGHPUT=$(python3 -c "print(round($DELTA/$WINDOW, 1))")
  [ -z "$BASELINE" ] && BASELINE="$THROUGHPUT"
  SPEEDUP=$(python3 -c "print(round($THROUGHPUT/$BASELINE, 2)) if $BASELINE>0 else 'n/a'")

  # Sanity flag: if the queue emptied during the window the matcher was starved, not saturated,
  # so the number understates throughput. Note it rather than silently reporting a low value.
  NOTE=""
  [ "${LEFT:-0}" -eq 0 ] && NOTE=" (queue emptied: increase QUEUE_DEPTH)"
  echo "| $MATCHERS | $STRATEGY | $DELTA | $THROUGHPUT | ${SPEEDUP}x | $ANOM | ${LEFT}${NOTE} |" >> "$OUT"

  stop_all
  sleep 3
done

echo
echo "Results written to $OUT:"
cat "$OUT"
