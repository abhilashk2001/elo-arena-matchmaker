# Learning Notes

A running study guide built up while implementing Elo Arena Matchmaker. Each entry
explains a backend concept the way it shows up in this project: what problem it solves,
the naive approach, and why the chosen approach is better.

## Curriculum coverage

The 15 core concepts this project set out to teach, and where each one is explained and exercised
in code. Use this as the index into the per-phase notes below.

| # | Concept | Notes section | Code / test artifact |
|---|---------|---------------|----------------------|
| 1 | ACID transactions & boundaries | Phase 5 | `ResultService`, `ResultConcurrencyTest` |
| 2 | Pessimistic locking, `FOR UPDATE SKIP LOCKED` | Phase 4 | `LockingMatcher`, `LockingRaceConditionTest` |
| 3 | Race conditions & deterministic reproduction | Phase 3 | `RaceConditionTest` (CyclicBarrier), `RACE_REPRODUCTION.md` |
| 4 | Idempotency & idempotency keys | Phase 5 | `ResultService`, `ResultIdempotencyTest` |
| 5 | Read models / CQRS-lite | Phase 6 | `Leaderboard`, `LeaderboardService`, `LeaderboardRebuildService` |
| 6 | Redis data structures (sorted sets, TTLs, atomic ops) | Phase 6, Phase 8 | `Leaderboard` (ZSET), `RedisLiveStats` (sliding window + TTL) |
| 7 | Connection pooling & lifecycle | Phase 7 | HikariCP config in `application.yml`, `scripts/benchmark.sh` |
| 8 | Scheduled jobs & distributed scheduling pitfalls | Phase 2, Phase 4 | `MatcherLoop`, `StrategySelector` |
| 9 | Indexing & query planning (`EXPLAIN ANALYZE`) | Phase 4, Phase 8 | `V4__queue_waiting_enqueued_index.sql`, `ClaimQueryPlanTest` |
| 10 | Load testing, p50/p95/p99 | Phase 7 | `Simulator`, `loadtest/*.js`, `BENCHMARKS.md` |
| 11 | Observability: metrics, counters, gauges | Phase 7 | `MatchmakingMetrics`, `/actuator/prometheus` |
| 12 | API design: resources, status codes, validation | Phase 1, Phase 2 | `*Controller`, `GlobalExceptionHandler`, `*ControllerTest` |
| 13 | Data modeling: normalization, history tables | Phase 1, Phase 5 | `V1__init.sql`, `RatingHistory` |
| 14 | Docker & Docker Compose orchestration | Phase 9 | `docker-compose.yml`, `Dockerfile`, `dashboard/Dockerfile` |
| 15 | Normal distributions & seed-data shape | Phase 1 | `PlayerSeeder`, `PlayerSeederTest` |

---

## Phase 1: Skeleton and data layer

### Migrations vs ddl-auto

The naive way to get tables in a Spring Boot app is `spring.jpa.hibernate.ddl-auto=update`,
which lets Hibernate create and alter tables to match the entities. It is convenient and
wrong for anything real: the changes are implicit, unordered, not reviewable, and differ
between machines depending on entity state. We use Flyway instead. Every schema change is
a numbered SQL file (`V1__init.sql`, `V2__...`) that runs in order exactly once and is
recorded in `flyway_schema_history`. The schema becomes a versioned, reviewable artifact
that is identical on every machine and in CI. We set `ddl-auto: validate` so Hibernate
never changes the schema but does fail startup if an entity stops matching its table,
which catches drift between the migrations and the JPA mappings early.

### Partial unique indexes

We need to enforce "one active (WAITING) queue entry per player." The obvious
`UNIQUE (player_id, status)` is wrong: it would also forbid a player from having two
historical rows with the same status, for example two CANCELLED entries, blocking normal
history. The idiomatic Postgres tool is a partial unique index that applies the constraint
only to a subset of rows: `CREATE UNIQUE INDEX uq_player_waiting ON queue_entries (player_id)
WHERE status = 'WAITING'`. Uniqueness is enforced over just the WAITING rows; everything
else is unconstrained. We use the same trick to enforce a single active season with a
unique index on a constant key `WHERE ended_at IS NULL`, which avoids needing the
btree_gist extension that an exclusion constraint would require.

### Testcontainers, and why real infrastructure beats mocks here

A mocked repository returns whatever you tell it, so it can only prove that your code calls
the methods you think it calls. It cannot prove anything about database behaviour. This
project lives or dies on database behaviour that does not exist in a mock: row locking with
FOR UPDATE SKIP LOCKED, partial unique indexes actually rejecting duplicates, transaction
rollback, and real query plans. So integration tests run against real Postgres 16 and Redis
7 started by Testcontainers. The `IntegrationTest` base class boots the full context against
those containers, and because every test shares the same configuration, Spring caches one
context and one set of containers for the whole suite (the first test pays startup, the rest
run in milliseconds).

### Normal distribution seeding, and why seed shape matters

Player ratings are seeded from a normal distribution (mean 1200, stddev 300) clamped to
[400, 3000], not from a uniform spread. The shape is load-bearing for the whole project.
With a bell curve, mid-rated players sit in a dense neighbourhood and match instantly, while
rare tail players (say 2800) have almost no one near them and must wait for their acceptable
rating band to expand. That asymmetry is exactly the behaviour the matcher exists to handle
and the dashboard exists to show. Uniform seeding would flatten it and hide the most
interesting matchmaking behaviour. Seeding is deterministic for a given seed so a benchmark
can be reproduced exactly.

### Docker Compose and healthchecks

Compose describes the whole local stack (Postgres, Redis, the app) in one file so
`docker compose up` brings everything up together. The detail that matters is ordering: a
container being "started" does not mean the service inside is ready to accept connections.
Postgres and Redis declare healthchecks (`pg_isready`, `redis-cli ping`), and the app
declares `depends_on: condition: service_healthy`, so the app does not start until both
datastores actually pass their healthchecks. Without this the app can boot first, fail to
connect, and crash. Connection settings come from environment variables so the same image
runs locally and in Compose.

---

## Checkpoint questions: Phase 1

Answer these before moving to Phase 2.

1. Why Flyway over `ddl-auto`? Give at least two concrete problems `ddl-auto=update` causes.
2. What does the partial index on `queue_entries` enforce that a plain `UNIQUE (player_id, status)` cannot, and why does the plain version actively break normal use?
3. Why does the shape of the seed distribution change matchmaking behaviour? What would a uniform distribution hide?

---

## Phase 2: Queue and the naive matcher

### Band expansion

A waiting player accepts opponents within a rating half-width called the band. It starts at
a base (50) and grows by a fixed amount per second of waiting (5), capped at a maximum (400).
This is why a rare 2800-rated player eventually matches: nobody is within 50 points of them,
but after a minute their band is wide enough to reach the nearest opponent. Compatibility is
symmetric: both players must be inside each other's band. Because they may have waited
different amounts, their bands differ, so a patient player with a wide band can still be
rejected by a fresh opponent with a narrow one.

### Greedy pairing

Within a set of candidates we pair greedily: walk them oldest-first and, for each unpaired
player, take the compatible partner with the smallest rating gap (closest skill). It is
O(n^2) within the set, which is fine because the set handed to the algorithm is a bounded
batch. We deliberately do not optimise it early; clarity matters more than shaving an
already-cheap loop.

### Scheduled jobs and the distributed-scheduling preview

The matcher runs on a fixed-delay schedule (@Scheduled). The important idea, which becomes
the centre of the project in Phase 4, is what happens with more than one instance: every
instance has its own timer, so N instances means N concurrent executions each tick. For most
jobs (think "email every customer") that is a duplicate-work bug, usually fixed with leader
election so only one instance runs. Here we will instead make concurrent execution safe and
beneficial with FOR UPDATE SKIP LOCKED, so the matchers partition the queue and add
throughput. For now there is one matcher, gated by config so it is off in api-only and during
tests.

### The leave-versus-match race and the conditional update

Leaving the queue looks trivial until you remember the matcher is running at the same time.
The naive approach, "read the entry, see it is WAITING, then cancel it," has a gap: the
matcher can flip the entry to MATCHED between the read and the write, and you would cancel a
player who is already in a match. The fix is to make the check and the write one atomic
statement: UPDATE queue_entries SET status='CANCELLED' WHERE player_id=? AND status='WAITING'.
If the matcher already claimed them, the WHERE matches zero rows and nothing is cancelled.
There is no read-then-write window to race into. The same idea (let the database check and
act in one statement) shows up again in the join path, where we attempt the insert and let
the uq_player_waiting index reject a duplicate, rather than checking "is this player already
waiting?" and then inserting.

### API validation and a single error shape

Request validation lives at the edge with Bean Validation (@Valid on bodies, @Min on params),
and a single @RestControllerAdvice turns every handled exception into one shape:
{ error, message, timestamp }. Centralising this keeps controllers free of error-mapping
noise and guarantees clients always see the same structure, which matters once the dashboard
starts consuming these endpoints.

### Per-pair transaction (for the naive matcher)

The naive matcher writes each pair in its own transaction. Per-pair boundaries keep the lock
footprint small and let partial progress survive: if the fifth pair fails, the four already
committed stand. (The locking strategy in Phase 4 will deliberately choose the opposite,
one transaction per claimed batch, because by then the batch is already exclusively locked.)

---

## Checkpoint questions: Phase 2

Answer these before moving to Phase 3.

1. Walk through the naive matcher running on two instances against the same queue. Step by step, where exactly does it break, and what bad outcome results?
2. What does the conditional UPDATE on leave protect against, and what would go wrong with a plain read-then-write?
3. Why does the naive matcher write each pair in its own transaction rather than all pairs in one? Give one benefit.

---

## Phase 3: Break it on purpose

### Reproducing a race deterministically

Race conditions are timing-dependent, so they often do not appear when you go looking, which
is why "it did not fail on my machine" means nothing. To make the double-match reliable we use
a CyclicBarrier as a synchronized starting gate: both matcher threads do their setup, wait at
the gate, and are released at the same instant, like a sprint start. This maximises the chance
they read the same WAITING snapshot and collide, turning a flaky one-in-many bug into one that
reproduces on essentially every run. With 100 mutually-compatible players and two matchers, the
result is stable: 100 matches instead of 50, every player double-matched, around 103 anomalies.

### Detection versus enforcement

The anomaly detector is allowed to be slightly racy, but the matcher is not. They have different
jobs. The matcher enforces a hard guarantee (never double-match) and must be exact. The detector
only measures (count the double-matches that happened) and just needs to be good enough to tell
the story: the counter climbs in naive mode and sits at zero in locking mode. A detector that
occasionally miscounts by one still makes the bug and the fix obvious. A smoke detector that is
off by a degree still tells you the house is on fire. So we accept that the detector reads then
writes (itself a small race) because being approximately right is fine for measurement.

### READ COMMITTED and when a concurrent write becomes visible

The detector runs after a match commits, in its own read. Under Postgres' default READ COMMITTED
isolation, a transaction only sees rows other transactions have committed. So a matcher that
checks for a conflict inside its own still-open transaction would not see the other matcher's
uncommitted match. Running detection after commit lets it see the committed conflict, which is
why the second matcher to commit is the one that records the anomaly.

---

## Checkpoint questions: Phase 3

Answer these before moving to Phase 4.

1. Why do we use a CyclicBarrier in the race test instead of just starting two threads and hoping they collide?
2. Could the anomaly detector itself miss (or double-count) anomalies? Why is that acceptable for a detector but would not be acceptable for the matcher?
3. Under READ COMMITTED, why does running detection after the match commits catch the double-match, while checking inside the creating transaction would not?

---

## Phase 4: Fix it

### FOR UPDATE and SKIP LOCKED

`SELECT ... FOR UPDATE` takes a row lock on each selected row and holds it until the
transaction ends; a second transaction that selects the same rows FOR UPDATE would block,
waiting. Adding `SKIP LOCKED` changes that: instead of waiting, the second matcher skips any
row already locked and takes the next unlocked rows. So two matchers running at the same
instant claim disjoint batches and work in parallel. The fix does not serialise the matchers,
it partitions the queue between them, so adding matchers adds throughput. This is the single
most important idea in the project.

### The distributed-scheduling inversion

Running `@Scheduled` on N instances means N concurrent executions each tick. The usual reaction
is "that is duplicate work, add leader election so only one runs." We do the opposite on
purpose: the work (queue rows) is partitionable, and SKIP LOCKED partitions it automatically,
so concurrent execution is correct and additive instead of duplicated. We did not need leader
election because we did not need to suppress concurrency; we made it safe.

### Batch as the transaction boundary

The locking matcher runs the whole tick in one transaction: claim the batch, pair in Java,
write all the matches, commit once. The claim's locks are held the whole time, so no other
matcher can touch the claimed rows mid-pairing. Claimed rows that are not paired are simply
left WAITING and their locks drop at commit, so the next tick can claim them again. (The naive
matcher used a transaction per pair; once the batch is exclusively locked, one transaction per
batch is both safe and simpler, which is why the two strategies make opposite choices.)

### Correctness versus liveness

Because each matcher only claims a bounded batch, a compatible pair can be split across two
matchers' batches, so neither pairs them this tick. That is a liveness cost (they wait one
extra tick), not a correctness bug (nobody is ever double-matched). They almost certainly land
in the same batch next tick. Trading a one-tick delay for safe parallelism is a good deal, and
naming that tradeoff (correctness we never compromise, liveness we relax slightly) is a strong
talking point.

### Reading a query plan

The claim filters `status='WAITING'` and orders by `enqueued_at`. We added a partial index on
`enqueued_at WHERE status='WAITING'` so the claim can walk rows in wait-time order and stop
after the first N. `EXPLAIN ANALYZE` at 50,000 waiting rows confirms it (no sort, no seq scan):

```
Limit  (cost=0.29..337.50 rows=100 ...) (actual rows=100 loops=1)
  ->  LockRows  (cost=0.29..597.16 ...) (actual rows=100 loops=1)
        ->  Index Scan using idx_queue_waiting_enqueued on queue_entries  (actual rows=100 ...)
              Filter: (status = 'WAITING')
Execution Time: ~0.15 ms
```

`LockRows` is the FOR UPDATE SKIP LOCKED step; the Index Scan over the partial index is what
keeps the claim fast as the queue grows.

---

## Checkpoint questions: Phase 4

Answer these before moving to Phase 5.

1. What happens to claimed-but-unpaired rows when the locking matcher's transaction commits?
2. Why can a compatible pair be split across two matchers' batches, and why is that acceptable?
3. What would FOR UPDATE *without* SKIP LOCKED do to throughput when two matchers run at once?

---

## Phase 5: Results, Elo, and idempotency

### Transaction boundaries

Processing a result does several writes (two player ratings, two history rows, the match
status) that must all happen or none. They run in one transaction, so a crash anywhere rolls
the whole thing back and ratings can never disagree with match status. The match row is loaded
FOR UPDATE at the start, so two submissions for the same match serialize: the second waits for
the first to commit and then sees the match already COMPLETED.

### Idempotency, in two layers

Clients retry, so the same result can arrive twice. Layer 1 (application): if the match is
already COMPLETED, return 200 with the existing result rather than an error, because the caller
is usually an honest retry that just did not hear the first response. Layer 2 (database): the
uq_rating_once_per_match constraint forbids a second rating row for the same player and match,
so even if two duplicates raced past the status check, the database rejects the second. The app
check handles the common case nicely; the constraint is the hard guarantee that survives a race.

### Why 200-with-existing beats 409

A 409 tells an honest retrying client "conflict, something is wrong," when in fact their result
was applied and everything is fine. Returning 200 with the existing outcome means the same call
always yields the same answer, which is exactly what idempotency promises and what makes clients
safe to retry.

### Post-commit side effects and the trust order

The Redis leaderboard update is published as an event and handled only AFTER the transaction
commits. If the transaction rolls back, the listener never runs, so Redis is never given a
rating the truth store did not record. The residual window (commit succeeds, process dies before
the ZADD) is accepted: Postgres is truth, Redis may briefly lag, and the rebuild op (Phase 6)
repairs it. The industrial version of this is a transactional outbox; the post-commit hook plus
rebuild is the right-sized choice here.

---

## Checkpoint questions: Phase 5

Answer these before moving to Phase 6.

1. Trace a crash between the player rating UPDATE and the match-status UPDATE. What state results, and why?
2. Why return 200 with the existing result for a replayed submission instead of 409?
3. Which fires first for a duplicate, the application status check or the uq_rating_once_per_match constraint, and why do we want both?

---

## Phase 6: Leaderboard and seasons

### CQRS-lite: a read model that is not the source of truth

The leaderboard is a separate read-optimised copy of data that already lives in Postgres. Asking
Postgres "rank everyone by rating and give me the top 20" is an ORDER BY plus a scan that gets
more expensive as players grow, and a single player's rank is even worse. A Redis sorted set
keeps members ordered by score for us: top-N is `ZREVRANGE` (O(log N + M)) and a player's rank is
`ZREVRANK` (O(log N)), both cheap regardless of size. The cost is that we now hold the same fact
in two places, so the discipline is strict: Postgres is the source of truth, Redis is a
projection of it, writes always go to Postgres first and only reach Redis after commit, and we
never read a number from Redis we could not rebuild from Postgres.

### Handle hydration with a single IN query

The sorted set stores only player id and rating, not handles, because duplicating the handle into
Redis would be a second copy that can drift when a handle changes. So the top-N read returns ids,
and we hydrate them into display rows with one `findAllById` (a single `WHERE id IN (...)`) rather
than one lookup per row, which would be the classic N+1 query problem. One round trip fills in
every handle.

### A uniform contract for live and frozen leaderboards

The active season's leaderboard is served live from Redis; an ended season's is served from the
frozen `season_leaderboard_snapshots` rows in Postgres. The dashboard should not care which is
which, so both return the same shape (rank, player id, handle, rating). The active path reads
Redis then hydrates; the historical path reads snapshot rows that already carry the frozen rank.
Same response either way, so one piece of UI renders both.

### Rebuild: why treating Redis as a cache is actually safe

There is a window where a result commits to Postgres but the process dies before the post-commit
ZADD runs, so Redis is missing that rating; a flush would lose the whole board. The rebuild op is
the answer: one scan of players, ratings pipelined back into the sorted set, and the live board
matches the truth again. Pipelining sends all the ZADDs in one round trip instead of paying
network latency per player. The existence of a cheap, correct rebuild is what lets us treat Redis
as a disposable projection rather than something we must protect.

### Derived divisions: do not store what you can compute

A division is just a band of rating (ten bands, 200 points each, Division 1 on top). We compute it
from the rating on read instead of storing a division column. A stored copy would be a second
value that has to be kept in step with the rating every time the rating changes, and the day they
disagree you have a bug and no clear truth. Since the division is a pure function of the rating,
computing it on demand means the two can never disagree. This is the same "single source of truth"
instinct that governs the Redis projection, applied to a derived field.

### Season rollover in one transaction

Ending a season does four database things: freeze the final standings into snapshot rows, close
the season (stamp `ended_at`), soft-reset every rating, and open the next season. These must be
all-or-nothing. If a crash froze the standings but never opened a new season, the system would
have zero active seasons and the matcher could not stamp matches; if it reset ratings but never
froze the standings, the old season's results would be lost. So all four run in one transaction:
either the whole rollover happened or none of it did. The ranks in the snapshot are computed in a
single SQL statement with `ROW_NUMBER() OVER (ORDER BY rating DESC, id ASC)`, so every rank is a
consistent view of the same instant, with ties broken deterministically by id.

### Soft reset, and why a partial unique index makes "close then open" safe

The reset is eFootball-style, not a wipe: every player drops one division (-200) clamped at the
400 floor (`GREATEST(rating - 200, 400)`), so skill ordering is broadly preserved and nobody is
flung back to 1200. Opening the next season relies on the `uq_one_active_season` partial unique
index from Phase 1, which forbids two rows with `ended_at IS NULL`. Because we stamp the old
season's `ended_at` before inserting the new active row, the index is satisfied at every step; if
we tried to insert the new season first, the index would reject it. The constraint enforces the
"exactly one active season" invariant for us rather than trusting application order.

### Resetting Redis only after the database commits

The same trust order from Phase 5 applies, harder. The live Redis board is reset (old key dropped,
new season's board rebuilt from the reset ratings) only in an `afterCommit` hook, never inside the
rollover transaction. If we reset Redis inside the transaction and it then rolled back, Redis would
be advertising a rollover that never happened in the truth store, and there would be no event to
undo it. Doing it after commit means Redis can only ever reflect a rollover that actually
succeeded, and even if the process died in the gap, the rebuild op reconstructs the new board from
Postgres. Postgres leads, Redis follows.

---

## Checkpoint questions: Phase 6

1. Why keep the leaderboard in Redis at all when Postgres already has every rating? What specifically is cheaper, and what is the cost of the duplication?
2. Why is the division derived from rating on read rather than stored in its own column?
3. The rollover does four writes. Why must they share one transaction, and give one concrete bad state that a partial rollover would leave behind.
4. Why is the Redis reset done in an afterCommit hook instead of inside the rollover transaction?

---

## Phase 7: Load, metrics, and benchmarks

### Closed-loop load, and why the simulator lives in the app

The load generator is an in-app simulator driven by virtual threads, not an external script hammering
the HTTP API. Each virtual player runs a loop: join the queue, wait to be matched, "play" for a short
randomised duration, submit a result, then with some probability requeue. That closed loop matters
because it models the real shape of the system, where a finite population of players cycles through
the queue rather than an infinite firehose of independent requests. Virtual threads make tens of
thousands of these loops cheap: each one is mostly parked waiting (in queue, in a match), so the cost
is a heap object, not an OS thread. A thread-per-player pool would have fallen over long before 50k.
The simulator lives inside the app so it can talk to the services directly and so the whole thing is
one reproducible artifact you start with an admin endpoint, instead of a separate load rig you have
to stand up and keep in sync.

### Average hides, percentiles tell the truth

The single most useful idea in this phase. Under load the average latency stays calm while a real and
growing fraction of requests are slow, because the average is dominated by the many fast requests and
the slow tail barely moves it. If 95 requests take 5ms and 5 take 2000ms, the average is about 105ms,
which looks fine and describes none of the actual experiences. The percentile asks a different
question: sort every request by latency and read the value at the 99th position out of 100. p99 = 2000ms
here, and that is what one in a hundred of your users actually felt. We publish p50, p95, and p99 on
the result-processing timer precisely so the tail is visible. The average is the number that lets a
starved system look healthy; the percentile is the number that catches it.

### Coordinated omission, the measurement bug that flatters you

A subtle trap specific to closed-loop load. When a request is slow, the loop that issued it is blocked
waiting for it, so it does not issue the requests it *would* have issued during that stall. The slow
period therefore produces fewer samples, not more, so the very latencies you most want to see are
under-counted and your percentiles come out optimistic. The system stalled but the histogram barely
noticed, because the stall suppressed its own measurements. The honest mitigations are to keep the
offered load independent of response time where possible, to measure from the would-have-started time
rather than the actually-sent time, and at minimum to be explicit that a closed-loop number is a
floor on the real tail, not the ceiling. Naming the bias is the point: a benchmark that does not
mention coordinated omission is probably reporting numbers that are too good.

### Pool sizing: the connection pool is a queue, and small is sometimes faster

HikariCP's default pool is 10 connections. Under the queue storm that default is the bottleneck, and
the way it shows up is the lesson: the average request latency stays low while p99 spikes, because
requests are not slow doing work, they are fast once they have a connection and slow *waiting in line
to get one*. The probe that localises this is `hikaricp.connections.acquire`, the time a thread spends
blocked waiting for a free connection. When acquire-wait is high and actual query time is low, the
pool is the wall. The fix is not "make the pool huge". A pool larger than the database can usefully
serve just moves the queue from your app into Postgres and adds context-switching, so there is a sweet
spot. We measured it by sweeping pool sizes and watching acquire-wait fall and then flatten; the knee
is where you stop. The counterintuitive part is that a right-sized small pool can beat a large one,
because bounded concurrency keeps each query fast instead of letting everything contend at once.

### Scaling curves, and why N matchers do not give N times the throughput

The headline claim of the project is that FOR UPDATE SKIP LOCKED lets concurrent matchers partition
the queue and add real throughput instead of fighting over the same rows. The scaling benchmark tests
exactly that by running 1, 2, and 3 matcher instances against one shared queue and plotting pairing
throughput. The honest expectation is sublinear scaling, not perfect. Going from 1 to N never
multiplies throughput by N, because the matchers still share one Postgres: the same row locks, the
same connection pool, the same disk. This is Amdahl's law in the flesh, the serial fraction (the
shared database) caps the speedup no matter how many matchers you add. We measured roughly 1.0x, 1.6x,
2.0x for one, two, three matchers: the curve climbs, just not linearly. What SKIP LOCKED buys is that
the curve goes *up* at all rather than flat or down. The naive matcher's curve is the cautionary
contrast: add matchers and throughput does not climb, and worse, the anomaly count does, because they
are pairing the same players twice. A good scaling story is therefore two numbers per point, not one:
throughput climbed, and anomalies stayed at zero.

The methodology trap worth remembering: **you can only measure a component's scaling when that
component is the bottleneck.** The first attempt ran the scaling test through the closed-loop
simulator, and the curve was flat and noisy, because the simulator's poll-play-submit cycle offers
only a few dozen matches per second, far below what even one matcher can pair. The matchers were
sitting idle, so adding more changed nothing; the benchmark was measuring the *load generator*, not
the matcher. The fix was to take the simulator out of the loop and pre-fill the queue with a deep
backlog of waiting players, so every matcher is saturated and the only variable left is how well they
partition the work. If your scaling curve is flat, before concluding "it does not scale", check that
the thing you are scaling is actually the bottleneck and not starved for input.

---

## Checkpoint questions: Phase 7

1. Under the queue storm the average result latency stayed flat while p99 spiked. What was actually slow, and why did the average fail to show it?
2. Which single metric localised the connection-pool bottleneck, and how do you read it to tell "waiting for a connection" apart from "slow query"?
3. Why is a bigger connection pool not automatically better? What did sweeping pool sizes reveal about the sweet spot?
4. Did three matchers triple pairing throughput? Give the measured factor and explain the gap.
5. What is coordinated omission, and why does a closed-loop simulator make a stalled system look healthier than it is?

---

## Phase 8: Dashboard

### Poll-friendly read endpoints, and the budget that keeps them honest

The dashboard is a set of small panels that each re-fetch on a timer: the stats strip every second,
the queue, match feed, and leaderboard every two. Multiply that by every open tab and these endpoints
are some of the most frequently hit in the system, so each one carries an explicit budget: under 50ms
at 10k players. A budget you do not measure is a wish, so the budget shapes the implementation rather
than decorating it. Three things follow from it. First, every read is bounded: the queue and match
feed are top-N queries (LIMIT 25 and 30), never "select all waiting" or "all matches", so latency does
not grow with the queue depth or the size of the matches table. Second, the reads run against indexes,
not scans: the queue read leans on the partial index on (enqueued_at) WHERE status='WAITING' so it
walks waiting rows in wait order and stops after N, and the match feed reads the primary-key index
backwards (ORDER BY id DESC LIMIT 30). Third, the cheapest source wins: the stats strip is assembled
from Redis counters and in-memory Micrometer snapshots and touches Postgres for exactly one count, so
the most-polled endpoint is also the lightest. The leaderboard top-20 comes from the Redis sorted set,
not a Postgres ORDER BY, for the same reason. The budget is verified, not assumed: an integration test
asserts the median read stays under 50ms on representative data, and a load script
(`scripts/dashboard-latency-benchmark.sh`) measures p50/p99 against a live 10k-player system.

### Compute display values on the server so the rule lives in one place

The queue panel draws a bar for each player's current rating band, the window of opponents they will
accept, which widens the longer they wait. The tempting move is to send the raw enqueue time and the
band parameters to the browser and let the UI compute the band. That would be a second copy of the
band-expansion formula, and the day someone tweaks the expansion rate on the server, the bars would
quietly lie until the frontend was changed to match. So `currentBand` is computed on the server, by the
exact same `BandPolicy` the matcher pairs on, and the UI renders the number as-is. The formula has one
home, and the bar a player sees is provably the window the matcher is using for them. The general
principle: any value that encodes a business rule (a band, a tier, a score, an eligibility flag) should
be computed where the rule lives and shipped ready to render, not recomputed in the client. The UI
stays a thin renderer, which is also what keeps the meaningful tests at the API seam instead of in
React.

### Cheap real-time without websockets

"Real-time dashboard" sounds like it needs websockets or server-sent events, a push channel, a
connection to manage, reconnection logic, backpressure. For this view none of that is warranted, and
recognising when the simpler tool is enough is the lesson. The data is a snapshot, the queue right now,
the latest matches, the current standings. A dropped or slightly late frame changes nothing, there is
no event you must not miss, just the most recent state. So the whole "real-time" mechanism is a timer
per panel that re-fetches and replaces. Two small touches make polling feel live between fetches
without lying about the data: a shared one-second clock advances the wait timers locally (cosmetic, not
a decision), and the band bar animates toward each new server value with a CSS width transition instead
of recomputing the band. The rank-change highlight is the same idea applied to standings, diff the new
top-20 against the previous snapshot and flash the rows that moved, no animation library, just compare
two arrays. Push has real uses (chat, collaborative editing, anything where missing an event is a bug),
but reaching for it here would be cost without benefit. Polling cheap, bounded endpoints is the right
size of solution, which is why the endpoints were built cheap and bounded in the first place.

---

## Checkpoint questions: Phase 8

1. The dashboard polls a handful of endpoints every one to two seconds from every open tab. What is the per-endpoint latency budget, what three implementation choices keep each read inside it, and how is the budget verified rather than assumed?
2. Why is the rating band (`currentBand`) computed on the server and sent ready to render, instead of computing it in the browser from the raw enqueue time? What bug does that prevent?
3. This is a "real-time" dashboard with no websockets. Why is polling the right size of solution here, and when would it not be?

---

## Phase 9: Polish and publish

### Docker Compose orchestration: one command on a clean machine

The acceptance bar for the whole project is `git clone && docker compose up` producing a seeded,
working system on a machine with nothing but Docker installed. Hitting that bar is a lesson in a few
Compose mechanics. The first is ordering: the app must not start until Postgres and Redis are
actually accepting connections, which is not the same as their containers having started. Compose's
`depends_on` alone only waits for the container to start, so it is paired with a `healthcheck` on
each datastore (`pg_isready` for Postgres, `redis-cli ping` for Redis) and `depends_on:
condition: service_healthy`, which waits for the check to pass. The second is the image build: both
the app and the dashboard build inside Docker (a multi-stage Dockerfile each: Maven build then a
slim JRE for the app; Node build then nginx for the dashboard), so the clone has no host toolchain
requirement at all. The third is first-run usability: an empty database is technically working but
useless to a reader, so an env flag (`ELOARENA_SEED_ON_BOOT_ENABLED`) triggers a one-time seed of
1,000 players on boot, made idempotent by seeding only when the players table is empty so a restart
against the persistent volume does not duplicate. The fourth is the network seam: the dashboard is
served by nginx, which proxies the API paths to the app using the service name `app` as the
hostname, because Compose gives every service DNS on a shared network. The browser then makes
same-origin calls, which sidesteps CORS entirely, the same arrangement the Vite dev proxy provides
in development.

### Compose profiles: optional services without a second file

The multi-matcher demo needs extra matcher-only instances, but a default `docker compose up` should
stay lean (one API+matcher, the datastores, the dashboard). Compose profiles solve this without
maintaining a separate override file: the `matcher` service is tagged with a `matchers` profile, so
it is inert on a normal `up` and only starts with `docker compose --profile matchers up --scale
matcher=3`. That is the switch that turns the single-matcher default into the concurrent-matcher
configuration the race condition needs, and it keeps the "interesting" multi-instance setup one
explicit flag away rather than always-on.

### Traceability: every claim maps to an artifact

The closing discipline of a portfolio project is that nothing in the README is asserted without
something in the repo that backs it. Each performance claim points at the script that produced it
and the committed results file (the 840-vs-0 anomaly numbers come from `scaling-results.md` via
`scripts/scaling-benchmark.sh`; the 1.98x scaling from `drain-results.md`; the sub-50ms dashboard
reads from `dashboard-latency-results.md`). Each correctness claim points at a named test (the race
at `RaceConditionTest` and `LockingRaceConditionTest`, idempotency at `ResultIdempotencyTest`, the
query plan at `ClaimQueryPlanTest`). The curriculum coverage table at the top of this file is the
same idea applied to the learning goals: fifteen concepts, each mapped to where it is explained and
the code that exercises it. The point of the pass is adversarial: read each sentence and ask "what
proves this?", and if nothing does, either cut the sentence or write the test. A claim without an
artifact is a liability in an interview; a claim with one is a credential.
