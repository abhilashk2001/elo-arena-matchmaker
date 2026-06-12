package com.eloarena.matchmaking;

import com.eloarena.IntegrationTest;
import com.eloarena.match.Match;
import com.eloarena.match.MatchRepository;
import com.eloarena.player.Player;
import com.eloarena.player.PlayerRepository;
import com.eloarena.season.SeasonService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class AnomalyDetectorTest extends IntegrationTest {

    @Autowired
    private AnomalyDetector detector;

    @Autowired
    private MatchRepository matches;

    @Autowired
    private AnomalyRepository anomalies;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private SeasonService seasons;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE anomalies, matches, queue_entries, rating_history, "
                + "season_leaderboard_snapshots, players, seasons RESTART IDENTITY CASCADE");
    }

    @Test
    void recordsAnomalyWhenAPlayerIsInTwoInProgressMatches() {
        long x = players.save(new Player("X_1500", 1500, "NA")).getId();
        long y = players.save(new Player("Y_1500", 1500, "NA")).getId();
        long z = players.save(new Player("Z_1500", 1500, "NA")).getId();
        long season = seasons.currentSeasonId();

        // Simulate the double-match bug: player X ends up in two IN_PROGRESS matches.
        matches.save(new Match(season, x, y, 1500, 1500, 0, 0, 0));
        long secondMatch = matches.save(new Match(season, x, z, 1500, 1500, 0, 0, 0)).getId();

        double before = counterCount();
        detector.check(secondMatch, x, z);

        assertThat(anomalies.count()).isGreaterThanOrEqualTo(1);
        assertThat(anomalies.findAll()).anySatisfy(a -> assertThat(a.getPlayerId()).isEqualTo(x));
        assertThat(counterCount()).isGreaterThan(before);
    }

    @Test
    void recordsNoAnomalyForACleanMatch() {
        long a = players.save(new Player("A_1500", 1500, "NA")).getId();
        long b = players.save(new Player("B_1500", 1500, "NA")).getId();
        long season = seasons.currentSeasonId();

        long match = matches.save(new Match(season, a, b, 1500, 1500, 0, 0, 0)).getId();
        detector.check(match, a, b);

        assertThat(anomalies.count()).isZero();
    }

    private double counterCount() {
        return meterRegistry.find("eloarena.anomalies.detected").counter().count();
    }
}
