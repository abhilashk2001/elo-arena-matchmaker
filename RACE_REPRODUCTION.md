# Race Reproduction: the naive matcher under concurrency

This is the "before" baseline for the project's central claim. It records what happens when
the naive matcher runs on more than one instance, before the locking fix in Phase 4.

## The setup

`RaceConditionTest` seeds 100 mutually-compatible players into the queue (all rating 1500,
so any pairing is valid) and releases two `NaiveMatcher` threads at the same instant with a
`CyclicBarrier`. A correct matcher would produce exactly 50 matches and zero anomalies: 100
players, paired once each.

## The result (naive matcher, 2 concurrent threads)

Measured over three consecutive runs, identical each time:

| Metric | Correct | Naive, 2 matchers |
|---|---|---|
| Matches created | 50 | **100** |
| Players double-matched | 0 | **100** |
| Anomalies detected | 0 | **103** |

Every player was matched twice. The two matchers both read the same WAITING snapshot (no
locks), both paired the same players, and both inserted matches, doubling every result.

## Why it happens

The naive matcher does a plain `SELECT ... WHERE status = 'WAITING'` with no row locks. Two
matchers reading at the same instant see the same players and both act on that stale snapshot.
There is a read-then-write gap with nothing preventing two writers from acting on the same rows.

## The fix (Phase 4)

The locking matcher claims rows with `SELECT ... FOR UPDATE SKIP LOCKED`, so the two matchers
take disjoint sets and never pair the same player. Phase 4 reuses this exact scenario and
asserts the anomaly count is zero. The "after" numbers go here once that lands.
