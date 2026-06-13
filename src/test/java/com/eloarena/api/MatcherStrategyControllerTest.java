package com.eloarena.api;

import com.eloarena.IntegrationTest;
import com.eloarena.matchmaking.StrategySelector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MatcherStrategyControllerTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StrategySelector strategies;

    @AfterEach
    void resetToLocking() {
        strategies.select("locking");
    }

    @Test
    void switchesStrategyAtRuntime() throws Exception {
        mockMvc.perform(put("/api/admin/matcher-strategy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strategy\": \"naive\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("naive"));

        assertThat(strategies.currentName()).isEqualTo("naive");
    }

    @Test
    void rejectsUnknownStrategy() throws Exception {
        mockMvc.perform(put("/api/admin/matcher-strategy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strategy\": \"bogus\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }
}
