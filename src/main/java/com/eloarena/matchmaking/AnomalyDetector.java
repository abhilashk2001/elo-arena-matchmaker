package com.eloarena.matchmaking;

import com.eloarena.match.MatchRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Detects double-matches: a player pulled into more than one IN_PROGRESS match at almost the same
 * time. Called after a match is created (in its own read), so it sees other matchers' committed
 * matches under READ COMMITTED. It is allowed to be slightly racy: detection only has to show the
 * counter climb in naive mode and stay at zero in locking mode, unlike the matcher, which must be
 * exact.
 *
 * Detection is scoped to a recent window so a genuine concurrent double-booking (two matches for the
 * one player created within seconds of each other) is counted, while a stale orphaned match is not.
 * Without the window, a match left IN_PROGRESS forever (a naive duplicate that never gets a result,
 * or a match open when the simulator stopped) would make the player look double-booked every time
 * they legitimately rematched later, even under locking. That false positive is what made the
 * counter keep climbing after the strategy was flipped back to locking.
 */
@Service
public class AnomalyDetector {

    /** How fresh the conflicting match must be to count as a concurrent double-booking. */
    static final Duration RECENT_WINDOW = Duration.ofSeconds(10);

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
        Instant since = Instant.now().minus(RECENT_WINDOW);
        List<Long> recent = matches.findRecentInProgressMatchIdsForPlayer(playerId, since);
        if (recent.size() <= 1) {
            return;
        }
        long conflicting = recent.stream()
                .filter(id -> id != newMatchId)
                .findFirst()
                .orElse(recent.get(0));
        anomalies.save(new Anomaly(playerId, newMatchId, conflicting));
        detectedCounter.increment();
    }
}
