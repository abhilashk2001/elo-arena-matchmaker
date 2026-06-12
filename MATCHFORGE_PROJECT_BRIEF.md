# MatchForge — Project Brief & Implementation Guide

> **Audience for this document:** Claude Code (implementation agent) and Abhilash (developer/learner).
> **How to use:** Place this file at the root of the repository. Claude Code should treat it as the source of truth for scope, architecture, and — critically — the teaching mandate below. When in doubt, prefer depth and correctness over feature count.

---

## 0. The Dual Mandate (READ FIRST)

This project has **two equally important goals**:

1. **Portfolio artifact** — a recruiter-shareable, depth-first backend project demonstrating concurrency correctness, transactional integrity, and read-model design at scale.
2. **Learning assessment** — Abhilash is using this project to deeply learn core backend engineering concepts. **The implementation process must teach, not just produce code.**

### Teaching protocol for Claude Code

When implementing this project, Claude Code MUST follow these rules:

- **Explain before implementing.** Before writing any non-trivial component (the matcher, the pairing query, the transaction boundaries, the Redis leaderboard, the idempotency mechanism), give a short explanation (5–15 sentences) of: what problem this solves, what the naive approach would be, why the chosen approach is better, and what would break without it.
- **Concept callouts.** Whenever a core backend concept appears for the first time, flag it explicitly, e.g.:
  > 📚 **CONCEPT: Pessimistic locking** — what it is, when to use it vs optimistic locking, what `FOR UPDATE SKIP LOCKED` adds.
  Maintain a running `LEARNING_NOTES.md` in the repo where each concept callout is appended with a 1-paragraph summary. By project end this file is Abhilash's personal backend study guide.
- **Checkpoint questions.** At the end of each milestone (see §12), pose 3–5 comprehension questions to Abhilash before moving on (e.g., "What happens if two matcher threads run the pairing query simultaneously *without* SKIP LOCKED? Walk through it row by row."). Wait for his answers; correct misunderstandings before proceeding.
- **Show the failure first where designated.** For the race condition (§6.3), implement the *broken* version first, demonstrate the failure with a test/simulation, then implement the fix. Seeing the bug is the lesson.
- **No magic.** Prefer explicit code over framework magic when the explicit version teaches more (e.g., write the Elo math by hand rather than pulling a library; write the pairing SQL by hand rather than relying on JPA derived queries).
- **Ask, don't assume.** If a design decision has meaningful alternatives, briefly present the tradeoff and let Abhilash choose (timebox: one short paragraph per option, max 2–3 options).

### Core concepts this project must teach (the curriculum)

| # | Concept | Where it appears |
|---|---------|------------------|
| 1 | ACID transactions & transaction boundaries | Match result + rating update (§6.4) |
| 2 | Pessimistic locking, `FOR UPDATE SKIP LOCKED`, lock contention | Matcher pairing query (§6.3) |
| 3 | Race conditions & how to reproduce them deterministically | Naive matcher (§6.3) |
| 4 | Idempotency & idempotency keys | Duplicate result submission (§6.4) |
| 5 | Read models / CQRS-lite (write store vs read store) | Redis leaderboard vs Postgres (§6.5) |
| 6 | Redis data structures (sorted sets, TTLs, atomic ops) | Leaderboard, queue metrics (§6.5) |
| 7 | Connection pooling & DB connection lifecycle | HikariCP config under load (§9) |
| 8 | Scheduled jobs & distributed scheduling pitfalls | Matcher loop, multiple instances (§6.2) |
| 9 | Database indexing & query planning (`EXPLAIN ANALYZE`) | Pairing query optimization (§6.3) |
| 10 | Load testing methodology, p50/p95/p99 latency | Simulator & k6 (§9) |
| 11 | Observability basics: metrics, counters, gauges | Actuator + custom metrics (§10) |
| 12 | API design: resource modeling, status codes, validation | All endpoints (§7) |
| 13 | Data modeling: normalization, audit/history tables | Schema (§5) |
| 14 | Docker & Docker Compose orchestration | Deployment (§11) |
| 15 | Normal distributions & why seed data shape matters | Player seeder (§6.1) |

---

## 1. Project Summary

**MatchForge** is a ranked-matchmaking engine: a Spring Boot modular monolith backed by PostgreSQL and Redis that pairs simulated players into matches using time-expanding rating bands, processes match results with atomic Elo updates, and serves a live leaderboard — designed and verified to behave correctly when **multiple matcher instances run concurrently against thousands of active players**.

**One-line pitch (README headline):**
> A matchmaking engine that stays correct under concurrency — multiple matchers, zero double-matches, atomic rating updates — proven with load tests, and visualized live in an ops dashboard.

**The single story this project tells:** *correctness under concurrency at scale.* Every component either serves that story or gets cut.

---

## 2. Goals & Non-Goals

### Goals
- Correct concurrent matchmaking: N matcher instances, zero duplicate pairings, with the fix benchmarked against the naive version.
- Atomic, idempotent match-result processing with full rating history.
- Dual read-model leaderboard: Redis sorted sets (live) + Postgres snapshots (seasonal history).
- A load simulator that drives 1k–50k synthetic players through the full loop.
- A live React ops dashboard that makes the system's behavior visible — including a "disable locking" toggle that makes the race condition appear on screen.
- One-command local run (`docker compose up`), optional cloud-hosted demo URL.
- A README whose centerpiece is the race-condition narrative with before/after numbers.

### Non-Goals (explicit cuts — say "out of scope" in README)
- ❌ Authentication / authorization (players are seeded rows, identified by ID)
- ❌ Tournaments, brackets
- ❌ Disputes / moderation / anti-smurf
- ❌ Notifications, email, WebSockets-as-product-feature
- ❌ Kafka / RabbitMQ / microservices / multi-region
- ❌ A playable game (matches are simulated; there is no gameplay)
- ❌ Grafana/Prometheus stack (Actuator `/metrics` + README numbers suffice)

If asked "what next?" in interviews, the cut list above is the answer.

---

## 3. Tech Stack

| Layer | Choice | Notes |
|-------|--------|-------|
| Language | Java 21 | Use records, virtual threads optional (see §13 stretch) |
| Framework | Spring Boot 3.x | Web, Data JPA (entities/repos), JDBC for hand-written hot-path SQL, Validation, Actuator |
| Database | PostgreSQL 16 | Source of truth |
| Cache / read model | Redis 7 | Sorted sets for leaderboard; counters for live stats |
| Migrations | Flyway | Every schema change is a versioned migration — no `ddl-auto` |
| Build | Maven or Gradle (Abhilash's choice) | |
| Load testing | k6 (preferred) or Gatling | Scripted scenarios in `/loadtest` |
| Dashboard | React + Vite, plain CSS or Tailwind | Polling, no SSE in MVP |
| Container | Docker + Docker Compose | app, postgres, redis, (dashboard) |
| Testing | JUnit 5, Testcontainers (Postgres + Redis), Awaitility | Integration tests run against real containers |

> 📚 **CONCEPT trigger:** When setting up Testcontainers, explain why integration tests against a real Postgres matter more here than mocked-repository unit tests (locking behavior cannot be mocked).

---

## 4. Architecture

Modular monolith. One deployable Spring Boot app with clear internal module boundaries; the matcher can run embedded (default) or as additional instances of the same image with a profile flag (for the multi-matcher demo).

```
┌─────────────────────┐        ┌──────────────────────────────┐
│  React Dashboard    │ poll   │   Spring Boot App            │
│  (ops view +        │──────▶│  ┌────────────────────────┐  │
│   sim controls)     │        │  │ api        (REST)      │  │
└─────────────────────┘        │  │ matchmaking (matcher)  │  │
                               │  │ rating     (Elo)       │  │
        k6 / simulator ──────▶│  │ leaderboard            │  │
                               │  │ season                 │  │
                               │  │ simulation (seeder+sim)│  │
                               │  └───────┬───────┬────────┘  │
                               └──────────┼───────┼───────────┘
                                          ▼       ▼
                                   PostgreSQL    Redis
                                   (truth)       (live reads)
```

### Package layout

```
com.matchforge
├── api/            // controllers, request/response DTOs, exception handling
├── player/         // Player entity, repo, seeder
├── matchmaking/    // QueueEntry, Matcher (naive + locked strategies), pairing
├── match/          // Match entity, result submission, validation
├── rating/         // Elo calculator, RatingHistory
├── leaderboard/    // Redis ops, season snapshots
├── season/         // Season entity, end-season workflow
├── simulation/     // synthetic player driver (in-app simulator)
├── metrics/        // custom Micrometer counters/gauges
└── config/         // Redis, scheduling, profiles, properties
```

**Profiles:**
- `default` — API + matcher + everything (single instance dev mode)
- `matcher-only` — runs only the matcher loop (for spinning up extra matcher instances in compose)
- `api-only` — API without matcher (so matcher count is controlled explicitly during demos)

---

## 5. Data Model (PostgreSQL)

All tables via Flyway migrations (`V1__init.sql`, ...). Use `BIGINT GENERATED ALWAYS AS IDENTITY` PKs and `TIMESTAMPTZ`.

```sql
-- V1__init.sql

CREATE TABLE players (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    handle          TEXT NOT NULL UNIQUE,          -- generated name e.g. "CrimsonFox_4821"
    rating          INTEGER NOT NULL DEFAULT 1200,
    region          TEXT NOT NULL DEFAULT 'NA',    -- enum-ish: NA/EU/APAC (cosmetic for now)
    games_played    INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE seasons (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name            TEXT NOT NULL,                 -- "Season 1"
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at        TIMESTAMPTZ,                   -- NULL = active
    CONSTRAINT one_active_season EXCLUDE USING gist ((1) WITH =) WHERE (ended_at IS NULL)
    -- (alternative: partial unique index; explain tradeoff in a concept callout)
);

CREATE TABLE queue_entries (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    player_id       BIGINT NOT NULL REFERENCES players(id),
    rating_at_join  INTEGER NOT NULL,              -- denormalized snapshot; explain why
    enqueued_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    status          TEXT NOT NULL DEFAULT 'WAITING',  -- WAITING | MATCHED | CANCELLED
    matched_match_id BIGINT,                       -- set when matched
    CONSTRAINT uq_active_queue UNIQUE (player_id, status) DEFERRABLE INITIALLY IMMEDIATE
    -- NOTE: this constraint as written is too strict (blocks historical rows).
    -- Correct approach: partial unique index, created below. Keep both in the brief
    -- so the *wrong* first attempt and the fix become a teaching moment.
);

-- The correct way to enforce "one active queue entry per player":
CREATE UNIQUE INDEX uq_player_waiting
    ON queue_entries (player_id)
    WHERE status = 'WAITING';

-- Hot-path index for the pairing query:
CREATE INDEX idx_queue_waiting_rating
    ON queue_entries (rating_at_join, enqueued_at)
    WHERE status = 'WAITING';

CREATE TABLE matches (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    season_id       BIGINT NOT NULL REFERENCES seasons(id),
    player_a_id     BIGINT NOT NULL REFERENCES players(id),
    player_b_id     BIGINT NOT NULL REFERENCES players(id),
    rating_a        INTEGER NOT NULL,              -- ratings at match creation
    rating_b        INTEGER NOT NULL,
    rating_delta    INTEGER NOT NULL,              -- |rating_a - rating_b| (band-quality metric)
    wait_ms_a       BIGINT NOT NULL,               -- how long each waited (band-expansion metric)
    wait_ms_b       BIGINT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'IN_PROGRESS',  -- IN_PROGRESS | COMPLETED
    winner_id       BIGINT REFERENCES players(id), -- NULL until completed
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    CONSTRAINT chk_players_distinct CHECK (player_a_id <> player_b_id)
);

CREATE INDEX idx_matches_recent ON matches (created_at DESC);
CREATE INDEX idx_matches_status ON matches (status) WHERE status = 'IN_PROGRESS';

CREATE TABLE rating_history (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    player_id       BIGINT NOT NULL REFERENCES players(id),
    match_id        BIGINT NOT NULL REFERENCES matches(id),
    season_id       BIGINT NOT NULL REFERENCES seasons(id),
    rating_before   INTEGER NOT NULL,
    rating_after    INTEGER NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_rating_once_per_match UNIQUE (player_id, match_id)
    -- ^ This constraint is the database-level idempotency backstop. Concept callout required.
);

CREATE TABLE season_leaderboard_snapshots (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    season_id       BIGINT NOT NULL REFERENCES seasons(id),
    player_id       BIGINT NOT NULL REFERENCES players(id),
    final_rating    INTEGER NOT NULL,
    final_rank      INTEGER NOT NULL,
    games_played    INTEGER NOT NULL,
    snapshot_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_snapshot UNIQUE (season_id, player_id)
);
```

### Modeling notes & teaching points
- **`rating_at_join` denormalization:** pairing must not join against `players` on the hot path; explain read-amplification and snapshot semantics (a player's rating can change while queued — we accept the snapshot; discuss the tradeoff).
- **Partial unique indexes** (`WHERE status = 'WAITING'`): the idiomatic Postgres way to enforce business uniqueness on a subset of rows.
- **`uq_rating_once_per_match`:** defense-in-depth idempotency — even if application logic fails, the DB rejects a double rating application. Application code must handle the constraint violation gracefully.
- **Audit-style `rating_history`:** append-only history tables as a pattern; never UPDATE history.

---

## 6. Core Domain Logic

### 6.1 Player seeding & rating distribution

A `PlayerSeeder` (CLI arg or admin endpoint `POST /api/admin/seed?count=10000`) generates N players with:
- Handles: adjective+noun+number (deterministic with a seed for reproducible benchmarks).
- **Ratings drawn from a normal distribution**, mean 1200, stddev 300, clamped to [400, 3000].

> 📚 **CONCEPT:** Why distribution shape matters — with a bell curve, mid-rated players match instantly (dense neighborhood) while tail players (350 or 2800 rating) starve and exercise the band-expansion logic. Uniform seeding would hide the most interesting matchmaking behavior. The simulator's realism depends on this.

### 6.2 The matcher loop

A scheduled job (`@Scheduled(fixedDelay = matchforge.matcher.interval-ms)`, default 1000ms) that each tick:
1. Pairs as many compatible WAITING players as possible (see pairing algorithm).
2. For each pair: creates a `matches` row, marks both queue entries MATCHED, records wait times and rating delta — all in **one transaction per pair** (explain why per-pair beats one giant transaction: smaller lock footprint, partial progress survives).
3. Updates Redis live-stat counters (matches created, queue depth gauge).

**Multiple instances:** the same app image launched with `matcher-only` profile. Docker Compose includes a scalable service: `docker compose up --scale matcher=3`. The whole point of the project is that this is safe.

> 📚 **CONCEPT:** Distributed scheduling — why `@Scheduled` on N instances means N concurrent executions, why that's usually a bug (and people reach for ShedLock/leader election), and why *here* we instead make concurrent execution **safe and beneficial** via SKIP LOCKED queue partitioning. This inversion is the project's intellectual core.

### 6.3 Pairing algorithm & the race condition (THE CENTERPIECE)

**Band expansion rule:** a player's acceptable rating window grows with wait time:

```
band(waitSeconds) = BASE_BAND + EXPANSION_RATE * waitSeconds, capped at MAX_BAND
defaults: BASE_BAND = 50, EXPANSION_RATE = 5/sec, MAX_BAND = 400
```

Two players are compatible if each is within the *other's* current band (symmetric check).

**Implement two `MatchingStrategy` implementations behind an interface, switchable at runtime via config/endpoint (this powers the dashboard's lock toggle):**

#### Strategy A — `NaiveMatcher` (built FIRST, intentionally broken under concurrency)
1. `SELECT` all WAITING entries ordered by `enqueued_at` (plain read, no locks).
2. Greedy pairing in Java: walk the wait-sorted list; for each unpaired player, find the closest-rated compatible partner.
3. UPDATE both entries to MATCHED, INSERT match.

**The bug:** two matcher instances read the same WAITING snapshot and both pair player 42. Symptoms: duplicate matches per player, or constraint violations, depending on timing.

**Required demonstration before fixing:** an integration test (Testcontainers, two threads running the naive matcher against a seeded queue) that reliably catches double-matching; plus a simulator run with 2 naive matchers showing nonzero anomalies. Record the numbers — they go in the README's "before" column.

#### Strategy B — `LockingMatcher` (the fix)
Hand-written SQL via `JdbcTemplate` (not JPA — we need precise control):

```sql
-- Claim a batch of waiting players, skipping rows another matcher already claimed:
SELECT id, player_id, rating_at_join, enqueued_at
FROM queue_entries
WHERE status = 'WAITING'
ORDER BY enqueued_at
LIMIT :batchSize
FOR UPDATE SKIP LOCKED;
```

Pairing then happens in Java *within the same transaction* over only the claimed rows; unpaired claimed rows are simply released at commit (locks drop, they remain WAITING).

> 📚 **CONCEPT (multi-part, give this real attention):**
> 1. `FOR UPDATE` — row locks held until transaction end; a second matcher would *block* on the same rows.
> 2. `SKIP LOCKED` — the second matcher skips locked rows and claims the *next* batch instead → matchers automatically **partition the queue** and work in parallel. The fix adds throughput rather than serializing it. This is the single best interview talking point in the project.
> 3. Edge effect to discuss: batch partitioning can split a compatible pair across two matchers' batches (each holds one half). Neither pairs them this tick; next tick they likely land in one batch. Discuss why this is acceptable (eventual pairing, bounded by tick interval) — a great example of a *correctness vs. liveness* tradeoff.
> 4. Run `EXPLAIN ANALYZE` on the claim query with 50k queued rows; verify the partial index is used; document the plan in LEARNING_NOTES.md.

**Pairing within a batch (both strategies share this):** sort claimed rows by `enqueued_at`; for each unpaired entry, scan for the compatible candidate with minimum rating delta; mark both paired. O(n²) within a batch is fine for batch sizes ≤ 200 — note the complexity and why we don't optimize prematurely.

**Config:** `matchforge.matcher.strategy=locking|naive` + admin endpoint `PUT /api/admin/matcher-strategy` so the dashboard toggle can flip it live.

### 6.4 Match results, Elo, and idempotency

**Result submission** (`POST /api/matches/{id}/result`, body: `{"winnerId": 123}`) — in the simulator's world, results arrive from the simulation driver after a fake match duration.

All of the following happens in **ONE transaction**:
1. Load match `FOR UPDATE` (lock the match row — explain why: two simultaneous result submissions for the same match must serialize).
2. Validate: match exists, status is IN_PROGRESS, winnerId is one of the two players. Violations → 409/422 with clear error body.
3. If status is already COMPLETED → return **200 with the existing result** (idempotent replay, not an error). Discuss: idempotency means "same call, same outcome," and 200-with-existing beats 409 for retry-friendly clients. (Alternative discussed: explicit `Idempotency-Key` header — note it, but match-ID-based natural idempotency suffices here.)
4. Compute Elo for both players (see below).
5. UPDATE both players' ratings and `games_played`.
6. INSERT two `rating_history` rows (the `uq_rating_once_per_match` constraint is the safety net).
7. UPDATE match: status COMPLETED, winner, completed_at.
8. After commit (and only after — explain transactional outbox in one paragraph as the "real-world" version, then justify the simpler post-commit hook here): `ZADD` both players' new ratings into the Redis leaderboard.

**Elo (hand-rolled, ~20 lines):**
```
expectedA = 1 / (1 + 10^((ratingB - ratingA)/400))
newRatingA = round(ratingA + K * (scoreA - expectedA))   // scoreA: 1 win, 0 loss
K = 32 (flat; mention rating-dependent K and provisional periods as real-world refinements)
```

> 📚 **CONCEPT:** Transaction boundaries — walk through exactly which statements are inside the transaction and what happens on failure at each step (e.g., crash between step 5 and 7: everything rolls back; Redis was never touched because the ZADD is post-commit). Then discuss the *residual* inconsistency window (commit succeeds, process dies before ZADD) and the mitigation: a small reconciliation job or lazy repair on leaderboard read. State clearly: Postgres is truth, Redis is a cache that may briefly lag — that ordering of trust is the whole design.

**Hot-row contention (measure, don't guess):** under high load, popular players' rows see concurrent `FOR UPDATE`s from result processing. Add a histogram metric around result-processing time; report p99 in the benchmark table. Even "contention negligible at this load" is a finding.

### 6.5 Leaderboard — dual read model

- **Live:** Redis sorted set `leaderboard:season:{id}`, member = playerId, score = rating. Updated via post-commit `ZADD`. Reads: `ZREVRANGE ... WITHSCORES` for top-N; `ZREVRANK` for a player's rank. Hydrate player handles via a single `IN` query against Postgres (or a Redis hash cache `player:handle:{id}` — implement the simple IN-query first, mention the cache as an optimization).
- **Historical:** on season end, snapshot final standings into `season_leaderboard_snapshots`, then reset.

> 📚 **CONCEPT:** Read models / CQRS-lite — why "top 100 by rating" via Postgres `ORDER BY rating DESC LIMIT 100` is fine at 10k players but the sorted set is O(log N) per update and O(log N + M) per range read, scales independently of the write path, and offloads the truth store. Also cover: what happens if Redis is flushed (rebuild command: one Postgres scan → pipelined ZADDs; implement `POST /api/admin/rebuild-leaderboard` — this doubles as the recovery story for the residual-inconsistency discussion in §6.4).

### 6.6 Seasons

- Exactly one active season (enforced by partial unique/exclusion constraint — teaching moment in §5).
- `POST /api/admin/seasons/end` (one transaction): close season → write all snapshots (rank computed via `ROW_NUMBER() OVER (ORDER BY rating DESC)` — concept callout: window functions) → create next season → **soft reset** ratings: `new = 1200 + (old - 1200) / 2` (explain why hard resets are hated and what soft reset preserves) → reset Redis: delete old sorted set, seed new one.
- `GET /api/seasons/{id}/leaderboard` serves snapshots for ended seasons, Redis for the active one — same response shape (explain why a uniform contract matters for the dashboard).

---

## 7. API Specification

Base path `/api`. JSON everywhere. Bean Validation on request bodies. Global `@ControllerAdvice` exception handler → consistent error shape:

```json
{ "error": "MATCH_ALREADY_COMPLETED", "message": "Match 88 was already resolved.", "timestamp": "..." }
```

### Player & queue
| Method | Path | Purpose | Notes |
|--------|------|---------|-------|
| GET | `/players/{id}` | Profile + current rating + rank | rank via `ZREVRANK` |
| GET | `/players/{id}/history` | Rating history (paged) | |
| POST | `/queue/join` | `{"playerId": 1}` → 201 queue entry | 409 if already WAITING (partial unique index surfaces this — map the constraint violation to a clean error) |
| DELETE | `/queue/{playerId}` | Leave queue | 404 if not queued; note the race with the matcher (entry may get MATCHED between read and delete — handle with a conditional `UPDATE ... WHERE status='WAITING'` and explain) |

### Matches
| Method | Path | Purpose |
|--------|------|---------|
| GET | `/matches/{id}` | Match detail |
| POST | `/matches/{id}/result` | Submit result (idempotent — §6.4) |
| GET | `/matches/recent?limit=20` | Recent matches incl. rating delta & wait times (dashboard feed) |

### Leaderboard & seasons
| Method | Path | Purpose |
|--------|------|---------|
| GET | `/leaderboard?limit=20` | Active-season top N |
| GET | `/seasons` | All seasons |
| GET | `/seasons/{id}/leaderboard` | Snapshot (ended) or live (active) |

### Dashboard read endpoints (poll-optimized; each must answer in <50ms at 10k players — state this as an explicit perf budget and verify)
| Method | Path | Returns |
|--------|------|---------|
| GET | `/dashboard/queue?limit=50` | Waiting players: id, handle, rating, waitMs, **currentBand** (computed server-side so the UI just renders) |
| GET | `/dashboard/stats` | queueDepth, matchesPerSec (Redis counter w/ sliding window), avgWaitMs, p99PairingLatencyMs, activeMatcherCount, currentStrategy, anomalyCount |
| GET | `/dashboard/anomalies` | Detected double-match events (see §8 anomaly detector) |

### Admin / simulation control
| Method | Path | Purpose |
|--------|------|---------|
| POST | `/admin/seed?count=10000` | Seed players |
| POST | `/admin/simulate/start` | Body: `{"players": 1000, "requeueProbability": 0.7, "matchDurationMsMin": 2000, "matchDurationMsMax": 8000}` |
| POST | `/admin/simulate/stop` | Stop simulation |
| PUT | `/admin/matcher-strategy` | `{"strategy": "naive" \| "locking"}` — the dashboard toggle |
| POST | `/admin/seasons/end` | Season rollover |
| POST | `/admin/rebuild-leaderboard` | Redis rebuild from Postgres |

OpenAPI via springdoc; Swagger UI at `/swagger-ui.html`.

---

## 8. Dashboard (React ops view)

**Aesthetic:** dense, dark, monospace numbers — mission-control, not product. This signals "systems engineer." Single page, four panels + control strip. Polling: stats every 1s, queue/matches/leaderboard every 2s.

**Panels:**
1. **Queue** — top ~30 waiting players: handle, rating, live wait timer, and a horizontal bar visualizing the current rating band *widening in real time* (the band-expansion algorithm made visible).
2. **Match feed** — scrolling list: "CrimsonFox (1340) vs IronWolf (1318) · Δ22 · waited 4.2s / 1.8s". **Anomaly rows render flashing red.**
3. **Leaderboard** — top 20, rank-change animation on reorder (FLIP animation or simple highlight — keep it cheap).
4. **Stats strip** — queue depth, matches/sec, avg wait, p99 pairing latency, matcher count, big anomaly counter (should read 0 in locking mode).

**Control strip:**
- "Spawn 1,000 players" → `POST /admin/simulate/start`
- "Stop simulation"
- Matcher strategy toggle: **LOCKING / NAIVE** — flipping to NAIVE under load makes red anomalies appear in the feed within seconds; flipping back stops them. This is the demo's money shot and the README GIF.
- (If compose-scaled matchers can't be controlled from the UI, display matcher count read-only and control it via `docker compose --scale`; do not over-engineer container orchestration from the dashboard.)

**Anomaly detection (backend):** a lightweight check that makes the race *visible*: on match creation, verify neither player has another IN_PROGRESS match; violations are recorded to an `anomalies` table (or Redis list) with both match IDs, surfaced via `/dashboard/anomalies`. In naive mode this catches double-matches; in locking mode it stays empty. (Note the subtlety: the detector itself reads-then-writes and could race — for a *detector* that's acceptable; discuss why detection and enforcement have different correctness requirements.)

**Effort budget:** dashboard ≤ 15% of total project time. Polling + fetch + plain components. No state library, no SSR, no design system.

---

## 9. Simulator & Load Testing

### In-app simulator (`simulation` module)
A driver that runs the full player lifecycle against the system's own service layer or HTTP API (use HTTP — it exercises the real stack):
```
loop per simulated player:
  join queue → poll until matched → sleep(matchDuration ± jitter)
  → submit result (winner chosen with rating-aware probability — reuse the Elo expected-score formula, a nice touch: the simulation is self-consistent)
  → with probability p, requeue; else go idle for a while, then return
```
Run on virtual threads or a bounded executor (concept callout: thread-per-task vs pooled; if Java 21 virtual threads are used, explain what they change). Configurable: player count, requeue probability, match duration range.

### k6 external load tests (`/loadtest`)
Scenarios:
1. **Queue storm:** 500–2000 VUs joining the queue in 30s; measure join p50/p95/p99.
2. **Sustained loop:** steady-state full lifecycle for 5 minutes; measure matches/sec and result-submission p99.
3. **Race reproduction:** queue storm with `strategy=naive`, 3 matcher instances → record anomaly count; repeat with `locking` → assert 0.

### The benchmark table (README centerpiece — produce this for real)
| Players | Matchers | Strategy | Matches/sec | p99 join (ms) | p99 pairing (ms) | Double-matches |
|---------|----------|----------|-------------|---------------|------------------|----------------|
| 10,000 | 1 | locking | … | … | … | 0 |
| 10,000 | 3 | naive | … | … | … | **N > 0** |
| 10,000 | 3 | locking | … | … | … | **0** |
| 50,000 | 3 | locking | … | … | … | 0 |

Also report: does 3× matchers ≈ 3× pairing throughput? (SKIP LOCKED partitioning should scale well; measure and discuss whatever you find — honest analysis of *imperfect* scaling is more impressive than suspiciously clean numbers.)

> 📚 **CONCEPT:** Load-testing methodology — warmup before measuring, why p99 ≠ avg, coordinated omission (one paragraph), connection-pool sizing (HikariCP default 10 will bottleneck the queue storm — let it happen, observe pool-wait metrics, then tune; another deliberate teach-by-failure moment).

---

## 10. Metrics & Observability

Spring Actuator + Micrometer. Custom metrics:
- `matchforge.matches.created` (counter, tagged by strategy)
- `matchforge.queue.depth` (gauge)
- `matchforge.queue.wait` (timer/histogram → avg & p99 wait)
- `matchforge.pairing.duration` (timer per matcher tick)
- `matchforge.result.processing` (timer — the hot-row contention probe from §6.4)
- `matchforge.anomalies.detected` (counter)

Exposed at `/actuator/metrics` + aggregated into `/api/dashboard/stats`. **No Grafana** — numbers flow into the README and the dashboard instead.

---

## 11. Deployment & Repo Deliverables

### Docker Compose (root `docker-compose.yml`)
Services: `postgres`, `redis`, `app` (api+matcher default), `matcher` (matcher-only profile, `--scale matcher=N`), `dashboard` (nginx-served build). Healthchecks on postgres/redis; app `depends_on` healthy. Seed on first boot via env flag.

**Acceptance:** `git clone && docker compose up` → dashboard at `localhost:3000`, Swagger at `localhost:8080/swagger-ui.html`, seeded with 1,000 players — on a clean machine, no other steps.

### Optional hosted demo
Dashboard → Vercel; backend+Postgres+Redis → Fly.io/Railway/Render free tier. Constraints to verify: managed Redis availability, always-on matcher (no scale-to-zero). If hosting is painful, fallback = README GIF + 60-second screen recording. Do not burn more than a day on hosting.

### README structure (treat as a deliverable spec)
1. Title + one-line pitch + **animated GIF** of the dashboard with the NAIVE→anomalies→LOCKING sequence (record with the simulator at ~1k players)
2. "What this is" — 3 sentences
3. Architecture diagram (one image, 4 boxes)
4. **"The interesting problem: double-matching under concurrent matchers"** — the bug, the failing numbers, `FOR UPDATE SKIP LOCKED`, the partitioning insight, the after numbers. This section is why the project exists.
5. Benchmark table (§9)
6. Design decisions (5–7 bullets: dual read model, idempotency, post-commit Redis + rebuild, soft reset, modular monolith, what was deliberately cut)
7. Run instructions (compose, simulate script, k6)
8. "What I'd build next" (the §2 cut list)

### Other repo files
- `LEARNING_NOTES.md` — the accumulated concept callouts (see §0)
- `demo.sh` — seed 1k → start sim → tail stats for 60s → print leaderboard (the 30-second terminal demo)
- `/loadtest/*.js` — k6 scenarios
- ADR-style notes optional; the README design-decisions section may suffice

---

## 12. Build Plan — Milestones with Learning Objectives

Each milestone ends with: working code, passing tests, a `LEARNING_NOTES.md` update, and **checkpoint questions answered by Abhilash** before the next milestone begins.

### M1 — Skeleton & data layer (Days 1–2)
Spring Boot project, Docker Compose (postgres+redis), Flyway migrations (§5 schema), Player entity/repo, seeder with normal-distribution ratings, Testcontainers harness, first integration test.
**Learn:** migrations vs ddl-auto, Testcontainers, partial unique indexes, seeding distributions.
**Checkpoint:** Why Flyway? What does the partial index on `queue_entries` enforce that a plain UNIQUE can't? Why does seed distribution shape change matchmaking behavior?

### M2 — Queue & naive matcher (Days 3–4)
Join/leave endpoints (with the conditional-update leave race handled), `NaiveMatcher` behind the `MatchingStrategy` interface, band-expansion logic + unit tests, match creation, scheduled loop, `matcher-only` profile.
**Learn:** scheduled jobs, API validation/error design, greedy pairing, the leave-vs-match race.
**Checkpoint:** Walk through the naive matcher with 2 instances and explain exactly where it breaks. What does the conditional UPDATE on leave protect against?

### M3 — Break it on purpose (Day 5)
Two-threaded Testcontainers test that reliably reproduces double-matching; anomaly detector + table; run 2–3 naive matchers under the (rough, early) simulator; **record the failure numbers.**
**Learn:** reproducing races deterministically (barriers/latches to maximize collision probability), why "it didn't fail on my machine" means nothing.
**Checkpoint:** Why do we use a CyclicBarrier in the race test? Could the anomaly detector itself miss anomalies — why is that acceptable for a detector?

### M4 — Fix it (Days 6–7)
`LockingMatcher` with the SKIP LOCKED claim query (JdbcTemplate), runtime strategy switch + admin endpoint, race test now passes with 0 anomalies, `EXPLAIN ANALYZE` on the claim query documented.
**Learn:** FOR UPDATE, SKIP LOCKED, queue partitioning, transaction-scoped locks, reading query plans.
**Checkpoint:** What happens to claimed-but-unpaired rows at commit? Why can a compatible pair be split across two matchers' batches, and why is that OK? What would FOR UPDATE *without* SKIP LOCKED do to throughput?

### M5 — Results, Elo, idempotency (Days 8–9)
Result endpoint with the full single-transaction flow (§6.4), hand-rolled Elo + unit tests against known values, idempotent-replay behavior + test, duplicate-submission concurrency test (two threads, one match), rating history, post-commit Redis hook stub.
**Learn:** transaction boundaries, idempotency, DB constraints as backstops, locking the match row.
**Checkpoint:** Trace a crash between the rating UPDATE and the match-status UPDATE — what state results? Why 200-with-existing instead of 409 for a replayed result? What fires first: the application's status check or the `uq_rating_once_per_match` constraint, and why do we want both?

### M6 — Leaderboard & seasons (Days 10–11)
Redis sorted-set leaderboard, post-commit ZADD wired in, top-N + player-rank endpoints, rebuild-from-Postgres admin op, season end (snapshot via window function, soft reset, Redis reset), uniform live/snapshot leaderboard contract.
**Learn:** sorted sets & complexity, read models, cache-rebuild as recovery, window functions, the post-commit consistency window.
**Checkpoint:** Redis dies and restarts empty — what does the user see and how do we recover? Why snapshot ranks with ROW_NUMBER at season end instead of computing ranks on read?

### M7 — Simulator & benchmarks (Days 12–13)
Full HTTP-driven simulator with rating-aware win probability, k6 scenarios, HikariCP bottleneck → observe → tune (deliberate failure #2), produce the real benchmark table, capture pairing-throughput scaling across 1/2/3 matchers.
**Learn:** load methodology, percentiles, connection pools, interpreting scaling curves.
**Checkpoint:** Why did p99 spike before the pool tune while avg barely moved? Did 3 matchers triple pairing throughput — and whatever the answer, why?

### M8 — Dashboard (Days 14–15)
React ops page (§8): four panels + control strip + strategy toggle, polling against the dashboard endpoints, anomaly red-flash, the NAIVE→LOCKING live demo working end-to-end.
**Learn:** designing poll-friendly read endpoints with perf budgets, server-side computation of display values (band width), cheap real-time UX without websockets.
**Checkpoint:** Why compute `currentBand` server-side? What's the perf budget per dashboard endpoint and how do we know we're meeting it?

### M9 — Polish & publish (Days 16–17)
README per §11 (GIF recorded, benchmark table filled, "interesting problem" section written), `demo.sh`, LEARNING_NOTES.md cleanup, optional hosting, final pass: every README claim must trace to a test or a benchmark artifact in the repo.
**Final assessment:** Abhilash explains the entire system out loud (rubber-duck style) — matcher concurrency story end-to-end, transaction walk-through, read-model rationale — as interview rehearsal. Claude Code plays interviewer and asks follow-ups.

---

## 13. Stretch Goals (only after M9, only if time allows)
- Redis-claim matcher as Strategy C; benchmark vs SKIP LOCKED (distributed-lock concepts: SETNX, TTLs, fencing)
- SSE for the dashboard instead of polling
- Rating-dependent K-factor / provisional ratings
- Virtual-threads experiment: result endpoint on virtual threads, before/after throughput

---

## 14. Definition of Done
- [ ] `docker compose up` on a clean machine → seeded, working system (app, dashboard, Swagger)
- [ ] Race condition: reproduced by a committed test (naive), eliminated (locking), both states demonstrable via the dashboard toggle
- [ ] Benchmark table filled with real measured numbers, incl. 0 anomalies at 10k+ players / 3 matchers (locking)
- [ ] Idempotency: duplicate & concurrent result submissions covered by tests; DB constraint backstop verified
- [ ] Leaderboard: live Redis + season snapshots + rebuild op, all tested
- [ ] ~15–25 meaningful tests (race, idempotency, transaction rollback, band expansion, Elo math, season rollover) — quality over coverage %
- [ ] README complete per §11 with GIF and the "interesting problem" narrative
- [ ] `LEARNING_NOTES.md` covers all 15 curriculum concepts (§0)
- [ ] Abhilash has answered all milestone checkpoints and passed the M9 mock-interview walkthrough
- [ ] Resume bullet drafted, every clause traceable to an artifact in the repo

---

## 15. Guardrails (anti-scope-creep contract)
If during implementation anyone (including Abhilash) proposes adding: tournaments, auth, Kafka, microservice extraction, WebSockets-for-gameplay, moderation, or multi-region — **stop and re-read §2.** The answer is "great 'what's next' interview answer," not code. The project's value is depth on one problem; every addition dilutes it.
