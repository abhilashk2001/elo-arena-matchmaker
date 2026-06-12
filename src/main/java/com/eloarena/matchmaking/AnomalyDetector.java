package com.eloarena.matchmaking;

import com.eloarena.match.MatchRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Detects double-matches: a player in more than one IN_PROGRESS match. Called after a match
 * is created (in its own read), so it sees other matchers' committed matches under READ
 * COMMITTED. It is allowed to be slightly racy: detection only has to show the counter climb
 * in naive mode and stay at zero in locking mode, unlike the matcher, which must be exact.
 */
@Service
public class AnomalyDetector {

    private final MatchRepository matches;
    private final AnomalyRepository anomalies;
    private final Counter detectedCounter;

    public AnomalyDetector(MatchRepository matches, AnomalyRepository anomalies, MeterRegistry meterRegistry) {
        this.matches = matches;
        this.anomalies = anomalies;
        this.detectedCounter = Counter.builder("eloarena.anomalies.detected")
                .description("Double-match anomalies detected")
                .register(meterRegistry);
    }

    /** Check both players of a freshly created match for a concurrent IN_PROGRESS match. */
    @Transactional
    public void check(long newMatchId, long playerAId, long playerBId) {
        detectFor(playerAId, newMatchId);
        detectFor(playerBId, newMatchId);
    }

    private void detectFor(long playerId, long newMatchId) {
        List<Long> inProgress = matches.findInProgressMatchIdsForPlayer(playerId);
        if (inProgress.size() <= 1) {
            return;
        }
        long conflicting = inProgress.stream()
                .filter(id -> id != newMatchId)
                .findFirst()
                .orElse(inProgress.get(0));
        anomalies.save(new Anomaly(playerId, newMatchId, conflicting));
        detectedCounter.increment();
    }
}
