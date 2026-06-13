package com.eloarena.api;

import com.eloarena.IntegrationTest;
import com.eloarena.leaderboard.Leaderboard;
import com.eloarena.player.Player;
import com.eloarena.player.PlayerRepository;
import com.eloarena.season.SeasonService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LeaderboardControllerTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private Leaderboard leaderboard;

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
    void activeLeaderboardReturnsHydratedTopN() throws Exception {
        long top = players.save(new Player("Top_1600", 1600, "NA")).getId();
        long mid = players.save(new Player("Mid_1500", 1500, "NA")).getId();
        players.save(new Player("Low_1400", 1400, "NA"));
        leaderboard.addRating(seasonId, top, 1600);
        leaderboard.addRating(seasonId, mid, 1500);

        mockMvc.perform(get("/api/leaderboard").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].rank").value(1))
                .andExpect(jsonPath("$[0].playerId").value(top))
                .andExpect(jsonPath("$[0].handle").value("Top_1600"))
                .andExpect(jsonPath("$[0].rating").value(1600));
    }

    @Test
    void seasonsListsTheActiveSeason() throws Exception {
        mockMvc.perform(get("/api/seasons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Season 1"))
                .andExpect(jsonPath("$[0].active").value(true));
    }

    @Test
    void activeSeasonLeaderboardUsesLiveData() throws Exception {
        long p = players.save(new Player("Solo_1500", 1500, "NA")).getId();
        leaderboard.addRating(seasonId, p, 1500);

        mockMvc.perform(get("/api/seasons/{id}/leaderboard", seasonId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].handle").value("Solo_1500"));
    }
}
