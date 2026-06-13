package com.eloarena.api;

import com.eloarena.IntegrationTest;
import com.eloarena.match.Match;
import com.eloarena.match.MatchRepository;
import com.eloarena.player.Player;
import com.eloarena.player.PlayerRepository;
import com.eloarena.rating.RatingHistoryRepository;
import com.eloarena.season.SeasonService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ResultControllerTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private MatchRepository matches;

    @Autowired
    private RatingHistoryRepository history;

    @Autowired
    private SeasonService seasons;

    @Autowired
    private JdbcTemplate jdbc;

    private long playerA;
    private long playerB;
    private long matchId;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE anomalies, matches, queue_entries, rating_history, "
                + "season_leaderboard_snapshots, players RESTART IDENTITY CASCADE");
        playerA = players.save(new Player("Alice_1500", 1500, "NA")).getId();
        playerB = players.save(new Player("Bob_1500", 1500, "NA")).getId();
        matchId = matches.save(new Match(seasons.currentSeasonId(), playerA, playerB, 1500, 1500, 0, 0, 0)).getId();
    }

    @Test
    void appliesEloAndCompletesTheMatch() throws Exception {
        mockMvc.perform(post("/api/matches/{id}/result", matchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"winnerId\": " + playerA + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.winnerId").value(playerA))
                .andExpect(jsonPath("$.ratingChanges.length()").value(2));

        assertThat(players.findById(playerA).orElseThrow().getRating()).isEqualTo(1516);
        assertThat(players.findById(playerA).orElseThrow().getGamesPlayed()).isEqualTo(1);
        assertThat(players.findById(playerB).orElseThrow().getRating()).isEqualTo(1484);
        assertThat(matches.findById(matchId).orElseThrow().getStatus().name()).isEqualTo("COMPLETED");
        assertThat(history.findByMatchId(matchId)).hasSize(2);
    }

    @Test
    void rejectsAWinnerNotInTheMatch() throws Exception {
        long outsider = players.save(new Player("Outsider_1500", 1500, "NA")).getId();

        mockMvc.perform(post("/api/matches/{id}/result", matchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"winnerId\": " + outsider + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_WINNER"));
    }

    @Test
    void unknownMatchReturnsNotFound() throws Exception {
        mockMvc.perform(post("/api/matches/{id}/result", 999999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"winnerId\": " + playerA + "}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("MATCH_NOT_FOUND"));
    }
}
