package com.eloarena.matchmaking;

import com.eloarena.IntegrationTest;
import com.eloarena.match.MatchRepository;
import com.eloarena.player.Player;
import com.eloarena.player.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the double-match bug deterministically.
 *
 * 100 mutually-compatible players wait in the queue. Two naive matcher threads are released
 * at the same instant by a CyclicBarrier, so both read the same WAITING snapshot and both
 * pair the same players. With no locking, the matchers do not partition the queue, so the
 * same players get matched twice.
 *
 * The test passes by proving the bug is present: more matches than physically possible for
 * 100 players, and recorded anomalies. Phase 4 reuses this exact scenario against the
 * locking matcher and asserts zero anomalies.
 */
class RaceConditionTest extends IntegrationTest {

    private static final int PLAYER_COUNT = 100;

    @Autowired
    private NaiveMatcher naiveMatcher;

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
    void twoNaiveMatchersDoubleMatchTheSamePlayers() throws Exception {
        // All the same rating, so every player is compatible with every other: a correct run
        // would produce exactly PLAYER_COUNT / 2 matches and zero anomalies.
        for (int i = 0; i < PLAYER_COUNT; i++) {
            long id = players.save(new Player("Racer_" + i, 1500, "NA")).getId();
            queueService.join(id);
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier startGate = new CyclicBarrier(2);
        try {
            Future<Integer> first = pool.submit(() -> runMatcherAtGate(startGate));
            Future<Integer> second = pool.submit(() -> runMatcherAtGate(startGate));
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        long maxCorrectMatches = PLAYER_COUNT / 2;
        assertThat(matches.count())
                .as("two unlocked matchers should create more matches than the %d possible", maxCorrectMatches)
                .isGreaterThan(maxCorrectMatches);
        assertThat(anomalies.count())
                .as("double-matches should be detected")
                .isGreaterThan(0);
    }

    private int runMatcherAtGate(CyclicBarrier startGate) throws Exception {
        startGate.await(10, TimeUnit.SECONDS);
        return naiveMatcher.matchTick();
    }
}
