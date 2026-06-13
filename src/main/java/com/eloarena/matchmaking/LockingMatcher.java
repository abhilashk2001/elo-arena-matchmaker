package com.eloarena.matchmaking;

import com.eloarena.season.SeasonService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The fix: claims a batch of waiting rows with FOR UPDATE SKIP LOCKED, so concurrent matchers
 * take disjoint batches and never pair the same player.
 *
 * The whole tick is one transaction. The claim locks the batch rows; pairing and the match
 * writes happen while those locks are held; everything commits together. Claimed rows that
 * were not paired are simply left WAITING and their locks drop at commit, so the next tick can
 * claim them again. Because matchTick is transactional, the calls into MatchWriter join this
 * transaction (one transaction per batch), which is exactly what we want here.
 */
@Component
public class LockingMatcher implements MatchingStrategy {

    private static final String CLAIM_SQL = """
            SELECT id, player_id, rating_at_join, enqueued_at
              FROM queue_entries
             WHERE status = 'WAITING'
             ORDER BY enqueued_at
             LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;

    private final JdbcTemplate jdbc;
    private final PairingAlgorithm pairingAlgorithm;
    private final MatchWriter matchWriter;
    private final AnomalyDetector anomalyDetector;
    private final SeasonService seasons;
    private final MatcherProperties properties;

    public LockingMatcher(JdbcTemplate jdbc,
                          PairingAlgorithm pairingAlgorithm,
                          MatchWriter matchWriter,
                          AnomalyDetector anomalyDetector,
                          SeasonService seasons,
                          MatcherProperties properties) {
        this.jdbc = jdbc;
        this.pairingAlgorithm = pairingAlgorithm;
        this.matchWriter = matchWriter;
        this.anomalyDetector = anomalyDetector;
        this.seasons = seasons;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "locking";
    }

    @Override
    @Transactional
    public int matchTick() {
        Instant now = Instant.now();

        List<Candidate> claimed = claimBatch(properties.batchSize());
        if (claimed.isEmpty()) {
            return 0;
        }

        List<Pairing> pairings = pairingAlgorithm.pair(claimed, now);
        if (pairings.isEmpty()) {
            return 0;
        }

        long seasonId = seasons.currentSeasonId();
        for (Pairing pairing : pairings) {
            long matchId = matchWriter.createMatch(seasonId, pairing, now);
            anomalyDetector.check(matchId, pairing.a().playerId(), pairing.b().playerId());
        }
        return pairings.size();
    }

    private List<Candidate> claimBatch(int batchSize) {
        return jdbc.query(CLAIM_SQL, (rs, rowNum) -> new Candidate(
                rs.getLong("id"),
                rs.getLong("player_id"),
                rs.getInt("rating_at_join"),
                rs.getObject("enqueued_at", OffsetDateTime.class).toInstant()),
                batchSize);
    }
}
