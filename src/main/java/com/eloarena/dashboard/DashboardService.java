package com.eloarena.dashboard;

import com.eloarena.matchmaking.AnomalyRepository;
import com.eloarena.matchmaking.BandPolicy;
import com.eloarena.matchmaking.Candidate;
import com.eloarena.matchmaking.MatchmakingMetrics;
import com.eloarena.matchmaking.RedisLiveStats;
import com.eloarena.matchmaking.StrategySelector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Backs the ops dashboard's read panels. Every method here is on a polling hot path (hit every
 * one to two seconds by every open dashboard), so each is deliberately cheap:
 *
 * <ul>
 *   <li>the queue and match feeds are bounded top-N reads over indexed columns, never full scans;
 *   <li>the stats strip is assembled from Redis counters and in-memory Micrometer snapshots, so it
 *       touches Postgres only for the anomaly count;
 *   <li>currentBand is computed here, on the server, from the one {@link BandPolicy} the matcher
 *       itself uses, so the UI is a thin renderer and the band formula can never drift between the
 *       backend that pairs on it and the frontend that draws it.
 * </ul>
 *
 * The target is under 50ms per endpoint at 10k players; see DASHBOARD perf notes in BENCHMARKS.md.
 */
@Service
public class DashboardService {

    // Bounded feeds. The dashboard only ever shows a screenful, and capping the read is what keeps
    // these queries fast no matter how deep the queue or how many matches have been played.
    private static final int QUEUE_LIMIT = 25;
    private static final int MATCH_FEED_LIMIT = 30;
    private static final int ANOMALY_LIMIT = 30;

    // Top waiting players, longest waiter first. WHERE status + ORDER BY enqueued_at is served by
    // the idx_queue_waiting_enqueued partial index (V4), so this is a short indexed read, not a scan.
    private static final String QUEUE_SQL = """
            SELECT q.player_id, p.handle, q.rating_at_join, q.enqueued_at
              FROM queue_entries q
              JOIN players p ON p.id = q.player_id
             WHERE q.status = 'WAITING'
             ORDER BY q.enqueued_at
             LIMIT ?
            """;

    // Most recent matches. ORDER BY id DESC LIMIT N is a backward scan of the primary-key index,
    // so it stays cheap as the matches table grows.
    private static final String MATCH_FEED_SQL = """
            SELECT m.id, m.player_a_id, pa.handle AS handle_a, m.rating_a,
                   m.player_b_id, pb.handle AS handle_b, m.rating_b,
                   m.rating_delta, m.wait_ms_a, m.wait_ms_b, m.created_at
              FROM matches m
              JOIN players pa ON pa.id = m.player_a_id
              JOIN players pb ON pb.id = m.player_b_id
             ORDER BY m.id DESC
             LIMIT ?
            """;

    private final JdbcTemplate jdbc;
    private final BandPolicy bandPolicy;
    private final RedisLiveStats liveStats;
    private final MatchmakingMetrics metrics;
    private final StrategySelector strategies;
    private final AnomalyRepository anomalies;

    // Display-only. With docker compose scale the matchers run in separate containers and there is
    // no cheap cross-process count, so we surface a configured value rather than over-engineer
    // service discovery just to print a number. Defaults to 1 (single instance).
    private final int matcherCount;

    public DashboardService(JdbcTemplate jdbc,
                            BandPolicy bandPolicy,
                            RedisLiveStats liveStats,
                            MatchmakingMetrics metrics,
                            StrategySelector strategies,
                            AnomalyRepository anomalies,
                            @Value("${eloarena.matcher.count:1}") int matcherCount) {
        this.jdbc = jdbc;
        this.bandPolicy = bandPolicy;
        this.liveStats = liveStats;
        this.metrics = metrics;
        this.strategies = strategies;
        this.anomalies = anomalies;
        this.matcherCount = matcherCount;
    }

    /** Top waiting players with their current rating band, computed server-side. */
    public List<DashboardQueueEntry> queue() {
        Instant now = Instant.now();
        return jdbc.query(QUEUE_SQL, (rs, rowNum) -> {
            long playerId = rs.getLong("player_id");
            int rating = rs.getInt("rating_at_join");
            Instant enqueuedAt = rs.getObject("enqueued_at", OffsetDateTime.class).toInstant();
            long waitMs = Math.max(0, now.toEpochMilli() - enqueuedAt.toEpochMilli());
            // Same BandPolicy the matcher pairs on, so the bar the UI draws is the real window.
            int currentBand = bandPolicy.bandFor(new Candidate(0, playerId, rating, enqueuedAt), now);
            return new DashboardQueueEntry(playerId, rs.getString("handle"), rating, waitMs, currentBand);
        }, QUEUE_LIMIT);
    }

    /** Recent matches for the feed, newest first. */
    public List<DashboardMatch> matches() {
        return jdbc.query(MATCH_FEED_SQL, (rs, rowNum) -> new DashboardMatch(
                rs.getLong("id"),
                rs.getLong("player_a_id"), rs.getString("handle_a"), rs.getInt("rating_a"),
                rs.getLong("player_b_id"), rs.getString("handle_b"), rs.getInt("rating_b"),
                rs.getInt("rating_delta"),
                rs.getLong("wait_ms_a"), rs.getLong("wait_ms_b"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()), MATCH_FEED_LIMIT);
    }

    /** The glanceable health strip. Assembled from Redis and Micrometer; one Postgres count. */
    public DashboardStats stats() {
        return new DashboardStats(
                liveStats.queueDepth(),
                liveStats.matchesPerSec(),
                metrics.averageQueueWaitMs(),
                metrics.p99PairingLatencyMs(),
                matcherCount,
                strategies.currentName(),
                anomalies.count());
    }

    /** Recent detected double-matches, newest first; the headline of the demo. */
    public List<DashboardAnomaly> anomalies() {
        return anomalies.findAllByOrderByDetectedAtDesc(org.springframework.data.domain.PageRequest.of(0, ANOMALY_LIMIT))
                .stream()
                .map(a -> new DashboardAnomaly(
                        a.getId(), a.getPlayerId(), a.getMatchId(), a.getConflictingMatchId(), a.getDetectedAt()))
                .toList();
    }
}
