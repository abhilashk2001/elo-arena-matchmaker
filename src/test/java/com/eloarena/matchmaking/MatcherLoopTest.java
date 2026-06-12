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

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the scheduled loop actually fires and matches queued players without anyone
 * calling matchTick() by hand. The loop is enabled and sped up just for this test.
 */
@TestPropertySource(properties = {
        "eloarena.matcher.loop-enabled=true",
        "eloarena.matcher.interval-ms=200"
})
class MatcherLoopTest extends IntegrationTest {

    @Autowired
    private QueueService queueService;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private MatchRepository matches;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE matches, queue_entries, rating_history, "
                + "season_leaderboard_snapshots, players, seasons RESTART IDENTITY CASCADE");
    }

    @Test
    void scheduledLoopMatchesQueuedPlayersOnItsOwn() {
        Player a = players.save(new Player("LoopA_1200", 1200, "NA"));
        Player b = players.save(new Player("LoopB_1220", 1220, "NA"));
        queueService.join(a.getId());
        queueService.join(b.getId());

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(matches.count()).isEqualTo(1));
    }
}
