# Learning Notes

A running study guide built up while implementing Elo Arena Matchmaker. Each entry
explains a backend concept the way it shows up in this project: what problem it solves,
the naive approach, and why the chosen approach is better.

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
