package com.eloarena.leaderboard;

import com.eloarena.IntegrationTest;
import com.eloarena.player.Player;
import com.eloarena.player.PlayerRepository;
import com.eloarena.season.SeasonService;
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

class LeaderboardRebuildTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Leaderboard leaderboard;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private SeasonService seasons;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    private long seasonId;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE anomalies, matches, queue_entries, rating_history, "
                + "season_leaderboard_snapshots, players RESTART IDENTITY CASCADE");
        seasonId = seasons.currentSeasonId();
        redis.delete(Leaderboard.key(seasonId));
    }

    @Test
    void rebuildRestoresTheLeaderboardFromPostgres() throws Exception {
        long a = players.save(new Player("A_1700", 1700, "NA")).getId();
        long b = players.save(new Player("B_1300", 1300, "NA")).getId();

        // Simulate a wiped/empty Redis leaderboard: nothing there yet.
        assertThat(leaderboard.scoreOf(seasonId, a)).isNull();

        mockMvc.perform(post("/api/admin/rebuild-leaderboard").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        assertThat(leaderboard.scoreOf(seasonId, a)).isEqualTo(1700.0);
        assertThat(leaderboard.scoreOf(seasonId, b)).isEqualTo(1300.0);
    }
}
