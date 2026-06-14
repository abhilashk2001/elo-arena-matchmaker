# Resume bullet

Draft bullets for the project, with every clause traceable to a specific artifact in the repo. Pick
the one-liner or the two-line version depending on space.

## One-line version

> Built a concurrent matchmaking backend (Java/Spring Boot, Postgres, Redis) that eliminates a
> double-booking race under multiple matcher instances with `FOR UPDATE SKIP LOCKED`, taking
> anomalies from 840 to 0 at 10k players while scaling pairing throughput ~2x across three matchers.

## Two-line version

> Built a concurrent matchmaking backend (Java/Spring Boot, Postgres, Redis) with a batch matcher,
> Elo ratings, and a Redis-backed leaderboard, driven by an in-app load simulator and a React ops
> dashboard.
> Reproduced a double-match race across concurrent matchers with a deterministic test, fixed it with
> `FOR UPDATE SKIP LOCKED` (840 anomalies to 0 at 10k players, ~2x throughput across three matchers),
> and verified idempotent result processing and sub-50ms poll endpoints under load.

## Clause-to-artifact traceability

| Clause | Backing artifact |
|--------|------------------|
| concurrent matchmaking backend, Java/Spring Boot | `src/main/java/com/eloarena/**`, `pom.xml` |
| Postgres source of truth + Redis read model | `V1__init.sql`, `Leaderboard` (Redis ZSET), `RedisLiveStats` |
| batch matcher | `LockingMatcher`, `MatcherLoop`, `PairingAlgorithm` |
| Elo ratings | `EloCalculator`, `EloCalculatorTest` |
| Redis-backed leaderboard | `Leaderboard`, `LeaderboardService`, `LeaderboardUpdateTest` |
| in-app load simulator | `Simulator`, `SimulatorIntegrationTest` |
| React ops dashboard | `dashboard/`, `DashboardController` |
| reproduced a double-match race with a deterministic test | `RaceConditionTest` (CyclicBarrier), `RACE_REPRODUCTION.md` |
| fixed with `FOR UPDATE SKIP LOCKED` | `LockingMatcher` (CLAIM_SQL), `LockingRaceConditionTest` |
| 840 anomalies to 0 at 10k players | `scaling-results.md` (via `scripts/scaling-benchmark.sh`) |
| ~2x throughput across three matchers | `drain-results.md` (via `scripts/queue-drain-benchmark.sh`) |
| idempotent result processing | `ResultService`, `ResultIdempotencyTest`, `ResultConcurrencyTest` |
| sub-50ms poll endpoints under load | `dashboard-latency-results.md` (via `scripts/dashboard-latency-benchmark.sh`) |

Every number is reproducible: the scripts are committed and the results files are the output of
running them.
