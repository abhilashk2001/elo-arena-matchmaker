package com.eloarena.matchmaking;

import com.eloarena.IntegrationTest;
import com.eloarena.match.MatchRepository;
import com.eloarena.player.Player;
import com.eloarena.player.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fix proven against the exact scenario that breaks the naive matcher (RaceConditionTest).
 *
 * Batch size is 20 so that with two matchers and 100 players, each matcher claims its own
 * disjoint batch of 20 (FOR UPDATE SKIP LOCKED) and pairs 10. The two never touch the same
 * row, so there are no double-matches and no anomalies, and both matchers do real work in
 * parallel (each returns 10), which is the partitioning payoff.
 */
@TestPropertySource(properties = "eloarena.matcher.batch-size=20")
class LockingRaceConditionTest extends IntegrationTest {

    private static final int PLAYER_COUNT = 100;

    @Autowired
    private LockingMatcher lockingMatcher;

    @Autowired
    private QueueService queueService;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private MatchRepository matches;

    @Autowired
    private AnomalyRepository anomalies;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE anomalies, matches, queue_entries, rating_history, "
                + "season_leaderboard_snapshots, players RESTART IDENTITY CASCADE");
    }

    @Test
    void twoLockingMatchersPartitionTheQueueWithNoDoubleMatches() throws Exception {
        for (int i = 0; i < PLAYER_COUNT; i++) {
            long id = players.save(new Player("Racer_" + i, 1500, "NA")).getId();
            queueService.join(id);
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier startGate = new CyclicBarrier(2);
        int firstCreated;
        int secondCreated;
        try {
            Future<Integer> first = pool.submit(() -> runMatcherAtGate(startGate));
            Future<Integer> second = pool.submit(() -> runMatcherAtGate(startGate));
            firstCreated = first.get(30, TimeUnit.SECONDS);
            secondCreated = second.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        Long doubleMatchedPlayers = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT player_id FROM (
                        SELECT player_a_id AS player_id FROM matches
                        UNION ALL
                        SELECT player_b_id AS player_id FROM matches
                    ) p GROUP BY player_id HAVING count(*) > 1
                ) doubled
                """, Long.class);

        System.out.printf(
                "LOCKING_RACE players=%d batchSize=20 firstCreated=%d secondCreated=%d "
                        + "actualMatches=%d doubleMatchedPlayers=%d anomaliesDetected=%d%n",
                PLAYER_COUNT, firstCreated, secondCreated, matches.count(), doubleMatchedPlayers, anomalies.count());

        // The whole point: no double-matches, no anomalies.
        assertThat(anomalies.count()).as("locking matcher must produce no anomalies").isZero();
        assertThat(doubleMatchedPlayers).as("no player should be in more than one match").isZero();
        // Both matchers claimed a disjoint batch of 20 and paired 10: partitioning in action.
        assertThat(firstCreated).isEqualTo(10);
        assertThat(secondCreated).isEqualTo(10);
        assertThat(matches.count()).isEqualTo(20);
    }

    private int runMatcherAtGate(CyclicBarrier startGate) throws Exception {
        startGate.await(10, TimeUnit.SECONDS);
        return lockingMatcher.matchTick();
    }
}
