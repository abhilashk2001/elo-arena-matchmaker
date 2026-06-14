# Elo Arena Matchmaker

A concurrent matchmaking backend that pairs ranked players at scale without ever double-booking one, and proves it. The headline is a race condition: run several matcher instances against one queue the naive way and the same player gets pulled into two matches at once. The fix is one SQL clause, and the dashboard lets you watch the bug appear and disappear live.

**[Live demo →](https://abhilashk2001.github.io/elo-arena-matchmaker/)** — flip the LOCKING/NAIVE toggle and watch the race appear and vanish.

![Dashboard demo: flipping the matcher from LOCKING to NAIVE under load makes red double-match rows appear, then flipping back stops them](docs/demo.gif)

> The live demo replays two real captured runs of the system (a clean LOCKING run and a NAIVE run), so it is always on and costs nothing to host. It is a recording of the real thing, not synthetic data: the system that produced it is this repo, one `docker compose up` away, with the benchmark suite that produced the numbers below. Flip the toggle and red anomaly rows appear within seconds while the counter climbs; flip back to LOCKING and it returns to zero.

## What this is

A Spring Boot service that runs an Elo ranking system, a queue, and a batch matcher that pairs nearby-rated players, plus a load simulator and a React ops dashboard. Postgres is the source of truth and Redis is a live read model for the leaderboard and stats. It is a depth-first portfolio project about one thing: correctness under concurrency at scale, measured rather than asserted.

## Architecture

```
   simulator / k6 / curl          React dashboard (nginx)
            │                        │  polls /dashboard/* every 1-2s
            ▼                        ▼
   ┌───────────────────────────────────────────────┐
   │           Spring Boot app (modular monolith)   │
   │   REST API   ·   matcher loop   ·   Elo + Redis│
   └───────────────────────────────────────────────┘
        │  writes (source of truth)     │  live read model
        ▼                               ▼
   ┌──────────────┐               ┌──────────────┐
   │  PostgreSQL  │               │    Redis     │
   │ players,     │               │ leaderboard  │
   │ queue,       │               │ sorted set,  │
   │ matches,     │               │ live stats   │
   │ rating_history               └──────────────┘
   └──────────────┘
```

One process holds the API and the matcher (a modular monolith, packaged by feature). Extra matcher-only instances can be scaled up to demonstrate concurrent matchers contending for the same queue.

## The interesting problem: double-matching under concurrent matchers

To pair players you read a batch of waiting entries, find good pairs, and write the matches. With one matcher that is fine. The moment you run more than one matcher against the same queue (which you want, for throughput), the naive version breaks.

**The bug.** Matcher A runs `SELECT ... WHERE status = 'WAITING'` and reads players 42 and 43. Before it commits, matcher B runs the same query and reads the same rows, because nothing stopped it. Both decide to pair 42 with 43. Now player 42 is in two `IN_PROGRESS` matches at once: a double-match. This is a classic read-then-write race, and under real concurrent load it happens on its own, no test harness required.

**The failing numbers.** Run three matchers on the naive strategy against 10,000 players and the anomaly detector records the damage:

| Strategy | Matchers | Players | Throughput | Anomalies |
|----------|----------|---------|------------|-----------|
| naive    | 3        | 10,000  | 3.9 /s     | **840**   |
| locking  | 3        | 10,000  | 30.1 /s    | **0**     |

The naive matcher is not just wrong, it is also slower, because the matchers fight over the same rows and redo each other's work.

**The fix: `FOR UPDATE SKIP LOCKED`.** The claim query becomes:

```sql
SELECT id, player_id, rating_at_join, enqueued_at
  FROM queue_entries
 WHERE status = 'WAITING'
 ORDER BY enqueued_at
 LIMIT ?
FOR UPDATE SKIP LOCKED
```

`FOR UPDATE` locks the rows this matcher reads. `SKIP LOCKED` tells every other matcher to step over rows that are already locked instead of blocking on them. The whole tick is one transaction: claim a batch, pair it, write the matches, commit, and the locks drop.

**The partitioning insight.** The win is not that matchers wait their turn. It is that they never look at the same rows in the first place. `SKIP LOCKED` turns one shared queue into disjoint batches, one per matcher, so they run in parallel without coordinating and without colliding. Correctness and concurrency stop being in tension.

**The after numbers.** With locking, anomalies are zero at every scale tested (10k and 50k players, up to three matchers), and throughput goes *up* with more matchers instead of down. Measured against a saturated queue, pairing throughput scales 1.0x → 1.64x → 1.98x for one, two, three matchers. The scaling is sublinear (Amdahl's law: the matchers still share one Postgres), but the curve climbs and anomalies stay at zero, which is the whole point. Correct and scaling beats wrong and not.

## Benchmarks

Real measured numbers from the scripts in `scripts/`. Full write-up in [BENCHMARKS.md](BENCHMARKS.md).

**Matcher scaling against a saturated queue** (`scripts/queue-drain-benchmark.sh`, locking, deep pre-filled queue):

| Matchers | Throughput (matches/s) | Speedup vs 1 | Anomalies |
|----------|------------------------|--------------|-----------|
| 1        | 250.0                  | 1.0x         | 0         |
| 2        | 410.0                  | 1.64x        | 0         |
| 3        | 495.0                  | 1.98x        | 0         |

**Naive vs locking under closed-loop load** (`scripts/scaling-benchmark.sh`, 10k players, 3 matchers):

| Strategy | Throughput (matches/s) | Anomalies |
|----------|------------------------|-----------|
| naive    | 3.9                    | 840       |
| locking  | 30.1                   | 0         |

**Connection-pool sizing** (`scripts/benchmark.sh`, 8k players, 60s): the default pool of 10 is the bottleneck, and it shows up as a tail-latency problem, not an average one. Acquire-wait falls as the pool grows (122 → 75 → 59 ms for pool 10/30/50) while result p99 eventually rises as contention relocates into Postgres. The measured sweet spot is ~30.

**Dashboard read latency** (`scripts/dashboard-latency-benchmark.sh`, 10k-player working set at rest): every polled endpoint is well under its 50ms budget (p99 of 7.8 / 17.3 / 10.8 / 6.0 ms for queue / matches / stats / anomalies).

## Design decisions

- **Dual read model (CQRS-lite).** Writes go to Postgres (the source of truth: players, matches, rating history). The leaderboard is read from a Redis sorted set, which answers a top-N query in O(log n) without an `ORDER BY` over the players table on every dashboard poll. Postgres is correct; Redis is fast; each does what it is good at.
- **Two-layer idempotency for result submission.** A duplicate or concurrent result for the same match must not apply the rating change twice. The first layer is an application check that returns the existing result; the backstop is a database uniqueness constraint, so even a true concurrent double-submit cannot write two results. The constraint is the thing that is actually trusted.
- **Post-commit Redis update, plus a rebuild button.** The leaderboard is updated in Redis only after the Postgres transaction commits, so a rolled-back match never leaves a phantom rating in the read model. Because a derived store can still drift (a missed update, a Redis flush), there is an admin endpoint that rebuilds the sorted set from Postgres, the source of truth. Postgres leads, Redis follows, and can always be reconstructed.
- **Derived divisions and a division-drop soft reset (deviation from the brief).** The original brief specified a halving soft reset (`new = 1200 + (old - 1200) / 2`). This implementation instead derives divisions from rating (10 divisions of 200 points, rating is the only stored value) and resets a season by dropping one division (subtract 200, clamped to a 400 floor). The reason: a more intuitive competitive narrative with the same order-preserving property, and deliberately *not* storing a division alongside the rating, because a second stored copy is exactly the kind of value that drifts out of sync, in a project whose whole theme is avoiding that.
- **Server-side display values.** Anything that encodes a rule (the rating band a waiting player accepts, their division) is computed on the server and sent ready to render, so the formula lives in one place and the dashboard cannot drift from the matcher that pairs on it.
- **Modular monolith, packaged by feature.** One deployable, packages by domain (`matchmaking`, `match`, `leaderboard`, `season`, `rating`). The interesting problems here are concurrency and data, not service boundaries, so microservices would add operational cost and distributed-systems failure modes without buying anything the project needs.
- **What was deliberately cut.** No auth, no tournaments, no message queue, no microservices, no real gameplay (matches are simulated). See "What I'd build next".

## Running it

Requires Docker. On a clean machine:

```
git clone https://github.com/abhilashk2001/elo-arena-matchmaker
cd elo-arena-matchmaker
docker compose up --build
```

That brings up Postgres, Redis, the app (API + matcher), and the dashboard, and seeds 1,000 players on first boot. Then:

- Dashboard: http://localhost:3000
- Swagger UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health

**Drive some load and watch it.** Either click "start sim" on the dashboard, or run the terminal demo:

```
./demo.sh
```

which seeds 1k players, starts the simulator, tails the live stats for 60 seconds, and prints the leaderboard.

**See the race.** With the simulator running, flip the dashboard strategy toggle to NAIVE. To make it collide hard, scale up extra matchers first:

```
docker compose --profile matchers up -d --scale matcher=3
```

**Load tests (k6).** Scenarios live in `loadtest/`:

```
k6 run loadtest/queue-storm.js        # connection-pool contention
k6 run loadtest/sustained.js          # steady-state throughput
STRATEGY=naive k6 run loadtest/race-reproduction.js   # anomalies > 0
```

## What I'd build next

The cuts were deliberate, to keep the project pointed at concurrency and data correctness. If this were to grow, in rough order:

- **Authentication and identity.** Players are seeded rows identified by id; real users need accounts and auth.
- **Tournaments and brackets** on top of the existing match primitive.
- **A message queue** (Kafka/RabbitMQ) to decouple result processing and fan out leaderboard updates, instead of the in-process post-commit hook.
- **Horizontal API scaling and a real deployment** (the matcher already scales; the API would need a load balancer and session-free request handling, which it is built for).
- **Anti-smurf / rating-integrity** work: smurf detection, rating-dependent K-factor, dispute handling.

Microservices and multi-region are explicitly *not* on this list until there is a load or team reason for them.

## Learning notes

This project was also a structured way to learn backend engineering. [LEARNING_NOTES.md](LEARNING_NOTES.md) is the running study guide: one section per phase, covering transactions, pessimistic locking, race reproduction, idempotency, CQRS, Redis data structures, connection pooling, indexing and query plans, load-testing methodology, observability, and more. [CHECKPOINT_ANSWERS.md](CHECKPOINT_ANSWERS.md) holds the worked answers to the per-phase comprehension questions.
