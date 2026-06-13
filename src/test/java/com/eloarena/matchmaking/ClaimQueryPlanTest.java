package com.eloarena.matchmaking;

import com.eloarena.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents and verifies the plan for the locking matcher's claim query at scale. Seeds 50k
 * waiting rows and runs EXPLAIN ANALYZE, asserting the partial enqueued_at index is used and
 * the query does not fall back to a sequential scan of the whole queue.
 */
class ClaimQueryPlanTest extends IntegrationTest {

    private static final String CLAIM_PLAN_SQL = """
            EXPLAIN ANALYZE
            SELECT id, player_id, rating_at_join, enqueued_at
              FROM queue_entries
             WHERE status = 'WAITING'
             ORDER BY enqueued_at
             LIMIT 100
            FOR UPDATE SKIP LOCKED
            """;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void claimUsesThePartialEnqueuedIndexAt50kRows() {
        jdbc.execute("TRUNCATE TABLE anomalies, matches, queue_entries, rating_history, "
                + "season_leaderboard_snapshots, players RESTART IDENTITY CASCADE");

        // 50k players and 50k WAITING entries, generated in-database for speed.
        jdbc.update("INSERT INTO players (handle, rating, region) "
                + "SELECT 'P' || g, 1500, 'NA' FROM generate_series(1, 50000) g");
        jdbc.update("INSERT INTO queue_entries (player_id, rating_at_join, enqueued_at, status) "
                + "SELECT id, rating, now() - (random() * interval '60 seconds'), 'WAITING' FROM players");

        List<String> plan = jdbc.queryForList(CLAIM_PLAN_SQL, String.class);
        String planText = String.join("\n", plan);
        System.out.println("CLAIM QUERY PLAN (50k waiting rows):\n" + planText);

        assertThat(planText).contains("idx_queue_waiting_enqueued");
        assertThat(planText).doesNotContain("Seq Scan on queue_entries");
    }
}
