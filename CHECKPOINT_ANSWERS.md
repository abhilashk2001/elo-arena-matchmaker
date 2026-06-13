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
