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
