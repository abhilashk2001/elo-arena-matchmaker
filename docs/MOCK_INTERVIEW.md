# Mock interview walkthrough (M9)

The final assessment is explaining the whole system out loud, with someone playing interviewer and
asking follow-ups. This is the question flow to rehearse against. Worked answers to the per-phase
comprehension questions are in [CHECKPOINT_ANSWERS.md](../CHECKPOINT_ANSWERS.md); the depth here is
the same, just spoken and probed.

Rule of the exercise: answer from understanding, not memorisation. The interviewer should interrupt
with "why?" and "what breaks if not?" until the mechanism, not just the vibe, is on the table.

## 1. System overview (warm-up, 2 minutes)

- Give the 30-second pitch: what the system does and the one hard problem it is about.
- Draw the four boxes (clients/simulator, app, Postgres, Redis) and say what each is for.
- Follow-up: why a modular monolith and not microservices here?

## 2. The matcher concurrency story (the core)

- Walk through the naive matcher with two instances against the same queue, row by row. Where
  exactly does the double-match happen?
- What is `FOR UPDATE SKIP LOCKED` doing? Separate the two halves: what `FOR UPDATE` does, what
  `SKIP LOCKED` adds.
- The key insight: is the win that matchers wait their turn, or that they never touch the same rows?
  Explain the partitioning framing.
- Follow-ups:
  - Why is the whole tick one transaction instead of one transaction per pair?
  - What happens to rows you claimed but did not pair? When do their locks drop?
  - Did three matchers triple throughput? Give the number and explain the gap (Amdahl).
  - How did you reproduce the race *deterministically* in a test, separate from load?

## 3. Transaction walk-through (result submission)

- Trace a match-result submission end to end: what reads, what writes, what the transaction
  boundary is, and what commits together.
- Idempotency: what happens on a duplicate submit? On two concurrent submits for the same match?
  Name the two layers and say which one is actually trusted and why.
- Follow-ups:
  - Why update Redis only after the Postgres commit? What bug does pre-commit cause?
  - If Redis and Postgres disagree, how do you recover? (the rebuild path)

## 4. Read-model rationale (CQRS-lite)

- Why is the leaderboard read from Redis instead of an `ORDER BY` on Postgres? What does the sorted
  set buy, and what does it cost?
- Active season vs ended season: live Redis vs Postgres snapshot. Why two paths, same response
  shape?
- Follow-up: where is the source of truth, and what guarantees the read model can always be
  reconstructed from it?

## 5. Evidence and method (closing)

- How do you know the fix works at scale? Point at the numbers and how they were measured.
- What is coordinated omission, and why are the closed-loop tail latencies a floor, not a ceiling?
- Pool sizing: why is a bigger connection pool not automatically better?
- Follow-up: name one thing you deliberately did not build, and why that was the right call.

## Self-grade

You have passed when you can field every "why?" without reaching for notes, and when a wrong-but-
confident answer (e.g. "SKIP LOCKED makes them wait their turn") gets corrected by you, unprompted,
to the precise mechanism.
