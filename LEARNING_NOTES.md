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
