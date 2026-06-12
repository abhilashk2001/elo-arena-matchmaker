package com.eloarena.api;

import com.eloarena.IntegrationTest;
import com.eloarena.player.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminControllerTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE players RESTART IDENTITY CASCADE");
    }

    @Test
    void seedEndpointInsertsRequestedPlayers() throws Exception {
        mockMvc.perform(post("/api/admin/seed").param("count", "25").param("seed", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seeded").value(25));

        assertThat(players.count()).isEqualTo(25);
    }

    // The @Min(1) guard on count is in place, but mapping the resulting
    // ConstraintViolationException to a clean 400 needs the global exception
    // handler from story #22. That rejection case is tested there.
}
