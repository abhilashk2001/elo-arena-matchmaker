# Benchmarks

This is the evidence behind the project's headline claim: that switching the matcher from the naive
strategy to `FOR UPDATE SKIP LOCKED` makes concurrent matching both **correct** (zero double-bookings)
and **scalable** (throughput climbs as you add matcher instances), and that the connection pool, not
the matcher, is the throughput wall once correctness is settled.

All numbers here are produced by committed scripts against the project's own Postgres and Redis, so
they are reproducible rather than hand-waved.

## How the load is generated

Load is a closed-loop, in-app simulator driven by virtual threads (`SimulationService`). A finite
population of virtual players each runs the real lifecycle: join queue, wait for a match, "play" for
a randomised 20 to 60 ms, submit the result over the actual service path, then requeue with high
probability. This models a fixed roster cycling through the queue, which is the true shape of a
matchmaking system, rather than an open firehose of unrelated requests. Virtual threads make tens of
thousands of mostly-parked player loops cheap, which is how the 50k run is feasible on one machine.

Sections 1 and 2 use this closed-loop simulator. Section 3 deliberately does not: to make the matcher
itself the bottleneck it bypasses the simulator and pre-fills the queue directly, which is explained
where it is used.

**A measurement caveat we state up front: coordinated omission.** In a closed loop, a slow request
blocks the player that issued it, so the stall suppresses the very samples that would have recorded it.
Closed-loop percentiles are therefore a *floor* on the real tail, not the ceiling. The relative
comparisons below (default pool vs tuned, naive vs locking, 1 vs 3 matchers) are sound because every
run carries the same bias; treat the absolute tail latencies as optimistic.

## 1. Connection-pool bottleneck: observe, localise, tune

The matcher is deliberately over-provisioned for this experiment (large batch, frequent tick) so it is
*not* the bottleneck. The variable under test is HikariCP's `maximum-pool-size`. The probe is
`hikaricp.connections.acquire`, the time a thread spends blocked waiting for a free connection: when
that is high while query time stays low, the pool is the wall.

| Pool size | Acquire wait mean (ms) | Acquire wait max (ms) | Result p99 (ms) |
|-----------|------------------------|-----------------------|-----------------|
| 10 (default) | 121.84 | 1797 | 39.8 |
| 30 (tuned)   | 75.25  | 1963 | 129.9 |
| 50           | 58.66  | 2026 | 201.2 |

(Full columns, including matches created and queue-wait p99, are in `benchmark-results.md`.)

**Reading it.** At the HikariCP default of 10 connections, threads spend an average of **122 ms just
waiting to acquire a connection** before they can do any work. That is the textbook pool-too-small
symptom and it is exactly what `hikaricp.connections.acquire` is for: the time is lost in line, not in
the query. Raising the pool to 30 cuts that wait by ~40% (to 75 ms), and 50 trims it further (59 ms).
So far, bigger looks better.

But watch the result-processing p99 move the other way: **39.8 ms at pool 10, then 130 ms, then
201 ms.** This is the lesson that makes pool sizing interesting. A bigger pool does not make the work
faster, it just lets *more of it happen at once*, and the result path all converges on the same hot
rows (the two players' ratings and the match row). Past a point, relieving connection starvation
simply relocates the contention from "waiting for a connection" into "waiting for a row lock inside
Postgres". The pool is no longer the wall; the database is. That is why the answer is a *sweet spot*,
not "set it to a thousand": 30 is the knee here, enough to stop starving on connections without
flooding the database into lock contention, which is why the app defaults to it. (These runs share one
laptop with the load generator, so the absolute millisecond values are noisier than a dedicated rig
would give; the *direction* of each curve is the durable result.)

## 2. Correctness under realistic load: naive vs locking, 10k and 50k

One api-only instance runs the closed-loop simulator and keeps the queue fed; N matcher-only instances
do the pairing, all sharing one Postgres and Redis. Throughput is the shared Redis matches counter
summed across instances. "Anomalies" is the count of detected double-bookings (the same player in two
live matches), which is the correctness axis.

| Players | Matchers | Strategy | Matches in 30s | Throughput (matches/s) | Anomalies |
|---------|----------|----------|----------------|------------------------|-----------|
| 10,000  | 1        | locking  | 681            | 22.7                   | 0         |
| 10,000  | 2        | locking  | 1152           | 38.4                   | 0         |
| 10,000  | 3        | locking  | 903            | 30.1                   | 0         |
| 10,000  | 3        | naive    | 117            | 3.9                    | **840**   |
| 50,000  | 3        | locking  | 922            | 30.7                   | 0         |

**Reading it.**

**Correctness is the headline, and naive fails it.** The naive matcher with three instances produced
**840 anomalies**: double-bookings where the same player landed in two live matches because two
matchers read the same queue rows before either committed. The locking matcher produced **zero**
anomalies at every point, including 50k players. This is the whole reason the project exists:
`FOR UPDATE SKIP LOCKED` lets each matcher claim a disjoint slice of the queue, so concurrent matchers
never pair the same player twice. Naive is not even faster for its incorrectness, it managed 3.9
matches/s, far below a single locking matcher, because re-pairing the same players and tripping the
anomaly path costs more than the locking it skips.

**The throughput column here is supply-bound, not matcher-bound, on purpose.** Notice the locking
rows do not scale cleanly with matcher count (22.7, 38.4, 30.1), and 50k is no faster than 10k. That
is because in this closed loop the *offered load* is the bottleneck, not the matchers: a player only
returns to the queue after polling (200 ms cadence), playing, and submitting, so the queue is shallow
and each matcher is mostly idle. So this table is the right tool for the question it answers
(is it correct, and does it hold at 50k: yes and yes) and the wrong tool for "do matchers scale". For
that you have to make the matcher the bottleneck, which is section 3.

## 3. Pairing-throughput scaling, matcher saturated

To measure whether adding matchers actually adds pairing throughput, the matcher has to be the
bottleneck. The drain benchmark removes the simulator and instead pre-fills a very deep queue
(200,000 mutually-compatible waiting players, inserted straight into Postgres), then measures how fast
1, 2, and 3 matcher instances drain it while the queue stays deep. Only the locking strategy runs
here: the naive matcher reads the entire WAITING set every tick (no `LIMIT`, by design), so a deep
queue chokes it before it writes a single match, which is why its correctness failure is measured
under the gentler load of section 2 instead.

| Matchers | Strategy | Matches in 20s | Throughput (matches/s) | Speedup vs 1 | Anomalies |
|----------|----------|----------------|------------------------|--------------|-----------|
| 1        | locking  | 5000           | 250                    | 1.00x        | 0         |
| 2        | locking  | 8200           | 410                    | 1.64x        | 0         |
| 3        | locking  | 9900           | 495                    | 1.98x        | 0         |

**Reading it.** Now the matchers are saturated (the queue never emptied, 176k still waiting at the
end), so the throughput delta is a clean read on scaling. Adding matchers really does add throughput,
**1.98x at three instances**, and correctness holds: zero anomalies even fully saturated, because
`SKIP LOCKED` hands each matcher a disjoint batch. But the scaling is sublinear, and honestly so:
three matchers give roughly double, not triple. They partition the *queue claim* perfectly, yet they
still share one Postgres, the connection pool, the write-ahead log, CPU, and the single active-season
row every match insert reads. That shared serial fraction caps the speedup. This is Amdahl's law made
concrete: SKIP LOCKED buys you a curve that climbs instead of flattening or collapsing into
double-bookings (which is exactly what the naive matcher would do here), not a magic Nx multiplier.

## Reproducing

```bash
# build once
./mvnw -DskipTests clean package

# section 1, pool sweep: starts a fresh app per pool size
scripts/benchmark.sh target/elo-arena-matchmaker-0.0.1-SNAPSHOT.jar "10 30 50" 8000 60

# section 2, correctness under realistic closed-loop load (10k, 50k, naive vs locking)
scripts/scaling-benchmark.sh target/elo-arena-matchmaker-0.0.1-SNAPSHOT.jar 30

# section 3, pairing-throughput scaling against a saturated queue
scripts/queue-drain-benchmark.sh target/elo-arena-matchmaker-0.0.1-SNAPSHOT.jar 20 200000
```

Requires the project's Postgres (5433) and Redis (6379) up, port 8080 free for sections 1 and 2, and
`JAVA_HOME` on a JDK 21. Raw outputs are committed as `benchmark-results.md`, `scaling-results.md`,
and `drain-results.md`.

> Note on running these on a laptop: the closed-loop simulator opens a socket per in-flight request
> against its own JVM, so the scripts raise the file-descriptor limit (`ulimit -n`) and ramp the
> simulator's start to avoid a thundering-herd of joins. The 50k row still logs a few transient
> "too many open files" events on a 10,240-fd-per-process macOS default; they slightly understate its
> throughput but do not affect the anomaly count, which is what that row is there to show.
