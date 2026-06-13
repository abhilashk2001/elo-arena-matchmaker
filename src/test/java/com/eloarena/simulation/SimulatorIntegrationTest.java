package com.eloarena.simulation;

import com.eloarena.TestcontainersConfiguration;
import com.eloarena.match.MatchStatus;
import com.eloarena.player.Player;
import com.eloarena.player.PlayerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Drives the simulator end to end against a real running server on a fixed port. The scheduled
 * matcher loop is on, so this exercises the whole loop: simulated players join over HTTP, the
 * matcher pairs them, the players poll, play, and submit results, and ratings move. A fixed port
 * (not RANDOM_PORT) is used so the simulator's base-url can be set statically in config; the
 * port binding for an eager singleton cannot capture a random port chosen later in startup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "server.port=18099",
        "eloarena.simulator.base-url=http://localhost:18099",
        "eloarena.simulator.poll-ms=100",
        "eloarena.matcher.loop-enabled=true",
        "eloarena.matcher.interval-ms=200"
})
class SimulatorIntegrationTest {

    @Autowired
    private Simulator simulator;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE anomalies, matches, queue_entries, rating_history, "
                + "season_leaderboard_snapshots, players RESTART IDENTITY CASCADE");
        // Ten players at an identical rating: all mutually compatible, so the matcher pairs them
        // into five matches almost immediately.
        for (int i = 0; i < 10; i++) {
            players.save(new Player("Sim_" + i, 1200, "NA"));
        }
    }

    @AfterEach
    void tearDown() {
        simulator.stop();
    }

    @Test
    void simulatorDrivesPlayersThroughTheFullLifecycle() {
        // requeueProbability 0: each player plays exactly one match, then stops.
        SimulationStatus started = simulator.start(new SimulationConfig(10, 0.0, 10, 40));
        assertThat(started.running()).isTrue();
        assertThat(started.activePlayers()).isEqualTo(10);

        // The five matches all complete: results were submitted over the real HTTP API.
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    long completed = jdbc.queryForObject(
                            "SELECT count(*) FROM matches WHERE status = ?", Long.class, MatchStatus.COMPLETED.name());
                    assertThat(completed).isEqualTo(5);
                });

        // Every player has played a game and the winners gained rating, the losers lost it.
        long playedAGame = jdbc.queryForObject(
                "SELECT count(*) FROM players WHERE games_played > 0", Long.class);
        assertThat(playedAGame).isEqualTo(10);
        long ratingMoved = jdbc.queryForObject(
                "SELECT count(*) FROM players WHERE rating <> 1200", Long.class);
        assertThat(ratingMoved).isEqualTo(10);
    }
}
