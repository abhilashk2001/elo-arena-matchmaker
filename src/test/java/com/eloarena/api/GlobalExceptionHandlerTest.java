package com.eloarena.api;

import com.eloarena.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void domainErrorsUseTheUniformShape() throws Exception {
        mockMvc.perform(post("/api/queue/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerId\": 999999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLAYER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(notNullValue()))
                .andExpect(jsonPath("$.timestamp").value(notNullValue()));
    }

    @Test
    void bodyValidationFailureReturns400() throws Exception {
        // Missing required playerId.
        mockMvc.perform(post("/api/queue/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void paramValidationFailureReturns400() throws Exception {
        // The @Min(1) guard on seed count, deferred from story #14, is now a clean 400.
        mockMvc.perform(post("/api/admin/seed").param("count", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }
}
