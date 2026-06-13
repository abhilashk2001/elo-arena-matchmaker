package com.eloarena.api;

import com.eloarena.IntegrationTest;
import com.eloarena.leaderboard.Leaderboard;
import com.eloarena.match.Match;
import com.eloarena.match.MatchRepository;
import com.eloarena.player.Player;
import com.eloarena.player.PlayerRepository;
import com.eloarena.rating.RatingHistory;
import com.eloarena.rating.RatingHistoryRepository;
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

class PlayerControllerTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private MatchRepository matches;

    @Autowired
    private RatingHistoryRepository history;

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
    void profileIncludesRankFromLeaderboard() throws Exception {
        long top = players.save(new Player("Top_1600", 1600, "NA")).getId();
        long mid = players.save(new Player("Mid_1500", 1500, "NA")).getId();
        long low = players.save(new Player("Low_1400", 1400, "NA")).getId();
        leaderboard.addRating(seasonId, top, 1600);
        leaderboard.addRating(seasonId, mid, 1500);
        leaderboard.addRating(seasonId, low, 1400);

        mockMvc.perform(get("/api/players/{id}", mid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(1500))
                .andExpect(jsonPath("$.rank").value(2));
    }

    @Test
    void unknownPlayerReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/players/{id}", 999999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLAYER_NOT_FOUND"));
    }

    @Test
    void historyReturnsRecentEntries() throws Exception {
        long player = players.save(new Player("Hist_1500", 1500, "NA")).getId();
        long other = players.save(new Player("Other_1500", 1500, "NA")).getId();
        long match1 = matches.save(new Match(seasonId, player, other, 1500, 1500, 0, 0, 0)).getId();
        long match2 = matches.save(new Match(seasonId, player, other, 1516, 1484, 32, 0, 0)).getId();
        history.save(new RatingHistory(player, match1, seasonId, 1500, 1516));
        history.save(new RatingHistory(player, match2, seasonId, 1516, 1530));

        mockMvc.perform(get("/api/players/{id}/history", player))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }
}
