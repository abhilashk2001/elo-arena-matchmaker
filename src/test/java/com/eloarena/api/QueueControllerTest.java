package com.eloarena.api;

import com.eloarena.IntegrationTest;
import com.eloarena.matchmaking.QueueEntryRepository;
import com.eloarena.player.Player;
import com.eloarena.player.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class QueueControllerTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private QueueEntryRepository queue;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE queue_entries, players RESTART IDENTITY CASCADE");
    }

    @Test
    void joinCreatesWaitingEntryWithRatingSnapshot() throws Exception {
        Player player = players.save(new Player("Joiner_1", 1500, "NA"));

        mockMvc.perform(post("/api/queue/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerId\": " + player.getId() + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.ratingAtJoin").value(1500))
                .andExpect(jsonPath("$.playerId").value(player.getId()));

        assertThat(queue.count()).isEqualTo(1);
    }

    @Test
    void duplicateJoinIsRejectedWithConflict() throws Exception {
        Player player = players.save(new Player("Dup_1", 1400, "NA"));
        String body = "{\"playerId\": " + player.getId() + "}";

        mockMvc.perform(post("/api/queue/join").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/queue/join").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());

        assertThat(queue.count()).isEqualTo(1);
    }

    @Test
    void joinUnknownPlayerReturnsNotFound() throws Exception {
        mockMvc.perform(post("/api/queue/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerId\": 999999}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void leaveCancelsAWaitingEntry() throws Exception {
        Player player = players.save(new Player("Leaver_1", 1500, "NA"));
        mockMvc.perform(post("/api/queue/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerId\": " + player.getId() + "}"))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/queue/{playerId}", player.getId()))
                .andExpect(status().isOk());

        String status = jdbc.queryForObject(
                "SELECT status FROM queue_entries WHERE player_id = ?", String.class, player.getId());
        assertThat(status).isEqualTo("CANCELLED");
    }

    @Test
    void leaveWhenNotQueuedReturnsNotFound() throws Exception {
        Player player = players.save(new Player("Idle_1", 1500, "NA"));

        mockMvc.perform(delete("/api/queue/{playerId}", player.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void leaveDoesNotCancelAnEntryTheMatcherAlreadyMatched() throws Exception {
        Player player = players.save(new Player("Racer_1", 1500, "NA"));
        mockMvc.perform(post("/api/queue/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerId\": " + player.getId() + "}"))
                .andExpect(status().isCreated());

        // Simulate the matcher claiming this player between the client's intent and the leave.
        jdbc.update("UPDATE queue_entries SET status = 'MATCHED' WHERE player_id = ?", player.getId());

        mockMvc.perform(delete("/api/queue/{playerId}", player.getId()))
                .andExpect(status().isNotFound());

        // The conditional update must not have touched the MATCHED entry.
        String status = jdbc.queryForObject(
                "SELECT status FROM queue_entries WHERE player_id = ?", String.class, player.getId());
        assertThat(status).isEqualTo("MATCHED");
    }
}
