# Load tests (k6)

External load tests that drive the real HTTP API and report honest percentiles. They complement
the in-app simulator (`POST /api/admin/simulate/start`): the simulator is convenient for a quick
local run, k6 gives you proper percentile reporting, arrival-rate control, and thresholds.

## Prerequisites

- [k6](https://k6.io/docs/get-started/installation/) installed
- The stack running (`docker compose up -d`), reachable at `http://localhost:8080` (override with
  `BASE_URL`)

## Scenarios

| Script                  | What it does                                                                 |
|-------------------------|------------------------------------------------------------------------------|
| `queue-storm.js`        | Sharp VU ramp to a sustained peak. Surfaces the connection-pool bottleneck: p99 spikes while the average stays calm. |
| `sustained.js`          | Constant arrival rate held for minutes. Steady-state throughput and queue wait. |
| `race-reproduction.js`  | Generates concurrent matching pressure and measures the anomalies counter for the selected strategy. |

## Running

```bash
# Queue storm (defaults: 10k players, peak 500 VUs)
k6 run loadtest/queue-storm.js

# Sustained load: 100 iterations/sec for 5 minutes
RATE=100 DURATION=5m k6 run loadtest/sustained.js

# Race reproduction — REQUIRES multiple matcher instances:
docker compose up -d --scale app=3
STRATEGY=naive   k6 run loadtest/race-reproduction.js   # expect anomalies > 0
STRATEGY=locking k6 run loadtest/race-reproduction.js   # expect anomalies == 0
```

### Why the race scenario needs `--scale app=3`

The double-match bug is a race between concurrent matchers. A single matcher instance, even under
heavy load, processes one tick at a time and will not double-match. Run three instances so three
matcher loops contend for the same `WAITING` rows: the naive strategy (plain `SELECT`) then lets
multiple matchers claim the same player and the anomaly counter climbs, while the locking strategy
(`FOR UPDATE SKIP LOCKED`) keeps it at zero. This is the load-test view of the same race the
`RaceConditionTest` proves deterministically with a `CyclicBarrier`.

## Reading the results

While a test runs, scrape the custom metrics:

```bash
curl -s localhost:8080/actuator/prometheus | grep -E 'eloarena_|hikaricp_connections_acquire'
```

`eloarena_queue_wait` and `hikaricp_connections_acquire_seconds` are the two to watch during the
storm: when the pool is the bottleneck, acquisition wait climbs and drags p99 with it.
