package com.eloarena.matchmaking;

import com.eloarena.match.Match;
import com.eloarena.match.MatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Persists one pairing as a match plus the two queue-entry updates.
 *
 * Each pair is written in its own transaction (this is a separate bean so the @Transactional
 * proxy actually applies). Per-pair transactions keep the lock footprint small and let
 * partial progress survive: if one pair fails, the pairs already committed this tick stand.
 * The locking strategy in Phase 4 deliberately uses one transaction per claimed batch instead.
 */
@Service
public class MatchWriter {

    private final MatchRepository matches;
    private final QueueEntryRepository queue;
    private final MatchmakingMetrics metrics;

    public MatchWriter(MatchRepository matches, QueueEntryRepository queue, MatchmakingMetrics metrics) {
        this.matches = matches;
        this.queue = queue;
        this.metrics = metrics;
    }

    @Transactional
    public long createMatch(long seasonId, Pairing pairing, Instant now) {
        Candidate a = pairing.a();
        Candidate b = pairing.b();

        long waitA = waitMillis(a, now);
        long waitB = waitMillis(b, now);

        Match match = matches.save(new Match(
                seasonId,
                a.playerId(), b.playerId(),
                a.rating(), b.rating(),
                pairing.ratingDelta(),
                waitA, waitB));

        queue.markMatched(a.queueEntryId(), match.getId());
        queue.markMatched(b.queueEntryId(), match.getId());

        metrics.recordQueueWait(waitA);
        metrics.recordQueueWait(waitB);
        return match.getId();
    }

    private long waitMillis(Candidate candidate, Instant now) {
        return Math.max(0, Duration.between(candidate.enqueuedAt(), now).toMillis());
    }
}
