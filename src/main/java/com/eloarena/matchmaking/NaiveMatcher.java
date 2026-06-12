package com.eloarena.matchmaking;

import com.eloarena.season.SeasonService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * The first matching strategy, intentionally unsafe under concurrency.
 *
 * It reads all WAITING entries with a plain SELECT (no locks), pairs them with the shared
 * greedy algorithm, then writes each pair in its own transaction. With a single matcher this
 * is correct. With two matchers it is not: both read the same WAITING snapshot and both pair
 * the same players, producing duplicate matches. That failure is reproduced and measured in
 * Phase 3, then fixed by the locking strategy in Phase 4.
 */
@Component
public class NaiveMatcher implements MatchingStrategy {

    private final QueueEntryRepository queue;
    private final PairingAlgorithm pairingAlgorithm;
    private final MatchWriter matchWriter;
    private final SeasonService seasons;
    private final AnomalyDetector anomalyDetector;

    public NaiveMatcher(QueueEntryRepository queue,
                        PairingAlgorithm pairingAlgorithm,
                        MatchWriter matchWriter,
                        SeasonService seasons,
                        AnomalyDetector anomalyDetector) {
        this.queue = queue;
        this.pairingAlgorithm = pairingAlgorithm;
        this.matchWriter = matchWriter;
        this.seasons = seasons;
        this.anomalyDetector = anomalyDetector;
    }

    @Override
    public String name() {
        return "naive";
    }

    @Override
    public int matchTick() {
        Instant now = Instant.now();

        List<Candidate> candidates = queue.findByStatusOrderByEnqueuedAtAsc(QueueStatus.WAITING)
                .stream()
                .map(e -> new Candidate(e.getId(), e.getPlayerId(), e.getRatingAtJoin(), e.getEnqueuedAt()))
                .toList();

        List<Pairing> pairings = pairingAlgorithm.pair(candidates, now);
        if (pairings.isEmpty()) {
            return 0;
        }

        long seasonId = seasons.currentSeasonId();
        for (Pairing pairing : pairings) {
            long matchId = matchWriter.createMatch(seasonId, pairing, now);
            // Detection runs after the match commits so it can see other matchers' matches.
            anomalyDetector.check(matchId, pairing.a().playerId(), pairing.b().playerId());
        }
        return pairings.size();
    }
}
