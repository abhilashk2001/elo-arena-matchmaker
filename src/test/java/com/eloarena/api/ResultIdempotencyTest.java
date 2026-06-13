package com.eloarena.api;

import com.eloarena.IntegrationTest;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ResultIdempotencyTest extends IntegrationTest {

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
    void resubmittingTheSameResultIsIdempotent() throws Exception {
        String body = "{\"winnerId\": " + playerA + "}";

        mockMvc.perform(post("/api/matches/{id}/result", matchId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        // Replay: still 200, same outcome, and the rating is not applied a second time.
        mockMvc.perform(post("/api/matches/{id}/result", matchId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.winnerId").value(playerA));

        assertThat(players.findById(playerA).orElseThrow().getRating()).isEqualTo(1516);
        assertThat(players.findById(playerA).orElseThrow().getGamesPlayed()).isEqualTo(1);
        assertThat(history.findByMatchId(matchId)).hasSize(2);
    }

    @Test
    void databaseRejectsASecondRatingForTheSamePlayerAndMatch() {
        history.saveAndFlush(new RatingHistory(playerA, matchId, seasons.currentSeasonId(), 1500, 1516));

        assertThatThrownBy(() ->
                history.saveAndFlush(new RatingHistory(playerA, matchId, seasons.currentSeasonId(), 1500, 1520)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
