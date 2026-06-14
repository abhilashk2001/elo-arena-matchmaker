# Checkpoint Answers

A self-study answer key for the milestone checkpoint questions in LEARNING_NOTES.md.
Read these when preparing to explain the system (interviews, the Phase 9 walkthrough).
Each phase appends its questions and model answers here.

---

## Phase 1: Skeleton and data layer

**Q1. Why Flyway over ddl-auto? Give two concrete problems ddl-auto=update causes.**

- No visibility or review. ddl-auto runs whatever DDL Hibernate infers, invisibly. You cannot
  see exactly what ran, code-review it, or roll it back. A Flyway migration is a file in the PR.
- Drift and danger. The schema it produces depends on entity state at startup, so different
  machines, CI, and prod can silently diverge. It never safely drops or renames a column or
  migrates data, so it leaves mismatches, and pointing it at a production database risks data loss.

**Q2. What does the partial index on queue_entries enforce that a plain UNIQUE (player_id, status) cannot, and why does the plain version break normal use?**

A plain UNIQUE (player_id, status) forbids two rows that share the same (player_id, status) pair.
That blocks a player from ever having, say, two CANCELLED entries, which is normal history. The
partial index `UNIQUE (player_id) WHERE status = 'WAITING'` constrains only the WAITING rows, so a
player can have at most one active entry while keeping unlimited historical rows.

**Q3. Why does the shape of the seed distribution change matchmaking behaviour? What would uniform seeding hide?**

It is about density. A bell curve makes mid-rated players dense (they match instantly) and tail
players (around 2800) sparse, so the tail players starve and must wait for their band to expand.
That starvation path is exactly what band expansion exists to handle. Uniform seeding makes every
rating region equally populated, so nobody starves and the band-expansion code path never gets
exercised, hiding the most interesting behaviour from benchmarks and the dashboard.

---

## Phase 2: Queue and the naive matcher

**Q1. Walk through the naive matcher on two instances against the same queue. Where does it break, and what is the bad outcome?**

1. Matcher A runs a plain `SELECT ... WHERE status='WAITING'` and reads, among others, players 42 and 43.
2. Matcher B runs the same plain SELECT at nearly the same time and reads the same snapshot, also seeing 42 and 43. Neither took any lock, so neither knows about the other.
3. Both independently decide 42 and 43 are a good pair.
4. A inserts a match (42 vs 43) and marks the entries MATCHED. B also inserts a match (42 vs 43) and marks them MATCHED.
5. Bad outcome: player 42 (and 43) now appear in two IN_PROGRESS matches. Ratings would later be applied twice, and the queue entry's matched_match_id is overwritten. This is the double-match the project exists to prevent. The root cause is a read-then-write with no lock between the read and the write, so two readers act on the same stale snapshot. Phase 4 fixes it by claiming rows with FOR UPDATE SKIP LOCKED so the two matchers take disjoint sets.

**Q2. What does the conditional UPDATE on leave protect against? What goes wrong with read-then-write?**

It protects against cancelling an entry the matcher has already matched. With "read the entry, see
it is WAITING, then cancel it," the matcher can flip the entry to MATCHED in the gap between the
read and the write; you would then cancel a player who is already in a match, corrupting state. The
single statement `UPDATE ... SET status='CANCELLED' WHERE player_id=? AND status='WAITING'` checks
and acts atomically: if the matcher already claimed them, it matches zero rows and cancels nothing.

**Q3. Why write each pair in its own transaction rather than all pairs in one? One benefit.**

Partial progress survives: if one pair fails to persist, the pairs already committed this tick still
stand instead of the whole tick rolling back. It also keeps the lock footprint per transaction small.
(Phase 4's locking strategy deliberately chooses the opposite, one transaction per claimed batch,
because by then the batch rows are already exclusively locked, so a single transaction is both safe
and simpler.)

---

## Phase 3: Break it on purpose

**Q1. Why a CyclicBarrier instead of just starting two threads?**

Because race conditions are timing-dependent. If you just start two threads, one usually
finishes most of its work before the other reads, so they do not collide and the bug stays
hidden ("did not fail on my machine"). A CyclicBarrier makes both threads wait at a gate and
releases them at the same instant, so they read the same WAITING snapshot at nearly the same
time. That maximises the collision window and makes the double-match reproduce on essentially
every run, which is what you need for a regression test.

**Q2. Could the detector miss or double-count anomalies? Why is that acceptable for the detector but not the matcher?**

Yes. The detector reads then writes (it queries in-progress matches, then records a row), which
is itself a small race, so under unlucky timing it could miscount by one. That is acceptable
because the detector only measures: it needs to show the counter climbing in naive mode and
sitting at zero in locking mode, and being approximately right does that. The matcher, by
contrast, enforces a hard guarantee (never double-match a player). Enforcement must be exact;
measurement only has to be good enough to tell the story.

**Q3. Under READ COMMITTED, why does detecting after commit work, while checking inside the creating transaction would not?**

READ COMMITTED means a transaction only sees rows other transactions have already committed.
If a matcher checked for a conflict while its own transaction (and the other matcher's) were
still open, it would not see the other matcher's uncommitted match, so it would find no
conflict. Running detection after the match commits, in a fresh read, lets it see the other
matcher's now-committed match. That is why the second matcher to commit is the one that records
the anomaly.

---

## Phase 4: Fix it

**Q1. What happens to claimed-but-unpaired rows when the locking matcher's transaction commits?**

Nothing is done to them, so they stay WAITING. The claim took a FOR UPDATE lock on every row
in the batch, but we only UPDATE the rows we actually paired. The unpaired claimed rows are
never modified, and when the transaction commits their locks are released. They are immediately
claimable again by the next tick (this one or another matcher).

**Q2. Why can a compatible pair be split across two matchers' batches, and why is that acceptable?**

Each matcher claims only a bounded batch (LIMIT N FOR UPDATE SKIP LOCKED), so two players who
would pair well can end up in different matchers' batches. Neither matcher sees both halves, so
neither pairs them this tick. That is acceptable because it is a liveness cost, not a
correctness bug: nobody is double-matched, the two players just wait one extra tick, and they
will very likely fall into the same batch next tick. We never compromise correctness; we relax
liveness slightly in exchange for safe parallelism.

**Q3. What would FOR UPDATE without SKIP LOCKED do to throughput with two matchers?**

The second matcher would block instead of skipping. Plain FOR UPDATE locks the claimed rows and
holds them until commit, so a second matcher selecting the same WAITING rows (the front of the
queue, since both order by enqueued_at) would wait for the first matcher to finish rather than
working on other rows. The matchers would serialise: two matchers would do roughly the work of
one. SKIP LOCKED is what lets the second matcher skip the locked rows and claim the next batch,
so they run in parallel and throughput scales.

---

## Phase 5: Results, Elo, and idempotency

**Q1. Trace a crash between the player rating UPDATE and the match-status UPDATE.**

Nothing partial survives. All the writes (both player ratings, both rating-history rows, the
match status) are in one transaction, and none of them are committed until the method returns
successfully. A crash in the middle means the transaction never commits, so Postgres rolls back
every uncommitted change. The ratings revert and the match stays IN_PROGRESS, exactly as if the
submission never happened. Redis is untouched too, because the leaderboard update only runs
after commit. That is the point of the single transaction: ratings can never disagree with match
status.

**Q2. Why 200-with-existing instead of 409 for a replayed submission?**

Because the caller is almost always an honest client retrying after it did not hear the first
response. The result was applied; nothing is wrong. A 409 would tell them "conflict" and make
them think it failed. Returning 200 with the same outcome means the same call always produces the
same answer, which is what idempotency means and what makes the endpoint safe to retry.

**Q3. Which fires first for a duplicate, the app check or the constraint, and why both?**

In the normal case the application status check fires first: the match is already COMPLETED, so
we return the existing result and never reach the inserts. The uq_rating_once_per_match constraint
only fires if that check is bypassed, which can happen when two duplicates race and both read
IN_PROGRESS before either commits. We want both because they cover different cases: the app check
gives a friendly 200 for ordinary retries, and the constraint is the hard guarantee that a rating
is applied at most once even under a race the app check cannot see. The FOR UPDATE lock on the
match makes the race rare by serializing submissions, but the constraint is the backstop.

## Phase 6: Leaderboard and seasons

**Q1. Why keep the leaderboard in Redis when Postgres already has every rating?**

Because the read patterns the dashboard hammers are cheap in a sorted set and expensive in a
relational table. Top-N from Postgres is an ORDER BY over the players plus a limit, and it gets
slower as players grow; a single player's rank is worse, because you have to count how many
players outrank them. A Redis sorted set keeps members ordered by score, so top-N is ZREVRANGE
(O(log N + M)) and a player's rank is ZREVRANK (O(log N)), both fast at any size. The cost is that
the same fact now lives in two places, so it can drift. We pay that down with discipline: Postgres
is the truth, Redis is only ever written after a Postgres commit, and anything in Redis can be
rebuilt from Postgres, so a stale or wiped Redis is a recoverable cache miss, not lost data.

**Q2. Why is the division derived from rating rather than stored?**

A division is a pure function of rating (ten 200-point bands), so storing it would create a second
value that has to be updated in lockstep every time the rating changes. The moment those two
disagree, through a missed update or a partial write, you have a bug and no obvious source of
truth. Computing it on read means there is only one number, the rating, and the division is always
exactly consistent with it by construction. It is the same single-source-of-truth instinct as the
Redis projection, applied to a field cheap enough to recompute every time.

**Q3. Why must the rollover's four writes share one transaction? Give a bad partial state.**

Because the four writes (freeze standings, close the season, reset ratings, open the next season)
only make sense as a unit, and a crash between them would leave the system inconsistent with no
way to tell how far it got. Concrete bad state: suppose it closed the season but crashed before
opening the next one. Now zero seasons have ended_at IS NULL, so there is no active season, and the
matcher cannot stamp new matches with a season id; the whole system stalls. Another: it reset every
rating but never wrote the snapshot, so the final standings of the season just ended are gone for
good. Wrapping all four in one transaction makes it all-or-nothing: either the rollover happened or
the world is exactly as it was before.

**Q4. Why reset Redis in an afterCommit hook instead of inside the transaction?**

Because Redis is not part of the database transaction and cannot be rolled back with it. If we
dropped the old board and rebuilt the new one inside the transaction and the transaction then
rolled back, Postgres would revert but Redis would be left advertising a rollover that never
happened, with no event to undo it. Running the reset in an afterCommit hook means Redis is only
ever touched once the database change is durable, so it can only reflect a rollover that actually
succeeded. And if the process dies in the gap between commit and the reset, the rebuild op
reconstructs the new season's board from Postgres, so the worst case is a brief lag, not a wrong
board. Postgres leads, Redis follows.

## Phase 7: Load, metrics, and benchmarks

**Q1. The average result latency stayed flat while p99 spiked. What was actually slow, and why did the average miss it?**

Nothing was slow doing the actual work; requests were slow *waiting for a database connection*. At
the default pool of 10, threads blocked an average of ~122 ms in `hikaricp.connections.acquire`
before they could run their query, and a handful blocked far longer (the max was ~1.8 s). The
average hid it because the average is dominated by the majority of requests that did get a connection
quickly: if most requests are fast and a slice are very slow, the many fast ones drag the mean down to
something that looks healthy. The p99 asks the question the average refuses to: sort every request by
latency and read the one at the 99th percentile, and that is the request that waited seconds in line.
The average is the metric that lets a starved pool look fine; the percentile is the one that catches
it. That is exactly why we publish p50/p95/p99 on the result timer instead of just a mean.

**Q2. Which metric localised the connection-pool bottleneck, and how do you read it?**

`hikaricp.connections.acquire`, the time a thread spends blocked waiting for a free connection from
the pool, separate from how long the query itself takes once it has one. You read it against the
actual work time: when acquire-wait is high (122 ms) while the query/result processing itself is low
(tens of ms), the bottleneck is the pool, not the database, because threads are spending their time in
line rather than doing work. If instead acquire-wait were near zero but query time were high, the pool
would be fine and the database would be the bottleneck. The two metrics together tell you *where* the
time goes, which is the whole game in performance work: do not guess, measure which stage is slow.

**Q3. Why is a bigger pool not automatically better? What did sweeping pool sizes reveal?**

Because a bigger pool does not make any single piece of work faster; it just allows more work to hit
the database at the same time, and past a point that concurrency is the problem rather than the
solution. The sweep showed it directly: as the pool grew 10 → 30 → 50, acquire-wait fell (122 → 75 →
59 ms) exactly as you would hope, but result-processing p99 went the *other* way (40 → 130 → 201 ms).
The contention did not disappear, it relocated: with connections no longer scarce, more result
submissions ran concurrently and piled onto the same hot rows (the two players' ratings, the match
row), so the wait moved from "acquiring a connection" to "acquiring a row lock in Postgres". The sweet
spot is where you have relieved connection starvation without drowning the database, about 30 here,
which is what the app defaults to. A pool larger than the database can usefully serve just moves the
queue from your app into Postgres.

**Q4. Did three matchers triple pairing throughput? Give the measured factor and explain the gap.**

No. With the matcher saturated against a deep pre-filled queue, throughput went 250 → 410 → 495
matches/s for one, two, three matchers, so three matchers gave about **1.98x, roughly double, not
triple**. The gap is Amdahl's law. `FOR UPDATE SKIP LOCKED` lets the matchers partition the *queue
claim* perfectly (each grabs a disjoint batch, none blocks another, and anomalies stayed at zero), but
that is only one part of the work. They still share one Postgres: the same connection pool, the same
write-ahead log, the same CPU, and the single active-season row that every match insert reads. That
shared, serial fraction cannot be parallelised by adding matchers, so it caps the speedup. The win
SKIP LOCKED actually delivers is qualitative: the curve climbs instead of flattening or collapsing
into double-bookings, which is what the naive matcher does (under realistic load it produced 840
anomalies and *less* throughput than a single locking matcher). Correct and scaling beats wrong and
not.

**Q5. What is coordinated omission, and why does a closed loop make a stalled system look healthy?**

Coordinated omission is a measurement bias in closed-loop load: when a request is slow, the client
loop that issued it is blocked waiting, so during the stall it does *not* issue the requests it
otherwise would have. The slow period therefore generates fewer samples, not more, so the worst
latencies are under-counted and the percentiles come out optimistic, the system stalled but the
histogram barely flinched, because the stall suppressed its own measurements. A closed-loop simulator
(one virtual thread per player, each blocked on its own outstanding request) is the textbook setup for
it. That is why the benchmark write-up calls its tail latencies a floor, not a ceiling, and why the
relative comparisons (default vs tuned pool, naive vs locking) are trusted while the absolute
millisecond tails are treated as best-case. The honest fixes are to keep offered load independent of
response time, or to measure from when a request *should* have started rather than when it actually
did; the minimum is to name the bias so nobody reads the numbers as worst-case truth.

## Phase 8: Dashboard

**Q1. What is the per-endpoint latency budget, what three choices keep each read inside it, and how is the budget verified?**

The budget is under 50ms per dashboard endpoint at 10k players. Three choices keep the reads there.
First, every read is bounded: the queue and match feed are top-N queries (LIMIT 25 and 30), so latency
does not grow with how deep the queue is or how many matches have ever been played. Second, every read
hits an index instead of scanning: the queue read uses the partial index on (enqueued_at) WHERE
status='WAITING', so it walks waiting rows in wait order and stops after N rather than sorting the whole
set, and the match feed reads the primary-key index backwards (ORDER BY id DESC LIMIT 30). Third, the
most-polled endpoint uses the cheapest source: the stats strip is built from Redis counters
(queue depth, a sliding-window matches/sec) and in-memory Micrometer snapshots (avg wait, p99 pairing),
and touches Postgres only for one anomaly COUNT; the leaderboard top-20 comes from the Redis sorted set,
not a Postgres ORDER BY. It is verified two ways, not assumed: an integration test asserts the median
read stays under 50ms on representative data (200 players, 100 queued, 500 matches), and
`scripts/dashboard-latency-benchmark.sh` measures p50/p99 of each endpoint against a live 10k-player
system under simulator load.

**Q2. Why compute currentBand on the server instead of in the browser? What bug does it prevent?**

Because the band-expansion rule is a piece of business logic, and a value that encodes a rule should be
computed where the rule lives. The band is the rating window a waiting player will accept; it starts at
a base and widens with wait time, and the matcher pairs on it. If the browser recomputed the band from
the raw enqueue time and the band parameters, there would be two copies of that formula. The day the
expansion rate is changed on the server, the matcher would pair on the new rule while the dashboard
bars kept drawing the old one, so the UI would silently lie about the window the matcher is actually
using. Computing currentBand on the server with the same BandPolicy the matcher uses means the formula
has exactly one home and the bar a player sees is provably the window being applied to them. It also
keeps the UI a thin renderer, which is why the meaningful tests sit at the API seam, not in React.

**Q3. It is a real-time dashboard with no websockets. Why is polling the right size here, and when would it not be?**

Because the data is a snapshot, not a stream of events. The dashboard shows the queue right now, the
latest matches, the current standings; a dropped or slightly late frame changes nothing because there
is no individual event you must not miss, only the most recent state. So the whole real-time mechanism
is a timer per panel that re-fetches and replaces (stats every 1s; queue, feed, leaderboard every 2s),
backed by endpoints that are cheap and bounded precisely so frequent polling is safe. Two small touches
make it feel live between fetches without misrepresenting the data: a shared one-second clock advances
the wait timers locally, and the band bar animates toward each new server value with a CSS transition.
Polling would be the wrong tool when missing an event is itself a bug or when updates must be immediate:
chat, collaborative editing, presence, live trading. There, the cost of a push channel (a managed
connection, reconnection, backpressure) buys something real. Here it would be cost without benefit.
