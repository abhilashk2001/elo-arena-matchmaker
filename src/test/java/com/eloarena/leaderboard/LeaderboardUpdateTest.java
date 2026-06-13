package com.eloarena.leaderboard;

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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LeaderboardUpdateTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Leaderboard leaderboard;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private MatchRepository matches;

    @Autowired
    private SeasonService seasons;

    @Autowired
    private JdbcTemplate jdbc;

    private long playerA;
    private long playerB;
    private long matchId;
    private long seasonId;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE anomalies, matches, queue_entries, rating_history, "
                + "season_leaderboard_snapshots, players RESTART IDENTITY CASCADE");
        seasonId = seasons.currentSeasonId();
        redis.delete(Leaderboard.key(seasonId));
        playerA = players.save(new Player("Alice_1500", 1500, "NA")).getId();
        playerB = players.save(new Player("Bob_1500", 1500, "NA")).getId();
        matchId = matches.save(new Match(seasonId, playerA, playerB, 1500, 1500, 0, 0, 0)).getId();
    }

    @Test
    void leaderboardIsUpdatedAfterAResultCommits() throws Exception {
        double timerCountBefore = timerCount();

        mockMvc.perform(post("/api/matches/{id}/result", matchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"winnerId\": " + playerA + "}"))
                .andExpect(status().isOk());

        // The post-commit hook ZADDed both players' new ratings.
        assertThat(leaderboard.scoreOf(seasonId, playerA)).isEqualTo(1516.0);
        assertThat(leaderboard.scoreOf(seasonId, playerB)).isEqualTo(1484.0);
        // The result-processing timer recorded the work.
        assertThat(timerCount()).isGreaterThan(timerCountBefore);
    }

    private double timerCount() {
        var timer = meterRegistry.find("eloarena.result.processing").timer();
        return timer == null ? 0 : timer.count();
    }
}
