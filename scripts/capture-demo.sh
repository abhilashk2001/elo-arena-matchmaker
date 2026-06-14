#!/usr/bin/env bash
#
# Capture the dashboard's data as two replayable recordings: a clean LOCKING segment (anomalies
# stay at zero) and a NAIVE segment (the race, anomalies climb, double-match rows). The static
# replay build of the dashboard plays these back so the demo needs no live backend.
#
# It drives the real running stack exactly as a viewer would see it: reset to a clean slate, run the
# simulator, and poll the same endpoints the dashboard polls, once per second, recording each frame.
# The recordings are therefore real captured runs of the real system, not synthetic data.
#
# Usage:   scripts/capture-demo.sh [seconds_per_segment]
# Requires: the stack up (docker compose up), app on :8080. For a strong NAIVE signal, bring up
#           extra matchers first: docker compose --profile matchers up -d --scale matcher=3
set -euo pipefail

SECONDS_PER_SEGMENT="${1:-60}"
B="${BASE_URL:-http://localhost:8080}"
PG="${PG_CONTAINER:-match-forge-postgres-1}"
REDIS="${REDIS_CONTAINER:-match-forge-redis-1}"
OUT_DIR="dashboard/src/replay"
mkdir -p "$OUT_DIR"

reset_clean() {
  docker exec "$PG" psql -U eloarena -d eloarena -c \
    "TRUNCATE anomalies, matches, queue_entries, rating_history RESTART IDENTITY;" >/dev/null
  docker exec "$REDIS" redis-cli FLUSHALL >/dev/null
}

set_strategy() {
  curl -s -X PUT "$B/api/admin/matcher-strategy" -H 'Content-Type: application/json' \
    -d "{\"strategy\":\"$1\"}" >/dev/null
}

start_sim() {
  curl -s -X POST "$B/api/admin/simulate/start" -H 'Content-Type: application/json' \
    -d '{"players":1000,"requeueProbability":0.95,"matchDurationMinMs":800,"matchDurationMaxMs":2500}' >/dev/null
}

stop_sim() { curl -s -X POST "$B/api/admin/simulate/stop" >/dev/null; }

# Poll the five dashboard endpoints once per second and write {segment,intervalMs,frames:[...]}.
poll_to_file() { # poll_to_file(segment_name, seconds, outfile)
  BASE_URL="$B" SEGMENT="$1" SECONDS_TO_RUN="$2" OUTFILE="$3" python3 - <<'PY'
import json, os, time, urllib.request

base = os.environ["BASE_URL"]
segment = os.environ["SEGMENT"]
seconds = int(os.environ["SECONDS_TO_RUN"])
outfile = os.environ["OUTFILE"]

def get(path):
    try:
        with urllib.request.urlopen(base + path, timeout=5) as r:
            return json.load(r)
    except Exception:
        return None

frames = []
for t in range(seconds):
    start = time.time()
    frames.append({
        "t": t,
        "stats": get("/dashboard/stats"),
        "queue": get("/dashboard/queue"),
        "matches": get("/dashboard/matches"),
        "anomalies": get("/dashboard/anomalies"),
        "leaderboard": get("/api/leaderboard?limit=20"),
    })
    # Keep a steady ~1s cadence regardless of how long the fetches took.
    time.sleep(max(0, 1.0 - (time.time() - start)))

with open(outfile, "w") as f:
    json.dump({"segment": segment, "intervalMs": 1000, "frames": frames}, f)
print(f"  wrote {len(frames)} frames to {outfile}")
PY
}

capture_segment() { # capture_segment(name, strategy)
  local name="$1" strategy="$2"
  echo ">>> capturing $name segment (${SECONDS_PER_SEGMENT}s)"
  # Stop any sim already running, otherwise start_sim hits an "already running" instance (whose
  # players may have drained) and the segment records a dead, idle system.
  stop_sim
  sleep 2
  reset_clean
  set_strategy "$strategy"
  start_sim
  # Let load build so the first frames are not an empty cold start.
  sleep 5
  poll_to_file "$name" "$SECONDS_PER_SEGMENT" "$OUT_DIR/$name.json"
  stop_sim
  sleep 2
}

curl -fs "$B/actuator/health" >/dev/null || { echo "app not reachable at $B" >&2; exit 1; }

capture_segment locking locking
capture_segment naive naive

# Leave the system clean and back on locking after capturing.
reset_clean
set_strategy locking

echo
echo "Done. Recordings:"
ls -lh "$OUT_DIR"/*.json
echo "Peak anomalies per segment:"
for f in "$OUT_DIR"/locking.json "$OUT_DIR"/naive.json; do
  python3 -c "import json;d=json.load(open('$f'));m=max((fr['stats'] or {}).get('anomalyCount',0) for fr in d['frames']);print(f'  {d[\"segment\"]}: {m}')"
done
